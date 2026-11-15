#include "security.h"

#include "detection_utils.h"

#ifndef CHIMERA_VERIFY_SIGNATURE
#define CHIMERA_VERIFY_SIGNATURE 1
#endif
#ifndef CHIMERA_EXPECTED_CERT_SHA256
#define CHIMERA_EXPECTED_CERT_SHA256 "0C2E2593E6D882994D2E8F19F60B5CE84FEA2BE609E07803978752A423EBD39F"
#endif

namespace chimera {
namespace security {
namespace {

#if CHIMERA_VERIFY_SIGNATURE
bool currentSignerMatches(JNIEnv* env, jobject context, bool* available) {
    using namespace detection_utils;

    // 读取当前签名证书并与构建时配置的 SHA-256 比较。
    *available = false;
    const jint sdkInt = getSdkInt(env);
    if (sdkInt < 24) return false;

    jclass contextClass = env->GetObjectClass(context);
    if (contextClass == nullptr) {
        clearPendingException(env);
        return false;
    }
    jmethodID getPackageManager = env->GetMethodID(
            contextClass,
            "getPackageManager",
            "()Landroid/content/pm/PackageManager;");
    jmethodID getPackageName = env->GetMethodID(
            contextClass, "getPackageName", "()Ljava/lang/String;");
    if (getPackageManager == nullptr || getPackageName == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(contextClass);
        return false;
    }
    jobject packageManager = env->CallObjectMethod(context, getPackageManager);
    jstring packageName = static_cast<jstring>(env->CallObjectMethod(context, getPackageName));
    if (env->ExceptionCheck() || packageManager == nullptr || packageName == nullptr) {
        clearPendingException(env);
        if (packageName != nullptr) env->DeleteLocalRef(packageName);
        if (packageManager != nullptr) env->DeleteLocalRef(packageManager);
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
        env->DeleteLocalRef(packageName);
        env->DeleteLocalRef(contextClass);
        return false;
    }

    const jint flags = sdkInt >= 28 ? 0x08000000 : 64;
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfo, packageName, flags);
    if (env->ExceptionCheck() || packageInfo == nullptr) {
        clearPendingException(env);
        env->DeleteLocalRef(packageManagerClass);
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(packageName);
        env->DeleteLocalRef(contextClass);
        return false;
    }

    jclass packageInfoClass = env->GetObjectClass(packageInfo);
    jobjectArray signers = nullptr;
    if (sdkInt >= 28 && packageInfoClass != nullptr) {
        jfieldID signingInfoField = env->GetFieldID(
                packageInfoClass,
                "signingInfo",
                "Landroid/content/pm/SigningInfo;");
        if (signingInfoField != nullptr) {
            jobject signingInfo = env->GetObjectField(packageInfo, signingInfoField);
            if (!env->ExceptionCheck() && signingInfo != nullptr) {
                jclass signingInfoClass = env->GetObjectClass(signingInfo);
                jmethodID getApkContentsSigners = signingInfoClass == nullptr ? nullptr : env->GetMethodID(
                        signingInfoClass,
                        "getApkContentsSigners",
                        "()[Landroid/content/pm/Signature;");
                if (getApkContentsSigners != nullptr) {
                    signers = static_cast<jobjectArray>(
                            env->CallObjectMethod(signingInfo, getApkContentsSigners));
                }
                if (env->ExceptionCheck()) clearPendingException(env);
                if (signingInfoClass != nullptr) env->DeleteLocalRef(signingInfoClass);
                env->DeleteLocalRef(signingInfo);
            }
        }
        if (env->ExceptionCheck()) clearPendingException(env);
    }
    if (signers == nullptr && packageInfoClass != nullptr) {
        jfieldID signaturesField = env->GetFieldID(
                packageInfoClass, "signatures", "[Landroid/content/pm/Signature;");
        if (signaturesField != nullptr) {
            signers = static_cast<jobjectArray>(env->GetObjectField(packageInfo, signaturesField));
        }
        if (env->ExceptionCheck()) clearPendingException(env);
    }

