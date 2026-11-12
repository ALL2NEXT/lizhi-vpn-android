#include <jni.h>

#include <algorithm>
#include <string>
#include <vector>

#include "security/detection/security_detector.h"
#include "security/handling/security_handler.h"

namespace {

void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

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
    chimera::security::handling::handleInitialFindings(env, context, findings);
    return toJavaFindings(env, findings);
}

jstring nativeReportScreenshotCapture(JNIEnv* env, jclass /* 未使用的 JNI 类参数 */, jobject context) {
    jstring finding = chimera::security::reportScreenshotCapture(env);
    if (finding != nullptr) {
        chimera::security::handling::handleScreenshotCapture(env, context);
    }
    return finding;
}

}  // 匿名命名空间结束

void nativeApplyActivitySecurity(JNIEnv* env, jclass, jobject activity) {
    chimera::security::handling::applyScreenshotProtection(env, activity);
}

jobjectArray nativeRefreshCapturePolicy(JNIEnv* env, jclass, jobject context) {
    std::vector<std::string> findings;
    chimera::security::detectCapture(env, context, &findings);
    // 防护不依赖代理检测成功，也不因代理暂时消失而重新放开系统代理。
    chimera::security::handling::setHttpProxyBlocking(env, true);
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
            {"nativeReportScreenshotCapture", "(Landroid/content/Context;)Ljava/lang/String;",
             reinterpret_cast<void*>(nativeReportScreenshotCapture)},
            {"nativeApplyActivitySecurity", "(Landroid/app/Activity;)V",
             reinterpret_cast<void*>(nativeApplyActivitySecurity)},
            {"nativeRefreshCapturePolicy", "(Landroid/content/Context;)[Ljava/lang/String;",
             reinterpret_cast<void*>(nativeRefreshCapturePolicy)}};
    if (env->RegisterNatives(detectorClass, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        env->DeleteLocalRef(detectorClass);
        return JNI_ERR;
    }
    env->DeleteLocalRef(detectorClass);
    return JNI_VERSION_1_6;
}
