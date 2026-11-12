#include "toast_notifier.h"

namespace chimera {
namespace security {
namespace handling {
namespace {

void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

}  // 匿名命名空间结束

void showToast(JNIEnv* env, jobject context, const char* message) {
    jclass toastClass = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    if (toastClass == nullptr) {
        clearPendingException(env);
        return;
    }

    jmethodID show = env->GetStaticMethodID(
            toastClass,
            "showSecurityToast",
            "(Landroid/content/Context;Ljava/lang/String;)V");
    if (show == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(toastClass);
        return;
    }

    jstring text = env->NewStringUTF(message);
    if (text != nullptr) {
        env->CallStaticVoidMethod(toastClass, show, context, text);
        clearPendingException(env);
    }
    if (text != nullptr) env->DeleteLocalRef(text);
    env->DeleteLocalRef(toastClass);
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
