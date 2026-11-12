#include "security_detector.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_XPOSED
#define CHIMERA_DETECT_XPOSED 1
#endif

namespace chimera {
namespace security {

void detectXposed(std::vector<std::string>* findings) {
#if CHIMERA_DETECT_XPOSED
    // 检查进程映射和常见 Xposed 文件痕迹。
    static const char* kMapMarkers[] = {"xposedbridge", "libxposed", "edxp", "riru_xposed"};
    if (detection_utils::mapsContain(kMapMarkers, sizeof(kMapMarkers) / sizeof(kMapMarkers[0])) ||
            detection_utils::fileExists("/system/framework/XposedBridge.jar") ||
            detection_utils::fileExists("/system/lib/libxposed_art.so") ||
            detection_utils::fileExists("/system/lib64/libxposed_art.so")) {
        findings->push_back("xposed_artifact");
    }
#else
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
