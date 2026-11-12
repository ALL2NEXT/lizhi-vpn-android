#pragma once

#include <jni.h>

#include <cstddef>
#include <string>

namespace chimera {
namespace security {
namespace detection_utils {

void clearPendingException(JNIEnv* env);
jint getSdkInt(JNIEnv* env);
std::string toLower(std::string value);
bool containsAny(const std::string& text, const char* const* markers, size_t count);
bool readTextFile(const char* path, std::string* contents);
bool fileExists(const char* path);
bool hasPackage(JNIEnv* env, jobject context, const char* packageName);
bool mapsContain(const char* const* markers, size_t count);
bool getGlobalSettingInt(JNIEnv* env, jobject context, const char* settingName, jint defaultValue, jint* value);

}  // detection_utils 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
