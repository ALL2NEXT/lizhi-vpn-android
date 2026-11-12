#include "security.h"

#include "detection_utils.h"

#include <android/log.h>

#ifndef CHIMERA_DETECT_SCREENSHOT
#define CHIMERA_DETECT_SCREENSHOT 1
#endif

#ifndef CHIMERA_BLOCK_SCREENSHOTS
#define CHIMERA_BLOCK_SCREENSHOTS 1
#endif

#define TAG "NativeSecurity"

namespace chimera {
namespace security {

void applyScreenshotProtection(JNIEnv* env, jobject activity) {
#if CHIMERA_BLOCK_SCREENSHOTS
    if (env == nullptr || activity == nullptr) {
        fatalFinding(nullptr, "screenshot_protection_unavailable");
        return;
    }
    jclass actions = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    jmethodID block = actions == nullptr ? nullptr : env->GetStaticMethodID(
            actions, "blockScreenshots", "(Landroid/app/Activity;)V");
    if (block == nullptr) {
        detection_utils::clearPendingException(env);
        if (actions != nullptr) env->DeleteLocalRef(actions);
        fatalFinding(nullptr, "screenshot_protection_unavailable");
        return;
    }
    env->CallStaticVoidMethod(actions, block, activity);
    const bool failed = env->ExceptionCheck();
    detection_utils::clearPendingException(env);
    env->DeleteLocalRef(actions);
    if (failed) fatalFinding(nullptr, "screenshot_protection_unavailable");
#else
    (void) env;
    (void) activity;
#endif
}

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
