/*
 * Android process wrapper for hev-socks5-tunnel.
 *
 * The wrapper deliberately keeps packet data inside HEV. The control socket
 * only transfers the Android TUN descriptor, parent liveness and statistics.
 * Running this as a PIE child process prevents a native HEV abort from taking
 * down the Kotlin VPN controller.
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

#include "hev-main.h"

#define CONTROL_BUFFER 256
#define STOP_GRACE_MS 3000

static pthread_mutex_t output_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_t tunnel_thread;
static pthread_t watcher_thread;
static pthread_t stats_thread;
static int watch_fd = -1;
static int tun_fd = -1;
static const char *config_path;
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
        /* LocalServerSocket uses the Linux abstract namespace. */
        if (path_length >= sizeof (address.sun_path))
            return -1;
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

static void *
tunnel_entry (void *unused)
{
    (void)unused;
    tunnel_result = hev_socks5_tunnel_main (config_path, tun_fd);
    atomic_store (&tunnel_done, 1);
    return NULL;
}

static void *
watcher_entry (void *unused)
{
    char byte;
    int elapsed = 0;
    (void)unused;

    (void)read (watch_fd, &byte, sizeof (byte));
    /* hev_socks5_tunnel_quit waits for initialization. Bound the wait and
       let the parent process kill this worker if configuration never starts. */
    while (!atomic_load (&tunnel_done) && elapsed < STOP_GRACE_MS) {
        usleep (100 * 1000);
        elapsed += 100;
    }
    if (!atomic_load (&tunnel_done))
        hev_socks5_tunnel_quit ();
    return NULL;
}

static void *
stats_entry (void *unused)
{
    char line[CONTROL_BUFFER];
    size_t tx_packets, tx_bytes, rx_packets, rx_bytes;
    (void)unused;

    while (!atomic_load (&tunnel_done)) {
        sleep (1);
        hev_socks5_tunnel_stats (&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
        snprintf (line, sizeof (line),
                  "{\"event\":\"traffic\",\"upload\":%zu,\"download\":%zu}",
                  tx_bytes, rx_bytes);
        emit_line (line);
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

    config_path = argument (argc, argv, "--config");
    control_path = argument (argc, argv, "--control");
    token = argument (argc, argv, "--token");
    if (!config_path || !control_path || !token || strlen (token) < 16) {
        emit_error ("HEV runner 参数无效");
        return 2;
    }

    tun_fd = receive_tun (control_path, token);
    if (tun_fd < 0) {
        emit_error ("无法接收 Android TUN 描述符");
        return 3;
    }
    watch_fd = connect_control (control_path, token, "WATCH");
    if (watch_fd < 0) {
        close (tun_fd);
        emit_error ("无法建立 HEV 生命周期通道");
        return 4;
    }

    atomic_init (&tunnel_done, 0);
    if (pthread_create (&tunnel_thread, NULL, tunnel_entry, NULL) != 0 ||
        pthread_create (&watcher_thread, NULL, watcher_entry, NULL) != 0 ||
        pthread_create (&stats_thread, NULL, stats_entry, NULL) != 0) {
        emit_error ("无法创建 HEV 工作线程");
        close (watch_fd);
        return 5;
    }

    /* The API is blocking and exposes no ready callback. The host checks that
       the child remains alive after this event; an immediate startup error is
       therefore still reported as a closed process/output stream. */
    emit_line ("{\"event\":\"ready\"}");
    pthread_join (tunnel_thread, NULL);
    close (watch_fd);
    watch_fd = -1;
    pthread_join (watcher_thread, NULL);
    pthread_join (stats_thread, NULL);
    close (tun_fd);
    tun_fd = -1;

    if (tunnel_result < 0)
        emit_error ("HEV 原生运行失败");
    return tunnel_result < 0 ? 6 : 0;
}
