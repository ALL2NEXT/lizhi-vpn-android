#pragma once

#include <array>
#include <cstdint>
#include <vector>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/pk.h>

namespace chimera::transport {

using Bytes = std::vector<unsigned char>;
constexpr size_t kMaxRequestSize = 1024 * 1024;
constexpr size_t kMaxResponseSize = 24 * 1024 * 1024;

// One request and one response per session. Keys never cross JNI.
class Session final {
public:
    explicit Session(const Bytes& publicKeyDer);
    ~Session();
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    Bytes seal(const Bytes& plaintext);
    Bytes open(const Bytes& packet);

private:
    std::array<unsigned char, 64> keys_{};
    mbedtls_entropy_context entropy_{};
    mbedtls_ctr_drbg_context random_{};
    mbedtls_pk_context publicKey_{};
    bool sealed_ = false;
    bool opened_ = false;
    void clear();
};

}  // namespace chimera::transport
