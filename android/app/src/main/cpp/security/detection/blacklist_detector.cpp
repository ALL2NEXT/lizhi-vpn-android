#include "security_detector.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_BLACKLIST
#define CHIMERA_DETECT_BLACKLIST 1
#endif

namespace chimera {
namespace security {

void detectBlacklist(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_BLACKLIST
    // 仅报告命中的软件包名，不采取限制措施。
    static const char* kBlacklistedPackages[] = {
            "com.guoshi.httpcanary",
            "com.guoshi.httpcanary.premium",
            "com.minhui.networkcapture",
            "com.minhui.networkcapture.pro",
            "app.greyshirts.sslcapture",
            "com.emanuelef.remote_capture",
            "com.reqable.android",
            "com.junge.algorithmAidePro",
            "com.houvven.guise",
            "Hook.JiuWu.Xp",
            "com.coderstory.toolkit",
            "com.apocalua.run",
            "com.sukisu.ultra",
            "com.doubihuaji.Tool",
            "com.junge.justtrustmepp",
            "com.absinthe.libchecker",
            "de.robv.android.xposed.installer",
            "org.lsposed.manager",
            "org.meowcat.edxposed.manager",
            "com.topjohnwu.magisk",
            "com.saurik.substrate"};
    for (size_t i = 0; i < sizeof(kBlacklistedPackages) / sizeof(kBlacklistedPackages[0]); ++i) {
        if (detection_utils::hasPackage(env, context, kBlacklistedPackages[i])) {
            findings->push_back(std::string("blacklisted_package:") + kBlacklistedPackages[i]);
        }
    }
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
