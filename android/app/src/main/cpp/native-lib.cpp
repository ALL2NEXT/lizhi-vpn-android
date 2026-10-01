#include <jni.h>
#include <android/log.h>
#include "runtime-tuner.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <stdexcept>
#include <string>

#define TAG "NativeSecurity"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool isExpectedSignature(const jbyte* hash, jsize hashLen) {
    if (hash == nullptr || hashLen != 32) return false;

    // Release 证书 SHA-256 的混淆表示。
    // 对应证书指纹: 0C2E2593E6D882994D2E8F19F60B5CE84FEA2BE609E07803978752A423EBD39F
static const uint8_t partA[32] = {
        0x3F, 0xAC, 0xBF, 0x53, 0x22, 0xB3, 0xBB, 0xE9,
        0xDB, 0x61, 0xB9, 0x0C, 0x28, 0xC2, 0x89, 0x7D,
        0xB2, 0x54, 0x10, 0x9E, 0xF2, 0x32, 0x94, 0x49,
        0xCA, 0xFD, 0x2C, 0xE2, 0x6B, 0x37, 0x46, 0x6C
};
static const uint8_t partB[32] = {
        0x21, 0x90, 0x88, 0xD2, 0xD6, 0x79, 0x2B, 0x62,
        0x84, 0x5D, 0x24, 0x07, 0xCC, 0xDB, 0xC7, 0x87,
        0xEF, 0xAC, 0x29, 0x6A, 0xE9, 0xC0, 0xFE, 0x58,
        0x4F, 0x68, 0x6C, 0x54, 0x5A, 0xCE, 0x87, 0xE1
};
constexpr uint8_t kXor = 0x12;

    const uintptr_t hashPtr = reinterpret_cast<uintptr_t>(hash);
    for (int i = 0; i < 32; ++i) {
        volatile uint8_t noise1 = static_cast<uint8_t>((hashPtr >> ((i % 7) + 1)) & 0xFFu);
        volatile uint8_t noise2 = static_cast<uint8_t>((hashPtr >> ((i % 7) + 1)) & 0xFFu);
        const uint8_t expected = static_cast<uint8_t>(partA[i] ^ partB[i] ^ kXor ^ noise1 ^ noise2);
        if (static_cast<uint8_t>(hash[i]) != expected) {
            return false;
        }
    }
    return true;
}

static void clearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

static jint getSdkInt(JNIEnv* env) {
    jclass versionClass = env->FindClass("android/os/Build$VERSION");
    if (versionClass == nullptr) {
        clearPendingException(env);
        return 0;
    }

    jfieldID sdkIntField = env->GetStaticFieldID(versionClass, "SDK_INT", "I");
    if (sdkIntField == nullptr) {
        clearPendingException(env);
        return 0;
    }

    return env->GetStaticIntField(versionClass, sdkIntField);
}

static jobject getPackageInfoCompat(JNIEnv* env, jobject packageManager, jstring packageName, jint sdkInt) {
    jclass pmClass = env->GetObjectClass(packageManager);
    if (pmClass == nullptr) {
        clearPendingException(env);
        return nullptr;
    }

    constexpr jint kGetSignatures = 64;
    constexpr jint kGetSigningCertificates = 0x08000000;

    if (sdkInt >= 33) {
        jmethodID getPackageInfoLong = env->GetMethodID(
                pmClass,
                "getPackageInfo",
                "(Ljava/lang/String;Landroid/content/pm/PackageManager$PackageInfoFlags;)Landroid/content/pm/PackageInfo;");
        if (getPackageInfoLong != nullptr) {
            jclass flagsClass = env->FindClass("android/content/pm/PackageManager$PackageInfoFlags");
            if (flagsClass != nullptr) {
                jmethodID flagsOf = env->GetStaticMethodID(
                        flagsClass,
                        "of",
                        "(J)Landroid/content/pm/PackageManager$PackageInfoFlags;");
                if (flagsOf != nullptr) {
                    jobject flagsObj = env->CallStaticObjectMethod(
                            flagsClass,
                            flagsOf,
                            static_cast<jlong>(kGetSigningCertificates));
                    if (!env->ExceptionCheck() && flagsObj != nullptr) {
                        jobject packageInfo = env->CallObjectMethod(
                                packageManager,
                                getPackageInfoLong,
                                packageName,
                                flagsObj);
                        if (!env->ExceptionCheck()) {
                            return packageInfo;
                        }
                    }
                }
            }
        }
        clearPendingException(env);
    }

    jmethodID getPackageInfoInt = env->GetMethodID(
            pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (getPackageInfoInt == nullptr) {
        clearPendingException(env);
        return nullptr;
    }

    const jint flags = (sdkInt >= 28) ? kGetSigningCertificates : kGetSignatures;
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfoInt, packageName, flags);
    if (env->ExceptionCheck()) {
        clearPendingException(env);
        return nullptr;
    }
    return packageInfo;
}

