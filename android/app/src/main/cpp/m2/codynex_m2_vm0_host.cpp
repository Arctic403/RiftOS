#include <android/asset_manager.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <jni.h>

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

constexpr const char* kTag = "CodynexM2VM0";
constexpr const char* kSeedAsset = "vm0_seed.bin";
constexpr size_t kSeedBytes = 332;
constexpr uint32_t kCanary = 0xA5A5A5A5u;

using VmFn = int32_t (*)(const uint8_t*, uint32_t, uint32_t*, uint32_t);

struct TestState {
    int passed = 0;
    int failed = 0;
    const char* firstFailure = nullptr;
};

struct ValidCase {
    const uint8_t* program;
    uint32_t bytes;
    uint32_t steps;
    uint32_t expected;
};

void record(TestState& state, bool ok, const char* label) {
    if (ok) {
        ++state.passed;
        return;
    }
    ++state.failed;
    if (state.firstFailure == nullptr) state.firstFailure = label;
}

void reportToActivity(ANativeActivity* activity, const char* message) {
    if (activity == nullptr || message == nullptr) return;

    __android_log_print(
        strncmp(message, "M2-A VM0 PASS", 12) == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        kTag,
        "%s",
        message
    );

    JNIEnv* env = nullptr;
    bool attached = false;
    if (activity->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (activity->vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
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

bool readSeed(ANativeActivity* activity, uint8_t* output) {
    if (activity == nullptr || activity->assetManager == nullptr || output == nullptr) return false;

    AAsset* asset = AAssetManager_open(
        activity->assetManager,
        kSeedAsset,
        AASSET_MODE_BUFFER
    );
    if (asset == nullptr) return false;

    const off_t length = AAsset_getLength(asset);
    if (length != static_cast<off_t>(kSeedBytes)) {
        AAsset_close(asset);
        return false;
    }

    size_t total = 0;
    while (total < kSeedBytes) {
        const int read = AAsset_read(asset, output + total, kSeedBytes - total);
        if (read <= 0) {
            AAsset_close(asset);
            return false;
        }
        total += static_cast<size_t>(read);
    }

    AAsset_close(asset);
    return total == kSeedBytes;
}

void* mapExecutable(const uint8_t* bytes, size_t size, size_t* mappedSize) {
    if (bytes == nullptr || size == 0 || mappedSize == nullptr) return nullptr;

    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return nullptr;

    const size_t pageSize = static_cast<size_t>(page);
    const size_t rounded = ((size + pageSize - 1U) / pageSize) * pageSize;

    void* memory = mmap(
        nullptr,
        rounded,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (memory == MAP_FAILED) return nullptr;

    memcpy(memory, bytes, size);

    if (mprotect(memory, rounded, PROT_READ | PROT_EXEC) != 0) {
        munmap(memory, rounded);
        return nullptr;
    }

    __builtin___clear_cache(
        reinterpret_cast<char*>(memory),
        reinterpret_cast<char*>(memory) + size
    );

    *mappedSize = rounded;
    return memory;
}

void runValid(
    VmFn vm,
    const ValidCase& test,
    TestState& state
) {
    uint32_t result = kCanary;
    const int32_t status = vm(test.program, test.bytes, &result, test.steps);
    record(state, status == 0, "valid-status");
    record(state, result == test.expected, "valid-result");
}

void runInvalid(
    VmFn vm,
    const uint8_t* program,
    uint32_t bytes,
    uint32_t steps,
    int32_t expectedStatus,
    TestState& state,
    const char* label,
    bool nullResult = false
) {
    uint32_t result = kCanary;
    uint32_t* resultPtr = nullResult ? nullptr : &result;

    const int32_t status = vm(program, bytes, resultPtr, steps);
    record(state, status == expectedStatus, label);
    record(state, result == kCanary, "failure-result-unchanged");
}

void runProof(ANativeActivity* activity, char* message, size_t messageBytes) {
    if (message == nullptr || messageBytes == 0) return;

#if !defined(__arm__)
    snprintf(message, messageBytes, "M2-A VM0 FAIL: proof must run in an ARM32 process");
#else
    uint8_t seed[kSeedBytes] = {};
    if (!readSeed(activity, seed)) {
        snprintf(message, messageBytes, "M2-A VM0 FAIL: canonical seed asset missing");
        return;
    }

    size_t mappedSize = 0;
    void* executable = mapExecutable(seed, sizeof(seed), &mappedSize);
    if (executable == nullptr) {
        snprintf(message, messageBytes, "M2-A VM0 FAIL: seed W->X mapping failed");
        return;
    }

    auto vm = reinterpret_cast<VmFn>(executable);
    TestState state;

    static const uint8_t kReturn7[] = {
        0x01, 0x00, 0x07, 0x00,
        0x03, 0x00, 0x00, 0x00,
    };
    static const uint8_t kAdd1And2[] = {
        0x01, 0x00, 0x01, 0x00,
        0x01, 0x01, 0x02, 0x00,
        0x02, 0x00, 0x00, 0x01,
        0x03, 0x00, 0x00, 0x00,
    };
    static const uint8_t kAdd255And255[] = {
        0x01, 0x00, 0xFF, 0x00,
        0x01, 0x01, 0xFF, 0x00,
        0x02, 0x00, 0x00, 0x01,
        0x03, 0x00, 0x00, 0x00,
    };
    static const uint8_t kChained[] = {
        0x01, 0x00, 0x2A, 0x00,
        0x01, 0x01, 0x63, 0x00,
        0x02, 0x02, 0x00, 0x01,
        0x01, 0x03, 0x07, 0x00,
        0x02, 0x02, 0x02, 0x03,
        0x03, 0x02, 0x00, 0x00,
    };

    const ValidCase validCases[] = {
        {kReturn7, sizeof(kReturn7), 2, 7},
        {kAdd1And2, sizeof(kAdd1And2), 4, 3},
        {kAdd255And255, sizeof(kAdd255And255), 4, 510},
        {kChained, sizeof(kChained), 6, 148},
    };
    for (const ValidCase& test : validCases) runValid(vm, test, state);

    static const uint8_t kOneByte[] = {0x01};
    static const uint8_t kUnknown[] = {0x7F, 0x00, 0x00, 0x00};
    static const uint8_t kBadMovReg[] = {0x01, 0x08, 0x01, 0x00};
    static const uint8_t kBadMovReserved[] = {0x01, 0x00, 0x01, 0x01};
    static const uint8_t kBadAddDst[] = {0x02, 0x08, 0x00, 0x00};
    static const uint8_t kBadAddLhs[] = {0x02, 0x00, 0x08, 0x00};
    static const uint8_t kBadAddRhs[] = {0x02, 0x00, 0x00, 0x08};
    static const uint8_t kBadRetReg[] = {0x03, 0x08, 0x00, 0x00};
    static const uint8_t kBadRetReserved[] = {0x03, 0x00, 0x01, 0x00};
    static const uint8_t kNoRet[] = {0x01, 0x00, 0x01, 0x00};

    runInvalid(vm, kReturn7, 0, 2, -4, state, "reject-empty");
    runInvalid(vm, kOneByte, sizeof(kOneByte), 2, -4, state, "reject-length-not-four");
    runInvalid(vm, nullptr, 4, 1, -1, state, "reject-null-program");
    runInvalid(vm, kReturn7, sizeof(kReturn7), 2, -1, state, "reject-null-result", true);
    runInvalid(vm, kUnknown, sizeof(kUnknown), 1, -1, state, "reject-unknown-opcode");
    runInvalid(vm, kBadMovReg, sizeof(kBadMovReg), 1, -1, state, "reject-movi-register");
    runInvalid(vm, kBadMovReserved, sizeof(kBadMovReserved), 1, -1, state, "reject-movi-reserved");
    runInvalid(vm, kBadAddDst, sizeof(kBadAddDst), 1, -1, state, "reject-add-dst");
    runInvalid(vm, kBadAddLhs, sizeof(kBadAddLhs), 1, -1, state, "reject-add-lhs");
    runInvalid(vm, kBadAddRhs, sizeof(kBadAddRhs), 1, -1, state, "reject-add-rhs");
    runInvalid(vm, kBadRetReg, sizeof(kBadRetReg), 1, -1, state, "reject-ret-register");
    runInvalid(vm, kBadRetReserved, sizeof(kBadRetReserved), 1, -1, state, "reject-ret-reserved");
    runInvalid(vm, kNoRet, sizeof(kNoRet), 1, -3, state, "reject-no-ret");
    runInvalid(vm, kReturn7, sizeof(kReturn7), 1, -2, state, "reject-step-limit");

    munmap(executable, mappedSize);

    if (state.failed == 0) {
        snprintf(
            message,
            messageBytes,
            "M2-A VM0 PASS: %d checks; external ARM32 VM executed",
            state.passed
        );
    } else {
        snprintf(
            message,
            messageBytes,
            "M2-A VM0 FAIL: %d failed / %d passed; first=%s",
            state.failed,
            state.passed,
            state.firstFailure != nullptr ? state.firstFailure : "unknown"
        );
    }
#endif
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

    char message[256] = {};
    runProof(activity, message, sizeof(message));
    reportToActivity(activity, message);
}
