#include "detection_utils.h"

#include <errno.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <limits>
#include <utility>

namespace chimera {
namespace security {
namespace detection_utils {

namespace {
// On the shipped arm64 ABI, read-only procfs/file probes do not traverse libc hooks.
long kernelCall(long number, long arg0 = 0, long arg1 = 0, long arg2 = 0, long arg3 = 0) {
#if defined(__aarch64__)
    register long x8 __asm__("x8") = number;
    register long x0 __asm__("x0") = arg0;
    register long x1 __asm__("x1") = arg1;
    register long x2 __asm__("x2") = arg2;
    register long x3 __asm__("x3") = arg3;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory", "cc");
    return x0;
#else
    const long result = syscall(number, arg0, arg1, arg2, arg3);
    return result == -1 ? -errno : result;
#endif
}

class FileDescriptor {
public:
    explicit FileDescriptor(const char* path, int flags = O_RDONLY)
        : fd_(static_cast<int>(kernelCall(__NR_openat, AT_FDCWD,
                  reinterpret_cast<long>(path), flags | O_CLOEXEC))) {}
    ~FileDescriptor() { if (fd_ >= 0) kernelCall(__NR_close, fd_); }
    int get() const { return fd_; }
private:
    int fd_;
};

std::string stringValue(JNIEnv* env, jstring value) {
    if (value == nullptr || env->ExceptionCheck()) {
        clearPendingException(env);
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        clearPendingException(env);
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string stringField(JNIEnv* env, jobject object, const char* name) {
    LocalFrame frame(env, 8);
    if (!frame || object == nullptr) return {};
    jclass clazz = env->GetObjectClass(object);
    jfieldID field = clazz == nullptr ? nullptr : env->GetFieldID(clazz, name, "Ljava/lang/String;");
    if (field == nullptr) {
        clearPendingException(env);
        return {};
    }
    return stringValue(env, static_cast<jstring>(env->GetObjectField(object, field)));
}
}  // namespace

LocalFrame::LocalFrame(JNIEnv* env, jint capacity)
    : env_(env), active_(env != nullptr && env->PushLocalFrame(capacity) == JNI_OK) {
    if (!active_ && env != nullptr) clearPendingException(env);
}

LocalFrame::~LocalFrame() {
    if (active_) {
        clearPendingException(env_);
        env_->PopLocalFrame(nullptr);
    }
}

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

bool readFile(const char* path, std::string* contents) {
    contents->clear();
    FileDescriptor input(path);
    if (input.get() < 0) return false;
    constexpr size_t kMaximumBytes = 4 * 1024 * 1024;
    std::array<char, 8192> buffer{};
    while (true) {
        const long count = kernelCall(__NR_read, input.get(),
                reinterpret_cast<long>(buffer.data()), buffer.size());
        if (count == -EINTR) continue;
        if (count < 0 || static_cast<size_t>(count) > kMaximumBytes - contents->size()) {
            contents->clear();
            return false;
        }
        if (count == 0) break;
        contents->append(buffer.data(), static_cast<size_t>(count));
    }
    return true;
}

bool readTextFile(const char* path, std::string* contents) {
    if (!readFile(path, contents)) return false;
    *contents = toLower(std::move(*contents));
    return true;
}

bool fileExists(const char* path) {
    return kernelCall(__NR_faccessat, AT_FDCWD, reinterpret_cast<long>(path), F_OK) == 0;
}

bool readDirectory(const char* path, std::vector<std::string>* names) {
    names->clear();
    FileDescriptor directory(path, O_RDONLY | O_DIRECTORY);
    if (directory.get() < 0) return false;
    // linux_dirent64 has a 19-byte fixed header; use memcpy for unaligned fields.
    std::array<char, 8192> buffer{};
    while (true) {
        const long bytes = kernelCall(__NR_getdents64, directory.get(),
                reinterpret_cast<long>(buffer.data()), buffer.size());
        if (bytes == -EINTR) continue;
        if (bytes < 0) return false;
        if (bytes == 0) return true;
        size_t offset = 0;
        while (offset < static_cast<size_t>(bytes)) {
            if (static_cast<size_t>(bytes) - offset < 20) return false;
            uint16_t length = 0;
            std::memcpy(&length, buffer.data() + offset + 16, sizeof(length));
            if (length < 20 || length > static_cast<size_t>(bytes) - offset) return false;
            const char* name = buffer.data() + offset + 19;
            const size_t size = strnlen(name, length - 19);
            if (size == length - 19) return false;
            if (name[0] != '.') names->emplace_back(name, size);
            if (names->size() > 16384) return false;
            offset += length;
        }
    }
}

bool readLink(const char* path, std::string* target) {
    std::array<char, 4096> buffer{};
    const long size = kernelCall(__NR_readlinkat, AT_FDCWD, reinterpret_cast<long>(path),
            reinterpret_cast<long>(buffer.data()), buffer.size());
    if (size <= 0 || static_cast<size_t>(size) >= buffer.size()) return false;
    *target = toLower(std::string(buffer.data(), static_cast<size_t>(size)));
    return true;
}

MemoryComparison compareFileToMemory(const char* path, uint64_t offset, uintptr_t address, size_t size) {
#if defined(__LP64__)
    const auto maximumOffset = static_cast<uint64_t>(std::numeric_limits<long>::max());
    if (offset > maximumOffset || size > maximumOffset - offset ||
            address > maximumOffset || size > maximumOffset - address) return MemoryComparison::UNAVAILABLE;
    FileDescriptor disk(path);
    FileDescriptor memory("/proc/self/mem");
    if (disk.get() < 0 || memory.get() < 0) return MemoryComparison::UNAVAILABLE;
    std::array<unsigned char, 8192> diskBytes{};
    std::array<unsigned char, 8192> memoryBytes{};
    auto readAt = [](int fd, void* buffer, size_t count, uint64_t position) {
        size_t done = 0;
        while (done < count) {
            const long bytes = kernelCall(__NR_pread64, fd,
                    reinterpret_cast<long>(static_cast<unsigned char*>(buffer) + done),
                    count - done, static_cast<long>(position + done));
            if (bytes == -EINTR) continue;
            if (bytes <= 0) return false;
            done += static_cast<size_t>(bytes);
        }
        return true;
    };
    for (size_t done = 0; done < size;) {
        const size_t count = std::min(diskBytes.size(), size - done);
        if (!readAt(disk.get(), diskBytes.data(), count, offset + done) ||
                !readAt(memory.get(), memoryBytes.data(), count, address + done)) return MemoryComparison::UNAVAILABLE;
        // Volatile loads prevent lowering this comparison to a hookable memcmp call.
        const volatile unsigned char* left = diskBytes.data();
        const volatile unsigned char* right = memoryBytes.data();
        for (size_t i = 0; i < count; ++i) {
            if (left[i] != right[i]) return MemoryComparison::DIFFERENT;
        }
        done += count;
    }
    return MemoryComparison::MATCH;
#else
    (void) path; (void) offset; (void) address; (void) size;
    return MemoryComparison::UNAVAILABLE;
#endif
}

std::vector<jobject> collectClassLoaders(JNIEnv* env, jobject context) {
    std::vector<jobject> loaders;
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    if (loaderClass == nullptr) { clearPendingException(env); return loaders; }
    jmethodID parent = env->GetMethodID(loaderClass, "getParent", "()Ljava/lang/ClassLoader;");
    if (parent == nullptr) { clearPendingException(env); return loaders; }
    auto appendChain = [&](jobject loader) {
        for (size_t depth = 0; loader != nullptr && depth < 16; ++depth) {
            bool duplicate = false;
            for (jobject previous : loaders) {
                if (env->IsSameObject(previous, loader)) { duplicate = true; break; }
            }
            if (duplicate) { env->DeleteLocalRef(loader); break; }
            loaders.push_back(loader);
            loader = env->CallObjectMethod(loader, parent);
            if (env->ExceptionCheck()) { clearPendingException(env); break; }
        }
    };
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getLoader = contextClass == nullptr ? nullptr : env->GetMethodID(
            contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (getLoader != nullptr) {
        jobject loader = env->CallObjectMethod(context, getLoader);
        if (!env->ExceptionCheck()) appendChain(loader);
    }
    clearPendingException(env);
    jmethodID systemLoader = env->GetStaticMethodID(loaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (systemLoader != nullptr) {
        jobject loader = env->CallStaticObjectMethod(loaderClass, systemLoader);
        if (!env->ExceptionCheck()) appendChain(loader);
    }
    clearPendingException(env);
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (threadClass == nullptr) { clearPendingException(env); return loaders; }
    jmethodID current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    if (current == nullptr) { clearPendingException(env); return loaders; }
    jmethodID threadLoader = env->GetMethodID(threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    if (threadLoader == nullptr) { clearPendingException(env); return loaders; }
    jobject thread = env->CallStaticObjectMethod(threadClass, current);
    if (thread != nullptr && !env->ExceptionCheck()) {
        jobject loader = env->CallObjectMethod(thread, threadLoader);
        if (!env->ExceptionCheck()) appendChain(loader);
    }
    clearPendingException(env);
    return loaders;
}

std::vector<std::string> describeClassLoaders(JNIEnv* env, const std::vector<jobject>& loaders) {
    std::vector<std::string> descriptions;
    LocalFrame frame(env);
    if (!frame) return descriptions;
    jclass objects = env->FindClass("java/lang/Object");
    if (objects == nullptr) { clearPendingException(env); return descriptions; }
    jmethodID toString = env->GetMethodID(objects, "toString", "()Ljava/lang/String;");
    jclass classes = env->FindClass("java/lang/Class");
    if (classes == nullptr || toString == nullptr) { clearPendingException(env); return descriptions; }
    jmethodID getName = env->GetMethodID(classes, "getName", "()Ljava/lang/String;");
    if (getName == nullptr) { clearPendingException(env); return descriptions; }
    for (jobject loader : loaders) {
        LocalFrame itemFrame(env, 8);
        if (!itemFrame) break;
        jclass clazz = env->GetObjectClass(loader);
        if (clazz == nullptr) { clearPendingException(env); continue; }
        auto name = static_cast<jstring>(env->CallObjectMethod(clazz, getName));
        descriptions.push_back(toLower(stringValue(env, name)));
        auto description = static_cast<jstring>(env->CallObjectMethod(loader, toString));
        descriptions.push_back(toLower(stringValue(env, description)));
    }
    return descriptions;
}

jclass findClassInLoaders(JNIEnv* env, const std::vector<jobject>& loaders, const char* name) {
    jclass classes = env->FindClass("java/lang/Class");
    if (classes == nullptr) { clearPendingException(env); return nullptr; }
    jmethodID forName = env->GetStaticMethodID(classes, "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (forName == nullptr) { clearPendingException(env); env->DeleteLocalRef(classes); return nullptr; }
    jstring className = env->NewStringUTF(name);
    if (className == nullptr) { clearPendingException(env); env->DeleteLocalRef(classes); return nullptr; }
    jclass found = nullptr;
    for (jobject loader : loaders) {
        auto result = static_cast<jclass>(env->CallStaticObjectMethod(classes, forName, className, JNI_FALSE, loader));
        if (env->ExceptionCheck()) { clearPendingException(env); continue; }
        if (result != nullptr) { found = result; break; }
    }
    env->DeleteLocalRef(className);
    env->DeleteLocalRef(classes);
    return found;
}

bool stackContains(JNIEnv* env, const char* const* classPrefixes, size_t count) {
    LocalFrame frame(env, 16);
    if (!frame) return false;
    jclass threads = env->FindClass("java/lang/Thread");
    if (threads == nullptr) { clearPendingException(env); return false; }
    jmethodID current = env->GetStaticMethodID(threads, "currentThread", "()Ljava/lang/Thread;");
    if (current == nullptr) { clearPendingException(env); return false; }
    jmethodID getStack = env->GetMethodID(threads, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    if (getStack == nullptr) { clearPendingException(env); return false; }
    jobject thread = env->CallStaticObjectMethod(threads, current);
    if (thread == nullptr || env->ExceptionCheck()) { clearPendingException(env); return false; }
    auto stack = static_cast<jobjectArray>(env->CallObjectMethod(thread, getStack));
    if (stack == nullptr || env->ExceptionCheck()) { clearPendingException(env); return false; }
    jclass elements = env->FindClass("java/lang/StackTraceElement");
    if (elements == nullptr) { clearPendingException(env); return false; }
    jmethodID className = env->GetMethodID(elements, "getClassName", "()Ljava/lang/String;");
    if (className == nullptr) { clearPendingException(env); return false; }
    const jsize length = std::min<jsize>(env->GetArrayLength(stack), 256);
    for (jsize i = 0; i < length; ++i) {
        LocalFrame itemFrame(env, 4);
        if (!itemFrame) return false;
        jobject element = env->GetObjectArrayElement(stack, i);
        if (element == nullptr || env->ExceptionCheck()) { clearPendingException(env); continue; }
        const std::string name = toLower(stringValue(env,
                static_cast<jstring>(env->CallObjectMethod(element, className))));
        for (size_t p = 0; p < count; ++p) if (name.rfind(classPrefixes[p], 0) == 0) return true;
    }
    return false;
}

AppInfo getAppInfo(JNIEnv* env, jobject context) {
    AppInfo result;
    LocalFrame frame(env, 32);
    if (!frame) return result;
    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) { clearPendingException(env); return result; }
    jmethodID getInfo = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    if (getInfo == nullptr) { clearPendingException(env); return result; }
    jobject info = env->CallObjectMethod(context, getInfo);
    if (info == nullptr || env->ExceptionCheck()) { clearPendingException(env); return result; }
    result.componentNames.push_back(toLower(stringField(env, info, "className")));
    if (getSdkInt(env) >= 28) result.componentNames.push_back(toLower(stringField(env, info, "appComponentFactory")));
    result.sourceDir = stringField(env, info, "sourceDir");
    result.dataDir = stringField(env, info, "dataDir");
    result.available = !result.sourceDir.empty() && !result.dataDir.empty();

    // Context.applicationInfo may omit metadata; request GET_META_DATA for our own package.
    jmethodID getPm = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (getPm == nullptr) { clearPendingException(env); return result; }
    jmethodID getPackage = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    if (getPackage == nullptr) { clearPendingException(env); return result; }
    jobject pm = env->CallObjectMethod(context, getPm);
    if (pm == nullptr || env->ExceptionCheck()) { clearPendingException(env); return result; }
    auto package = static_cast<jstring>(env->CallObjectMethod(context, getPackage));
    if (package == nullptr || env->ExceptionCheck()) { clearPendingException(env); return result; }
    jclass pmClass = env->GetObjectClass(pm);
    jmethodID query = pmClass == nullptr ? nullptr : env->GetMethodID(pmClass, "getApplicationInfo",
            "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;");
    if (query == nullptr) { clearPendingException(env); return result; }
    jobject manifestInfo = env->CallObjectMethod(pm, query, package, 128);
    if (manifestInfo == nullptr || env->ExceptionCheck()) { clearPendingException(env); return result; }
    // Inspect both the running Context and PM's installed manifest snapshot.
    result.componentNames.push_back(toLower(stringField(env, manifestInfo, "className")));
    if (getSdkInt(env) >= 28) result.componentNames.push_back(toLower(stringField(env, manifestInfo, "appComponentFactory")));
    jclass infoClass = env->GetObjectClass(manifestInfo);
    jfieldID metadata = infoClass == nullptr ? nullptr : env->GetFieldID(infoClass, "metaData", "Landroid/os/Bundle;");
    if (metadata == nullptr) { clearPendingException(env); return result; }
    jobject bundle = env->GetObjectField(manifestInfo, metadata);
    if (bundle == nullptr || env->ExceptionCheck()) { clearPendingException(env); return result; }
    jclass bundleClass = env->GetObjectClass(bundle);
    jmethodID keysMethod = bundleClass == nullptr ? nullptr : env->GetMethodID(bundleClass, "keySet", "()Ljava/util/Set;");
    if (keysMethod == nullptr) { clearPendingException(env); return result; }
    jobject keys = env->CallObjectMethod(bundle, keysMethod);
    if (keys == nullptr || env->ExceptionCheck()) { clearPendingException(env); return result; }
    jclass setClass = env->FindClass("java/util/Set");
    jmethodID toArray = setClass == nullptr ? nullptr : env->GetMethodID(setClass, "toArray", "()[Ljava/lang/Object;");
    if (toArray == nullptr) { clearPendingException(env); return result; }
    auto array = static_cast<jobjectArray>(env->CallObjectMethod(keys, toArray));
    if (array == nullptr || env->ExceptionCheck()) { clearPendingException(env); return result; }
    const jsize length = std::min<jsize>(env->GetArrayLength(array), 256);
    for (jsize i = 0; i < length; ++i) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.metadataKeys.push_back(toLower(stringValue(env, key)));
        if (key != nullptr) env->DeleteLocalRef(key);
    }
    return result;
}

bool assetExists(JNIEnv* env, jobject context, const char* path) {
    LocalFrame frame(env, 12);
    if (!frame) return false;
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getAssets = contextClass == nullptr ? nullptr : env->GetMethodID(
            contextClass, "getAssets", "()Landroid/content/res/AssetManager;");
    if (getAssets == nullptr) { clearPendingException(env); return false; }
    jobject assets = env->CallObjectMethod(context, getAssets);
    if (assets == nullptr || env->ExceptionCheck()) { clearPendingException(env); return false; }
    jclass assetsClass = env->GetObjectClass(assets);
    jmethodID open = assetsClass == nullptr ? nullptr : env->GetMethodID(assetsClass, "open", "(Ljava/lang/String;)Ljava/io/InputStream;");
    if (open == nullptr) { clearPendingException(env); return false; }
    jstring name = env->NewStringUTF(path);
    if (name == nullptr) { clearPendingException(env); return false; }
    jobject stream = env->CallObjectMethod(assets, open, name);
    if (stream == nullptr || env->ExceptionCheck()) { clearPendingException(env); return false; }
    jclass inputClass = env->FindClass("java/io/InputStream");
    jmethodID close = inputClass == nullptr ? nullptr : env->GetMethodID(inputClass, "close", "()V");
    if (close != nullptr) env->CallVoidMethod(stream, close);
    clearPendingException(env);
    return true;
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

bool getGlobalSettingInt(JNIEnv* env, jobject context, const char* settingName, jint defaultValue, jint* value) {
    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        clearPendingException(env);
        return false;
    }
    jmethodID getContentResolver = env->GetMethodID(
            contextClass,
            "getContentResolver",
            "()Landroid/content/ContentResolver;");
    if (getContentResolver == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(contextClass);
        return false;
    }
    jobject resolver = env->CallObjectMethod(context, getContentResolver);
    if (env->ExceptionCheck() || resolver == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(contextClass);
        return false;
    }

    jclass globalSettingsClass = env->FindClass("android/provider/Settings$Global");
    jmethodID getInt = globalSettingsClass == nullptr ? nullptr : env->GetStaticMethodID(
            globalSettingsClass,
            "getInt",
            "(Landroid/content/ContentResolver;Ljava/lang/String;I)I");
    if (getInt == nullptr) {
        clearPendingException(env);
        if (globalSettingsClass != nullptr) env->DeleteLocalRef(globalSettingsClass);
        env->DeleteLocalRef(resolver);
        env->DeleteLocalRef(contextClass);
        return false;
    }

    jstring name = env->NewStringUTF(settingName);
    const jint settingValue = env->CallStaticIntMethod(
            globalSettingsClass, getInt, resolver, name, defaultValue);
    const bool succeeded = !env->ExceptionCheck();
    clearPendingException(env);
    if (succeeded) *value = settingValue;

    if (name != nullptr) env->DeleteLocalRef(name);
    env->DeleteLocalRef(globalSettingsClass);
    env->DeleteLocalRef(resolver);
    env->DeleteLocalRef(contextClass);
    return succeeded;
}

}  // detection_utils 命名空间结束
}  // security 命名空间结束
}  // chimera 命名空间结束
