/*
 * Android process wrapper for hev-socks5-tunnel.
 *
 * Packet data stays inside HEV. The private control socket transfers only the
 * Android TUN descriptor and parent liveness. stdout carries lifecycle events
 * and aggregate counters.
 */

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <lwip/init.h>
#include <hev-task-system.h>
#include <hev-socks5-logger.h>
#include <hev-socks5-misc.h>

#include "hev-config.h"
#include "hev-logger.h"
#include "hev-socks5-tunnel.h"
#include "hev-utils.h"

#define CONTROL_BUFFER 256

static pthread_mutex_t output_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t stats_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_t tunnel_thread;
static pthread_t watcher_thread;
static pthread_t stats_thread;
static int watch_fd = -1;
static int tun_fd = -1;
static const char *config_path;
static atomic_int tunnel_ready;
static atomic_int tunnel_done;
static int tunnel_result;

static void
emit_line (const char *line)
{
    pthread_mutex_lock (&output_mutex);
    fputs (line, stdout);
    fputc ('\n', stdout);
    fflush (stdout);
    pthread_mutex_unlock (&output_mutex);
}

static void
emit_error (const char *message)
{
    char line[CONTROL_BUFFER];
    snprintf (line, sizeof (line),
              "{\"event\":\"error\",\"message\":\"%s\"}", message);
    emit_line (line);
}

