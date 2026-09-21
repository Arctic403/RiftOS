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

constexpr const char* kTag = "CodynexMC2A";
constexpr const char* kVmAsset = "vm1_seed.bin";
constexpr const char* kCompilerAsset = "selfhost_compiler.bin";
constexpr const char* kSourceAsset = "selfhost_compiler.cx0";

constexpr size_t kVmBytes = 812;
constexpr size_t kCompilerBytes = 292;
constexpr size_t kSourceBytes = 584;
constexpr uint32_t kStepBudget = 20000;

struct VmContext {
    const uint8_t* source;
    uint32_t sourceLength;
    uint8_t* output;
    uint32_t outputCapacity;
    uint32_t result;
};

#if defined(__arm__)
static_assert(sizeof(void*) == 4, "MC2-A proof requires ARM32 pointer width");
static_assert(sizeof(VmContext) == 20, "VM1 context layout drift");
#endif

using VmFn = int32_t (*)(const uint8_t*, uint32_t, VmContext*, uint32_t);

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
        strncmp(message, "MC2-A PASS", 10) == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
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
            jmethodID setTitle = env->GetMethodID(activityClass, "setTitle", "(Ljava/lang/CharSequence;)V");
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
                jobject toast = env->CallStaticObjectMethod(toastClass, makeText, activity->clazz, text, 1);
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

bool readAssetExact(
    ANativeActivity* activity,
    const char* name,
    uint8_t* output,
    size_t expected
) {
    if (activity == nullptr || activity->assetManager == nullptr || name == nullptr || output == nullptr) return false;

    AAsset* asset = AAssetManager_open(activity->assetManager, name, AASSET_MODE_BUFFER);
    if (asset == nullptr) return false;

    const off_t length = AAsset_getLength(asset);
    if (length != static_cast<off_t>(expected)) {
        AAsset_close(asset);
        return false;
    }

    size_t total = 0;
    while (total < expected) {
        const int read = AAsset_read(asset, output + total, expected - total);
        if (read <= 0) {
            AAsset_close(asset);
            return false;
        }
        total += static_cast<size_t>(read);
    }

    AAsset_close(asset);
    return total == expected;
}

void* mapExecutable(const uint8_t* bytes, size_t size, size_t* mappedSize) {
    if (bytes == nullptr || size == 0 || mappedSize == nullptr) return nullptr;

    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return nullptr;

    const size_t pageSize = static_cast<size_t>(page);
    const size_t rounded = ((size + pageSize - 1U) / pageSize) * pageSize;

    void* memory = mmap(nullptr, rounded, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
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

bool compileOnce(
    VmFn vm,
    const uint8_t* compiler,
    const uint8_t* source,
    uint8_t* output,
    uint32_t* compilerResult,
    int32_t* vmStatus
) {
    VmContext context{
        source,
        static_cast<uint32_t>(kSourceBytes),
        output,
        static_cast<uint32_t>(kCompilerBytes),
        0xA5A5A5A5u
    };

    const int32_t status = vm(
        compiler,
        static_cast<uint32_t>(kCompilerBytes),
        &context,
        kStepBudget
    );

    if (compilerResult != nullptr) *compilerResult = context.result;
    if (vmStatus != nullptr) *vmStatus = status;
    return status == 0;
}

void runProof(ANativeActivity* activity, char* message, size_t messageBytes) {
    if (message == nullptr || messageBytes == 0) return;

#if !defined(__arm__)
    snprintf(message, messageBytes, "MC2-A FAIL: proof must run in an ARM32 process");
#else
    uint8_t vmBytes[kVmBytes] = {};
    uint8_t compilerA[kCompilerBytes] = {};
    uint8_t source[kSourceBytes] = {};
    uint8_t compilerB[kCompilerBytes] = {};
    uint8_t compilerC[kCompilerBytes] = {};

    if (!readAssetExact(activity, kVmAsset, vmBytes, sizeof(vmBytes))) {
        snprintf(message, messageBytes, "MC2-A FAIL: VM1 asset missing");
        return;
    }
    if (!readAssetExact(activity, kCompilerAsset, compilerA, sizeof(compilerA))) {
        snprintf(message, messageBytes, "MC2-A FAIL: compiler A asset missing");
        return;
    }
    if (!readAssetExact(activity, kSourceAsset, source, sizeof(source))) {
        snprintf(message, messageBytes, "MC2-A FAIL: external compiler source missing");
        return;
    }

    size_t mappedSize = 0;
    void* executable = mapExecutable(vmBytes, sizeof(vmBytes), &mappedSize);
    if (executable == nullptr) {
        snprintf(message, messageBytes, "MC2-A FAIL: VM1 W->X mapping failed");
        return;
    }

    auto vm = reinterpret_cast<VmFn>(executable);
    TestState state;

    memset(compilerB, 0xA5, sizeof(compilerB));
    uint32_t resultB = 0;
    int32_t statusB = -99;
    const bool runB = compileOnce(vm, compilerA, source, compilerB, &resultB, &statusB);

    record(state, runB && statusB == 0, "A-to-B-vm-status");
    record(state, resultB == kCompilerBytes, "A-to-B-byte-count");
    record(state, memcmp(compilerA, compilerB, kCompilerBytes) == 0, "B-equals-A");

    memset(compilerC, 0xA5, sizeof(compilerC));
    uint32_t resultC = 0;
    int32_t statusC = -99;
    const bool runC = compileOnce(vm, compilerB, source, compilerC, &resultC, &statusC);

    record(state, runC && statusC == 0, "B-to-C-vm-status");
    record(state, resultC == kCompilerBytes, "B-to-C-byte-count");
    record(state, memcmp(compilerB, compilerC, kCompilerBytes) == 0, "C-equals-B");
    record(state, memcmp(compilerA, compilerC, kCompilerBytes) == 0, "C-equals-A");

    munmap(executable, mappedSize);

    if (state.failed == 0) {
        snprintf(
            message,
            messageBytes,
            "MC2-A PASS: %d checks; self-hosted compiler fixed point A=B=C",
            state.passed
        );
    } else {
        snprintf(
            message,
            messageBytes,
            "MC2-A FAIL: %d failed / %d passed; first=%s",
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