static jobjectArray getSignaturesCompat(JNIEnv* env, jobject packageInfo, jint sdkInt) {
    jclass piClass = env->GetObjectClass(packageInfo);
    if (piClass == nullptr) {
        clearPendingException(env);
        return nullptr;
    }

    if (sdkInt >= 28) {
        jfieldID signingInfoField = env->GetFieldID(
                piClass, "signingInfo", "Landroid/content/pm/SigningInfo;");
        if (signingInfoField != nullptr) {
            jobject signingInfo = env->GetObjectField(packageInfo, signingInfoField);
            if (!env->ExceptionCheck() && signingInfo != nullptr) {
                jclass signingInfoClass = env->GetObjectClass(signingInfo);
                if (signingInfoClass != nullptr) {
                    jmethodID getApkContentsSigners = env->GetMethodID(
                            signingInfoClass, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
                    if (getApkContentsSigners != nullptr) {
                        auto* signers = static_cast<jobjectArray>(
                                env->CallObjectMethod(signingInfo, getApkContentsSigners));
                        if (!env->ExceptionCheck() && signers != nullptr && env->GetArrayLength(signers) > 0) {
                            return signers;
                        }
                        clearPendingException(env);
                    }

                    jmethodID getSigningCertificateHistory = env->GetMethodID(
                            signingInfoClass,
                            "getSigningCertificateHistory",
                            "()[Landroid/content/pm/Signature;");
                    if (getSigningCertificateHistory != nullptr) {
                        auto* history = static_cast<jobjectArray>(
                                env->CallObjectMethod(signingInfo, getSigningCertificateHistory));
                        if (!env->ExceptionCheck() && history != nullptr && env->GetArrayLength(history) > 0) {
                            return history;
                        }
                        clearPendingException(env);
                    }
                }
            }
        }
        clearPendingException(env);
    }

    jfieldID signaturesField = env->GetFieldID(piClass, "signatures", "[Landroid/content/pm/Signature;");
    if (signaturesField == nullptr) {
        clearPendingException(env);
        return nullptr;
    }

    auto* signatures = static_cast<jobjectArray>(env->GetObjectField(packageInfo, signaturesField));
    if (env->ExceptionCheck()) {
        clearPendingException(env);
        return nullptr;
    }
    return signatures;
}

static void nativeVerifySignature(JNIEnv* env, jclass /* clazz */, jobject context) {
    const jint sdkInt = getSdkInt(env);
    if (sdkInt < 24) {
        LOGE("Unsupported SDK_INT: %d", sdkInt);
        abort();
    }

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManager = env->GetMethodID(
            contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject packageManager = env->CallObjectMethod(context, getPackageManager);

    jmethodID getPackageName = env->GetMethodID(
            contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring packageName = static_cast<jstring>(env->CallObjectMethod(context, getPackageName));

    jobject packageInfo = getPackageInfoCompat(env, packageManager, packageName, sdkInt);
    if (packageInfo == nullptr) {
        LOGE("Failed to get PackageInfo");
        abort();
    }

    jobjectArray signatures = getSignaturesCompat(env, packageInfo, sdkInt);
    if (signatures == nullptr || env->GetArrayLength(signatures) == 0) {
        LOGE("No signatures found");
        abort();
    }

    jobject signature = env->GetObjectArrayElement(signatures, 0);
    jclass sigClass = env->GetObjectClass(signature);
    jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
    jbyteArray signatureBytes = static_cast<jbyteArray>(env->CallObjectMethod(signature, toByteArray));

    jclass mdClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(
            mdClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring sha256 = env->NewStringUTF("SHA-256");
    jobject md = env->CallStaticObjectMethod(mdClass, getInstance, sha256);

    jmethodID digest = env->GetMethodID(mdClass, "digest", "([B)[B");
    jbyteArray hashBytes = static_cast<jbyteArray>(env->CallObjectMethod(md, digest, signatureBytes));

    const jsize hashLen = env->GetArrayLength(hashBytes);
    jbyte* hash = env->GetByteArrayElements(hashBytes, nullptr);
    const bool match = isExpectedSignature(hash, hashLen);
    env->ReleaseByteArrayElements(hashBytes, hash, JNI_ABORT);

    if (!match) {
        LOGE("Signature verification failed");
        abort();
    }
}

static jobject utf8Charset(JNIEnv* env) {
    jclass standardCharsets = env->FindClass("java/nio/charset/StandardCharsets");
    if (standardCharsets == nullptr) return nullptr;
    jfieldID utf8Field = env->GetStaticFieldID(
            standardCharsets, "UTF_8", "Ljava/nio/charset/Charset;");
    if (utf8Field == nullptr) return nullptr;
    return env->GetStaticObjectField(standardCharsets, utf8Field);
}

static std::string toUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) throw std::invalid_argument("missing transform input");
    jobject utf8 = utf8Charset(env);
    if (utf8 == nullptr) throw std::runtime_error("UTF-8 charset unavailable");
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID getBytes = env->GetMethodID(
            stringClass, "getBytes", "(Ljava/nio/charset/Charset;)[B");
    auto* bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, utf8));
    if (bytes == nullptr || env->ExceptionCheck()) {
        throw std::runtime_error("failed to read transform input");
    }

    const jsize length = env->GetArrayLength(bytes);
    std::string result(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(&result[0]));
    }
    return result;
}

