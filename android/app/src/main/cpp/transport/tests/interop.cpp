#include "transport_crypto.h"
#include <iostream>
#include <stdexcept>
#include <string>

using chimera::transport::Bytes;

Bytes unhex(const std::string& text) {
    if (text.size() % 2) throw std::runtime_error("invalid hex");
    Bytes bytes;
    for (size_t i = 0; i < text.size(); i += 2) {
        bytes.push_back(static_cast<unsigned char>(std::stoul(text.substr(i, 2), nullptr, 16)));
    }
    return bytes;
}

void hex(const Bytes& bytes) {
    constexpr char digits[] = "0123456789abcdef";
    for (unsigned char byte : bytes) std::cout << digits[byte >> 4] << digits[byte & 15];
    std::cout << std::endl;
}

int main() {
    try {
        std::string publicKey, plaintext, response;
        if (!std::getline(std::cin, publicKey) || !std::getline(std::cin, plaintext)) return 2;
        chimera::transport::Session session(unhex(publicKey));
        hex(session.seal(unhex(plaintext)));
        if (!std::getline(std::cin, response)) return 2;
        hex(session.open(unhex(response)));
        // A native session must reject a second seal and a second open.
        bool sealRejected = false, openRejected = false;
        try { session.seal({}); } catch (...) { sealRejected = true; }
        try { session.open(unhex(response)); } catch (...) { openRejected = true; }
        return sealRejected && openRejected ? 0 : 3;
    } catch (const std::exception&) {
        std::cerr << "Native transport rejected input" << std::endl;
        return 1;
    }
}
