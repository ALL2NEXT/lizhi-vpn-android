#include "security.h"
#include "detection_utils.h"

namespace chimera {
namespace security {

void detectLspatch(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_DETECT_LSPATCH
    detection_utils::LocalFrame frame(env);
    if (!frame) { addFinding(findings, "lspatch_detection_unavailable"); return; }
    const auto info = detection_utils::getAppInfo(env, context);
    if (!info.available) addFinding(findings, "lspatch_detection_unavailable");
    static const char* kComponents[] = {"org.lsposed.lspatch.", "lspappcomponentfactorystub"};
    for (const auto& component : info.componentNames) {
        if (detection_utils::containsAny(component, kComponents, 2)) {
            addFinding(findings, "lspatch_component");
            break;
        }
    }
    for (const auto& key : info.metadataKeys) {
        if (key == "lspatch" || key.rfind("lspatch.", 0) == 0 || key.rfind("org.lsposed.lspatch", 0) == 0) {
            addFinding(findings, "lspatch_metadata");
            break;
        }
    }
    const auto loaders = detection_utils::collectClassLoaders(env, context);
    if (loaders.empty()) addFinding(findings, "lspatch_detection_unavailable");
    static const char* kClasses[] = {
        "org.lsposed.lspatch.loader.LSPApplication", "org.lsposed.lspatch.loader.LSPLoader",
        "org.lsposed.lspatch.metaloader.LSPAppComponentFactory",
        "org.lsposed.lspatch.loader.LSPAppComponentFactoryStub",
        "org.lsposed.lspatch.appstub.LSPAppComponentFactoryStub"
    };
    for (const char* name : kClasses) {
        jclass found = detection_utils::findClassInLoaders(env, loaders, name);
        if (found == nullptr) continue;
        env->DeleteLocalRef(found);
        addFinding(findings, "lspatch_class");
        break;
    }
    static const char* kAssets[] = {"lspatch/config.json", "lspatch/loader.dex", "lspatch/origin.apk", "lspatch/metaloader.dex"};
    for (const char* path : kAssets) {
        if (detection_utils::assetExists(env, context, path)) { addFinding(findings, "lspatch_apk_asset"); break; }
    }
    if (info.available && detection_utils::fileExists((info.dataDir + "/cache/lspatch").c_str())) {
        addFinding(findings, "lspatch_private_files");
    }
    std::string maps;
    static const char* kMaps[] = {"liblspatch.so", "/lspatch/", "lspatch-loader", "lspatch/metaloader"};
    if (!detection_utils::readTextFile("/proc/self/maps", &maps) || maps.empty()) {
        addFinding(findings, "lspatch_detection_unavailable");
    } else if (detection_utils::containsAny(maps, kMaps, sizeof(kMaps) / sizeof(kMaps[0]))) {
        addFinding(findings, "lspatch_native");
    }
#else
    (void) env; (void) context; (void) findings;
#endif
}

}  // namespace security
}  // namespace chimera
