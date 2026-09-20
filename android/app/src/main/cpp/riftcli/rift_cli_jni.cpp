#include "rift_cli_core.h"

#include <jni.h>

#include <cstdint>
#include <string>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {

constexpr std::uint32_t kReplacement = 0xfffd;
constexpr jsize kMaxNativeArgs = 512;
constexpr std::size_t kMaxNativeArgBytes = 128 * 1024;
constexpr std::size_t kMaxNativeTotalArgBytes = 512 * 1024;
constexpr std::size_t kMaxNativeCwdBytes = 4096;

void appendUtf8(std::string& out, std::uint32_t cp) {
    if (cp <= 0x7f) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
}

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};

    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve(static_cast<std::size_t>(length));

    for (jsize i = 0; i < length; ++i) {
        std::uint32_t cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (i + 1 < length) {
                const std::uint32_t low = chars[i + 1];
                if (low >= 0xdc00 && low <= 0xdfff) {
                    cp = 0x10000 + (((cp - 0xd800) << 10) | (low - 0xdc00));
                    ++i;
                } else {
                    cp = kReplacement;
                }
            } else {
                cp = kReplacement;
            }
        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
            cp = kReplacement;
        }
        appendUtf8(out, cp);
    }

    env->ReleaseStringChars(value, chars);
    return out;
}

bool isContinuation(unsigned char byte) {
    return (byte & 0xc0) == 0x80;
}

std::vector<jchar> utf8ToUtf16(const std::string& value) {
    std::vector<jchar> out;
    out.reserve(value.size());

    std::size_t i = 0;
    while (i < value.size()) {
        const unsigned char lead = static_cast<unsigned char>(value[i]);
        std::uint32_t cp = kReplacement;
        std::size_t advance = 1;

        if (lead <= 0x7f) {
            cp = lead;
        } else if ((lead & 0xe0) == 0xc0 && i + 1 < value.size()) {
            const unsigned char b1 = static_cast<unsigned char>(value[i + 1]);
            if (isContinuation(b1)) {
                const std::uint32_t candidate = ((lead & 0x1f) << 6) | (b1 & 0x3f);
                if (candidate >= 0x80) {
                    cp = candidate;
                    advance = 2;
                }
            }
        } else if ((lead & 0xf0) == 0xe0 && i + 2 < value.size()) {
            const unsigned char b1 = static_cast<unsigned char>(value[i + 1]);
            const unsigned char b2 = static_cast<unsigned char>(value[i + 2]);
            if (isContinuation(b1) && isContinuation(b2)) {
                const std::uint32_t candidate =
                    ((lead & 0x0f) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f);
                if (candidate >= 0x800 && !(candidate >= 0xd800 && candidate <= 0xdfff)) {
                    cp = candidate;
                    advance = 3;
                }
            }
        } else if ((lead & 0xf8) == 0xf0 && i + 3 < value.size()) {
            const unsigned char b1 = static_cast<unsigned char>(value[i + 1]);
            const unsigned char b2 = static_cast<unsigned char>(value[i + 2]);
            const unsigned char b3 = static_cast<unsigned char>(value[i + 3]);
            if (isContinuation(b1) && isContinuation(b2) && isContinuation(b3)) {
                const std::uint32_t candidate =
                    ((lead & 0x07) << 18) |
                    ((b1 & 0x3f) << 12) |
                    ((b2 & 0x3f) << 6) |
                    (b3 & 0x3f);
                if (candidate >= 0x10000 && candidate <= 0x10ffff) {
                    cp = candidate;
                    advance = 4;
                }
            }
        }

        if (cp <= 0xffff) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xd800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xdc00 + (cp & 0x3ff)));
        }

        i += advance;
    }

    return out;
}

jstring utf8ToJstring(JNIEnv* env, const std::string& value) {
    const std::vector<jchar> utf16 = utf8ToUtf16(value);
    return env->NewString(
        utf16.empty() ? nullptr : utf16.data(),
        static_cast<jsize>(utf16.size())
    );
}

std::string jsonEscape(const std::string& value) {
    std::string out;
    out.reserve(value.size() + 16);
    static constexpr char hex[] = "0123456789abcdef";

    for (unsigned char ch : value) {
        switch (ch) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (ch < 0x20) {
                    out += "\\u00";
                    out.push_back(hex[(ch >> 4) & 0x0f]);
                    out.push_back(hex[ch & 0x0f]);
                } else {
                    out.push_back(static_cast<char>(ch));
                }
        }
    }
    return out;
}

std::string envelope(const riftcli::CommandResponse& response) {
    return "{\"output\":\"" + jsonEscape(response.output) + "\",\"result\":" +
        response.result_json + "}";
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_riftos_app_RiftCliHost_nativeExecute(
    JNIEnv* env,
    jobject,
    jobjectArray args,
    jstring cwd
) {
    try {
        std::vector<std::string> nativeArgs;
        std::size_t totalArgBytes = 0;

        if (args != nullptr) {
            const jsize count = env->GetArrayLength(args);
            if (count < 0 || count > kMaxNativeArgs) {
                throw std::invalid_argument("too many RiftCLI JNI arguments");
            }
            nativeArgs.reserve(static_cast<std::size_t>(count));
            for (jsize i = 0; i < count; ++i) {
                auto* value = static_cast<jstring>(env->GetObjectArrayElement(args, i));
                std::string converted = jstringToUtf8(env, value);
                if (value != nullptr) env->DeleteLocalRef(value);
                if (converted.size() > kMaxNativeArgBytes) {
                    throw std::invalid_argument("RiftCLI JNI argument exceeds 131072 bytes");
                }
                totalArgBytes += converted.size();
                if (totalArgBytes > kMaxNativeTotalArgBytes) {
                    throw std::invalid_argument("RiftCLI JNI arguments exceed 524288 bytes total");
                }
                nativeArgs.push_back(std::move(converted));
            }
        }

        const std::string nativeCwd = jstringToUtf8(env, cwd);
        if (nativeCwd.size() > kMaxNativeCwdBytes) {
            throw std::invalid_argument("RiftCLI JNI cwd exceeds 4096 bytes");
        }

        return utf8ToJstring(env, envelope(riftcli::execute(nativeArgs, nativeCwd)));
    } catch (const std::exception& error) {
        const riftcli::CommandResponse response{
            std::string("RiftCLI native failure: ") + error.what(),
            "{\"schema\":\"rift.cli-native-error/1\",\"ok\":false,\"error\":\"native exception\",\"authorityState\":\"unknown\"}"
        };
        return utf8ToJstring(env, envelope(response));
    } catch (...) {
        const riftcli::CommandResponse response{
            "RiftCLI native failure: unknown exception",
            "{\"schema\":\"rift.cli-native-error/1\",\"ok\":false,\"error\":\"unknown native exception\",\"authorityState\":\"unknown\"}"
        };
        return utf8ToJstring(env, envelope(response));
    }
}