static jstring fromUtf8(JNIEnv* env, const std::string& value) {
    jobject utf8 = utf8Charset(env);
    if (utf8 == nullptr) return nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) return nullptr;
    if (!value.empty()) {
        env->SetByteArrayRegion(
                bytes, 0, static_cast<jsize>(value.size()),
                reinterpret_cast<const jbyte*>(value.data()));
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(
            stringClass, "<init>", "([BLjava/nio/charset/Charset;)V");
    return static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, utf8));
}

static jstring runTransform(JNIEnv* env, jstring value, jstring route) {
    try {
        const std::string input = toUtf8(env, value);
        const std::string routeValue = toUtf8(env, route);
        return fromUtf8(env, rt::b(input, routeValue));
    } catch (const std::exception& error) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, error.what());
        return nullptr;
    }
}

static jstring nativeC1(JNIEnv* env, jclass, jstring value, jstring route) {
    return runTransform(env, value, route);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass signatureCls = env->FindClass("io/github/HanJason233/chimera/util/SignatureVerifier");
    if (signatureCls == nullptr) {
        return JNI_ERR;
    }
    JNINativeMethod signatureMethods[] = {
            {"verifySignature", "(Landroid/content/Context;)V",
             reinterpret_cast<void*>(nativeVerifySignature)}
    };
    if (env->RegisterNatives(signatureCls, signatureMethods, 1) != JNI_OK) {
        return JNI_ERR;
    }

    jclass tunerCls = env->FindClass("io/github/HanJason233/chimera/util/RuntimeTuner");
    if (tunerCls == nullptr) {
        return JNI_ERR;
    }
    JNINativeMethod tunerMethods[] = {
            {"c1", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
             reinterpret_cast<void*>(nativeC1)},
    };
    if (env->RegisterNatives(tunerCls, tunerMethods, 1) != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
