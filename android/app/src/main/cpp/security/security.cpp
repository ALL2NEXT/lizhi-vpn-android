#include "security.h"
#include "detection_utils.h"

#include <android/log.h>
#include <algorithm>
#include <cstdlib>
#include <mutex>
#include <set>

#ifndef CHIMERA_CRASH_ON_FINDING
#define CHIMERA_CRASH_ON_FINDING 1
#endif
#ifndef CHIMERA_REPORT_FINDINGS
#define CHIMERA_REPORT_FINDINGS 1
#endif

namespace chimera {
namespace security {
namespace {
thread_local JNIEnv* currentReportEnv = nullptr;
thread_local jobject currentReportContext = nullptr;

bool isReportOnly(const std::string& code) {
    return code.rfind("blacklisted_package:", 0) == 0 ||
            code == "adb_enabled" || code == "developer_options_enabled";
}

void reportFatalFindingBeforeTermination(JNIEnv* env, jobject context, const char* code) {
#if CHIMERA_REPORT_FINDINGS
    if (env == nullptr || context == nullptr || code == nullptr ||
            isReportOnly(code) || std::string(code) == "screenshot_capture" ||
            std::string(code) == "frida_integrity_unavailable") return;
    if (env->PushLocalFrame(8) != JNI_OK) {
        detection_utils::clearPendingException(env);
        return;
    }
    jclass actions = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    jmethodID report = actions == nullptr ? nullptr : env->GetStaticMethodID(
            actions,
            "reportFatalFindingBeforeTermination",
            "(Landroid/content/Context;Ljava/lang/String;)Z");
    jstring finding = report == nullptr ? nullptr : env->NewStringUTF(code);
    if (finding != nullptr && !env->ExceptionCheck()) {
        env->CallStaticBooleanMethod(actions, report, context, finding);
    }
    detection_utils::clearPendingException(env);
    env->PopLocalFrame(nullptr);
#else
    (void) env;
    (void) context;
    (void) code;
#endif
}
}

ReportContextScope::ReportContextScope(JNIEnv* env, jobject context)
    : previousEnv_(currentReportEnv), previousContext_(currentReportContext) {
    currentReportEnv = env;
    currentReportContext = context;
}

ReportContextScope::~ReportContextScope() {
    currentReportEnv = previousEnv_;
    currentReportContext = previousContext_;
}

void addFinding(std::vector<std::string>* findings, const std::string& code) {
    if (findings != nullptr) findings->push_back(code);
    __android_log_print(ANDROID_LOG_WARN, "NativeSecurity", "Security detection: %s", code.c_str());
}

void terminateOnFinding(const char* code) {
    __android_log_print(ANDROID_LOG_ERROR, "NativeSecurity", "Security termination: %s", code);
#if CHIMERA_CRASH_ON_FINDING
    std::abort();
#endif
}

void fatalFinding(std::vector<std::string>* findings, const char* code) {
    addFinding(findings, code);
    reportFatalFindingBeforeTermination(currentReportEnv, currentReportContext, code);
    terminateOnFinding(code);
}

void reportFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings) {
#if CHIMERA_REPORT_FINDINGS
    if (env == nullptr || context == nullptr || findings.empty()) return;
    static std::mutex reportMutex;
    static std::set<std::string> queuedCodes;
    std::lock_guard<std::mutex> lock(reportMutex);
    std::vector<std::string> pending;
    for (const auto& code : findings) {
        if (isReportOnly(code) && queuedCodes.count(code) == 0) pending.push_back(code);
    }
    if (pending.empty()) return;
    if (env->PushLocalFrame(16) != JNI_OK) {
        detection_utils::clearPendingException(env);
        return;
    }
    jclass actions = env->FindClass(
            "io/github/HanJason233/chimera/security/handling/NativeSecurityActions");
    if (actions == nullptr) {
        detection_utils::clearPendingException(env);
        env->PopLocalFrame(nullptr);
        return;
    }
    jmethodID report = env->GetStaticMethodID(
            actions, "reportFindings", "(Landroid/content/Context;[Ljava/lang/String;)Z");
    if (report == nullptr) {
        detection_utils::clearPendingException(env);
        env->PopLocalFrame(nullptr);
        return;
    }
    jclass strings = env->FindClass("java/lang/String");
    if (strings == nullptr || env->ExceptionCheck()) {
        detection_utils::clearPendingException(env);
        env->PopLocalFrame(nullptr);
        return;
    }
    jobjectArray codes = env->NewObjectArray(static_cast<jsize>(pending.size()), strings, nullptr);
    if (codes == nullptr) {
        detection_utils::clearPendingException(env);
        env->PopLocalFrame(nullptr);
        return;
    }
    bool valid = true;
    for (size_t i = 0; i < pending.size(); ++i) {
        jstring code = env->NewStringUTF(pending[i].c_str());
        if (code == nullptr) { valid = false; break; }
        env->SetObjectArrayElement(codes, static_cast<jsize>(i), code);
        env->DeleteLocalRef(code);
        if (env->ExceptionCheck()) { valid = false; break; }
    }
    if (valid) {
        const bool saved = env->CallStaticBooleanMethod(actions, report, context, codes) == JNI_TRUE;
        if (!env->ExceptionCheck() && saved) queuedCodes.insert(pending.begin(), pending.end());
    }
    detection_utils::clearPendingException(env);
    env->PopLocalFrame(nullptr);
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

std::vector<std::string> scan(JNIEnv* env, jobject context) {
    ReportContextScope reportContext(env, context);
    std::vector<std::string> findings;
    // 先持久化仅上报项，再执行可能立即结束进程的检查。
#if CHIMERA_DETECT_ADB
    detectAdb(env, context, &findings);
#endif
#if CHIMERA_DETECT_DEVELOPER_MODE
    detectDeveloperMode(env, context, &findings);
#endif
#if CHIMERA_DETECT_BLACKLIST
    detectBlacklist(env, context, &findings);
#endif
#if CHIMERA_VERIFY_SIGNATURE
    detectSignature(env, context, &findings);
#endif
#if CHIMERA_DETECT_CAPTURE || CHIMERA_BLOCK_HTTP_PROXY
    detectCapture(env, context, &findings);
#endif
#if CHIMERA_DETECT_XPOSED
    detectXposed(env, context, &findings);
#endif
#if CHIMERA_DETECT_LSPOSED
    detectLsposed(env, context, &findings);
#endif
#if CHIMERA_DETECT_FRIDA
    detectFrida(&findings);
#endif
#if CHIMERA_DETECT_ROOT
    detectRoot(&findings);
#endif
#if CHIMERA_DETECT_LSPATCH
    detectLspatch(env, context, &findings);
#endif
#if CHIMERA_DETECT_NPATCH
    detectNpatch(env, context, &findings);
#endif
    return findings;
}

}  // namespace security
}  // namespace chimera
