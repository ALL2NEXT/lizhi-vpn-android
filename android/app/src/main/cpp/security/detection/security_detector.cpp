#include "security_detector.h"

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
#ifndef CHIMERA_VERIFY_SIGNATURE
#define CHIMERA_VERIFY_SIGNATURE 1
#endif
#ifndef CHIMERA_DETECT_BLACKLIST
#define CHIMERA_DETECT_BLACKLIST 1
#endif

namespace chimera {
namespace security {

std::vector<std::string> scan(JNIEnv* env, jobject context) {
    std::vector<std::string> findings;

#if CHIMERA_VERIFY_SIGNATURE
    detectSignature(env, context, &findings);
#endif
#if CHIMERA_DETECT_CAPTURE
    detectCapture(env, context, &findings);
#endif
#if CHIMERA_DETECT_XPOSED
    detectXposed(&findings);
#endif
#if CHIMERA_DETECT_LSPOSED
    detectLsposed(&findings);
#endif
#if CHIMERA_DETECT_FRIDA
    detectFrida(&findings);
#endif
#if CHIMERA_DETECT_BLACKLIST
    detectBlacklist(env, context, &findings);
#endif

    return findings;
}

}  // security 命名空间结束
}  // chimera 命名空间结束
