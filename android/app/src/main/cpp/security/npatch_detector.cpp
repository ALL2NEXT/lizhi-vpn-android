#include "security.h"
#include "detection_utils.h"

namespace chimera {
namespace security {

void detectNpatch(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_NPATCH
    detection_utils::LocalFrame frame(env);
    if (!frame) { addFinding(findings, "npatch_detection_unavailable"); return; }
    const auto info = detection_utils::getAppInfo(env, context);
    if (!info.available) addFinding(findings, "npatch_detection_unavailable");
    static const char* kComponents[] = {"org.lsposed.npatch.", "npatchappcomponentfactorystub"};
    for (const auto& component : info.componentNames) {
        if (detection_utils::containsAny(component, kComponents, 2)) {
            addFinding(findings, "npatch_component");
            break;
        }
    }
    for (const auto& key : info.metadataKeys) {
        if (key == "npatch" || key.rfind("npatch.", 0) == 0 || key.rfind("org.lsposed.npatch", 0) == 0) {
            addFinding(findings, "npatch_metadata");
            break;
        }
    }
    const auto loaders = detection_utils::collectClassLoaders(env, context);
    if (loaders.empty()) addFinding(findings, "npatch_detection_unavailable");
    static const char* kClasses[] = {
        "org.lsposed.npatch.loader.NpatchAppComponentFactoryStub",
        "org.lsposed.npatch.metaloader.LSPAppComponentFactory",
        "org.lsposed.npatch.loader.LSPApplication", "org.lsposed.npatch.loader.LSPLoader"
    };
    for (const char* name : kClasses) {
        jclass found = detection_utils::findClassInLoaders(env, loaders, name);
        if (found == nullptr) continue;
        env->DeleteLocalRef(found);
        addFinding(findings, "npatch_class");
        break;
    }
    static const char* kAssets[] = {"npatch/config.json", "npatch/loader.dex", "npatch/origin.apk", "npatch/metaloader.dex"};
    for (const char* path : kAssets) {
        if (detection_utils::assetExists(env, context, path)) { addFinding(findings, "npatch_apk_asset"); break; }
    }
    if (info.available && (detection_utils::fileExists((info.dataDir + "/cache/npatch").c_str()) ||
            detection_utils::fileExists((info.dataDir + "/cache/npatch_origin").c_str()))) {
        addFinding(findings, "npatch_private_files");
    }
    std::string maps;
    static const char* kMaps[] = {"libnpatch.so", "/npatch/", "/npatch_origin/", "npatch-loader"};
    if (!detection_utils::readTextFile("/proc/self/maps", &maps) || maps.empty()) {
        addFinding(findings, "npatch_detection_unavailable");
    } else if (detection_utils::containsAny(maps, kMaps, sizeof(kMaps) / sizeof(kMaps[0]))) {
        addFinding(findings, "npatch_native");
    }
#else
    (void) env; (void) context; (void) findings;
#endif
}

}  // namespace security
}  // namespace chimera
