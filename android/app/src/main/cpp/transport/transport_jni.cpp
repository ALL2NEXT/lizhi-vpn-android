#include "transport_jni.h"
#include "transport_crypto.h"

#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <mbedtls/platform_util.h>

namespace chimera::transport {
namespace {
std::mutex sessionsMutex;
std::unordered_map<jlong, std::unique_ptr<Session>> sessions;
jlong nextHandle = 1;

void throwIo(JNIEnv* env) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) {
        env->ThrowNew(type, "Native encrypted transport failed");
        env->DeleteLocalRef(type);
    }
}

Bytes bytes(JNIEnv* env, jbyteArray value, size_t limit) {
    if (value == nullptr) throw std::runtime_error("missing bytes");
    const auto length = env->GetArrayLength(value);
    if (static_cast<size_t>(length) > limit) throw std::runtime_error("too large");
    Bytes result(static_cast<size_t>(length));
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("JNI read failed");
    return result;
}

jlong create(JNIEnv* env, jclass, jbyteArray publicKey) {
    try {
        auto session = std::make_unique<Session>(bytes(env, publicKey, 2048));
        std::lock_guard<std::mutex> lock(sessionsMutex);
        if (sessions.size() >= 256 || nextHandle == std::numeric_limits<jlong>::max()) {
            throw std::runtime_error("too many sessions");
        }
        const jlong handle = nextHandle++;
        sessions.emplace(handle, std::move(session));
        return handle;
    } catch (...) { throwIo(env); return 0; }
}

jbyteArray transform(JNIEnv* env, jlong handle, jbyteArray input, bool encrypt) {
    Bytes output;
    Bytes source;
    try {
        source = bytes(env, input, encrypt ? kMaxRequestSize : kMaxResponseSize + 32);
        {
            std::lock_guard<std::mutex> lock(sessionsMutex);
            const auto it = sessions.find(handle);
            if (it == sessions.end()) throw std::runtime_error("invalid session");
            output = encrypt ? it->second->seal(source) : it->second->open(source);
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result != nullptr) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                    reinterpret_cast<const jbyte*>(output.data()));
        }
        mbedtls_platform_zeroize(source.data(), source.size());
        mbedtls_platform_zeroize(output.data(), output.size());
        return result;
    } catch (...) {
        mbedtls_platform_zeroize(source.data(), source.size());
        mbedtls_platform_zeroize(output.data(), output.size());
        throwIo(env);
        return nullptr;
    }
}

jbyteArray seal(JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    return transform(env, handle, input, true);
}
jbyteArray open(JNIEnv* env, jclass, jlong handle, jbyteArray input) {
    return transform(env, handle, input, false);
}
void release(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(sessionsMutex);
    sessions.erase(handle);
}
}  // namespace

bool registerNatives(JNIEnv* env) {
    jclass type = env->FindClass("io/github/HanJason233/chimera/network/transport/NativeTransportCrypto");
    if (type == nullptr) return false;
    JNINativeMethod methods[] = {
        {"nativeCreate", "([B)J", reinterpret_cast<void*>(create)},
        {"nativeSeal", "(J[B)[B", reinterpret_cast<void*>(seal)},
        {"nativeOpen", "(J[B)[B", reinterpret_cast<void*>(open)},
        {"nativeRelease", "(J)V", reinterpret_cast<void*>(release)},
    };
    const bool ok = env->RegisterNatives(type, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
    env->DeleteLocalRef(type);
    return ok;
}
}  // namespace chimera::transport
