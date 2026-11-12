#include "security_handler.h"

#include "toast_notifier.h"

#include <string>

namespace chimera {
namespace security {
namespace handling {

void handleInitialFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings) {
    if (findings.empty()) return;
    // 启动检测只显示汇总提示，详细代码由检测器写入日志。
    const std::string message = "安全检测发现 " + std::to_string(findings.size()) +
            " 项风险信号，详情请查看 NativeSecurity 日志。";
    showToast(env, context, message.c_str());
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
