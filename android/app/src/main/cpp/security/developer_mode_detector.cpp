#include "security.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_DEVELOPER_MODE
#define CHIMERA_DETECT_DEVELOPER_MODE 1
#endif

namespace chimera {
namespace security {

void detectDeveloperMode(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_DEVELOPER_MODE
    // 读取系统开发者选项开关；检测到开启时仅上报，不限制使用。
    jint developerModeEnabled = 0;
    if (detection_utils::getGlobalSettingInt(
                env, context, "development_settings_enabled", 0, &developerModeEnabled) &&
            developerModeEnabled != 0) {
        addFinding(findings, "developer_options_enabled");
    }
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
