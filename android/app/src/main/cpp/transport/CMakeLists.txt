cmake_minimum_required(VERSION 3.22.1)
project(chimera-transport LANGUAGES C CXX)

include(FetchContent)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
# Mbed TLS 3.6.7, pinned to the release commit and archive digest.
FetchContent_Declare(chimera_mbedtls
    URL https://codeload.github.com/Mbed-TLS/mbedtls/tar.gz/068ff080b369adfac81509f9b57b2afabaf82dc5
    URL_HASH SHA256=ca6bd316bbec49ef20088f39b8755fcaec0b7e45781506de55b17cd191ae6937
    # Populate without invoking upstream's TLS/framework build.
    SOURCE_SUBDIR chimera-crypto-only)
FetchContent_MakeAvailable(chimera_mbedtls)

# Build only crypto; no TLS stack, Java crypto provider, or private Android APIs.
set(crypto_sources
    aes aesce aesni asn1parse asn1write bignum bignum_core bignum_mod bignum_mod_raw
    cipher cipher_wrap constant_time ctr_drbg entropy entropy_poll gcm md oid
    pk pk_wrap pkparse platform platform_util rsa rsa_alt_helpers sha256)
list(TRANSFORM crypto_sources PREPEND "${chimera_mbedtls_SOURCE_DIR}/library/")
list(TRANSFORM crypto_sources APPEND ".c")
add_library(chimera-crypto STATIC ${crypto_sources})
target_include_directories(chimera-crypto PUBLIC "${chimera_mbedtls_SOURCE_DIR}/include")
target_compile_definitions(chimera-crypto PUBLIC
    MBEDTLS_CONFIG_FILE="${CMAKE_CURRENT_SOURCE_DIR}/mbedtls_config.h")
set_target_properties(chimera-crypto PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)
if(WIN32)
    target_link_libraries(chimera-crypto PRIVATE bcrypt)
endif()

add_library(chimera-transport STATIC transport_crypto.cpp)
target_compile_features(chimera-transport PUBLIC cxx_std_17)
target_include_directories(chimera-transport PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(chimera-transport PUBLIC chimera-crypto)
set_target_properties(chimera-transport PROPERTIES POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)

option(CHIMERA_TRANSPORT_TESTS "Build the native/WebCrypto interoperability harness" OFF)
if(CHIMERA_TRANSPORT_TESTS)
    add_executable(transport-interop tests/interop.cpp)
    target_link_libraries(transport-interop PRIVATE chimera-transport)
endif()
