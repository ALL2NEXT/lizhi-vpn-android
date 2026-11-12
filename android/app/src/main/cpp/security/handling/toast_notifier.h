#pragma once

#include <jni.h>

namespace chimera {
namespace security {
namespace handling {

void showToast(JNIEnv* env, jobject context, const char* message);

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
