#include "security_handler.h"

#include "toast_notifier.h"

#include <algorithm>
#include <android/log.h>
#include <mutex>
#include <set>
#include <string>

namespace chimera {
namespace security {
namespace handling {

void handleInitialFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings) {
    setHttpProxyBlocking(env, true);
    static std::mutex findingsMutex;
    static std::set<std::string> previousFindings;
    std::vector<std::string> newFindings;
    {
        std::lock_guard<std::mutex> guard(findingsMutex);
        for (const auto& finding : findings) {
            if (previousFindings.count(finding) == 0) newFindings.push_back(finding);
        }
        previousFindings = std::set<std::string>(findings.begin(), findings.end());
    }
    if (newFindings.empty()) return;
    for (const auto& finding : newFindings) {
        __android_log_print(ANDROID_LOG_WARN, "NativeSecurity", "Security detection: %s", finding.c_str());
    }
    const std::string message = "安全检测发现 " + std::to_string(newFindings.size()) +
            " 项新的风险信号，详情见 NativeSecurity 日志。";
    showToast(env, context, message.c_str());

    std::vector<std::string> blacklistedPackages;
    for (const std::string& finding : newFindings) {
        if (finding.rfind("blacklisted_package:", 0) == 0) {
            blacklistedPackages.push_back(finding);
        }
    }
    if (!blacklistedPackages.empty()) {
        reportSecurityFindings(env, context, blacklistedPackages);
    }
    if (hasCriticalFinding(findings)) crashOnFinding();
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
