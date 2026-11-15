#include "security.h"
#include "detection_utils.h"

#include <android/log.h>
#include <elf.h>
#include <errno.h>
#include <link.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <limits>
#include <sstream>

namespace chimera {
namespace security {
namespace {

bool isNumeric(const std::string& value) {
    return !value.empty() && std::all_of(value.begin(), value.end(), [](char ch) { return ch >= '0' && ch <= '9'; });
}

bool statusHasTracer(const std::string& status, bool* available) {
    *available = false;
    std::istringstream lines(status);
    std::string line;
    while (std::getline(lines, line)) {
        if (line.rfind("tracerpid:", 0) != 0) continue;
        std::istringstream value(line.substr(10));
        int pid = -1;
        value >> pid;
        *available = !value.fail() && pid >= 0;
        return *available && pid > 0;
    }
    return false;
}

bool hasFridaThread(bool* available, bool* traced) {
    // gmain/gdbus are ordinary GLib names and are not evidence on their own.
    static const char* kThreadMarkers[] = {"frida", "gum-js-loop", "linjector"};
    std::vector<std::string> threads;
    *traced = false;
    *available = detection_utils::readDirectory("/proc/self/task", &threads);
    if (!*available) return false;
    bool inspectedThread = false;
    for (const auto& thread : threads) {
        if (!isNumeric(thread)) continue;
        const std::string path = "/proc/self/task/" + thread + "/status";
        std::string status;
        if (!detection_utils::readTextFile(path.c_str(), &status)) continue; // Threads may exit during enumeration.
        bool tracerAvailable = false;
        if (statusHasTracer(status, &tracerAvailable)) { *traced = true; return false; }
        inspectedThread = inspectedThread || tracerAvailable;
        const auto nameEnd = status.find('\n');
        const std::string name = status.substr(0, nameEnd);
        if (name.rfind("name:", 0) == 0 && detection_utils::containsAny(
                name, kThreadMarkers, sizeof(kThreadMarkers) / sizeof(kThreadMarkers[0]))) return true;
    }
    *available = inspectedThread;
    return false;
}

bool hasFridaDescriptor(bool* available) {
    std::vector<std::string> descriptors;
    *available = detection_utils::readDirectory("/proc/self/fd", &descriptors);
    if (!*available) return false;
    static const char* kMarkers[] = {"linjector", "frida-agent", "frida-gadget", "frida-pipe", "frida-server"};
    for (const auto& descriptor : descriptors) {
        if (!isNumeric(descriptor)) continue;
        std::string target;
        const std::string path = "/proc/self/fd/" + descriptor;
        // FDs may close between getdents64 and readlinkat; a disappearing FD is normal.
        if (detection_utils::readLink(path.c_str(), &target) && detection_utils::containsAny(
                target, kMarkers, sizeof(kMarkers) / sizeof(kMarkers[0]))) return true;
    }
    return false;
}

bool waitFor(int fd, short events, std::chrono::steady_clock::time_point deadline) {
    while (true) {
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        if (remaining <= 0) return false;
        pollfd descriptor{fd, events, 0};
        const int result = poll(&descriptor, 1, static_cast<int>(remaining));
        if (result < 0 && errno == EINTR) continue;
        return result > 0 && (descriptor.revents & events) != 0;
    }
}

struct ListenerProbe { bool open = false; bool dbus = false; };

ListenerProbe probeListener(int family, uint16_t port) {
    ListenerProbe result;
    const int fd = socket(family, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return result;
    sockaddr_in address4{};
    address4.sin_family = AF_INET;
    address4.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address4.sin_port = htons(port);
    sockaddr_in6 address6{};
    address6.sin6_family = AF_INET6;
    address6.sin6_addr = in6addr_loopback;
    address6.sin6_port = htons(port);
    const auto* address = family == AF_INET ? reinterpret_cast<const sockaddr*>(&address4) : reinterpret_cast<const sockaddr*>(&address6);
    const socklen_t length = family == AF_INET ? sizeof(address4) : sizeof(address6);
    const int connected = connect(fd, address, length);
    result.open = connected == 0;
    if (!result.open && errno == EINPROGRESS && waitFor(fd, POLLOUT,
            std::chrono::steady_clock::now() + std::chrono::milliseconds(40))) {
        int error = 0;
        socklen_t size = sizeof(error);
        result.open = getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) == 0 && error == 0;
    }
    if (result.open) {
        // Probe legacy Frida D-Bus authentication, including its mandatory leading NUL.
        static const char kAuth[] = "\0AUTH\r\n";
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(80);
        size_t sent = 0;
        while (sent < sizeof(kAuth) - 1 && waitFor(fd, POLLOUT, deadline)) {
            const ssize_t bytes = send(fd, kAuth + sent, sizeof(kAuth) - 1 - sent, MSG_NOSIGNAL | MSG_DONTWAIT);
            if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (bytes <= 0) break;
            sent += static_cast<size_t>(bytes);
        }
        std::string response;
        std::array<char, 256> buffer{};
        while (sent == sizeof(kAuth) - 1 && response.size() < buffer.size() && waitFor(fd, POLLIN, deadline)) {
            const ssize_t bytes = recv(fd, buffer.data(), buffer.size() - response.size(), MSG_DONTWAIT);
            if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (bytes <= 0) break;
            response.append(buffer.data(), static_cast<size_t>(bytes));
            const size_t end = response.find("\r\n");
            if (end == std::string::npos) continue;
            const std::string line = response.substr(0, end);
            result.dbus = line == "REJECTED" || line.rfind("REJECTED ", 0) == 0;
            if (line.rfind("OK ", 0) == 0 && line.size() == 35) {
                result.dbus = std::all_of(line.begin() + 3, line.end(), [](char ch) {
                    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
                });
            }
            break;
        }
    }
    close(fd);
    return result;
}

bool hasTracerPid(bool* available) {
    std::string status;
    *available = false;
    if (!detection_utils::readTextFile("/proc/self/status", &status)) return false;
    return statusHasTracer(status, available);
}

struct MemoryMap {
    uintptr_t start = 0;
    uintptr_t end = 0;
    uint64_t offset = 0;
    std::string permissions;
    std::string path;
};

bool parseHex(const std::string& value, uint64_t* result) {
    if (value.empty()) return false;
    *result = 0;
    for (char ch : value) {
        unsigned digit;
        if (ch >= '0' && ch <= '9') digit = ch - '0';
        else if (ch >= 'a' && ch <= 'f') digit = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'F') digit = ch - 'A' + 10;
        else return false;
        if (*result > (std::numeric_limits<uint64_t>::max() - digit) / 16) return false;
        *result = *result * 16 + digit;
    }
    return true;
}

std::vector<MemoryMap> parseMaps(const std::string& text) {
    std::vector<MemoryMap> maps;
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        std::istringstream fields(line);
        std::string range, permissions, offset, device, inode;
        if (!(fields >> range >> permissions >> offset >> device >> inode) || permissions.size() != 4) continue;
        const auto dash = range.find('-');
        uint64_t start = 0, end = 0, fileOffset = 0;
        if (dash == std::string::npos || !parseHex(range.substr(0, dash), &start) ||
                !parseHex(range.substr(dash + 1), &end) || !parseHex(offset, &fileOffset) || start >= end ||
                end > std::numeric_limits<uintptr_t>::max()) continue;
        std::string path;
        std::getline(fields >> std::ws, path);
        maps.push_back({static_cast<uintptr_t>(start), static_cast<uintptr_t>(end), fileOffset, permissions, path});
    }
    return maps;
}

struct IntegrityProbe {
    const std::vector<MemoryMap>* maps;
    size_t bytes = 0;
    bool selfChecked = false;
    bool libcChecked = false;
    bool limited = false;
    bool changed = false;
    bool writable = false;
};

int compareLoadedLibrary(dl_phdr_info* info, size_t, void* opaque) {
    auto* probe = static_cast<IntegrityProbe*>(opaque);
    const std::string name = info->dlpi_name == nullptr ? "" : info->dlpi_name;
    const auto separator = name.find_last_of('/');
    const bool libc = name.substr(separator == std::string::npos ? 0 : separator + 1) == "libc.so";
    const uintptr_t ownCode = reinterpret_cast<uintptr_t>(&detectFrida);
    bool self = false;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const auto& segment = info->dlpi_phdr[i];
        if (segment.p_type != PT_LOAD || (segment.p_flags & PF_X) == 0) continue;
        const uintptr_t start = info->dlpi_addr + segment.p_vaddr;
        if (start <= ownCode && ownCode - start < segment.p_memsz) self = true;
    }
    if (!self && !libc) return 0;
    bool complete = true;
    bool inspected = false;
    constexpr size_t kMaximumBytes = 16 * 1024 * 1024;
    // Use PT_LOAD ranges and actual map offsets: valid for extracted SOs and APK-backed SOs.
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const auto& segment = info->dlpi_phdr[i];
        if (segment.p_type != PT_LOAD || (segment.p_flags & PF_X) == 0 || segment.p_filesz == 0) continue;
        const uintptr_t start = info->dlpi_addr + segment.p_vaddr;
        if (segment.p_filesz > kMaximumBytes - probe->bytes ||
                segment.p_filesz > std::numeric_limits<uintptr_t>::max() - start) { complete = false; continue; }
        probe->bytes += segment.p_filesz;
        const uintptr_t end = start + segment.p_filesz;
        uintptr_t position = start;
        while (position < end) {
            const auto map = std::find_if(probe->maps->begin(), probe->maps->end(), [position](const MemoryMap& item) {
                return item.start <= position && position < item.end;
            });
            if (map == probe->maps->end() || map->permissions[0] != 'r' || map->permissions[2] != 'x' ||
                    map->permissions[3] != 'p' || map->path.empty() || map->path[0] != '/') { complete = false; break; }
            if (map->permissions[1] == 'w') { probe->writable = true; return 1; }
            const size_t count = std::min(end, map->end) - position;
            const uint64_t displacement = position - map->start;
            if (displacement > std::numeric_limits<uint64_t>::max() - map->offset) { complete = false; break; }
            const auto result = detection_utils::compareFileToMemory(map->path.c_str(), map->offset + displacement, position, count);
            if (result == detection_utils::MemoryComparison::DIFFERENT) { probe->changed = true; return 1; }
            if (result == detection_utils::MemoryComparison::UNAVAILABLE) { complete = false; break; }
            inspected = true;
            position += count;
        }
    }
    if (complete && inspected) {
        if (self) probe->selfChecked = true;
        if (libc) probe->libcChecked = true;
    } else probe->limited = true;
    return 0;
}

