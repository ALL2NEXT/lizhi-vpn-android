#include "security.h"

#include <android/log.h>

#ifndef CHIMERA_DETECT_CAPTURE
#define CHIMERA_DETECT_CAPTURE 1
#endif
#ifndef CHIMERA_DETECT_XPOSED
#define CHIMERA_DETECT_XPOSED 1
#endif
#ifndef CHIMERA_DETECT_LSPOSED
#define CHIMERA_DETECT_LSPOSED 1
#endif
#ifndef CHIMERA_DETECT_FRIDA
#define CHIMERA_DETECT_FRIDA 1
#endif
#ifndef CHIMERA_DETECT_ROOT
#define CHIMERA_DETECT_ROOT 1
#endif
#ifndef CHIMERA_DETECT_LSPATCH
#define CHIMERA_DETECT_LSPATCH 1
#endif
#ifndef CHIMERA_DETECT_NPATCH
#define CHIMERA_DETECT_NPATCH 1
#endif
#ifndef CHIMERA_VERIFY_SIGNATURE
#define CHIMERA_VERIFY_SIGNATURE 1
#endif
#ifndef CHIMERA_DETECT_BLACKLIST
#define CHIMERA_DETECT_BLACKLIST 1
#endif
#ifndef CHIMERA_DETECT_ADB
#define CHIMERA_DETECT_ADB 1
#endif
#ifndef CHIMERA_DETECT_DEVELOPER_MODE
#define CHIMERA_DETECT_DEVELOPER_MODE 1
#endif

namespace chimera {
namespace security {

void addFinding(std::vector<std::string>* findings, const std::string& code) {
    if (findings != nullptr) findings->push_back(code);
    __android_log_print(ANDROID_LOG_WARN, "NativeSecurity", "Security detection: %s", code.c_str());
}

std::vector<std::string> scan(JNIEnv* env, jobject context) {
    std::vector<std::string> findings;
    // Return all findings to Kotlin; the app queues reports without terminating the process.
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
