#include "security_detector.h"

#include "detection_utils.h"

#include <dirent.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdlib>
#include <fstream>
#include <sstream>

#ifndef CHIMERA_DETECT_FRIDA
#define CHIMERA_DETECT_FRIDA 1
#endif

namespace chimera {
namespace security {
namespace {

bool hasFridaThread(bool* available) {
    // gmain/gdbus 也被正常 GLib 应用使用，不能单独作为 Frida 证据。
    static const char* kThreadMarkers[] = {"frida", "gum-js-loop"};
    DIR* taskDirectory = opendir("/proc/self/task");
    *available = taskDirectory != nullptr;
    if (taskDirectory == nullptr) return false;
    bool detected = false;
    dirent* entry = nullptr;
    while (!detected && (entry = readdir(taskDirectory)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        std::string path = "/proc/self/task/";
        path += entry->d_name;
        path += "/comm";
        std::string threadName;
        if (detection_utils::readTextFile(path.c_str(), &threadName) &&
                detection_utils::containsAny(threadName, kThreadMarkers,
                        sizeof(kThreadMarkers) / sizeof(kThreadMarkers[0]))) {
            detected = true;
        }
    }
    closedir(taskDirectory);
    return detected;
}

bool loopbackPortOpen(int family, uint16_t port) {
    const int fd = socket(family, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return false;
    sockaddr_in address4{};
    address4.sin_family = AF_INET;
    address4.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address4.sin_port = htons(port);
    sockaddr_in6 address6{};
    address6.sin6_family = AF_INET6;
    address6.sin6_addr = in6addr_loopback;
    address6.sin6_port = htons(port);
    const auto* address = family == AF_INET
            ? reinterpret_cast<const sockaddr*>(&address4)
            : reinterpret_cast<const sockaddr*>(&address6);
    const socklen_t length = family == AF_INET ? sizeof(address4) : sizeof(address6);
    const int result = connect(fd, address, length);
    bool connected = result == 0;
    if (!connected && errno == EINPROGRESS) {
        pollfd descriptor{fd, POLLOUT, 0};
        if (poll(&descriptor, 1, 40) > 0) {
            int error = 0;
            socklen_t errorLength = sizeof(error);
            connected = getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &errorLength) == 0 && error == 0;
        }
    }
    close(fd);
    return connected;
}

bool hasFridaListener() {
    // Android 10+ 不允许普通应用读取 /proc/net，改为有界的本机连接探测。
    // 开放端口只作为弱信号，不单独触发终止或网络封锁。
    for (const auto port : {27042, 27043}) {
        if (loopbackPortOpen(AF_INET, port) || loopbackPortOpen(AF_INET6, port)) return true;
    }
    return false;
}

bool hasTracerPid(bool* available) {
    std::ifstream input("/proc/self/status");
    *available = false;
    if (!input.is_open()) return false;
    std::string line;
    while (std::getline(input, line)) {
        if (line.compare(0, 10, "TracerPid:") == 0) {
            std::istringstream value(line.substr(10));
            int tracerPid = 0;
            value >> tracerPid;
            *available = !value.fail();
            return tracerPid > 0;
        }
    }
    return false;
}

}  // 匿名命名空间结束

void detectFrida(std::vector<std::string>* findings) {
#if CHIMERA_DETECT_FRIDA
    // 汇总映射、线程名、监听端口和 tracer PID 信号。
    static const char* kMapMarkers[] = {"frida", "linjector", "libgadget"};
    std::string maps;
    if (!detection_utils::readTextFile("/proc/self/maps", &maps)) {
        findings->push_back("process_maps_unavailable");
    } else if (detection_utils::containsAny(maps, kMapMarkers, sizeof(kMapMarkers) / sizeof(kMapMarkers[0]))) {
        findings->push_back("frida_maps");
    }
    bool threadsAvailable = false;
    if (hasFridaThread(&threadsAvailable)) findings->push_back("frida_thread");
    if (!threadsAvailable) findings->push_back("frida_threads_unavailable");
    if (hasFridaListener()) findings->push_back("frida_listener");
    bool tracerAvailable = false;
    if (hasTracerPid(&tracerAvailable)) findings->push_back("instrumentation_tracer");
    if (!tracerAvailable) findings->push_back("tracer_status_unavailable");
#else
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
