#include "security.h"

#include "detection_utils.h"

#ifndef CHIMERA_BLOCK_SCREENSHOTS
#define CHIMERA_BLOCK_SCREENSHOTS 1
#endif

namespace chimera {
namespace security {

void applyScreenshotProtection(
        JNIEnv* env, jobject activity, std::vector<std::string>* findings) {
#if CHIMERA_BLOCK_SCREENSHOTS
    if (env == nullptr || activity == nullptr) {
        addFinding(findings, "screenshot_protection_unavailable");
        return;
    }
    jclass actions = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    jmethodID block = actions == nullptr ? nullptr : env->GetStaticMethodID(
            actions, "blockScreenshots", "(Landroid/app/Activity;)V");
    if (block == nullptr) {
        detection_utils::clearPendingException(env);
        if (actions != nullptr) env->DeleteLocalRef(actions);
        addFinding(findings, "screenshot_protection_unavailable");
        return;
    }
    env->CallStaticVoidMethod(actions, block, activity);
    const bool failed = env->ExceptionCheck();
    detection_utils::clearPendingException(env);
    env->DeleteLocalRef(actions);
    if (failed) addFinding(findings, "screenshot_protection_unavailable");
#else
    (void) env;
    (void) activity;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