void inspectNativeIntegrity(const std::string& mapsText, std::vector<std::string>* findings) {
    const auto maps = parseMaps(mapsText);
    IntegrityProbe probe{&maps};
    dl_iterate_phdr(compareLoadedLibrary, &probe);
    if (probe.changed) addFinding(findings, "native_code_modified");
    if (probe.writable) addFinding(findings, "native_code_writable");
    if (probe.limited || !probe.selfChecked || !probe.libcChecked) {
        // This is a capability status, not a finding. Never claim unsupported memory checks passed.
        findings->push_back("frida_integrity_unavailable");
        __android_log_print(ANDROID_LOG_INFO, "NativeSecurity", "Native text integrity inspection is limited on this system");
    }
}

}  // namespace

void detectFrida(std::vector<std::string>* findings) {
#if CHIMERA_DETECT_FRIDA
    std::string maps;
    static const char* kMaps[] = {"frida", "linjector", "libgadget"};
    if (!detection_utils::readFile("/proc/self/maps", &maps) || maps.empty()) {
        addFinding(findings, "process_maps_unavailable");
    } else {
        if (detection_utils::containsAny(detection_utils::toLower(maps), kMaps, sizeof(kMaps) / sizeof(kMaps[0]))) {
            addFinding(findings, "frida_maps");
        }
        inspectNativeIntegrity(maps, findings);
    }
    bool threadsAvailable = false;
    bool threadTraced = false;
    if (hasFridaThread(&threadsAvailable, &threadTraced)) addFinding(findings, "frida_thread");
    if (threadTraced) addFinding(findings, "instrumentation_thread_tracer");
    if (!threadsAvailable) addFinding(findings, "frida_threads_unavailable");
    bool descriptorsAvailable = false;
    if (hasFridaDescriptor(&descriptorsAvailable)) addFinding(findings, "frida_named_pipe");
    if (!descriptorsAvailable) addFinding(findings, "frida_fds_unavailable");
    static const char* kServerPaths[] = {
        "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server",
        "/system/bin/frida-server", "/system/xbin/frida-server"
    };
    for (const char* path : kServerPaths) {
        if (detection_utils::fileExists(path)) { addFinding(findings, "frida_server_artifact"); break; }
    }
    bool openPort = false;
    bool dbus = false;
    for (const auto port : {27042, 27043}) {
        for (const auto family : {AF_INET, AF_INET6}) {
            const auto result = probeListener(family, port);
            openPort = openPort || result.open;
            dbus = dbus || result.dbus;
        }
    }
    if (dbus) addFinding(findings, "frida_dbus");
    else if (openPort) addFinding(findings, "frida_listener"); // Preserve the selected strict port policy.
    bool tracerAvailable = false;
    if (hasTracerPid(&tracerAvailable)) addFinding(findings, "instrumentation_tracer");
    if (!tracerAvailable) addFinding(findings, "tracer_status_unavailable");
#else
    (void) findings;
#endif
}

}  // namespace security
}  // namespace chimera
