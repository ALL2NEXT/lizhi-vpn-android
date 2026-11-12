#pragma once

#include <jni.h>

#include <string>
#include <vector>

namespace chimera {
namespace security {
namespace handling {

void handleInitialFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings);
void handleScreenshotCapture(JNIEnv* env, jobject context);
void applyScreenshotProtection(JNIEnv* env, jobject activity);
void setHttpProxyBlocking(JNIEnv* env, bool enabled);
void reportSecurityFindings(JNIEnv* env, jobject context, const std::vector<std::string>& findings);
void crashOnFinding();
bool hasCriticalFinding(const std::vector<std::string>& findings);

}  // handling 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
