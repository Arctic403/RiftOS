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

constexpr const char* kTag = "RiftppSeed0Arm64Proof";
constexpr const char* kCompilerAsset = "compiler.bin";
constexpr size_t kCompilerBytes = 276U;
constexpr uint32_t kBundleBytes = 32U;
constexpr size_t kPayloadOffset = 16U;
constexpr size_t kPayloadBytes = 8U;
constexpr uint32_t kReject = 0xffffffffU;
constexpr uint8_t kCanary = 0xA5U;

using CompilerFn = uint32_t (*)(
    const uint8_t* source,
    uint32_t sourceLength,
    uint8_t* output,
    uint32_t outputCapacity
);
using PayloadFn = uint32_t (*)();

struct GuardedPage {
    void* base = MAP_FAILED;
    uint8_t* page = nullptr;
    size_t pageSize = 0U;
    size_t totalSize = 0U;
};

struct PositiveCase {
    const char* source;
    uint32_t expected;
    const uint8_t* expectedBundle;
};

bool allocateGuarded(GuardedPage* region) {
    if (region == nullptr) return false;
    const long rawPage = sysconf(_SC_PAGESIZE);
    if (rawPage <= 0) return false;
    const size_t pageSize = static_cast<size_t>(rawPage);
    const size_t total = pageSize * 3U;
    void* base = mmap(
        nullptr,
        total,
        PROT_NONE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (base == MAP_FAILED) return false;
    uint8_t* middle = reinterpret_cast<uint8_t*>(base) + pageSize;
    if (mprotect(middle, pageSize, PROT_READ | PROT_WRITE) != 0) {
        munmap(base, total);
        return false;
    }
    region->base = base;
    region->page = middle;
    region->pageSize = pageSize;
    region->totalSize = total;
    return true;
}

void releaseGuarded(GuardedPage* region) {
    if (region == nullptr || region->base == MAP_FAILED) return;
    munmap(region->base, region->totalSize);
    region->base = MAP_FAILED;
    region->page = nullptr;
    region->pageSize = 0U;
    region->totalSize = 0U;
}

bool readCompilerAsset(ANativeActivity* activity, uint8_t* output) {
    if (activity == nullptr || activity->assetManager == nullptr || output == nullptr) {
        return false;
    }
    AAsset* asset = AAssetManager_open(
        activity->assetManager,
        kCompilerAsset,
        AASSET_MODE_BUFFER
    );
    if (asset == nullptr) return false;
    const off_t length = AAsset_getLength(asset);
    if (length != static_cast<off_t>(kCompilerBytes)) {
        AAsset_close(asset);
        return false;
    }
    size_t total = 0U;
    while (total < kCompilerBytes) {
        const int got = AAsset_read(asset, output + total, kCompilerBytes - total);
        if (got <= 0) {
            AAsset_close(asset);
            return false;
        }
        total += static_cast<size_t>(got);
    }
    AAsset_close(asset);
    return total == kCompilerBytes;
}

bool executePayload(const uint8_t* payload, uint32_t expected) {
    if (payload == nullptr) return false;
    GuardedPage region;
    if (!allocateGuarded(&region) || region.pageSize < kPayloadBytes) {
        releaseGuarded(&region);
        return false;
    }
    uint8_t* code = region.page + region.pageSize - kPayloadBytes;
    memcpy(code, payload, kPayloadBytes);
    if (mprotect(region.page, region.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&region);
        return false;
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(code),
        reinterpret_cast<char*>(code) + kPayloadBytes
    );
    auto fn = reinterpret_cast<PayloadFn>(code);
    const uint32_t value = fn();
    releaseGuarded(&region);
    return value == expected;
}

const uint8_t kBundle0[kBundleBytes] = {
    0x52,0x50,0x50,0x30,0x01,0x00,0x00,0x00,
    0x08,0x00,0x00,0x00,0x08,0x00,0x00,0x00,
    0x00,0x00,0x80,0x52,0xc0,0x03,0x5f,0xd6,
    0x00,0x00,0xa0,0xe3,0x1e,0xff,0x2f,0xe1
};
const uint8_t kBundle1[kBundleBytes] = {
    0x52,0x50,0x50,0x30,0x01,0x00,0x00,0x00,
    0x08,0x00,0x00,0x00,0x08,0x00,0x00,0x00,
    0x20,0x00,0x80,0x52,0xc0,0x03,0x5f,0xd6,
    0x01,0x00,0xa0,0xe3,0x1e,0xff,0x2f,0xe1
};
const uint8_t kBundle9[kBundleBytes] = {
    0x52,0x50,0x50,0x30,0x01,0x00,0x00,0x00,
    0x08,0x00,0x00,0x00,0x08,0x00,0x00,0x00,
    0x20,0x01,0x80,0x52,0xc0,0x03,0x5f,0xd6,
    0x09,0x00,0xa0,0xe3,0x1e,0xff,0x2f,0xe1
};
const uint8_t kBundle42[kBundleBytes] = {
    0x52,0x50,0x50,0x30,0x01,0x00,0x00,0x00,
    0x08,0x00,0x00,0x00,0x08,0x00,0x00,0x00,
    0x40,0x05,0x80,0x52,0xc0,0x03,0x5f,0xd6,
    0x2a,0x00,0xa0,0xe3,0x1e,0xff,0x2f,0xe1
};
const uint8_t kBundle255[kBundleBytes] = {
    0x52,0x50,0x50,0x30,0x01,0x00,0x00,0x00,
    0x08,0x00,0x00,0x00,0x08,0x00,0x00,0x00,
    0xe0,0x1f,0x80,0x52,0xc0,0x03,0x5f,0xd6,
    0xff,0x00,0xa0,0xe3,0x1e,0xff,0x2f,0xe1
};

const PositiveCase kPositiveCases[] = {
    {"ret 0\n", 0U, kBundle0},
    {"ret 1\n", 1U, kBundle1},
    {"ret 9\n", 9U, kBundle9},
    {"ret 42\n", 42U, kBundle42},
    {"ret 255\n", 255U, kBundle255},
};

const char* const kRejectedSources[] = {
    "",
    "ret",
    "Ret 1\n",
    "RET 1\n",
    "ret1\n",
    "ret  1\n",
    "ret \n",
    "ret a\n",
    "ret -1\n",
    "ret +1\n",
    "ret 00\n",
    "ret 01\n",
    "ret 256\n",
    "ret 9999\n",
    "ret 1",
    "ret 1\nX",
};

bool compileExact(
    CompilerFn compiler,
    const char* source,
    uint32_t capacity,
    uint8_t* output,
    uint32_t* result
) {
    if (compiler == nullptr || source == nullptr || output == nullptr || result == nullptr) {
        return false;
    }
    memset(output, kCanary, kBundleBytes);
    const size_t sourceLength = strlen(source);
    const uint32_t value = compiler(
        reinterpret_cast<const uint8_t*>(source),
        static_cast<uint32_t>(sourceLength),
        output,
        capacity
    );
    *result = value;
    return true;
}

bool runProof(ANativeActivity* activity, char* message, size_t messageBytes) {
#if !defined(__aarch64__)
    snprintf(message, messageBytes, "Rift++ ARM64 seed0 FAIL abi-not-aarch64");
    return false;
#else
    uint8_t compilerAsset[kCompilerBytes] = {};
    if (!readCompilerAsset(activity, compilerAsset)) {
        snprintf(message, messageBytes, "Rift++ ARM64 seed0 FAIL compiler-asset");
        return false;
    }

    GuardedPage compilerRegion;
    if (!allocateGuarded(&compilerRegion) || compilerRegion.pageSize < kCompilerBytes) {
        releaseGuarded(&compilerRegion);
        snprintf(message, messageBytes, "Rift++ ARM64 seed0 FAIL compiler-map");
        return false;
    }

    uint8_t* compilerBytes =
        compilerRegion.page + compilerRegion.pageSize - kCompilerBytes;
    memcpy(compilerBytes, compilerAsset, kCompilerBytes);
    if (mprotect(
            compilerRegion.page,
            compilerRegion.pageSize,
            PROT_READ | PROT_EXEC
        ) != 0) {
        releaseGuarded(&compilerRegion);
        snprintf(message, messageBytes, "Rift++ ARM64 seed0 FAIL compiler-rx");
        return false;
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(compilerBytes),
        reinterpret_cast<char*>(compilerBytes) + kCompilerBytes
    );
    auto compiler = reinterpret_cast<CompilerFn>(compilerBytes);

    uint8_t output[kBundleBytes] = {};
    for (size_t i = 0U; i < sizeof(kPositiveCases) / sizeof(kPositiveCases[0]); ++i) {
        uint32_t result = 0U;
        if (!compileExact(
                compiler,
                kPositiveCases[i].source,
                kBundleBytes,
                output,
                &result
            ) ||
            result != kBundleBytes ||
            memcmp(output, kPositiveCases[i].expectedBundle, kBundleBytes) != 0) {
            releaseGuarded(&compilerRegion);
            snprintf(
                message,
                messageBytes,
                "Rift++ ARM64 seed0 FAIL vector-%zu",
                i
            );
            return false;
        }
        if (!executePayload(output + kPayloadOffset, kPositiveCases[i].expected)) {
            releaseGuarded(&compilerRegion);
            snprintf(
                message,
                messageBytes,
                "Rift++ ARM64 seed0 FAIL payload-%zu",
                i
            );
            return false;
        }
    }

    uint8_t first42[kBundleBytes] = {};
    uint8_t second42[kBundleBytes] = {};
    uint32_t firstResult = 0U;
    uint32_t secondResult = 0U;
    if (!compileExact(compiler, "ret 42\n", kBundleBytes, first42, &firstResult) ||
        !compileExact(compiler, "ret 42\n", kBundleBytes, second42, &secondResult) ||
        firstResult != kBundleBytes ||
        secondResult != kBundleBytes ||
        memcmp(first42, second42, kBundleBytes) != 0) {
        releaseGuarded(&compilerRegion);
        snprintf(message, messageBytes, "Rift++ ARM64 seed0 FAIL determinism");
        return false;
    }

    for (size_t i = 0U; i < sizeof(kRejectedSources) / sizeof(kRejectedSources[0]); ++i) {
        uint32_t result = 0U;
        if (!compileExact(
                compiler,
                kRejectedSources[i],
                kBundleBytes,
                output,
                &result
            ) ||
            result != kReject) {
            releaseGuarded(&compilerRegion);
            snprintf(
                message,
                messageBytes,
                "Rift++ ARM64 seed0 FAIL reject-%zu",
                i
            );
            return false;
        }
    }

    uint32_t shortCapacityResult = 0U;
    if (!compileExact(
            compiler,
            "ret 42\n",
            kBundleBytes - 1U,
            output,
            &shortCapacityResult
        ) ||
        shortCapacityResult != kReject) {
        releaseGuarded(&compilerRegion);
        snprintf(message, messageBytes, "Rift++ ARM64 seed0 FAIL capacity");
        return false;
    }

    releaseGuarded(&compilerRegion);
    snprintf(
        message,
        messageBytes,
        "Rift++ ARM64 seed0 PASS compiler=b1f33b94 vectors=5 payloads=5 rejects=17 crossHost=exact"
    );
    return true;
#endif
}

void report(ANativeActivity* activity, const char* message, bool success) {
    if (activity == nullptr || message == nullptr) return;
    __android_log_print(
        success ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        kTag,
        "%s",
        message
    );

    JNIEnv* env = nullptr;
    bool attached = false;
    if (activity->vm->GetEnv(
            reinterpret_cast<void**>(&env),
            JNI_VERSION_1_6
        ) != JNI_OK) {
        if (activity->vm->AttachCurrentThread(&env, nullptr) != JNI_OK ||
            env == nullptr) {
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

}  // namespace

extern "C" __attribute__((visibility("default")))
void ANativeActivity_onCreate(
    ANativeActivity* activity,
    void* savedState,
    size_t savedStateSize
) {
    (void)savedState;
    (void)savedStateSize;
    char message[192] = {};
    const bool success = runProof(activity, message, sizeof(message));
    report(activity, message, success);
}
