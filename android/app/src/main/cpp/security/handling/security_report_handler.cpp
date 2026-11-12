#include "security_handler.h"

#ifndef CHIMERA_REPORT_FINDINGS
#define CHIMERA_REPORT_FINDINGS 0
#endif

namespace chimera {
namespace security {
namespace handling {
namespace {

void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

}  // 匿名命名空间结束

void reportSecurityFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings) {
#if CHIMERA_REPORT_FINDINGS
    if (env == nullptr || context == nullptr || findings.empty()) return;

    jclass actionsClass = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    if (actionsClass == nullptr) {
        clearPendingException(env);
        return;
    }
    jmethodID reportFindings = env->GetStaticMethodID(
            actionsClass,
            "reportFindings",
            "(Landroid/content/Context;[Ljava/lang/String;)V");
    jclass stringClass = env->FindClass("java/lang/String");
    if (reportFindings == nullptr || stringClass == nullptr) {
        clearPendingException(env);
        if (stringClass != nullptr) env->DeleteLocalRef(stringClass);
        env->DeleteLocalRef(actionsClass);
        return;
    }

    jobjectArray codes = env->NewObjectArray(static_cast<jsize>(findings.size()), stringClass, nullptr);
    if (codes != nullptr) {
        for (size_t index = 0; index < findings.size(); ++index) {
            jstring code = env->NewStringUTF(findings[index].c_str());
            if (code == nullptr) {
                clearPendingException(env);
                continue;
            }
            env->SetObjectArrayElement(codes, static_cast<jsize>(index), code);
            env->DeleteLocalRef(code);
            if (env->ExceptionCheck()) clearPendingException(env);
        }
        env->CallStaticVoidMethod(actionsClass, reportFindings, context, codes);
        clearPendingException(env);
        env->DeleteLocalRef(codes);
    }
    env->DeleteLocalRef(stringClass);
    env->DeleteLocalRef(actionsClass);
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
