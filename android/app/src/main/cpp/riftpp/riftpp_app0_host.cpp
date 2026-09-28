#include <android/asset_manager.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <jni.h>

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

namespace {

constexpr const char* kTag = "RiftppApp0";
constexpr const char* kProgramAsset = "program.bin";
constexpr size_t kMaxProgramBytes = 64U * 1024U;
constexpr size_t kOutputBytes = 1024U;
constexpr uint32_t kStepBudget = 200000U;

bool readAssetBounded(
    ANativeActivity* activity,
    const char* name,
    uint8_t* output,
    size_t capacity,
    size_t* sizeOut
) {
    if (activity == nullptr || activity->assetManager == nullptr ||
        name == nullptr || output == nullptr || sizeOut == nullptr) {
        return false;
    }

    AAsset* asset = AAssetManager_open(activity->assetManager, name, AASSET_MODE_BUFFER);
    if (asset == nullptr) return false;

    const off_t length = AAsset_getLength(asset);
    if (length <= 0 || length > static_cast<off_t>(capacity)) {
        AAsset_close(asset);
        return false;
    }

    const size_t expected = static_cast<size_t>(length);
    size_t total = 0;
    while (total < expected) {
        const int got = AAsset_read(asset, output + total, expected - total);
        if (got <= 0) {
            AAsset_close(asset);
            return false;
        }
        total += static_cast<size_t>(got);
    }

    AAsset_close(asset);
    *sizeOut = total;
    return total == expected;
}

int32_t runVm1(
    const uint8_t* program,
    size_t programBytes,
    const uint8_t* source,
    uint32_t sourceLength,
    uint8_t* output,
    uint32_t outputCapacity,
    uint8_t* scratch,
    uint32_t scratchCapacity,
    uint32_t* result,
    uint32_t stepBudget
) {
    if (program == nullptr || programBytes == 0U || (programBytes & 3U) != 0U ||
        output == nullptr || result == nullptr) {
        return -4;
    }

    uint32_t regs[8] = {};
    const uint32_t instructionCount = static_cast<uint32_t>(programBytes / 4U);
    uint32_t pc = 0U;
    uint32_t steps = 0U;

    for (;;) {
        if (pc >= instructionCount) return -3;
        if (steps >= stepBudget) return -2;
        ++steps;

        const size_t base = static_cast<size_t>(pc) * 4U;
        const uint8_t op = program[base];
        const uint8_t a = program[base + 1U];
        const uint8_t b = program[base + 2U];
        const uint8_t c = program[base + 3U];
        ++pc;

        switch (op) {
            case 0x01:  // MOVI
                if (a >= 8U || c != 0U) return -1;
                regs[a] = b;
                break;
            case 0x02:  // ADD
                if (a >= 8U || b >= 8U || c >= 8U) return -1;
                regs[a] = regs[b] + regs[c];
                break;
            case 0x03:  // RET
                if (a >= 8U || b != 0U || c != 0U) return -1;
                *result = regs[a];
                return 0;
            case 0x04:  // SUB
                if (a >= 8U || b >= 8U || c >= 8U) return -1;
                regs[a] = regs[b] - regs[c];
                break;
            case 0x05:  // EQ
                if (a >= 8U || b >= 8U || c >= 8U) return -1;
                regs[a] = regs[b] == regs[c] ? 1U : 0U;
                break;
            case 0x06:  // LTU
                if (a >= 8U || b >= 8U || c >= 8U) return -1;
                regs[a] = regs[b] < regs[c] ? 1U : 0U;
                break;
            case 0x07: {  // BRNZ
                if (a >= 8U) return -1;
                if (regs[a] != 0U) {
                    const uint32_t target =
                        static_cast<uint32_t>(b) |
                        (static_cast<uint32_t>(c) << 8U);
                    if (target >= instructionCount) return -1;
                    pc = target;
                }
                break;
            }
            case 0x08:  // LEN
                if (a >= 8U || b >= 3U || c != 0U) return -1;
                regs[a] =
                    b == 0U ? sourceLength :
                    (b == 1U ? outputCapacity : scratchCapacity);
                break;
            case 0x09: {  // LD8
                if (a >= 8U || c >= 8U || (b != 0U && b != 2U)) return -1;
                const uint8_t* bytes = b == 0U ? source : scratch;
                const uint32_t length = b == 0U ? sourceLength : scratchCapacity;
                const uint32_t index = regs[c];
                if (index >= length || bytes == nullptr) return -1;
                regs[a] = bytes[index];
                break;
            }
            case 0x0a: {  // ST8
                if (a >= 8U || c >= 8U || (b != 1U && b != 2U)) return -1;
                uint8_t* bytes = b == 1U ? output : scratch;
                const uint32_t length = b == 1U ? outputCapacity : scratchCapacity;
                const uint32_t index = regs[c];
                if (index >= length || bytes == nullptr) return -1;
                bytes[index] = static_cast<uint8_t>(regs[a] & 0xffU);
                break;
            }
            default:
                return -1;
        }
    }
}

bool printableAscii(const uint8_t* bytes, size_t size) {
    if (bytes == nullptr) return false;
    for (size_t i = 0; i < size; ++i) {
        if (bytes[i] < 0x20U || bytes[i] > 0x7eU) return false;
    }
    return true;
}

void reportToActivity(ANativeActivity* activity, const char* message, bool success) {
    if (activity == nullptr || message == nullptr) return;

    __android_log_print(
        success ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        kTag,
        "%s",
        message
    );

    JNIEnv* env = nullptr;
    bool attached = false;
    if (activity->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (activity->vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
            return;
        }
        attached = true;
    }

    jstring text = env->NewStringUTF(message);
    if (text != nullptr) {
        jclass activityClass = env->GetObjectClass(activity->clazz);
        if (activityClass != nullptr) {
            jmethodID setTitle = env->GetMethodID(
                activityClass,
                "setTitle",
                "(Ljava/lang/CharSequence;)V"
            );
            if (setTitle != nullptr) {
                env->CallVoidMethod(activity->clazz, setTitle, text);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            env->DeleteLocalRef(activityClass);
        }

        jclass toastClass = env->FindClass("android/widget/Toast");
        if (toastClass != nullptr) {
            jmethodID makeText = env->GetStaticMethodID(
                toastClass,
                "makeText",
                "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;"
            );
            jmethodID show = env->GetMethodID(toastClass, "show", "()V");
            if (makeText != nullptr && show != nullptr) {
                jobject toast = env->CallStaticObjectMethod(
                    toastClass,
                    makeText,
                    activity->clazz,
                    text,
                    1
                );
                if (!env->ExceptionCheck() && toast != nullptr) {
                    env->CallVoidMethod(toast, show);
                    env->DeleteLocalRef(toast);
                }
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
            env->DeleteLocalRef(toastClass);
        }

        env->DeleteLocalRef(text);
    }

    if (attached) activity->vm->DetachCurrentThread();
}

bool runApp(ANativeActivity* activity, char* message, size_t messageBytes) {
    if (activity == nullptr || message == nullptr || messageBytes == 0U) return false;

    uint8_t program[kMaxProgramBytes] = {};
    uint8_t output[kOutputBytes + 1U] = {};

    size_t programBytes = 0U;
    if (!readAssetBounded(
            activity,
            kProgramAsset,
            program,
            sizeof(program),
            &programBytes
        ) ||
        (programBytes & 3U) != 0U) {
        snprintf(message, messageBytes, "Rift++ U0 FAIL: program asset");
        return false;
    }

    uint32_t result = 0xa5a5a5a5U;
    const int32_t status = runVm1(
        program,
        programBytes,
        nullptr,
        0U,
        output,
        static_cast<uint32_t>(kOutputBytes),
        nullptr,
        0U,
        &result,
        kStepBudget
    );

    if (status != 0) {
        snprintf(message, messageBytes, "Rift++ U0 FAIL: VM1 status %d", status);
        return false;
    }

    if (result > kOutputBytes) {
        snprintf(message, messageBytes, "Rift++ U0 FAIL: output bounds");
        return false;
    }

    if (!printableAscii(output, result)) {
        snprintf(message, messageBytes, "Rift++ U0 FAIL: text output");
        return false;
    }

    output[result] = 0;
    snprintf(
        message,
        messageBytes,
        "%.*s",
        static_cast<int>(result),
        reinterpret_cast<const char*>(output)
    );
    return true;
}

}  // namespace

extern "C" __attribute__((visibility("default")))
void ANativeActivity_onCreate(
    ANativeActivity* activity,
    void* savedState,
    size_t savedStateSize
) {
    (void)savedState;
    (void)savedStateSize;

    char message[kOutputBytes + 96U] = {};
    const bool success = runApp(activity, message, sizeof(message));
    reportToActivity(activity, message, success);
}
