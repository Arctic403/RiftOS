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

constexpr const char* kTag = "CodynexMC0";
constexpr const char* kSeedAsset = "mc0_seed.bin";
constexpr size_t kSeedBytes = 172;
constexpr size_t kGeneratedBytes = 8;
constexpr uint8_t kCanary = 0xA5;

using CompilerFn = int32_t (*)(const uint8_t*, uint32_t, uint8_t*, uint32_t);
using GeneratedFn = int32_t (*)();

struct TestState {
    int passed = 0;
    int failed = 0;
    const char* firstFailure = nullptr;
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
        strncmp(message, "MC0 PASS", 8) == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
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

bool runValidCase(CompilerFn compiler, int digit, TestState& state) {
    char source[6] = {'r', 'e', 't', ' ', static_cast<char>('0' + digit), '\0'};

    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        record(state, false, "page-size");
        return false;
    }

    const size_t pageSize = static_cast<size_t>(page);
    void* generated = mmap(
        nullptr,
        pageSize,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (generated == MAP_FAILED) {
        record(state, false, "generated-mmap");
        return false;
    }

    memset(generated, kCanary, pageSize);

    const int32_t compiled = compiler(
        reinterpret_cast<const uint8_t*>(source),
        5,
        reinterpret_cast<uint8_t*>(generated),
        static_cast<uint32_t>(pageSize)
    );
    record(state, compiled == static_cast<int32_t>(kGeneratedBytes), "compile-valid");

    if (compiled != static_cast<int32_t>(kGeneratedBytes)) {
        munmap(generated, pageSize);
        return false;
    }

    const auto* emitted = reinterpret_cast<const uint8_t*>(generated);
    const uint8_t expected[kGeneratedBytes] = {
        static_cast<uint8_t>(digit), 0x00, 0xA0, 0xE3,
        0x1E, 0xFF, 0x2F, 0xE1
    };
    record(
        state,
        memcmp(emitted, expected, kGeneratedBytes) == 0,
        "emitted-bytes"
    );

    if (mprotect(generated, pageSize, PROT_READ | PROT_EXEC) != 0) {
        record(state, false, "generated-mprotect");
        munmap(generated, pageSize);
        return false;
    }

    __builtin___clear_cache(
        reinterpret_cast<char*>(generated),
        reinterpret_cast<char*>(generated) + kGeneratedBytes
    );

    auto generatedFn = reinterpret_cast<GeneratedFn>(generated);
    const int32_t value = generatedFn();
    record(state, value == digit, "generated-result");

    munmap(generated, pageSize);
    return value == digit;
}

void runInvalidCase(
    CompilerFn compiler,
    const char* source,
    uint32_t sourceLength,
    uint32_t capacity,
    int32_t expected,
    TestState& state,
    const char* label
) {
    uint8_t output[kGeneratedBytes];
    memset(output, kCanary, sizeof(output));

    const int32_t result = compiler(
        reinterpret_cast<const uint8_t*>(source),
        sourceLength,
        output,
        capacity
    );

    record(state, result == expected, label);

    bool unchanged = true;
    for (size_t i = 0; i < sizeof(output); ++i) {
        if (output[i] != kCanary) {
            unchanged = false;
            break;
        }
    }
    record(state, unchanged, "reject-output-unchanged");
}

void runProof(ANativeActivity* activity, char* message, size_t messageBytes) {
    if (message == nullptr || messageBytes == 0) return;

#if !defined(__arm__)
    snprintf(message, messageBytes, "MC0 FAIL: proof must run in an ARM32 process");
#else
    uint8_t seed[kSeedBytes] = {};
    if (!readSeed(activity, seed)) {
        snprintf(message, messageBytes, "MC0 FAIL: canonical seed asset missing");
        return;
    }

    size_t mappedSize = 0;
    void* executable = mapExecutable(seed, sizeof(seed), &mappedSize);
    if (executable == nullptr) {
        snprintf(message, messageBytes, "MC0 FAIL: seed W->X mapping failed");
        return;
    }

    auto compiler = reinterpret_cast<CompilerFn>(executable);
    TestState state;

    const int validDigits[] = {0, 1, 7, 9};
    for (const int digit : validDigits) {
        runValidCase(compiler, digit, state);
    }

    runInvalidCase(compiler, "ret", 3, 8, -1, state, "reject-short");
    runInvalidCase(compiler, "ret 10", 6, 8, -1, state, "reject-two-digit");
    runInvalidCase(compiler, "RET 1", 5, 8, -1, state, "reject-case");
    runInvalidCase(compiler, "ret x", 5, 8, -1, state, "reject-nondigit");
    runInvalidCase(compiler, " ret 1", 6, 8, -1, state, "reject-leading-space");
    runInvalidCase(compiler, "ret 1", 5, 0, -2, state, "reject-capacity-0");
    runInvalidCase(compiler, "ret 1", 5, 7, -2, state, "reject-capacity-7");

    munmap(executable, mappedSize);

    if (state.failed == 0) {
        snprintf(
            message,
            messageBytes,
            "MC0 PASS: %d checks; exact ARM32 seed compiled + emitted code executed",
            state.passed
        );
    } else {
        snprintf(
            message,
            messageBytes,
            "MC0 FAIL: %d failed / %d passed; first=%s",
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
