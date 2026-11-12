#include "security_handler.h"

#ifndef CHIMERA_BLOCK_SCREENSHOTS
#define CHIMERA_BLOCK_SCREENSHOTS 0
#endif
#ifndef CHIMERA_DETECT_SCREENSHOT
#define CHIMERA_DETECT_SCREENSHOT 1
#endif

namespace chimera {
namespace security {
namespace handling {
namespace {

void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

}  // 匿名命名空间结束

void applyScreenshotProtection(JNIEnv* env, jobject activity) {
#if CHIMERA_DETECT_SCREENSHOT && CHIMERA_BLOCK_SCREENSHOTS
    if (env == nullptr || activity == nullptr) return;

    jclass actionsClass = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    if (actionsClass == nullptr) {
        clearPendingException(env);
        return;
    }
    jmethodID blockScreenshots = env->GetStaticMethodID(
            actionsClass, "blockScreenshots", "(Landroid/app/Activity;)V");
    if (blockScreenshots != nullptr) {
        env->CallStaticVoidMethod(actionsClass, blockScreenshots, activity);
        clearPendingException(env);
    } else {
        clearPendingException(env);
    }
    env->DeleteLocalRef(actionsClass);
#else
    (void) env;
    (void) activity;
#endif
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
