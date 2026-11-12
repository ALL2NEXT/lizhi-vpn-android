#include "security_handler.h"

#include "toast_notifier.h"

namespace chimera {
namespace security {
namespace handling {

void handleScreenshotCapture(JNIEnv* env, jobject context) {
    // 截图事件只显示提示，不尝试阻止截图。
    showToast(env, context, "检测到截图操作");
}

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
