#include "security_detector.h"

#include "detection_utils.h"

#include <dirent.h>

#include <cstdlib>
#include <fstream>
#include <sstream>

#ifndef CHIMERA_DETECT_FRIDA
#define CHIMERA_DETECT_FRIDA 1
#endif

namespace chimera {
namespace security {
namespace {

bool hasFridaThread() {
    static const char* kThreadMarkers[] = {"frida", "gum-js-loop", "gmain", "gdbus"};
    DIR* taskDirectory = opendir("/proc/self/task");
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

bool hasFridaListener() {
    const char* paths[] = {"/proc/net/tcp", "/proc/net/tcp6"};
    for (size_t pathIndex = 0; pathIndex < sizeof(paths) / sizeof(paths[0]); ++pathIndex) {
        std::ifstream input(paths[pathIndex]);
        if (!input.is_open()) continue;
        std::string line;
        std::getline(input, line);  // 跳过表头行。
        while (std::getline(input, line)) {
            std::istringstream fields(line);
            std::string slot;
            std::string localAddress;
            std::string remoteAddress;
            std::string state;
            fields >> slot >> localAddress >> remoteAddress >> state;
            const size_t colon = localAddress.find(':');
            if (colon == std::string::npos || state != "0A") continue;
            const long port = std::strtol(localAddress.c_str() + colon + 1, nullptr, 16);
            if (port == 27042 || port == 27043) return true;
        }
    }
    return false;
}

bool hasTracerPid() {
    std::ifstream input("/proc/self/status");
    if (!input.is_open()) return false;
    std::string line;
    while (std::getline(input, line)) {
        if (line.compare(0, 10, "TracerPid:") == 0) {
            std::istringstream value(line.substr(10));
            int tracerPid = 0;
            value >> tracerPid;
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
    if (detection_utils::mapsContain(kMapMarkers, sizeof(kMapMarkers) / sizeof(kMapMarkers[0]))) {
        findings->push_back("frida_maps");
    }
    if (hasFridaThread()) findings->push_back("frida_thread");
    if (hasFridaListener()) findings->push_back("frida_listener");
    if (hasTracerPid()) findings->push_back("instrumentation_tracer");
#else
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
