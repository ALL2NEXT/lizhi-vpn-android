#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace chimera {
namespace security {
namespace detection_utils {

class LocalFrame {
public:
    explicit LocalFrame(JNIEnv* env, jint capacity = 64);
    ~LocalFrame();
    explicit operator bool() const { return active_; }
    LocalFrame(const LocalFrame&) = delete;
    LocalFrame& operator=(const LocalFrame&) = delete;
private:
    JNIEnv* env_;
    bool active_;
};

struct AppInfo {
    bool available = false;
    std::vector<std::string> componentNames;
    std::string sourceDir;
    std::string dataDir;
    std::vector<std::string> metadataKeys;
};

void clearPendingException(JNIEnv* env);
jint getSdkInt(JNIEnv* env);
std::string toLower(std::string value);
bool containsAny(const std::string& text, const char* const* markers, size_t count);
bool readFile(const char* path, std::string* contents);
bool readTextFile(const char* path, std::string* contents);
bool fileExists(const char* path);
bool readDirectory(const char* path, std::vector<std::string>* names);
bool readLink(const char* path, std::string* target);
enum class MemoryComparison { MATCH, DIFFERENT, UNAVAILABLE };
MemoryComparison compareFileToMemory(const char* path, uint64_t offset, uintptr_t address, size_t size);
// Returned JNI references belong to the caller's local frame.
std::vector<jobject> collectClassLoaders(JNIEnv* env, jobject context);
std::vector<std::string> describeClassLoaders(JNIEnv* env, const std::vector<jobject>& loaders);
jclass findClassInLoaders(JNIEnv* env, const std::vector<jobject>& loaders, const char* name);
bool stackContains(JNIEnv* env, const char* const* classPrefixes, size_t count);
AppInfo getAppInfo(JNIEnv* env, jobject context);
bool assetExists(JNIEnv* env, jobject context, const char* path);
bool hasPackage(JNIEnv* env, jobject context, const char* packageName);
bool mapsContain(const char* const* markers, size_t count);
bool getGlobalSettingInt(JNIEnv* env, jobject context, const char* settingName, jint defaultValue, jint* value);

}  // detection_utils 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
