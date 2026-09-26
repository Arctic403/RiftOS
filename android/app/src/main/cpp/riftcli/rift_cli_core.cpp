#include "rift_cli_core.h"

#include <atomic>
#include <cctype>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

namespace riftcli {
namespace {

std::atomic<bool> g_enabled{false};

constexpr unsigned int kDriverProtocolVersion = 1;
constexpr unsigned int kMaxDriverLoopSteps = 8;
constexpr std::size_t kMaxIdentityBytes = 256;
constexpr std::size_t kMaxGoalBytes = 16 * 1024;
constexpr std::size_t kMaxEvidenceBytes = 8 * 1024;
constexpr std::size_t kMaxActionBytes = 128 * 1024;
constexpr std::size_t kMaxEvidenceItems = 32;
constexpr std::size_t kMaxActiveDriverLoops = 64;
constexpr std::size_t kMaxDriverRequestIds = 4096;

struct DriverLoopState {
    std::string sessionId;
    std::string taskId;
    std::string projectId;
    unsigned int loopMax{1};
    unsigned int expectedStep{1};
};

std::mutex g_loopMutex;
std::unordered_map<std::string, DriverLoopState> g_driverLoops;
std::mutex g_requestMutex;
std::unordered_set<std::string> g_recentRequestIds;

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

bool enabled() {
    return g_enabled.load();
}

std::string authorityJson() {
    const bool on = enabled();
    std::ostringstream out;
    out
        << "{"
        << "\"mode\":\"full-riftos-when-enabled\","
        << "\"enabled\":" << (on ? "true" : "false") << ","
        << "\"mutation\":" << (on ? "true" : "false") << ","
        << "\"toolExecution\":" << (on ? "true" : "false") << ","
        << "\"networkViaRiftOs\":" << (on ? "true" : "false") << ","
        << "\"directModelBackend\":false,"
        << "\"directNetworkClient\":false,"
        << "\"delegation\":\"bounded-riftos-authorities\""
        << "}";
    return out.str();
}

std::string baseStatus(const std::string& command, const std::string& cwd) {
    const bool on = enabled();
    std::ostringstream out;
    out
        << "{"
        << "\"schema\":\"rift.cli-native-bootstrap/1\","
        << "\"coreVersion\":\"0.2.0-pre-n2\","
        << "\"phase\":\"n1-driver-protocol\","
        << "\"command\":" << quote(command) << ","
        << "\"cwd\":" << quote(cwd) << ","
        << "\"coreLanguage\":\"c++\","
        << "\"androidHost\":\"kotlin-jni-thin-host\","
        << "\"abi\":" << quote(abiName()) << ","
        << "\"enabled\":" << (on ? "true" : "false") << ","
        << "\"defaultEnabled\":false,"
        << "\"persistentEnable\":false,"
        << "\"driverProtocolVersion\":" << kDriverProtocolVersion << ","
        << "\"driverLoopMax\":" << kMaxDriverLoopSteps << ","
        << "\"driverReplayCapacity\":" << kMaxDriverRequestIds << ","
        << "\"driverReplayEviction\":false,"
        << "\"driverReplayReset\":\"process-restart-only\","
        << "\"driverToolExecution\":\"push-first-jobs-with-poll-fallback\","
        << "\"driverObservationMode\":\"persistent-push-steady-state\","
        << "\"automaticPolling\":false,"
        << "\"pollFallbackOnly\":true,"
        << "\"driverEventDelivery\":\"persistent-relay-push\","
        << "\"driverEventReplay\":\"device-ring-256\","
        << "\"batchV2\":true,"
        << "\"batchV2MaxSteps\":16,"
        << "\"driverDirection\":\"MCP/RiftShell -> RiftOS Local Agent -> RiftCLI\","
        << "\"hostOwner\":\"riftos-local-agent\","
        << "\"hostedByLocalAgent\":true,"
        << "\"directExternalHost\":false,"
        << "\"cliCallsDriver\":false,"
        << "\"driverContinuationExternalOnly\":true,"
        << "\"modelBackend\":false,"
        << "\"authority\":" << authorityJson() << ","
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
        << "\"schema\":\"rift.cli-native-error/1\","
        << "\"ok\":false,"
        << "\"error\":" << quote(message) << ","
        << "\"cwd\":" << quote(cwd) << ","
        << "\"authority\":" << authorityJson()
        << "}";
    return {message, result.str()};
}

void requireBounded(const std::string& name, const std::string& value, std::size_t maxBytes) {
    if (value.empty()) throw std::invalid_argument(name + " must not be empty");
    if (value.size() > maxBytes) {
        throw std::invalid_argument(name + " exceeds " + std::to_string(maxBytes) + " bytes");
    }
}

unsigned int parseUnsigned(const std::string& name, const std::string& value, unsigned int maxValue) {
    if (value.empty()) throw std::invalid_argument(name + " must not be empty");
    unsigned int result = 0;
    for (unsigned char ch : value) {
        if (!std::isdigit(ch)) throw std::invalid_argument(name + " must be an unsigned integer");
        const unsigned int digit = static_cast<unsigned int>(ch - '0');
        if (result > (maxValue - digit) / 10U) {
            throw std::invalid_argument(name + " exceeds allowed range");
        }
        result = result * 10U + digit;
    }
    if (result > maxValue) throw std::invalid_argument(name + " exceeds allowed range");
    return result;
}

bool actionTargetsRiftCli(const std::string& action) {
    std::size_t start = 0;
    while (start < action.size() && std::isspace(static_cast<unsigned char>(action[start]))) ++start;
    std::size_t end = start;
    while (end < action.size() && !std::isspace(static_cast<unsigned char>(action[end]))) ++end;
    return lowerAscii(action.substr(start, end - start)) == "rift-cli";
}

bool isAllowedDriverTool(const std::string& name) {
    if (name.size() < 6 || name.rfind("rift_", 0) != 0) return false;
    return name != "rift_shell_exec";
}

bool isDriverJobControl(const std::string& name) {
    return name == "rift_cli_job_list" ||
        name == "rift_cli_job_poll" ||
        name == "rift_cli_job_cancel";
}


enum class RequestReservation {
    Accepted,
    Duplicate,
    Capacity
};

RequestReservation reserveDriverRequestId(const std::string& requestId) {
    std::lock_guard<std::mutex> lock(g_requestMutex);
    if (g_recentRequestIds.find(requestId) != g_recentRequestIds.end()) {
        return RequestReservation::Duplicate;
    }
    if (g_recentRequestIds.size() >= kMaxDriverRequestIds) {
        return RequestReservation::Capacity;
    }
    g_recentRequestIds.insert(requestId);
    return RequestReservation::Accepted;
}

void resetDriverLoops() {
    std::lock_guard<std::mutex> lock(g_loopMutex);
    g_driverLoops.clear();
}

std::string jsonStringArray(const std::vector<std::string>& values) {
    std::ostringstream out;
    out << "[";
    for (std::size_t i = 0; i < values.size(); ++i) {
        if (i != 0) out << ",";
        out << quote(values[i]);
    }
    out << "]";
    return out.str();
}

struct DriverRequest {
    std::string requestId;
    std::string sessionId;
    std::string taskId;
    std::string projectId;
    std::string goal;
    std::vector<std::string> assumptions;
    std::vector<std::string> evidence;
    std::string capability{"riftos"};
    std::string action;
    std::string toolName;
    std::string toolArgsJson{"{}"};
    std::string toolPayloadId;
    std::string loopId;
    unsigned int loopStep{0};
    unsigned int loopMax{1};
    std::string requestMoreInfo;
};

void validateAndAdvanceDriverLoop(const DriverRequest& request, bool wantsInfo) {
    if (request.loopMax == 1U) {
        if (request.loopStep != 0U) {
            throw std::invalid_argument("single-step driver request must use loop-step 0");
        }
        if (wantsInfo) {
            throw std::invalid_argument("request-more-info requires loop-max greater than 1");
        }
        return;
    }

    std::lock_guard<std::mutex> lock(g_loopMutex);

    if (request.loopStep == 0U) {
        if (!wantsInfo) return;
        if (g_driverLoops.find(request.loopId) != g_driverLoops.end()) {
            throw std::invalid_argument("loop-id is already active");
        }
        if (g_driverLoops.size() >= kMaxActiveDriverLoops) {
            throw std::invalid_argument("too many active driver loops; disable/restart RiftCLI or finish existing loops");
        }
        if (request.loopStep + 1U >= request.loopMax) {
            throw std::invalid_argument("loop budget exhausted; cannot request more information");
        }
        g_driverLoops.emplace(
            request.loopId,
            DriverLoopState{
                request.sessionId,
                request.taskId,
                request.projectId,
                request.loopMax,
                1U
            }
        );
        return;
    }

    const auto it = g_driverLoops.find(request.loopId);
    if (it == g_driverLoops.end()) {
        throw std::invalid_argument("driver continuation has no active loop state");
    }

    const DriverLoopState& state = it->second;
    if (state.sessionId != request.sessionId ||
        state.taskId != request.taskId ||
        state.projectId != request.projectId) {
        throw std::invalid_argument("driver continuation identity does not match active loop");
    }
    if (state.loopMax != request.loopMax) {
        throw std::invalid_argument("driver continuation loop-max changed mid-loop");
    }
    if (state.expectedStep != request.loopStep) {
        throw std::invalid_argument("driver continuation must advance exactly one loop step");
    }

    if (wantsInfo) {
        if (request.loopStep + 1U >= request.loopMax) {
            throw std::invalid_argument("loop budget exhausted; final step must dispatch an action or end");
        }
        it->second.expectedStep = request.loopStep + 1U;
    } else {
        g_driverLoops.erase(it);
    }
}

DriverRequest parseDriverRequest(const std::vector<std::string>& args) {
    if (args.size() < 2 || lowerAscii(args[1]) != "request") {
        throw std::invalid_argument(
            "usage: rift-cli driver request --request-id <id> --session <id> --task <id> --project <id> "
            "--goal <text> [--assumption <text>] [--evidence <ref>] --capability riftos "
            "[--action <single-riftshell-command> | --tool <rift-tool> [--tool-args <json> | --tool-payload-id <id>] | "
            "--request-more-info <question>] [--loop-id <id> --loop-step <0..7> --loop-max <1..8>]"
        );
    }

    DriverRequest request;
    for (std::size_t i = 2; i < args.size(); ++i) {
        const std::string flag = lowerAscii(args[i]);
        if (i + 1 >= args.size()) throw std::invalid_argument("driver flag requires a value: " + args[i]);
        const std::string value = args[++i];

        if (flag == "--request-id") request.requestId = value;
        else if (flag == "--session") request.sessionId = value;
        else if (flag == "--task") request.taskId = value;
        else if (flag == "--project") request.projectId = value;
        else if (flag == "--goal") request.goal = value;
        else if (flag == "--assumption") {
            if (request.assumptions.size() >= kMaxEvidenceItems) {
                throw std::invalid_argument("too many driver assumptions");
            }
            requireBounded("assumption", value, kMaxEvidenceBytes);
            request.assumptions.push_back(value);
        } else if (flag == "--evidence") {
            if (request.evidence.size() >= kMaxEvidenceItems) {
                throw std::invalid_argument("too many driver evidence references");
            }
            requireBounded("evidence", value, kMaxEvidenceBytes);
            request.evidence.push_back(value);
        } else if (flag == "--capability") request.capability = lowerAscii(value);
        else if (flag == "--action") request.action = value;
        else if (flag == "--tool") request.toolName = lowerAscii(value);
        else if (flag == "--tool-args") request.toolArgsJson = value;
        else if (flag == "--tool-payload-id") request.toolPayloadId = value;
        else if (flag == "--loop-id") request.loopId = value;
        else if (flag == "--loop-step") request.loopStep = parseUnsigned("loop-step", value, kMaxDriverLoopSteps - 1);
        else if (flag == "--loop-max") request.loopMax = parseUnsigned("loop-max", value, kMaxDriverLoopSteps);
        else if (flag == "--request-more-info") request.requestMoreInfo = value;
        else throw std::invalid_argument("unknown driver flag: " + args[i - 1]);
    }

    requireBounded("request-id", request.requestId, kMaxIdentityBytes);
    requireBounded("session", request.sessionId, kMaxIdentityBytes);
    requireBounded("task", request.taskId, kMaxIdentityBytes);
    requireBounded("project", request.projectId, kMaxIdentityBytes);
    requireBounded("goal", request.goal, kMaxGoalBytes);
    requireBounded("capability", request.capability, 64);

    if (!request.loopId.empty()) requireBounded("loop-id", request.loopId, kMaxIdentityBytes);
    if (!request.action.empty() && request.action.size() > kMaxActionBytes) {
        throw std::invalid_argument("action exceeds 131072 bytes");
    }
    if (!request.toolName.empty()) requireBounded("tool", request.toolName, 128);
    if (request.toolArgsJson.size() > kMaxActionBytes) {
        throw std::invalid_argument("tool-args exceeds 131072 bytes");
    }
    if (!request.toolPayloadId.empty()) requireBounded("tool-payload-id", request.toolPayloadId, kMaxIdentityBytes);
    if (!request.toolPayloadId.empty() && request.toolName.empty()) {
        throw std::invalid_argument("tool-payload-id requires --tool");
    }
    if (!request.toolPayloadId.empty() && request.toolArgsJson != "{}") {
        throw std::invalid_argument("tool-payload-id and tool-args are mutually exclusive");
    }
    if (!request.requestMoreInfo.empty() && request.requestMoreInfo.size() > kMaxGoalBytes) {
        throw std::invalid_argument("request-more-info exceeds 16384 bytes");
    }
    if (request.loopMax == 0 || request.loopMax > kMaxDriverLoopSteps) {
        throw std::invalid_argument("loop-max must be between 1 and 8");
    }
    if (request.loopStep >= request.loopMax) {
        throw std::invalid_argument("loop-step must be less than loop-max");
    }
    if (request.loopMax > 1 && request.loopId.empty()) {
        throw std::invalid_argument("loop-id is required when loop-max is greater than 1");
    }
    if (request.capability != "riftos") {
        throw std::invalid_argument("N1 supports capability riftos only");
    }

    const unsigned int modeCount =
        (!request.action.empty() ? 1U : 0U) +
        (!request.toolName.empty() ? 1U : 0U) +
        (!request.requestMoreInfo.empty() ? 1U : 0U);
    if (modeCount != 1U) {
        throw std::invalid_argument("driver request must choose exactly one of action, tool, or request-more-info");
    }

    if (!request.action.empty() && actionTargetsRiftCli(request.action)) {
        throw std::invalid_argument(
            "internal RiftCLI recursion is forbidden; continue a driver loop with a new external rift-cli driver request"
        );
    }
    if (!request.toolName.empty() && !isAllowedDriverTool(request.toolName)) {
        throw std::invalid_argument(
            "unsupported RiftCLI tool lane target; rift_shell_exec is intentionally routed through the CLI action lane"
        );
    }
    if (request.toolName.empty() && (request.toolArgsJson != "{}" || !request.toolPayloadId.empty())) {
        throw std::invalid_argument("tool arguments require --tool");
    }

    return request;
}

CommandResponse driverRequest(const std::vector<std::string>& args, const std::string& cwd) {
    DriverRequest request;
    try {
        request = parseDriverRequest(args);
    } catch (const std::exception& error) {
        return failure(std::string("Driver request rejected: ") + error.what(), cwd);
    }

    const bool on = enabled();
    const bool wantsInfo = !request.requestMoreInfo.empty();
    const bool wantsTool = !request.toolName.empty();
    const bool jobControl = wantsTool && isDriverJobControl(request.toolName);
    const bool canContinue = request.loopStep + 1U < request.loopMax;

    if (jobControl && (request.loopMax != 1U || request.loopStep != 0U || !request.loopId.empty())) {
        return failure("RiftCLI job-control requests must be single-step and must not join a driver loop.", cwd);
    }

    if (!on && !jobControl) {
        std::ostringstream result;
        result
            << "{"
            << "\"schema\":\"rift.cli-driver/1\","
            << "\"ok\":false,"
            << "\"accepted\":false,"
            << "\"state\":\"rejected\","
            << "\"reason\":\"cli-disabled\","
            << "\"requestId\":" << quote(request.requestId) << ","
            << "\"sessionId\":" << quote(request.sessionId) << ","
            << "\"taskId\":" << quote(request.taskId) << ","
            << "\"projectId\":" << quote(request.projectId) << ","
            << "\"goal\":" << quote(request.goal) << ","
            << "\"authority\":" << authorityJson() << ","
            << "\"nextSafeActionHints\":[\"enable RiftCLI explicitly for this process and resend the same driver request\"]"
            << "}";
        return {"RiftCLI driver request rejected: CLI is disabled.", result.str()};
    }

    const RequestReservation reservation =
        jobControl ? RequestReservation::Accepted : reserveDriverRequestId(request.requestId);
    if (reservation != RequestReservation::Accepted) {
        const bool duplicate = reservation == RequestReservation::Duplicate;
        std::ostringstream result;
        result
            << "{"
            << "\"schema\":\"rift.cli-driver/1\","
            << "\"ok\":false,"
            << "\"accepted\":false,"
            << "\"state\":\"rejected\","
            << "\"reason\":" << quote(duplicate ? "duplicate-request-id" : "request-id-capacity") << ","
            << "\"requestId\":" << quote(request.requestId) << ","
            << "\"sessionId\":" << quote(request.sessionId) << ","
            << "\"taskId\":" << quote(request.taskId) << ","
            << "\"projectId\":" << quote(request.projectId) << ","
            << "\"authority\":" << authorityJson() << ","
            << "\"nextSafeActionHints\":["
            << quote(duplicate
                ? "do not replay the authority request; recover any started job with rift_cli_job_list filtered by the original requestId"
                : "request-id protection is full for this process; restart RiftOS before accepting more authority requests")
            << "]"
            << "}";
        return {
            duplicate
                ? "RiftCLI driver request rejected: duplicate request-id."
                : "RiftCLI driver request rejected: request-id protection capacity reached.",
            result.str()
        };
    }

    try {
        validateAndAdvanceDriverLoop(request, wantsInfo);
    } catch (const std::exception& error) {
        return failure(std::string("Driver loop rejected: ") + error.what(), cwd);
    }

    std::ostringstream result;
    result
        << "{"
        << "\"schema\":\"rift.cli-driver/1\","
        << "\"ok\":true,"
        << "\"accepted\":true,"
        << "\"protocolVersion\":" << kDriverProtocolVersion << ","
        << "\"state\":" << quote(wantsInfo ? "need_more_info" : "dispatch") << ","
        << "\"requestId\":" << quote(request.requestId) << ","
        << "\"sessionId\":" << quote(request.sessionId) << ","
        << "\"taskId\":" << quote(request.taskId) << ","
        << "\"projectId\":" << quote(request.projectId) << ","
        << "\"goal\":" << quote(request.goal) << ","
        << "\"assumptions\":" << jsonStringArray(request.assumptions) << ","
        << "\"evidence\":" << jsonStringArray(request.evidence) << ","
        << "\"requestedCapability\":" << quote(request.capability) << ","
        << "\"authority\":" << authorityJson() << ","
        << "\"loop\":{"
        << "\"id\":" << quote(request.loopId) << ","
        << "\"step\":" << request.loopStep << ","
        << "\"max\":" << request.loopMax << ","
        << "\"externalContinuationOnly\":true,"
        << "\"canContinue\":" << (canContinue ? "true" : "false");

    if (canContinue) result << ",\"nextStep\":" << (request.loopStep + 1U);
    result << "}";

    if (wantsInfo) {
        result
            << ",\"requestMoreInfo\":" << quote(request.requestMoreInfo) << ","
            << "\"continuationRequired\":true,"
            << "\"acceptanceReasons\":[\"RiftOS Local Agent host explicitly requested another bounded information round trip\"],"
            << "\"nextSafeActionHints\":[\"RiftOS Local Agent host must send exactly the next loop step with matching identity and loop-max\"]";
    } else if (wantsTool) {
        result
            << ",\"dispatch\":{"
            << "\"kind\":\"rift-tool\","
            << "\"name\":" << quote(request.toolName) << ","
            << "\"argsJson\":" << quote(request.toolArgsJson) << ","
            << "\"payloadId\":" << quote(request.toolPayloadId)
            << "},"
            << "\"acceptanceReasons\":["
            << quote(jobControl
                ? "request is an idempotent RiftCLI job-control operation; observation/cancellation remains available while disabled"
                : "RiftCLI is explicitly enabled and request is one bounded RiftOS tool action")
            << ",\"tool lane excludes shell recursion and workspace-exec\"],"
            << "\"nextSafeActionHints\":[\"inspect dispatchResult before issuing a dependent action\"]";
    } else {
        result
            << ",\"dispatch\":{"
            << "\"kind\":\"rift-shell\","
            << "\"command\":" << quote(request.action) << ","
            << "\"cwd\":" << quote(cwd)
            << "},"
            << "\"acceptanceReasons\":[\"RiftCLI is explicitly enabled\",\"request is one bounded RiftOS shell action\",\"action is not recursive RiftCLI execution\"],"
            << "\"nextSafeActionHints\":[\"inspect dispatchResult before issuing a dependent action\"]";
    }

    result << "}";
    return {
        wantsInfo
            ? "RiftCLI requests one bounded Local Agent continuation."
            : (wantsTool
                ? (jobControl ? "RiftCLI accepted one idempotent job-control request."
                              : "RiftCLI authorized one RiftOS tool action for execution.")
                : "RiftCLI authorized one RiftOS shell action for execution."),
        result.str()
    };
}

}  // namespace

CommandResponse execute(const std::vector<std::string>& args, const std::string& cwd) {
    const std::string command = args.empty() ? "help" : lowerAscii(args.front());

    if (command == "help") {
        const std::string output =
            "RiftCLI N1 native driver protocol\n"
            "Core: C++ / thin Kotlin JNI host\n"
            "Dependency: MCP/RiftShell -> RiftOS Local Agent -> RiftCLI -> bounded RiftOS authorities\n\n"
            "rift-cli help\n"
            "rift-cli status\n"
            "rift-cli architecture\n"
            "rift-cli enable CONFIRM-EXPERIMENTAL\n"
            "rift-cli disable\n"
            "rift-cli driver request --request-id <id> --session <id> --task <id> --project <id> --goal <text> "
            "--capability riftos [--action <single-command> | --tool <rift-tool> [--tool-args <json> | --tool-payload-id <id>] | --request-more-info <question>] "
            "[--loop-id <id> --loop-step <0..7> --loop-max <1..8>]\n\n"
            "When enabled, RiftCLI may authorize the full existing RiftOS authority surface through one bounded action at a time. "
            "CLI actions publish persistent relay events; automatic polling is disabled. rift_cli_job_list and rift_cli_job_poll are explicit recovery/debug fallbacks, while rift_cli_job_cancel remains an explicit control. "
            "CLI Batch V2 is available as --tool rift_cli_batch with at most 16 prevalidated sequential steps; retired RiftShell/workspace-exec batch paths remain disabled. "
            "Every authority-bearing request-id is replay-protected in-process. Driver continuation loops are external-only and capped at 8 steps.";
        return {output, baseStatus("help", cwd)};
    }

    if (command == "status") {
        return {baseStatus("status", cwd), baseStatus("status", cwd)};
    }

    if (command == "architecture") {
        std::ostringstream result;
        result
            << "{"
            << "\"schema\":\"rift.cli-native-architecture/1\","
            << "\"phase\":\"n1-driver-protocol\","
            << "\"coreLanguage\":\"c++\","
            << "\"hostLanguage\":\"kotlin\","
            << "\"boundary\":\"jni\","
            << "\"driverDirection\":\"MCP/RiftShell -> RiftOS Local Agent -> RiftCLI -> bounded RiftOS authorities\","
            << "\"hostOwner\":\"riftos-local-agent\","
            << "\"hostedByLocalAgent\":true,"
            << "\"directExternalHost\":false,"
            << "\"cliCallsDriver\":false,"
            << "\"driverContinuationExternalOnly\":true,"
            << "\"driverLoopMax\":" << kMaxDriverLoopSteps << ","
            << "\"driverReplayCapacity\":" << kMaxDriverRequestIds << ","
        << "\"driverReplayEviction\":false,"
            << "\"driverToolExecution\":\"push-first-jobs-with-poll-fallback\","
        << "\"driverObservationMode\":\"persistent-push-steady-state\","
        << "\"automaticPolling\":false,"
        << "\"pollFallbackOnly\":true,"
        << "\"driverEventDelivery\":\"persistent-relay-push\","
        << "\"driverEventReplay\":\"device-ring-256\","
        << "\"batchV2\":true,"
        << "\"batchV2MaxSteps\":16,"
            << "\"modelBackend\":false,"
            << "\"supportedTargetAbis\":[\"arm64-v8a\",\"armeabi-v7a\"],"
            << "\"globalPlanningLocalActing\":true,"
            << "\"fullRiftOsAuthorityWhenEnabled\":true,"
            << "\"authority\":" << authorityJson() << ","
            << "\"n1Rule\":\"RiftOS Local Agent owns the CLI host boundary; native CLI remains gated and owns bounded authorization/execution\""
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
        if (!enabled()) {
            resetDriverLoops();
            g_enabled.store(true);
            return {
                "RiftCLI enabled for this process. N1 may authorize full RiftOS authority one bounded action at a time.",
                baseStatus("enable", cwd)
            };
        }
        return {
            "RiftCLI is already enabled for this process; active loop/replay state was preserved.",
            baseStatus("enable", cwd)
        };
    }

    if (command == "disable") {
        g_enabled.store(false);
        resetDriverLoops();
        return {
            "RiftCLI disabled. Restarting RiftOS also resets it to OFF.",
            baseStatus("disable", cwd)
        };
    }

    if (command == "driver") {
        return driverRequest(args, cwd);
    }

    return failure(
        "Unsupported RiftCLI N1 command: " + command +
            ". Use help/status/architecture/enable/disable/driver.",
        cwd
    );
}

}  // namespace riftcli