    bool matches = false;
    if (signers != nullptr) {
        const jsize signerCount = env->GetArrayLength(signers);
        if (signerCount > 0) *available = true;
        for (jsize i = 0; i < signerCount && signerCount == 1; ++i) {
            jobject signer = env->GetObjectArrayElement(signers, i);
            if (signer == nullptr) continue;
            jclass signerClass = env->GetObjectClass(signer);
            jmethodID toByteArray = signerClass == nullptr ? nullptr : env->GetMethodID(
                    signerClass, "toByteArray", "()[B");
            jbyteArray encoded = toByteArray == nullptr ? nullptr : static_cast<jbyteArray>(
                    env->CallObjectMethod(signer, toByteArray));
            if (env->ExceptionCheck()) clearPendingException(env);

            if (encoded != nullptr) {
                jclass digestClass = env->FindClass("java/security/MessageDigest");
                jmethodID getInstance = digestClass == nullptr ? nullptr : env->GetStaticMethodID(
                        digestClass,
                        "getInstance",
                        "(Ljava/lang/String;)Ljava/security/MessageDigest;");
                jstring algorithm = env->NewStringUTF("SHA-256");
                jobject digest = getInstance == nullptr ? nullptr : env->CallStaticObjectMethod(
                        digestClass, getInstance, algorithm);
                if (env->ExceptionCheck()) clearPendingException(env);
                jmethodID digestMethod = digestClass == nullptr ? nullptr : env->GetMethodID(
                        digestClass, "digest", "([B)[B");
                jbyteArray hash = digest == nullptr || digestMethod == nullptr ? nullptr :
                        static_cast<jbyteArray>(env->CallObjectMethod(digest, digestMethod, encoded));
                if (env->ExceptionCheck()) clearPendingException(env);

                if (hash != nullptr && env->GetArrayLength(hash) == 32) {
                    unsigned char bytes[32];
                    env->GetByteArrayRegion(hash, 0, 32, reinterpret_cast<jbyte*>(bytes));
                    if (!env->ExceptionCheck()) {
                        static const char hex[] = "0123456789ABCDEF";
                        std::string actual;
                        actual.reserve(64);
                        for (size_t byteIndex = 0; byteIndex < 32; ++byteIndex) {
                            actual.push_back(hex[(bytes[byteIndex] >> 4) & 0x0F]);
                            actual.push_back(hex[bytes[byteIndex] & 0x0F]);
                        }
                        std::string expected = CHIMERA_EXPECTED_CERT_SHA256;
                        expected = toLower(expected);
                        actual = toLower(actual);
                        *available = true;
                        if (expected.size() == 64 && actual == expected) matches = true;
                    }
                    clearPendingException(env);
                }

                if (hash != nullptr) env->DeleteLocalRef(hash);
                if (digest != nullptr) env->DeleteLocalRef(digest);
                if (algorithm != nullptr) env->DeleteLocalRef(algorithm);
                if (digestClass != nullptr) env->DeleteLocalRef(digestClass);
                env->DeleteLocalRef(encoded);
            }
            if (signerClass != nullptr) env->DeleteLocalRef(signerClass);
            env->DeleteLocalRef(signer);
        }
        env->DeleteLocalRef(signers);
    }

    if (packageInfoClass != nullptr) env->DeleteLocalRef(packageInfoClass);
    env->DeleteLocalRef(packageInfo);
    env->DeleteLocalRef(packageManagerClass);
    env->DeleteLocalRef(packageManager);
    env->DeleteLocalRef(packageName);
    env->DeleteLocalRef(contextClass);
    return matches;
}
#endif

}  // 匿名命名空间结束

void detectSignature(JNIEnv* env, jobject context, std::vector<std::string>* findings) {
#if CHIMERA_VERIFY_SIGNATURE
    // 签名不匹配或无法读取时立即执行终止策略。
    bool signatureAvailable = false;
    const bool signatureMatches = currentSignerMatches(env, context, &signatureAvailable);
    if (!signatureAvailable) {
        addFinding(findings, "signature_unavailable");
    } else if (!signatureMatches) {
        addFinding(findings, "signature_mismatch");
    }
#else
    (void) env;
    (void) context;
    (void) findings;
#endif
}

}  // security 命名空间结束
}  // chimera 命名空间结束
