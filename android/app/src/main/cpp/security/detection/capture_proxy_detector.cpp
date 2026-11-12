#include "security_detector.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_CAPTURE
#define CHIMERA_DETECT_CAPTURE 1
#endif

namespace chimera {
namespace security {

void detectCapture(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_CAPTURE
    // 只检查系统 HTTP 代理配置，不读取 VPN 或 TUN 接口状态。
    using namespace detection_utils;

    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        clearPendingException(env);
        return;
    }
    jmethodID getSystemService = env->GetMethodID(
            contextClass,
            "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;");
    if (getSystemService == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(contextClass);
        return;
    }
    jstring serviceName = env->NewStringUTF("connectivity");
    jobject connectivityManager = env->CallObjectMethod(context, getSystemService, serviceName);
    if (env->ExceptionCheck() || connectivityManager == nullptr) {
        clearPendingException(env);
        if (serviceName != nullptr) env->DeleteLocalRef(serviceName);
        env->DeleteLocalRef(contextClass);
        return;
    }

    jclass connectivityClass = env->GetObjectClass(connectivityManager);
    jmethodID getDefaultProxy = connectivityClass == nullptr ? nullptr : env->GetMethodID(
            connectivityClass,
            "getDefaultProxy",
            "()Landroid/net/ProxyInfo;");
    jobject proxyInfo = getDefaultProxy == nullptr
            ? nullptr
            : env->CallObjectMethod(connectivityManager, getDefaultProxy);
    if (env->ExceptionCheck()) {
        clearPendingException(env);
        proxyInfo = nullptr;
    }

    if (proxyInfo != nullptr) {
        jclass proxyClass = env->GetObjectClass(proxyInfo);
        jmethodID getHost = proxyClass == nullptr ? nullptr : env->GetMethodID(
                proxyClass, "getHost", "()Ljava/lang/String;");
        jmethodID getPort = proxyClass == nullptr ? nullptr : env->GetMethodID(
                proxyClass, "getPort", "()I");
        jstring host = getHost == nullptr ? nullptr : static_cast<jstring>(
                env->CallObjectMethod(proxyInfo, getHost));
        const jint port = getPort == nullptr ? 0 : env->CallIntMethod(proxyInfo, getPort);
        if (env->ExceptionCheck()) clearPendingException(env);
        bool hasHost = false;
        if (host != nullptr) {
            const char* hostChars = env->GetStringUTFChars(host, nullptr);
            if (hostChars != nullptr) {
                hasHost = hostChars[0] != '\0';
                env->ReleaseStringUTFChars(host, hostChars);
            }
        }
        if (hasHost && port > 0) findings->push_back("http_proxy_configured");
        if (host != nullptr) env->DeleteLocalRef(host);
        if (proxyClass != nullptr) env->DeleteLocalRef(proxyClass);
        env->DeleteLocalRef(proxyInfo);
    }
    if (connectivityClass != nullptr) env->DeleteLocalRef(connectivityClass);
    env->DeleteLocalRef(connectivityManager);
    if (serviceName != nullptr) env->DeleteLocalRef(serviceName);
    env->DeleteLocalRef(contextClass);
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
