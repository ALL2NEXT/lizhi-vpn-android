#include "security.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_CAPTURE
#define CHIMERA_DETECT_CAPTURE 1
#endif
#ifndef CHIMERA_BLOCK_HTTP_PROXY
#define CHIMERA_BLOCK_HTTP_PROXY 1
#endif

namespace chimera {
namespace security {

void applyCaptureProtection(JNIEnv* env) {
#if CHIMERA_BLOCK_HTTP_PROXY
    if (env == nullptr) {
        fatalFinding(nullptr, "capture_detection_unavailable");
        return;
    }
    jclass actions = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    jmethodID block = actions == nullptr ? nullptr : env->GetStaticMethodID(
            actions, "setHttpProxyBlocking", "(Z)V");
    if (block == nullptr) {
        detection_utils::clearPendingException(env);
        if (actions != nullptr) env->DeleteLocalRef(actions);
        fatalFinding(nullptr, "capture_detection_unavailable");
        return;
    }
    env->CallStaticVoidMethod(actions, block, JNI_TRUE);
    const bool failed = env->ExceptionCheck();
    detection_utils::clearPendingException(env);
    env->DeleteLocalRef(actions);
    if (failed) fatalFinding(nullptr, "capture_detection_unavailable");
#else
    (void) env;
#endif
}

void reportNetworkFinding(JNIEnv* env, jstring finding) {
    if (finding == nullptr) {
        fatalFinding(nullptr, "capture_detection_unavailable");
        return;
    }
    const char* value = env->GetStringUTFChars(finding, nullptr);
    if (value == nullptr) {
        detection_utils::clearPendingException(env);
        fatalFinding(nullptr, "capture_detection_unavailable");
        return;
    }
    const std::string code(value);
    env->ReleaseStringUTFChars(finding, value);
    if (code == "tls_validation_failed" || code == "cleartext_traffic_blocked" ||
            code == "http_proxy_route_blocked" || code == "https_downgrade_blocked") {
        // 网络拦截器已经拒绝该请求，随后立即终止当前应用进程。
        fatalFinding(nullptr, code.c_str());
    }
}

void detectCapture(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_CAPTURE || CHIMERA_BLOCK_HTTP_PROXY
    applyCaptureProtection(env);
    // 保持请求防护，HTTP/PAC 代理或检测错误命中后直接终止，不检查 VPN/TUN 接口。
    using namespace detection_utils;

    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        clearPendingException(env);
        fatalFinding(findings, "capture_detection_unavailable");
        return;
    }
    jmethodID getSystemService = env->GetMethodID(
            contextClass,
            "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;");
    if (getSystemService == nullptr) {
        clearPendingException(env);
        fatalFinding(findings, "capture_detection_unavailable");
        env->DeleteLocalRef(contextClass);
        return;
    }
    jstring serviceName = env->NewStringUTF("connectivity");
    jobject connectivityManager = env->CallObjectMethod(context, getSystemService, serviceName);
    if (env->ExceptionCheck() || connectivityManager == nullptr) {
        clearPendingException(env);
        fatalFinding(findings, "capture_detection_unavailable");
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
        fatalFinding(findings, "capture_detection_unavailable");
    } else if (getDefaultProxy == nullptr) {
        fatalFinding(findings, "capture_detection_unavailable");
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
            fatalFinding(findings, "capture_detection_unavailable");
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
            fatalFinding(findings, "capture_detection_unavailable");
        }
        bool hasHost = false;
        if (host != nullptr) {
            const char* hostChars = env->GetStringUTFChars(host, nullptr);
            if (hostChars != nullptr) {
                hasHost = hostChars[0] != '\0';
                env->ReleaseStringUTFChars(host, hostChars);
            }
        }
        if (hasHost && port > 0) fatalFinding(findings, "http_proxy_configured");
        jobject pacUri = env->CallObjectMethod(proxyInfo, getPacFileUrl);
        if (env->ExceptionCheck()) {
            clearPendingException(env);
            fatalFinding(findings, "capture_detection_unavailable");
        } else if (pacUri != nullptr) {
            jclass uriClass = env->GetObjectClass(pacUri);
            jmethodID toString = uriClass == nullptr ? nullptr : env->GetMethodID(
                    uriClass, "toString", "()Ljava/lang/String;");
            jstring pacUrl = toString == nullptr ? nullptr : static_cast<jstring>(
                    env->CallObjectMethod(pacUri, toString));
            if (env->ExceptionCheck() || pacUrl == nullptr) {
                clearPendingException(env);
                fatalFinding(findings, "capture_detection_unavailable");
            } else if (env->GetStringLength(pacUrl) > 0) {
                fatalFinding(findings, "http_proxy_pac_configured");
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
