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

constexpr const char* kTag = "CodynexM2B";
constexpr const char* kVmAsset = "vm1_seed.bin";
constexpr const char* kCompilerAsset = "mc1b_compiler.bin";
constexpr size_t kVmBytes = 812;
constexpr size_t kCompilerBytes = 704;
constexpr size_t kGeneratedBytes = 12;
constexpr uint8_t kCanary = 0xA5;
constexpr uint32_t kStepBudget = 2048;

struct VmContext {
    const uint8_t* source;
    uint32_t sourceLength;
    uint8_t* output;
    uint32_t outputCapacity;
    uint32_t result;
};

#if defined(__arm__)
static_assert(sizeof(void*) == 4, "M2-B proof requires ARM32 pointer width");
static_assert(sizeof(VmContext) == 20, "VM1 context layout drift");
#endif

using VmFn = int32_t (*)(const uint8_t*, uint32_t, VmContext*, uint32_t);
using GeneratedFn = int32_t (*)();

struct TestState {
    int passed = 0;
    int failed = 0;
    const char* firstFailure = nullptr;
};

struct ValidCase {
    const char* source;
    uint32_t sourceLength;
    uint8_t left;
    uint8_t right;
    int32_t expectedValue;
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
        strncmp(message, "M2-B PASS", 9) == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
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

bool runCompiler(
    VmFn vm,
    const uint8_t* compiler,
    const char* source,
    uint32_t sourceLength,
    uint8_t* output,
    uint32_t capacity,
    int32_t* compilerStatus
) {
    VmContext context{
        reinterpret_cast<const uint8_t*>(source),
        sourceLength,
        output,
        capacity,
        0xA5A5A5A5u
    };
    const int32_t vmStatus = vm(compiler, static_cast<uint32_t>(kCompilerBytes), &context, kStepBudget);
    if (compilerStatus != nullptr) {
        *compilerStatus = static_cast<int32_t>(context.result);
    }
    return vmStatus == 0;
}

bool runValidCase(
    VmFn vm,
    const uint8_t* compiler,
    const ValidCase& test,
    TestState& state
) {
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        record(state, false, "page-size");
        return false;
    }

    const size_t pageSize = static_cast<size_t>(page);
    void* generated = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (generated == MAP_FAILED) {
        record(state, false, "generated-mmap");
        return false;
    }
    memset(generated, kCanary, pageSize);

    int32_t compilerStatus = 0;
    const bool vmOk = runCompiler(
        vm,
        compiler,
        test.source,
        test.sourceLength,
        reinterpret_cast<uint8_t*>(generated),
        static_cast<uint32_t>(pageSize),
        &compilerStatus
    );
    record(
        state,
        vmOk && compilerStatus == static_cast<int32_t>(kGeneratedBytes),
        "vm-compile-valid"
    );
    if (!vmOk || compilerStatus != static_cast<int32_t>(kGeneratedBytes)) {
        munmap(generated, pageSize);
        return false;
    }

