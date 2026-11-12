#include "security_detector.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_CAPTURE
#define CHIMERA_DETECT_CAPTURE 1
#endif
#ifndef CHIMERA_BLOCK_HTTP_PROXY
#define CHIMERA_BLOCK_HTTP_PROXY 0
#endif

namespace chimera {
namespace security {

void detectCapture(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_CAPTURE || CHIMERA_BLOCK_HTTP_PROXY
    // 只检查系统 HTTP 代理配置，不读取 VPN 或 TUN 接口状态。
    using namespace detection_utils;

    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        clearPendingException(env);
        findings->push_back("capture_detection_unavailable");
        return;
    }
    jmethodID getSystemService = env->GetMethodID(
            contextClass,
            "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;");
    if (getSystemService == nullptr) {
        clearPendingException(env);
        findings->push_back("capture_detection_unavailable");
        env->DeleteLocalRef(contextClass);
        return;
    }
    jstring serviceName = env->NewStringUTF("connectivity");
    jobject connectivityManager = env->CallObjectMethod(context, getSystemService, serviceName);
    if (env->ExceptionCheck() || connectivityManager == nullptr) {
        clearPendingException(env);
        findings->push_back("capture_detection_unavailable");
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
        findings->push_back("capture_detection_unavailable");
    } else if (getDefaultProxy == nullptr) {
        findings->push_back("capture_detection_unavailable");
    }

    if (proxyInfo != nullptr) {
        jclass proxyClass = env->GetObjectClass(proxyInfo);
        jmethodID getHost = proxyClass == nullptr ? nullptr : env->GetMethodID(
                proxyClass, "getHost", "()Ljava/lang/String;");
        jmethodID getPort = proxyClass == nullptr ? nullptr : env->GetMethodID(
                proxyClass, "getPort", "()I");
        jmethodID getPacFileUrl = proxyClass == nullptr ? nullptr : env->GetMethodID(
                proxyClass, "getPacFileUrl", "()Landroid/net/Uri;");
        if (getHost == nullptr || getPort == nullptr || getPacFileUrl == nullptr) {
            clearPendingException(env);
            findings->push_back("capture_detection_unavailable");
            if (proxyClass != nullptr) env->DeleteLocalRef(proxyClass);
            env->DeleteLocalRef(proxyInfo);
            if (connectivityClass != nullptr) env->DeleteLocalRef(connectivityClass);
            env->DeleteLocalRef(connectivityManager);
            env->DeleteLocalRef(serviceName);
            env->DeleteLocalRef(contextClass);
            return;
        }
        jstring host = getHost == nullptr ? nullptr : static_cast<jstring>(
                env->CallObjectMethod(proxyInfo, getHost));
        const jint port = getPort == nullptr ? 0 : env->CallIntMethod(proxyInfo, getPort);
        if (env->ExceptionCheck()) {
            clearPendingException(env);
            findings->push_back("capture_detection_unavailable");
        }
        bool hasHost = false;
        if (host != nullptr) {
            const char* hostChars = env->GetStringUTFChars(host, nullptr);
            if (hostChars != nullptr) {
                hasHost = hostChars[0] != '\0';
                env->ReleaseStringUTFChars(host, hostChars);
            }
        }
        if (hasHost && port > 0) findings->push_back("http_proxy_configured");
        jobject pacUri = env->CallObjectMethod(proxyInfo, getPacFileUrl);
        if (env->ExceptionCheck()) {
            clearPendingException(env);
            findings->push_back("capture_detection_unavailable");
        } else if (pacUri != nullptr) {
            jclass uriClass = env->GetObjectClass(pacUri);
            jmethodID toString = uriClass == nullptr ? nullptr : env->GetMethodID(
                    uriClass, "toString", "()Ljava/lang/String;");
            jstring pacUrl = toString == nullptr ? nullptr : static_cast<jstring>(
                    env->CallObjectMethod(pacUri, toString));
            if (env->ExceptionCheck() || pacUrl == nullptr) {
                clearPendingException(env);
                findings->push_back("capture_detection_unavailable");
            } else if (env->GetStringLength(pacUrl) > 0) {
                findings->push_back("http_proxy_pac_configured");
            }
            if (pacUrl != nullptr) env->DeleteLocalRef(pacUrl);
            if (uriClass != nullptr) env->DeleteLocalRef(uriClass);
        }
        if (pacUri != nullptr) env->DeleteLocalRef(pacUri);
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
