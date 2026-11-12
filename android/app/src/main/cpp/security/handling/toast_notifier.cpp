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
    jclass toastClass = env->FindClass("android/widget/Toast");
    if (toastClass == nullptr) {
        clearPendingException(env);
        return;
    }

    jmethodID makeText = env->GetStaticMethodID(
            toastClass,
            "makeText",
            "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;");
    jmethodID show = env->GetMethodID(toastClass, "show", "()V");
    if (makeText == nullptr || show == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(toastClass);
        return;
    }

    jstring text = env->NewStringUTF(message);
    jobject toast = text == nullptr ? nullptr : env->CallStaticObjectMethod(
            toastClass, makeText, context, text, 1 /* Toast.LENGTH_LONG 常量值 */);
    if (env->ExceptionCheck()) {
        clearPendingException(env);
        toast = nullptr;
    }
    if (toast != nullptr) {
        env->CallVoidMethod(toast, show);
        clearPendingException(env);
        env->DeleteLocalRef(toast);
    }
    if (text != nullptr) env->DeleteLocalRef(text);
    env->DeleteLocalRef(toastClass);
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
