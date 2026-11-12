#include "transport_crypto.h"

#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <mbedtls/gcm.h>
#include <mbedtls/platform_util.h>
#include <mbedtls/rsa.h>

namespace chimera::transport {
namespace {
constexpr unsigned char kRequestMagic[] = {'C', 'H', 'M', '1'};
constexpr unsigned char kResponseMagic[] = {'C', 'H', 'R', '1'};
constexpr char kRequestAad[] = "chimera-transport-v1/request";
constexpr char kResponseAad[] = "chimera-transport-v1/response";
constexpr size_t kIvSize = 12;
constexpr size_t kTagSize = 16;

struct Gcm {
    mbedtls_gcm_context context;
    Gcm() { mbedtls_gcm_init(&context); }
    ~Gcm() { mbedtls_gcm_free(&context); }
};

void require(bool condition) {
    if (!condition) throw std::runtime_error("Encrypted transport validation failed");
}
}  // namespace

Session::Session(const Bytes& publicKeyDer) {
    mbedtls_entropy_init(&entropy_);
    mbedtls_ctr_drbg_init(&random_);
    mbedtls_pk_init(&publicKey_);
    try {
        require(!publicKeyDer.empty() && publicKeyDer.size() <= 2048);
        require(mbedtls_pk_parse_public_key(&publicKey_, publicKeyDer.data(), publicKeyDer.size()) == 0);
        require(mbedtls_pk_get_type(&publicKey_) == MBEDTLS_PK_RSA);
        const size_t bits = mbedtls_pk_get_bitlen(&publicKey_);
        require(bits == 2048 || bits == 3072 || bits == 4096);
        const unsigned char personalization[] = "chimera-transport-v1";
        require(mbedtls_ctr_drbg_seed(&random_, mbedtls_entropy_func, &entropy_,
                                     personalization, sizeof(personalization) - 1) == 0);
        require(mbedtls_ctr_drbg_random(&random_, keys_.data(), keys_.size()) == 0);
    } catch (...) {
        clear();
        throw;
    }
}

void Session::clear() {
    mbedtls_platform_zeroize(keys_.data(), keys_.size());
    mbedtls_pk_free(&publicKey_);
    mbedtls_ctr_drbg_free(&random_);
    mbedtls_entropy_free(&entropy_);
}

Session::~Session() { clear(); }

Bytes Session::seal(const Bytes& plaintext) {
    require(!sealed_ && !opened_ && plaintext.size() <= kMaxRequestSize);
    sealed_ = true;  // Never retry an encryption with the same session after an error.
    const size_t wrappedSize = mbedtls_pk_get_len(&publicKey_);
    const size_t headerSize = 6 + wrappedSize;
    Bytes packet(headerSize + kIvSize + plaintext.size() + kTagSize);
    std::copy(std::begin(kRequestMagic), std::end(kRequestMagic), packet.begin());
    packet[4] = static_cast<unsigned char>(wrappedSize >> 8);
    packet[5] = static_cast<unsigned char>(wrappedSize);
    auto* rsa = mbedtls_pk_rsa(publicKey_);
    require(mbedtls_rsa_set_padding(rsa, MBEDTLS_RSA_PKCS_V21, MBEDTLS_MD_SHA256) == 0);
    require(mbedtls_rsa_rsaes_oaep_encrypt(rsa, mbedtls_ctr_drbg_random, &random_,
            nullptr, 0, keys_.size(), keys_.data(), packet.data() + 6) == 0);
    auto* iv = packet.data() + headerSize;
    require(mbedtls_ctr_drbg_random(&random_, iv, kIvSize) == 0);
    Bytes aad(kRequestAad, kRequestAad + sizeof(kRequestAad) - 1);
    aad.insert(aad.end(), packet.begin(), packet.begin() + headerSize);
    Gcm gcm;
    require(mbedtls_gcm_setkey(&gcm.context, MBEDTLS_CIPHER_ID_AES, keys_.data(), 256) == 0);
    require(mbedtls_gcm_crypt_and_tag(&gcm.context, MBEDTLS_GCM_ENCRYPT, plaintext.size(),
            iv, kIvSize, aad.data(), aad.size(), plaintext.data(), iv + kIvSize,
            kTagSize, packet.data() + packet.size() - kTagSize) == 0);
    // The request key is no longer needed once its single message has been sealed.
    mbedtls_platform_zeroize(keys_.data(), 32);
    return packet;
}

Bytes Session::open(const Bytes& packet) {
    require(sealed_ && !opened_);
    opened_ = true;
    try {
        require(packet.size() >= 4 + kIvSize + kTagSize &&
                packet.size() <= kMaxResponseSize + 4 + kIvSize + kTagSize);
        require(std::equal(std::begin(kResponseMagic), std::end(kResponseMagic), packet.begin()));
        const size_t length = packet.size() - 4 - kIvSize - kTagSize;
        Bytes plaintext(length);
        Gcm gcm;
        require(mbedtls_gcm_setkey(&gcm.context, MBEDTLS_CIPHER_ID_AES, keys_.data() + 32, 256) == 0);
        const int result = mbedtls_gcm_auth_decrypt(&gcm.context, length, packet.data() + 4,
                kIvSize, reinterpret_cast<const unsigned char*>(kResponseAad), sizeof(kResponseAad) - 1,
                packet.data() + packet.size() - kTagSize, kTagSize,
                packet.data() + 4 + kIvSize, plaintext.data());
        if (result != 0) {
            mbedtls_platform_zeroize(plaintext.data(), plaintext.size());
            require(false);
        }
        mbedtls_platform_zeroize(keys_.data(), keys_.size());
        return plaintext;
    } catch (...) {
        mbedtls_platform_zeroize(keys_.data(), keys_.size());
        throw;
    }
}

}  // namespace chimera::transport
