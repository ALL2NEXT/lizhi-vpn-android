#include "security.h"
#include "detection_utils.h"

#include <sys/system_properties.h>
#include <unistd.h>

#include <cstdlib>
#include <sstream>

namespace chimera {
namespace security {

void detectRoot(std::vector<std::string>* findings) {
#if CHIMERA_DETECT_ROOT
    if (geteuid() == 0) addFinding(findings, "root_uid");
    // Inspect artifacts only; never execute su or request superuser authorization.
    static const char* kSuPaths[] = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/bin/failsafe/su", "/system/sd/xbin/su", "/data/local/bin/su",
        "/data/local/xbin/su", "/data/local/su", "/debug_ramdisk/su", "/data/adb/magisk/magisk"
    };
    for (const char* path : kSuPaths) {
        if (detection_utils::fileExists(path)) { addFinding(findings, "root_su_binary"); break; }
    }
    const char* environmentPath = std::getenv("PATH");
    if (environmentPath != nullptr) {
        std::istringstream paths(std::string(environmentPath).substr(0, 8192));
        std::string directory;
        for (size_t count = 0; count < 64 && std::getline(paths, directory, ':'); ++count) {
            if (directory.empty() || directory.front() != '/') continue;
            if (detection_utils::fileExists((directory + "/su").c_str())) {
                addFinding(findings, "root_su_binary");
                break;
            }
        }
    }
    static const char* kRootPaths[] = {
        "/sbin/.magisk", "/debug_ramdisk/.magisk", "/dev/.magisk",
        "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap", "/data/adb/modules"
    };
    for (const char* path : kRootPaths) {
        if (detection_utils::fileExists(path)) { addFinding(findings, "root_framework_artifact"); break; }
    }
    std::string mounts;
    if (!detection_utils::readTextFile("/proc/self/mountinfo", &mounts) || mounts.empty()) {
        addFinding(findings, "root_mounts_unavailable");
    } else {
        // Generic overlay/tmpfs mounts also occur on stock Android; require framework-specific evidence.
        static const char* kMountMarkers[] = {
            "magisk", "/data/adb/modules", "/data/adb/ksu", "/data/adb/ap/", "kernelsu", "apatch"
        };
        if (detection_utils::containsAny(mounts, kMountMarkers, sizeof(kMountMarkers) / sizeof(kMountMarkers[0]))) {
            addFinding(findings, "root_mount");
        }
    }
    static const char* kContexts[] = {"/proc/self/attr/current", "/proc/self/attr/prev"};
    for (const char* path : kContexts) {
        std::string context;
        static const char* kContextMarkers[] = {"u:r:magisk:", "u:r:su:", "u:r:zygisk:", "u:r:ksu:"};
        if (detection_utils::readTextFile(path, &context) && detection_utils::containsAny(
                context, kContextMarkers, sizeof(kContextMarkers) / sizeof(kContextMarkers[0]))) {
            addFinding(findings, "root_selinux_context");
            break;
        }
    }
    std::string status;
    if (!detection_utils::readTextFile("/proc/self/status", &status) || status.empty()) {
        addFinding(findings, "root_status_unavailable");
    } else {
        std::istringstream lines(status);
        std::string line;
        bool readCapabilities = false;
        while (std::getline(lines, line)) {
            if (line.rfind("capeff:", 0) != 0) continue;
            std::istringstream value(line.substr(7));
            unsigned long long capabilities = 0;
            value >> std::hex >> capabilities;
            readCapabilities = !value.fail();
            if (readCapabilities && capabilities != 0) addFinding(findings, "root_capabilities");
            break;
        }
        if (!readCapabilities) addFinding(findings, "root_status_unavailable");
    }
    // Debuggable/test-keys builds alone are not proof of Root.
    static const char* kMagiskProperties[] = {"init.svc.magisk_daemon", "init.svc.magisk_service"};
    for (const char* name : kMagiskProperties) {
        char value[PROP_VALUE_MAX]{};
        if (__system_property_get(name, value) > 0 && std::string(value) == "running") {
            addFinding(findings, "root_magisk_service");
            break;
        }
    }
#else
    (void) findings;
#endif
}

}  // namespace security
}  // namespace chimera
