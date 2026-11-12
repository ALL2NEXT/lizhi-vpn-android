#include "security_detector.h"

#include <android/log.h>

#ifndef CHIMERA_DETECT_SCREENSHOT
#define CHIMERA_DETECT_SCREENSHOT 1
#endif

#define TAG "NativeSecurity"

namespace chimera {
namespace security {

jstring reportScreenshotCapture(JNIEnv* env) {
#if CHIMERA_DETECT_SCREENSHOT
    // 将 Android 截图回调转换为统一检测码。
    __android_log_print(ANDROID_LOG_INFO, TAG, "Screenshot capture callback received");
    return env->NewStringUTF("screenshot_capture");
#else
    (void) env;
    return nullptr;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
