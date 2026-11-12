#include "security.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_XPOSED
#define CHIMERA_DETECT_XPOSED 1
#endif

namespace chimera {
namespace security {
namespace {

bool hasPopulatedCache(JNIEnv* env, jclass owner, const char* fieldName) {
    detection_utils::LocalFrame frame(env, 16);
    if (!frame || owner == nullptr) return false;
    jclass classes = env->FindClass("java/lang/Class");
    if (classes == nullptr) { detection_utils::clearPendingException(env); return false; }
    jmethodID declaredField = env->GetMethodID(classes, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
    if (declaredField == nullptr) { detection_utils::clearPendingException(env); return false; }
    jstring name = env->NewStringUTF(fieldName);
    if (name == nullptr) { detection_utils::clearPendingException(env); return false; }
    jobject field = env->CallObjectMethod(owner, declaredField, name);
    if (field == nullptr || env->ExceptionCheck()) { detection_utils::clearPendingException(env); return false; }
    jclass fields = env->FindClass("java/lang/reflect/Field");
    if (fields == nullptr) { detection_utils::clearPendingException(env); return false; }
    jmethodID accessible = env->GetMethodID(fields, "setAccessible", "(Z)V");
    if (accessible == nullptr) { detection_utils::clearPendingException(env); return false; }
    env->CallVoidMethod(field, accessible, JNI_TRUE);
    if (env->ExceptionCheck()) { detection_utils::clearPendingException(env); return false; }
    jmethodID get = env->GetMethodID(fields, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (get == nullptr) { detection_utils::clearPendingException(env); return false; }
    jobject value = env->CallObjectMethod(field, get, nullptr);
    if (value == nullptr || env->ExceptionCheck()) { detection_utils::clearPendingException(env); return false; }
    jclass maps = env->FindClass("java/util/Map");
    if (maps == nullptr || !env->IsInstanceOf(value, maps)) { detection_utils::clearPendingException(env); return false; }
    jmethodID size = env->GetMethodID(maps, "size", "()I");
    if (size == nullptr) { detection_utils::clearPendingException(env); return false; }
    const jint entries = env->CallIntMethod(value, size);
    const bool found = !env->ExceptionCheck() && entries > 0;
    detection_utils::clearPendingException(env);
    return found;
}
}  // namespace

void detectXposed(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_XPOSED
    detection_utils::LocalFrame frame(env);
    if (!frame) { fatalFinding(findings, "xposed_classloader_unavailable"); return; }
    const auto loaders = detection_utils::collectClassLoaders(env, context);
    if (loaders.empty()) fatalFinding(findings, "xposed_classloader_unavailable");
    jclass helpers = detection_utils::findClassInLoaders(env, loaders, "de.robv.android.xposed.XposedHelpers");
    if (hasPopulatedCache(env, helpers, "methodCache")) fatalFinding(findings, "xposed_method_cache");
    jclass bridge = detection_utils::findClassInLoaders(env, loaders, "de.robv.android.xposed.XposedBridge");
    if (hasPopulatedCache(env, bridge, "sHookedMethodCallbacks")) fatalFinding(findings, "xposed_hook_callbacks");
    if (bridge != nullptr || helpers != nullptr) fatalFinding(findings, "xposed_class");

    static const char* kLoaderMarkers[] = {"de.robv.android.xposed", "xposedbridge.jar", "org.meowcat.edxposed"};
    for (const auto& description : detection_utils::describeClassLoaders(env, loaders)) {
        if (detection_utils::containsAny(description, kLoaderMarkers, sizeof(kLoaderMarkers) / sizeof(kLoaderMarkers[0]))) {
            fatalFinding(findings, "xposed_classloader");
            break;
        }
    }
    static const char* kStackPrefixes[] = {"de.robv.android.xposed.", "com.saurik.substrate."};
    if (detection_utils::stackContains(env, kStackPrefixes, sizeof(kStackPrefixes) / sizeof(kStackPrefixes[0]))) {
        fatalFinding(findings, "xposed_stack");
    }
    static const char* kMapMarkers[] = {"xposedbridge", "libxposed", "libedxp", "riru_xposed"};
    std::string maps;
    if (!detection_utils::readTextFile("/proc/self/maps", &maps) || maps.empty()) {
        fatalFinding(findings, "xposed_maps_unavailable");
    } else if (detection_utils::containsAny(maps, kMapMarkers, sizeof(kMapMarkers) / sizeof(kMapMarkers[0]))) {
        fatalFinding(findings, "xposed_artifact");
    }
    if (
            detection_utils::fileExists("/system/framework/XposedBridge.jar") ||
            detection_utils::fileExists("/system/lib/libxposed_art.so") ||
            detection_utils::fileExists("/system/lib64/libxposed_art.so") ||
            detection_utils::fileExists("/system/bin/app_process.orig") ||
            detection_utils::fileExists("/system/bin/app_process64_xposed")) {
        fatalFinding(findings, "xposed_artifact");
    }
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
