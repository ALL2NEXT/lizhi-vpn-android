#include "runtime-tuner.h"

#include <array>
#include <cctype>
#include <cstdint>
#include <stdexcept>
#include <string>

namespace {

constexpr uint8_t kMask = 0xA7;
constexpr std::array<uint8_t, 64> kTableData = {
        0xCE, 0xF0, 0xCA, 0x96, 0x95, 0x93, 0xF2, 0xE1,
        0xDE, 0xE8, 0x90, 0xEC, 0xE0, 0x9E, 0xE9, 0xE5,
        0xF7, 0xCF, 0xE4, 0xCD, 0x92, 0xCC, 0xF4, 0x9F,
        0xEE, 0xFE, 0xC5, 0xEF, 0xF5, 0xD1, 0xD3, 0xD4,
        0xC2, 0xCB, 0xF6, 0xC4, 0xF3, 0xE6, 0x94, 0xD2,
        0xC0, 0xF1, 0xE2, 0xC8, 0x91, 0xC6, 0xC9, 0xC3,
        0x8A, 0xEB, 0xD7, 0xD5, 0xD6, 0xF8, 0xE3, 0xED,
        0xEA, 0xD0, 0x97, 0xFD, 0xDD, 0xC1, 0xDF, 0xFF,
};

std::array<char, 64> table() {
    std::array<char, 64> value{};
    for (size_t i = 0; i < kTableData.size(); ++i) {
        value[i] = static_cast<char>(kTableData[i] ^ kMask);
    }
    return value;
}

std::string trim(std::string value) {
    size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) {
        ++start;
    }
    size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(start, end - start);
}

bool decimalByte(const std::string& value, int& result) {
    if (value.empty() || value.size() > 3) return false;
    int number = 0;
    for (const char ch : value) {
        if (ch < '0' || ch > '9') return false;
        number = number * 10 + (ch - '0');
    }
    if (number > 255) return false;
    result = number;
    return true;
}

bool decimalPort(const std::string& value) {
    if (value.empty() || value.size() > 5) return false;
    unsigned int number = 0;
    for (const char ch : value) {
        if (ch < '0' || ch > '9') return false;
        number = number * 10u + static_cast<unsigned int>(ch - '0');
    }
    return number <= 65535u;
}

bool ipv4Tail(const std::string& value, int& result) {
    std::string address = value;
    const size_t mapped = address.rfind(':');
    if (mapped != std::string::npos && address.find('.') != std::string::npos) {
        address = address.substr(mapped + 1);
    }

    std::array<int, 4> parts{};
    size_t start = 0;
    for (size_t i = 0; i < parts.size(); ++i) {
        const size_t end = i + 1 == parts.size() ? std::string::npos : address.find('.', start);
        if (end == std::string::npos && i + 1 != parts.size()) return false;
        const std::string part = address.substr(start, end == std::string::npos ? end : end - start);
        if (!decimalByte(part, parts[i])) return false;
        if (end == std::string::npos) {
            start = address.size();
        } else {
            start = end + 1;
        }
    }
    if (start != address.size()) return false;
    result = parts.back();
    return true;
}

std::string addressFromRoute(std::string route) {
    route = trim(route);
    const size_t comma = route.find(',');
    if (comma != std::string::npos) route = trim(route.substr(0, comma));

    const size_t scheme = route.find("://");
    if (scheme != std::string::npos) {
        route = route.substr(scheme + 3);
        route = route.substr(0, route.find_first_of("/?#"));
        const size_t userInfo = route.rfind('@');
        if (userInfo != std::string::npos) route = route.substr(userInfo + 1);
    }

    if (!route.empty() && route.front() == '[') {
        const size_t close = route.find(']');
        if (close == std::string::npos) return {};
        return route.substr(1, close - 1);
    }

    const size_t firstColon = route.find(':');
    const size_t lastColon = route.rfind(':');
    if (firstColon != std::string::npos && firstColon == lastColon) {
        const std::string port = route.substr(firstColon + 1);
        if (decimalPort(port)) route = route.substr(0, firstColon);
    }
    return route;
}

