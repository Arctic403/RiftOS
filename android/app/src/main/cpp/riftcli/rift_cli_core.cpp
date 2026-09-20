#include "rift_cli_core.h"

#include <atomic>
#include <cctype>
#include <sstream>
#include <stdexcept>

namespace riftcli {
namespace {

std::atomic<bool> g_enabled{false};

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (unsigned char ch : value) {
        switch (ch) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (ch < 0x20) {
                    static constexpr char hex[] = "0123456789abcdef";
                    out << "\\u00" << hex[(ch >> 4) & 0x0f] << hex[ch & 0x0f];
                } else {
                    out << static_cast<char>(ch);
                }
        }
    }
    return out.str();
}

std::string quote(const std::string& value) {
    return "\"" + jsonEscape(value) + "\"";
}

std::string lowerAscii(std::string value) {
    for (char& ch : value) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return value;
}

const char* abiName() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

std::string baseStatus(const std::string& command, const std::string& cwd) {
    std::ostringstream out;
    out
        << "{"
        << "\"schema\":\"rift.cli-native-bootstrap/0\","
        << "\"coreVersion\":\"0.0.1-bootstrap\","
        << "\"phase\":\"bootstrap-0\","
        << "\"command\":" << quote(command) << ","
        << "\"cwd\":" << quote(cwd) << ","
        << "\"coreLanguage\":\"c++\","
        << "\"androidHost\":\"kotlin-jni-thin-host\","
        << "\"abi\":" << quote(abiName()) << ","
        << "\"enabled\":" << (g_enabled.load() ? "true" : "false") << ","
        << "\"defaultEnabled\":false,"
        << "\"persistentEnable\":false,"
        << "\"driverDirection\":\"external-driver -> MCP/RiftShell -> RiftCLI\","
        << "\"cliCallsDriver\":false,"
        << "\"modelBackend\":false,"
        << "\"networkAuthority\":false,"
        << "\"mutationAuthority\":false,"
        << "\"toolExecution\":false,"
        << "\"projectMemory\":false,"
        << "\"projectGraph\":false,"
        << "\"planner\":false,"
        << "\"verificationEngine\":false"
        << "}";
    return out.str();
}

CommandResponse failure(const std::string& message, const std::string& cwd) {
    std::ostringstream result;
    result
        << "{"
        << "\"schema\":\"rift.cli-native-error/0\","
        << "\"ok\":false,"
        << "\"error\":" << quote(message) << ","
        << "\"cwd\":" << quote(cwd) << ","
        << "\"mutationAuthority\":false"
        << "}";
    return {message, result.str()};
}

}  // namespace

CommandResponse execute(const std::vector<std::string>& args, const std::string& cwd) {
    const std::string command = args.empty() ? "help" : lowerAscii(args.front());

    if (command == "help") {
        const std::string output =
            "RiftCLI native bootstrap-0\n"
            "Core: C++ / thin Kotlin JNI host\n"
            "Dependency: external driver -> MCP/RiftShell -> RiftCLI\n\n"
            "rift-cli help\n"
            "rift-cli status\n"
            "rift-cli architecture\n"
            "rift-cli enable CONFIRM-EXPERIMENTAL\n"
            "rift-cli disable\n\n"
            "Bootstrap-0 has zero mutation, tool, network, model, project-memory, or planner authority.";
        return {output, baseStatus("help", cwd)};
    }

    if (command == "status") {
        return {baseStatus("status", cwd), baseStatus("status", cwd)};
    }

    if (command == "architecture") {
        std::ostringstream result;
        result
            << "{"
            << "\"schema\":\"rift.cli-native-architecture/0\","
            << "\"phase\":\"bootstrap-0\","
            << "\"coreLanguage\":\"c++\","
            << "\"hostLanguage\":\"kotlin\","
            << "\"boundary\":\"jni\","
            << "\"driverDirection\":\"external-driver -> MCP/RiftShell -> RiftCLI\","
            << "\"cliCallsDriver\":false,"
            << "\"modelBackend\":false,"
            << "\"supportedTargetAbis\":[\"arm64-v8a\",\"armeabi-v7a\"],"
            << "\"globalPlanningLocalActing\":true,"
            << "\"mutationAuthority\":false,"
            << "\"bootstrapRule\":\"prove native core and ABI parity before adding engineering intelligence\""
            << "}";
        return {result.str(), result.str()};
    }

    if (command == "enable") {
        if (args.size() != 2 || args[1] != "CONFIRM-EXPERIMENTAL") {
            return failure(
                "RiftCLI stays OFF. To enable this process only: rift-cli enable CONFIRM-EXPERIMENTAL",
                cwd
            );
        }
        g_enabled.store(true);
        return {
            "RiftCLI native bootstrap enabled for this process only. Bootstrap-0 still has zero mutation authority.",
            baseStatus("enable", cwd)
        };
    }

    if (command == "disable") {
        g_enabled.store(false);
        return {
            "RiftCLI native bootstrap disabled. Restarting RiftOS also resets it to OFF.",
            baseStatus("disable", cwd)
        };
    }

    return failure(
        "Unsupported RiftCLI bootstrap-0 command: " + command +
            ". Only help/status/architecture/enable/disable exist until native foundation gates pass.",
        cwd
    );
}

}  // namespace riftcli
