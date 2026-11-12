#include "detection_utils.h"

#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <fstream>
#include <sstream>

namespace chimera {
namespace security {
namespace detection_utils {

void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

jint getSdkInt(JNIEnv* env) {
    jclass versionClass = env->FindClass("android/os/Build$VERSION");
    if (versionClass == nullptr) {
        clearPendingException(env);
        return 0;
    }
    jfieldID sdkIntField = env->GetStaticFieldID(versionClass, "SDK_INT", "I");
    if (sdkIntField == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(versionClass);
        return 0;
    }
    const jint sdkInt = env->GetStaticIntField(versionClass, sdkIntField);
    env->DeleteLocalRef(versionClass);
    return sdkInt;
}

std::string toLower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

bool containsAny(const std::string& text, const char* const* markers, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        if (text.find(markers[i]) != std::string::npos) return true;
    }
    return false;
}

bool readTextFile(const char* path, std::string* contents) {
    std::ifstream input(path);
    if (!input.is_open()) return false;
    std::ostringstream stream;
    stream << input.rdbuf();
    *contents = toLower(stream.str());
    return true;
}

bool fileExists(const char* path) {
    return access(path, F_OK) == 0;
}

bool hasPackage(JNIEnv* env, jobject context, const char* packageName) {
    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        clearPendingException(env);
        return false;
    }
    jmethodID getPackageManager = env->GetMethodID(
            contextClass,
            "getPackageManager",
            "()Landroid/content/pm/PackageManager;");
    if (getPackageManager == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(contextClass);
        return false;
    }
    jobject packageManager = env->CallObjectMethod(context, getPackageManager);
    if (env->ExceptionCheck() || packageManager == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(contextClass);
        return false;
    }

    jclass packageManagerClass = env->GetObjectClass(packageManager);
    jmethodID getPackageInfo = packageManagerClass == nullptr ? nullptr : env->GetMethodID(
            packageManagerClass,
            "getPackageInfo",
            "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (getPackageInfo == nullptr) {
        clearPendingException(env);
        if (packageManagerClass != nullptr) env->DeleteLocalRef(packageManagerClass);
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(contextClass);
        return false;
    }

    jstring name = env->NewStringUTF(packageName);
    jobject info = env->CallObjectMethod(packageManager, getPackageInfo, name, 0);
    const bool found = !env->ExceptionCheck() && info != nullptr;
    clearPendingException(env);
    if (info != nullptr) env->DeleteLocalRef(info);
    if (name != nullptr) env->DeleteLocalRef(name);
    env->DeleteLocalRef(packageManagerClass);
    env->DeleteLocalRef(packageManager);
    env->DeleteLocalRef(contextClass);
    return found;
}

bool mapsContain(const char* const* markers, size_t count) {
    std::string maps;
    return readTextFile("/proc/self/maps", &maps) && containsAny(maps, markers, count);
}

}  // detection_utils 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