size_t shiftFromRoute(const std::string& route) {
    const std::string address = addressFromRoute(route);
    int tail = 0;
    if (ipv4Tail(address, tail)) return static_cast<size_t>(tail) % 64;

    if (address.find(':') != std::string::npos) {
        const std::string group = address.substr(address.rfind(':') + 1);
        if (group.empty()) return 0;
        if (group.size() <= 4) {
            unsigned int number = 0;
            for (const char ch : group) {
                number <<= 4u;
                if (ch >= '0' && ch <= '9') number += static_cast<unsigned int>(ch - '0');
                else if (ch >= 'a' && ch <= 'f') number += static_cast<unsigned int>(ch - 'a' + 10);
                else if (ch >= 'A' && ch <= 'F') number += static_cast<unsigned int>(ch - 'A' + 10);
                else throw std::invalid_argument("invalid route address");
            }
            return static_cast<size_t>(number) % 64;
        }
    }

    throw std::invalid_argument("encrypted response is missing its numeric route address");
}

int tableIndex(const std::array<char, 64>& alphabet, char value) {
    for (size_t i = 0; i < alphabet.size(); ++i) {
        if (alphabet[i] == value) return static_cast<int>(i);
    }
    return -1;
}

std::string encodeBase64(const std::string& input, const std::array<char, 64>& alphabet) {
    std::string result;
    result.reserve(((input.size() + 2) / 3) * 4);
    for (size_t i = 0; i < input.size(); i += 3) {
        const size_t remaining = input.size() - i;
        const uint32_t a = static_cast<uint8_t>(input[i]);
        const uint32_t b = remaining > 1 ? static_cast<uint8_t>(input[i + 1]) : 0;
        const uint32_t c = remaining > 2 ? static_cast<uint8_t>(input[i + 2]) : 0;
        const uint32_t block = (a << 16u) | (b << 8u) | c;
        result.push_back(alphabet[(block >> 18u) & 0x3Fu]);
        result.push_back(alphabet[(block >> 12u) & 0x3Fu]);
        result.push_back(remaining > 1 ? alphabet[(block >> 6u) & 0x3Fu] : '=');
        result.push_back(remaining > 2 ? alphabet[block & 0x3Fu] : '=');
    }
    return result;
}

std::string decodeBase64(std::string input, const std::array<char, 64>& alphabet, size_t shift) {
    std::string compact;
    compact.reserve(input.size());
    for (const char ch : input) {
        if (!std::isspace(static_cast<unsigned char>(ch))) compact.push_back(ch);
    }
    if (compact.size() % 4 == 1) throw std::invalid_argument("invalid protected payload length");
    while (compact.size() % 4 != 0) compact.push_back('=');

    std::string result;
    result.reserve((compact.size() / 4) * 3);
    for (size_t i = 0; i < compact.size(); i += 4) {
        std::array<int, 4> values{};
        int padding = 0;
        for (size_t j = 0; j < values.size(); ++j) {
            const char ch = compact[i + j];
            if (ch == '=') {
                if (j < 2 || i + 4 != compact.size()) {
                    throw std::invalid_argument("invalid protected payload padding");
                }
                values[j] = 0;
                ++padding;
                continue;
            }
            if (padding != 0) throw std::invalid_argument("invalid protected payload padding");
            const int shiftedIndex = tableIndex(alphabet, ch);
            if (shiftedIndex < 0) throw std::invalid_argument("invalid protected payload character");
            values[j] = (shiftedIndex + 64 - static_cast<int>(shift)) % 64;
        }
        if (padding > 2) throw std::invalid_argument("invalid protected payload padding");

        const uint32_t block = (static_cast<uint32_t>(values[0]) << 18u) |
                               (static_cast<uint32_t>(values[1]) << 12u) |
                               (static_cast<uint32_t>(values[2]) << 6u) |
                               static_cast<uint32_t>(values[3]);
        result.push_back(static_cast<char>((block >> 16u) & 0xFFu));
        if (padding < 2) result.push_back(static_cast<char>((block >> 8u) & 0xFFu));
        if (padding < 1) result.push_back(static_cast<char>(block & 0xFFu));
    }
    return result;
}

}  // namespace

namespace rt {

std::string a(const std::string& value, const std::string& route) {
    const auto alphabet = table();
    const size_t shift = shiftFromRoute(route);
    std::string encoded = encodeBase64(value, alphabet);
    for (char& ch : encoded) {
        if (ch == '=') continue;
        const int index = tableIndex(alphabet, ch);
        ch = alphabet[(static_cast<size_t>(index) + shift) % alphabet.size()];
    }
    return encoded;
}

std::string b(const std::string& value, const std::string& route) {
    const auto alphabet = table();
    return decodeBase64(value, alphabet, shiftFromRoute(route));
}

}  // namespace rt
