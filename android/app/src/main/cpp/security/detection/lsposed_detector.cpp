#include "security_detector.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_LSPOSED
#define CHIMERA_DETECT_LSPOSED 1
#endif

namespace chimera {
namespace security {

void detectLsposed(std::vector<std::string>* findings) {
#if CHIMERA_DETECT_LSPOSED
    // 检查进程映射和常见 LSPosed 安装路径。
    static const char* kMapMarkers[] = {"lsposed", "lspd", "liblspd"};
    if (detection_utils::mapsContain(kMapMarkers, sizeof(kMapMarkers) / sizeof(kMapMarkers[0])) ||
            detection_utils::fileExists("/data/adb/lspd") ||
            detection_utils::fileExists("/data/adb/modules/zygisk_lsposed") ||
            detection_utils::fileExists("/data/adb/modules/lsposed")) {
        findings->push_back("lsposed_artifact");
    }
#else
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
