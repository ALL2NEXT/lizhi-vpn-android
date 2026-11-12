#include "security.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_ADB
#define CHIMERA_DETECT_ADB 1
#endif

namespace chimera {
namespace security {

void detectAdb(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_ADB
    // 读取系统 ADB 调试开关；不检查设备是否正连接到电脑。
    jint adbEnabled = 0;
    if (detection_utils::getGlobalSettingInt(env, context, "adb_enabled", 0, &adbEnabled) &&
            adbEnabled != 0) {
        addFinding(findings, "adb_enabled");
        reportFindings(env, context, {"adb_enabled"});
    }
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
