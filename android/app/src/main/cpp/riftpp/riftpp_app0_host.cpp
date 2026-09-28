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

constexpr const char* kTag = "RiftppApp0";
constexpr size_t kOutputBytes = 1024U;

#if defined(__arm__)
constexpr const char* kVmAsset = "vm1_seed.bin";
constexpr const char* kProgramAsset = "program.bin";
constexpr size_t kVmBytes = 812;
constexpr size_t kMaxProgramBytes = 64U * 1024U;
constexpr uint32_t kStepBudget = 200000U;

struct VmContext {
    const uint8_t* source;
    uint32_t sourceLength;
    uint8_t* output;
    uint32_t outputCapacity;
    uint32_t result;
    uint8_t* scratch;
    uint32_t scratchCapacity;
};

static_assert(sizeof(void*) == 4, "Rift++ App0 ARM32 runtime requires 32-bit pointers");
static_assert(sizeof(VmContext) == 28, "Rift++ App0 VM1 context layout drift");

using VmFn = int32_t (*)(const uint8_t*, uint32_t, VmContext*, uint32_t);

bool readAssetExact(
    ANativeActivity* activity,
    const char* name,
    uint8_t* output,
    size_t expected
) {
    if (activity == nullptr || activity->assetManager == nullptr ||
        name == nullptr || output == nullptr) {
        return false;
    }

    AAsset* asset = AAssetManager_open(activity->assetManager, name, AASSET_MODE_BUFFER);
    if (asset == nullptr) return false;

    const off_t length = AAsset_getLength(asset);
    if (length != static_cast<off_t>(expected)) {
        AAsset_close(asset);
        return false;
    }

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
    return total == expected;
}

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

bool printableAscii(const uint8_t* bytes, size_t size) {
    if (bytes == nullptr) return false;
    for (size_t i = 0; i < size; ++i) {
        if (bytes[i] < 0x20U || bytes[i] > 0x7EU) return false;
    }
    return true;
}
#endif

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
    if (activity == nullptr || message == nullptr || messageBytes == 0) return false;

#if !defined(__arm__)
    snprintf(
        message,
        messageBytes,
        "Rift++ App0 FAIL: ARM32 VM runtime required"
    );
    return false;
#else
    uint8_t vmBytes[kVmBytes] = {};
    uint8_t program[kMaxProgramBytes] = {};
    uint8_t output[kOutputBytes + 1U] = {};

    if (!readAssetExact(activity, kVmAsset, vmBytes, sizeof(vmBytes))) {
        snprintf(message, messageBytes, "Rift++ App0 FAIL: VM1 asset");
        return false;
    }

    size_t programBytes = 0;
    if (!readAssetBounded(
            activity,
            kProgramAsset,
            program,
            sizeof(program),
            &programBytes
        ) ||
        (programBytes & 3U) != 0U) {
        snprintf(message, messageBytes, "Rift++ App0 FAIL: program asset");
        return false;
    }

    size_t mappedSize = 0;
    void* executable = mapExecutable(vmBytes, sizeof(vmBytes), &mappedSize);
    if (executable == nullptr) {
        snprintf(message, messageBytes, "Rift++ App0 FAIL: VM mapping");
        return false;
    }

    VmContext context{
        nullptr,
        0U,
        output,
        static_cast<uint32_t>(kOutputBytes),
        0xA5A5A5A5U,
        nullptr,
        0U
    };

    auto vm = reinterpret_cast<VmFn>(executable);
    const int32_t status = vm(
        program,
        static_cast<uint32_t>(programBytes),
        &context,
        kStepBudget
    );

    munmap(executable, mappedSize);

    if (status != 0) {
        snprintf(message, messageBytes, "Rift++ App0 FAIL: VM status %d", status);
        return false;
    }

    const uint32_t result = context.result;
    if (result > kOutputBytes) {
        snprintf(message, messageBytes, "Rift++ App0 FAIL: output bounds");
        return false;
    }

    if (!printableAscii(output, result)) {
        snprintf(message, messageBytes, "Rift++ App0 FAIL: text output");
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

    char message[kOutputBytes + 96U] = {};
    const bool success = runApp(activity, message, sizeof(message));
    reportToActivity(activity, message, success);
}
