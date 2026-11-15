#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "security/security.h"
#include "transport/transport_jni.h"

namespace {

jobjectArray toJavaFindings(JNIEnv* env, const std::vector<std::string>& findings) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(findings.size()), stringClass, nullptr);
    if (result == nullptr) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    for (size_t i = 0; i < findings.size(); ++i) {
        jstring value = env->NewStringUTF(findings[i].c_str());
        if (value == nullptr) {
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(stringClass);
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(stringClass);
            return nullptr;
        }
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

jobjectArray nativeScan(JNIEnv* env, jclass, jobject context) {
    const std::vector<std::string> findings = chimera::security::scan(env, context);
    return toJavaFindings(env, findings);
}

}  // 匿名命名空间结束

jobjectArray nativeApplyActivitySecurity(JNIEnv* env, jclass, jobject activity) {
    std::vector<std::string> findings;
    chimera::security::applyScreenshotProtection(env, activity, &findings);
    return toJavaFindings(env, findings);
}

jobjectArray nativeRefreshCapturePolicy(JNIEnv* env, jclass, jobject context) {
    std::vector<std::string> findings;
    chimera::security::detectCapture(env, context, &findings);
    return toJavaFindings(env, findings);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass detectorClass = env->FindClass(
            "io/github/HanJason233/chimera/security/detection/NativeSecurityDetector");
    if (detectorClass == nullptr) return JNI_ERR;

    JNINativeMethod methods[] = {
            {"nativeScan", "(Landroid/content/Context;)[Ljava/lang/String;",
             reinterpret_cast<void*>(nativeScan)},
            {"nativeApplyActivitySecurity", "(Landroid/app/Activity;)[Ljava/lang/String;",
             reinterpret_cast<void*>(nativeApplyActivitySecurity)},
            {"nativeRefreshCapturePolicy", "(Landroid/content/Context;)[Ljava/lang/String;",
             reinterpret_cast<void*>(nativeRefreshCapturePolicy)}};
    if (env->RegisterNatives(detectorClass, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        env->DeleteLocalRef(detectorClass);
        return JNI_ERR;
    }
    env->DeleteLocalRef(detectorClass);
    if (!chimera::transport::registerNatives(env)) return JNI_ERR;
    return JNI_VERSION_1_6;
}