    const uint8_t expected[kGeneratedBytes] = {
        test.left, 0x00, 0xA0, 0xE3,
        test.right, 0x00, 0x80, 0xE2,
        0x1E, 0xFF, 0x2F, 0xE1
    };
    record(
        state,
        memcmp(generated, expected, kGeneratedBytes) == 0,
        "runtime-add-emission"
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
    record(state, value == test.expectedValue, "generated-runtime-add-result");
    munmap(generated, pageSize);
    return value == test.expectedValue;
}

void runInvalidCase(
    VmFn vm,
    const uint8_t* compiler,
    const char* source,
    uint32_t sourceLength,
    uint32_t capacity,
    int32_t expected,
    TestState& state,
    const char* label
) {
    uint8_t output[kGeneratedBytes];
    memset(output, kCanary, sizeof(output));

    int32_t compilerStatus = 0;
    const bool vmOk = runCompiler(
        vm,
        compiler,
        source,
        sourceLength,
        output,
        capacity,
        &compilerStatus
    );
    record(state, vmOk && compilerStatus == expected, label);

    bool unchanged = true;
    for (size_t i = 0; i < sizeof(output); ++i) {
        if (output[i] != kCanary) {
            unchanged = false;
            break;
        }
    }
    record(state, unchanged, "reject-output-unchanged");
}

void reject(
    VmFn vm,
    const uint8_t* compiler,
    const char* source,
    TestState& state,
    const char* label
) {
    runInvalidCase(
        vm,
        compiler,
        source,
        static_cast<uint32_t>(strlen(source)),
        static_cast<uint32_t>(kGeneratedBytes),
        -1,
        state,
        label
    );
}

void runProof(ANativeActivity* activity, char* message, size_t messageBytes) {
    if (message == nullptr || messageBytes == 0) return;

#if !defined(__arm__)
    snprintf(message, messageBytes, "M2-B FAIL: proof must run in an ARM32 process");
#else
    uint8_t vmBytes[kVmBytes] = {};
    uint8_t compilerBytes[kCompilerBytes] = {};
    if (!readAssetExact(activity, kVmAsset, vmBytes, sizeof(vmBytes))) {
        snprintf(message, messageBytes, "M2-B FAIL: canonical VM1 asset missing");
        return;
    }
    if (!readAssetExact(activity, kCompilerAsset, compilerBytes, sizeof(compilerBytes))) {
        snprintf(message, messageBytes, "M2-B FAIL: canonical compiler bytecode missing");
        return;
    }

    size_t mappedSize = 0;
    void* executable = mapExecutable(vmBytes, sizeof(vmBytes), &mappedSize);
    if (executable == nullptr) {
        snprintf(message, messageBytes, "M2-B FAIL: VM1 W->X mapping failed");
        return;
    }

    auto vm = reinterpret_cast<VmFn>(executable);
    TestState state;

    const ValidCase validCases[] = {
        {"ret 0+0", 7, 0, 0, 0},
        {"ret 1+2", 7, 1, 2, 3},
        {"ret 7+9", 7, 7, 9, 16},
        {"ret 9+10", 8, 9, 10, 19},
        {"ret 10+9", 8, 10, 9, 19},
        {"ret 42+99", 9, 42, 99, 141},
        {"ret 99+42", 9, 99, 42, 141},
        {"ret 100+27", 10, 100, 27, 127},
        {"ret 127+128", 11, 127, 128, 255},
        {"ret 128+127", 11, 128, 127, 255},
        {"ret 200+55", 10, 200, 55, 255},
        {"ret 254+1", 9, 254, 1, 255},
        {"ret 255+0", 9, 255, 0, 255},
        {"ret 255+1", 9, 255, 1, 256},
        {"ret 255+255", 11, 255, 255, 510},
        {"ret 0+255", 9, 0, 255, 255},
    };
    for (const ValidCase& test : validCases) {
        runValidCase(vm, compilerBytes, test, state);
    }

    reject(vm, compilerBytes, "ret", state, "reject-short");
    reject(vm, compilerBytes, "ret ", state, "reject-no-expression");
    reject(vm, compilerBytes, "ret 1", state, "reject-no-plus");
    reject(vm, compilerBytes, "ret 1+", state, "reject-no-right");
    reject(vm, compilerBytes, "ret +1", state, "reject-no-left");
    reject(vm, compilerBytes, "ret 01+1", state, "reject-left-leading-zero");
    reject(vm, compilerBytes, "ret 1+01", state, "reject-right-leading-zero");
    reject(vm, compilerBytes, "ret 00+1", state, "reject-left-double-zero");
    reject(vm, compilerBytes, "ret 1+00", state, "reject-right-double-zero");
    reject(vm, compilerBytes, "ret 256+1", state, "reject-left-overflow");
    reject(vm, compilerBytes, "ret 1+256", state, "reject-right-overflow");
    reject(vm, compilerBytes, "ret -1+1", state, "reject-left-minus");
    reject(vm, compilerBytes, "ret 1+-1", state, "reject-right-minus");
    reject(vm, compilerBytes, "ret 1+2 ", state, "reject-trailing-space");
    reject(vm, compilerBytes, "RET 1+2", state, "reject-case");
    reject(vm, compilerBytes, " ret 1+2", state, "reject-leading-space");
    reject(vm, compilerBytes, "ret 1++2", state, "reject-double-plus");
    reject(vm, compilerBytes, "ret 1+2+3", state, "reject-second-plus");
    reject(vm, compilerBytes, "ret 999+1", state, "reject-left-999");
    reject(vm, compilerBytes, "ret 1+999", state, "reject-right-999");
    reject(vm, compilerBytes, "ret 1+2x", state, "reject-right-suffix");

    runInvalidCase(vm, compilerBytes, "ret 255+255", 11, 0, -2, state, "reject-capacity-0");
    runInvalidCase(vm, compilerBytes, "ret 255+255", 11, 1, -2, state, "reject-capacity-1");
    runInvalidCase(vm, compilerBytes, "ret 255+255", 11, 11, -2, state, "reject-capacity-11");

    munmap(executable, mappedSize);

    if (state.failed == 0) {
        snprintf(
            message,
            messageBytes,
            "M2-B PASS: %d checks; MC1-B compiler executed inside external VM1",
            state.passed
        );
    } else {
        snprintf(
            message,
            messageBytes,
            "M2-B FAIL: %d failed / %d passed; first=%s",
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