static void
emit_traffic (void)
{
    char line[CONTROL_BUFFER];
    size_t tx_packets, tx_bytes, rx_packets, rx_bytes;

    hev_socks5_tunnel_stats (&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    snprintf (line, sizeof (line),
              "{\"event\":\"traffic\",\"upload\":%zu,\"download\":%zu}",
              tx_bytes, rx_bytes);
    emit_line (line);
}

static int
write_all (int fd, const void *data, size_t length)
{
    const unsigned char *bytes = data;
    while (length) {
        ssize_t written = write (fd, bytes, length);
        if (written < 0 && errno == EINTR)
            continue;
        if (written <= 0)
            return -1;
        bytes += written;
        length -= written;
    }
    return 0;
}

static int
connect_control (const char *path, const char *token, const char *command)
{
    struct sockaddr_un address;
    char line[CONTROL_BUFFER];
    size_t path_length;
    int fd;

    memset (&address, 0, sizeof (address));
    address.sun_family = AF_UNIX;
    path_length = strlen (path);
    if (path_length == 0 || path_length >= sizeof (address.sun_path))
        return -1;

    if (path[0] == '@') {
        address.sun_path[0] = '\0';
        memcpy (address.sun_path + 1, path + 1, path_length - 1);
    } else {
        memcpy (address.sun_path, path, path_length + 1);
    }

    fd = socket (AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    if (connect (fd, (struct sockaddr *)&address,
                 (socklen_t)(offsetof (struct sockaddr_un, sun_path) +
                             path_length + (path[0] == '@' ? 0 : 1))) < 0) {
        close (fd);
        return -1;
    }
    snprintf (line, sizeof (line), "%s %s\n", command, token);
    if (write_all (fd, line, strlen (line)) < 0) {
        close (fd);
        return -1;
    }
    return fd;
}

static int
receive_tun (const char *path, const char *token)
{
    char data[1];
    char ancillary[CMSG_SPACE (sizeof (int))];
    struct iovec vector = { data, sizeof (data) };
    struct msghdr message;
    struct cmsghdr *header;
    int fd;
    ssize_t received;

    fd = connect_control (path, token, "TUN");
    if (fd < 0)
        return -1;
    memset (&message, 0, sizeof (message));
    memset (ancillary, 0, sizeof (ancillary));
    message.msg_iov = &vector;
    message.msg_iovlen = 1;
    message.msg_control = ancillary;
    message.msg_controllen = sizeof (ancillary);
    received = recvmsg (fd, &message, 0);
    close (fd);
    if (received != 1 || data[0] != 1 || (message.msg_flags & MSG_CTRUNC))
        return -1;
    header = CMSG_FIRSTHDR (&message);
    if (!header || header->cmsg_level != SOL_SOCKET ||
        header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len < CMSG_LEN (sizeof (int)))
        return -1;
    fd = *(int *)CMSG_DATA (header);
    fcntl (fd, F_SETFD, FD_CLOEXEC);
    return fd;
}

/* This mirrors the pinned upstream main sequence so ready is emitted only
 * after configuration, task-system and tunnel initialization all succeed. */
static void *
tunnel_entry (void *unused)
{
    const char *log_file;
    int log_level;
    int config_initialized = 0;
    int logger_initialized = 0;
    int socks_logger_initialized = 0;
    int task_system_initialized = 0;
    int tunnel_initialized = 0;
    int res;
    (void)unused;

    tunnel_result = -1;
    res = hev_config_init_from_file (config_path);
    if (res < 0)
        goto done;
    config_initialized = 1;

    log_file = hev_config_get_misc_log_file ();
    log_level = hev_config_get_misc_log_level ();
    hev_socks5_set_connect_timeout (hev_config_get_misc_connect_timeout ());
    hev_socks5_set_tcp_timeout (hev_config_get_misc_tcp_read_write_timeout ());
    hev_socks5_set_udp_timeout (hev_config_get_misc_udp_read_write_timeout ());
    hev_socks5_set_udp_recv_buffer_size (hev_config_get_misc_udp_recv_buffer_size ());

    if (hev_logger_init (log_level, log_file) < 0)
        goto done;
    logger_initialized = 1;
    if (hev_socks5_logger_init (log_level, log_file) < 0)
        goto done;
    socks_logger_initialized = 1;
    (void)set_limit_nofile (hev_config_get_misc_limit_nofile ());

    if (hev_task_system_init () < 0)
        goto done;
    task_system_initialized = 1;
    lwip_init ();
    if (hev_socks5_tunnel_init (tun_fd) < 0)
        goto done;
    tunnel_initialized = 1;

    atomic_store (&tunnel_ready, 1);
    emit_line ("{\"event\":\"ready\"}");
    res = hev_socks5_tunnel_run ();

    /* Serialize the final snapshot with the polling thread and take it before
       upstream fini resets the counters. */
    pthread_mutex_lock (&stats_mutex);
    emit_traffic ();
    atomic_store (&tunnel_done, 1);
    hev_socks5_tunnel_fini ();
    tunnel_initialized = 0;
    pthread_mutex_unlock (&stats_mutex);
    tunnel_result = res < 0 ? -1 : 0;

done:
    atomic_store (&tunnel_done, 1);
    if (tunnel_initialized)
        hev_socks5_tunnel_fini ();
    if (task_system_initialized)
        hev_task_system_fini ();
    if (socks_logger_initialized)
        hev_socks5_logger_fini ();
    if (logger_initialized)
        hev_logger_fini ();
    if (config_initialized)
        hev_config_fini ();
    if (tunnel_result < 0)
        emit_error ("HEV initialization or runtime failed");
    return NULL;
}

static void *
watcher_entry (void *unused)
{
    char byte;
    (void)unused;

    (void)read (watch_fd, &byte, sizeof (byte));
    while (!atomic_load (&tunnel_ready) && !atomic_load (&tunnel_done))
        usleep (10 * 1000);
    if (atomic_load (&tunnel_ready) && !atomic_load (&tunnel_done))
        hev_socks5_tunnel_stop ();
    return NULL;
}

static void *
stats_entry (void *unused)
{
    (void)unused;
    while (!atomic_load (&tunnel_done)) {
        usleep (1000 * 1000);
        if (!atomic_load (&tunnel_ready) || atomic_load (&tunnel_done))
            continue;
        pthread_mutex_lock (&stats_mutex);
        if (!atomic_load (&tunnel_done))
            emit_traffic ();
        pthread_mutex_unlock (&stats_mutex);
    }
    return NULL;
}

static const char *
argument (int argc, char **argv, const char *name)
{
    int i;
    for (i = 1; i + 1 < argc; i++) {
        if (strcmp (argv[i], name) == 0)
            return argv[i + 1];
    }
    return NULL;
}

int
main (int argc, char **argv)
{
    const char *control_path;
    const char *token;
    int watcher_created = 0;
    int stats_created = 0;

    config_path = argument (argc, argv, "--config");
    control_path = argument (argc, argv, "--control");
    token = argument (argc, argv, "--token");
    if (!config_path || !control_path || !token || strlen (token) < 16) {
        emit_error ("Invalid HEV runner arguments");
        return 2;
    }

    tun_fd = receive_tun (control_path, token);
    if (tun_fd < 0) {
        emit_error ("Unable to receive Android TUN descriptor");
        return 3;
    }
    watch_fd = connect_control (control_path, token, "WATCH");
    if (watch_fd < 0) {
        close (tun_fd);
        emit_error ("Unable to establish HEV lifecycle channel");
        return 4;
    }

    atomic_init (&tunnel_ready, 0);
    atomic_init (&tunnel_done, 0);
    if (pthread_create (&watcher_thread, NULL, watcher_entry, NULL) == 0)
        watcher_created = 1;
    if (watcher_created && pthread_create (&stats_thread, NULL, stats_entry, NULL) == 0)
        stats_created = 1;
    if (!watcher_created || !stats_created ||
        pthread_create (&tunnel_thread, NULL, tunnel_entry, NULL) != 0) {
        atomic_store (&tunnel_done, 1);
        shutdown (watch_fd, SHUT_RDWR);
        close (watch_fd);
        if (watcher_created)
            pthread_join (watcher_thread, NULL);
        if (stats_created)
            pthread_join (stats_thread, NULL);
        close (tun_fd);
        emit_error ("Unable to create HEV worker threads");
        return 5;
    }

    pthread_join (tunnel_thread, NULL);
    shutdown (watch_fd, SHUT_RDWR);
    close (watch_fd);
    watch_fd = -1;
    pthread_join (watcher_thread, NULL);
    pthread_join (stats_thread, NULL);
    close (tun_fd);
    tun_fd = -1;
    return tunnel_result < 0 ? 6 : 0;
}
