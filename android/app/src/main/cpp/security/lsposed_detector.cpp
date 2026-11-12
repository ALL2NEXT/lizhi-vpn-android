#include "security.h"

#include "detection_utils.h"

#ifndef CHIMERA_DETECT_LSPOSED
#define CHIMERA_DETECT_LSPOSED 1
#endif

namespace chimera {
namespace security {
namespace {

bool hasUnexpectedNativeMethod(JNIEnv* env) {
    // Read ART's Java-visible modifiers. Never assume jmethodID is an ArtMethod pointer.
    static const struct { const char* owner; const char* name; const char* signature; } kMethods[] = {
        {"java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;"},
        {"java/lang/Runtime", "exec", "([Ljava/lang/String;)Ljava/lang/Process;"},
        {"java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"},
        {"android/app/Activity", "onCreate", "(Landroid/os/Bundle;)V"},
        {"android/content/ContextWrapper", "attachBaseContext", "(Landroid/content/Context;)V"}
    };
    for (const auto& target : kMethods) {
        detection_utils::LocalFrame frame(env, 8);
        if (!frame) continue;
        jclass owner = env->FindClass(target.owner);
        if (owner == nullptr) { detection_utils::clearPendingException(env); continue; }
        jmethodID method = env->GetMethodID(owner, target.name, target.signature);
        if (method == nullptr) { detection_utils::clearPendingException(env); continue; }
        jobject reflected = env->ToReflectedMethod(owner, method, JNI_FALSE);
        if (reflected == nullptr || env->ExceptionCheck()) { detection_utils::clearPendingException(env); continue; }
        jclass methods = env->GetObjectClass(reflected);
        jmethodID modifiers = methods == nullptr ? nullptr : env->GetMethodID(methods, "getModifiers", "()I");
        if (modifiers == nullptr) { detection_utils::clearPendingException(env); continue; }
        const jint flags = env->CallIntMethod(reflected, modifiers);
        const bool hooked = !env->ExceptionCheck() && (flags & 0x0100) != 0;
        detection_utils::clearPendingException(env);
        if (hooked) return true;
    }
    return false;
}
}  // namespace

void detectLsposed(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_LSPOSED
    detection_utils::LocalFrame frame(env);
    if (!frame) { fatalFinding(findings, "lsposed_classloader_unavailable"); return; }
    const auto loaders = detection_utils::collectClassLoaders(env, context);
    if (loaders.empty()) fatalFinding(findings, "lsposed_classloader_unavailable");
    static const char* kClasses[] = {
        "org.lsposed.lspd.core.Main", "org.lsposed.lspd.nativebridge.HookBridge",
        "org.lsposed.lspd.impl.LSPosedBridge", "org.lsposed.lspd.deopt.PrebuiltMethodsDeopter"
    };
    for (const char* name : kClasses) {
        jclass found = detection_utils::findClassInLoaders(env, loaders, name);
        if (found == nullptr) continue;
        env->DeleteLocalRef(found);
        fatalFinding(findings, "lsposed_class");
        break;
    }
    static const char* kLoaderMarkers[] = {"org.lsposed.lspd.", "lspd/inmemorydexclassloader", "/lsposed/"};
    for (const auto& description : detection_utils::describeClassLoaders(env, loaders)) {
        if (detection_utils::containsAny(description, kLoaderMarkers, sizeof(kLoaderMarkers) / sizeof(kLoaderMarkers[0]))) {
            fatalFinding(findings, "lsposed_classloader");
            break;
        }
    }
    static const char* kStackPrefixes[] = {"org.lsposed.lspd.", "org.lsposed.lsplant."};
    if (detection_utils::stackContains(env, kStackPrefixes, sizeof(kStackPrefixes) / sizeof(kStackPrefixes[0]))) {
        fatalFinding(findings, "lsposed_stack");
    }
    if (hasUnexpectedNativeMethod(env)) fatalFinding(findings, "art_method_native");
    static const char* kMapMarkers[] = {"/lsposed/", "/lspd/", "liblspd", "liblsposed", "liblsplant", "zygisk_lsposed", "riru_lsposed"};
    std::string maps;
    if (!detection_utils::readTextFile("/proc/self/maps", &maps) || maps.empty()) {
        fatalFinding(findings, "lsposed_maps_unavailable");
    } else if (detection_utils::containsAny(maps, kMapMarkers, sizeof(kMapMarkers) / sizeof(kMapMarkers[0]))) {
        fatalFinding(findings, "lsposed_artifact");
    }
    if (
            detection_utils::fileExists("/data/adb/lspd") ||
            detection_utils::fileExists("/data/adb/modules/zygisk_lsposed") ||
            detection_utils::fileExists("/data/adb/modules/lsposed")) {
        fatalFinding(findings, "lsposed_artifact");
    }
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
