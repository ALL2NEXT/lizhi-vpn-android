#include "security_handler.h"

#include "toast_notifier.h"

#include <algorithm>
#include <string>

namespace chimera {
namespace security {
namespace handling {

void handleInitialFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings) {
    const bool proxyDetected = std::find(
            findings.begin(), findings.end(), "http_proxy_configured") != findings.end();
    setHttpProxyBlocking(env, proxyDetected);
    if (findings.empty()) return;
    // 启动检测只显示汇总提示，详细代码由检测器写入日志。
    const std::string message = "安全检测发现 " + std::to_string(findings.size()) +
            " 项风险信号，详情请查看 NativeSecurity 日志。";
    showToast(env, context, message.c_str());

    std::vector<std::string> blacklistedPackages;
    for (const std::string& finding : findings) {
        if (finding.rfind("blacklisted_package:", 0) == 0) {
            blacklistedPackages.push_back(finding);
        }
    }
    if (!blacklistedPackages.empty()) {
        reportSecurityFindings(env, context, blacklistedPackages);
    }
    crashOnFinding();
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
