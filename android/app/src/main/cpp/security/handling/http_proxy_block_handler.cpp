#include "security_handler.h"

#ifndef CHIMERA_BLOCK_HTTP_PROXY
#define CHIMERA_BLOCK_HTTP_PROXY 0
#endif

namespace chimera {
namespace security {
namespace handling {
namespace {

void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

}  // 匿名命名空间结束

void setHttpProxyBlocking(JNIEnv* env, bool enabled) {
#if CHIMERA_BLOCK_HTTP_PROXY
    if (env == nullptr) return;

    jclass actionsClass = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    if (actionsClass == nullptr) {
        clearPendingException(env);
        return;
    }
    jmethodID setCaptureBlock = env->GetStaticMethodID(
            actionsClass, "setHttpProxyBlocking", "(Z)V");
    if (setCaptureBlock != nullptr) {
        env->CallStaticVoidMethod(actionsClass, setCaptureBlock, enabled ? JNI_TRUE : JNI_FALSE);
        clearPendingException(env);
    } else {
        clearPendingException(env);
    }
    env->DeleteLocalRef(actionsClass);
#else
    (void) env;
    (void) enabled;
#endif
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
