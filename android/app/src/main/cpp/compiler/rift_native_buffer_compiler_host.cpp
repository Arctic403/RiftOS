#include <jni.h>

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

constexpr jsize kMaxCompilerBytes = 256 * 1024;
constexpr jsize kMaxSourceBytes = 512 * 1024;
constexpr jsize kMaxOutputBytes = 512 * 1024;
constexpr uint8_t kCanary = 0xA5;

using CompilerFn = uint32_t (*)(
    const uint8_t* source,
    uint32_t sourceLength,
    uint8_t* output,
    uint32_t outputCapacity
);

struct GuardedSpan {
    void* base = MAP_FAILED;
    uint8_t* data = nullptr;
    size_t mappedSize = 0;
    size_t totalSize = 0;
};

bool allocateGuardedSpan(size_t requiredBytes, GuardedSpan* region) {
    if (region == nullptr || requiredBytes == 0U) return false;
    const long rawPage = sysconf(_SC_PAGESIZE);
    if (rawPage <= 0) return false;
    const size_t pageSize = static_cast<size_t>(rawPage);
    if (requiredBytes > SIZE_MAX - (pageSize - 1U)) return false;
    const size_t pageCount = (requiredBytes + pageSize - 1U) / pageSize;
    if (pageCount > (SIZE_MAX / pageSize) - 2U) return false;
    const size_t mappedSize = pageCount * pageSize;
    const size_t totalSize = mappedSize + pageSize * 2U;

    void* base = mmap(
        nullptr,
        totalSize,
        PROT_NONE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (base == MAP_FAILED) return false;

    uint8_t* data = reinterpret_cast<uint8_t*>(base) + pageSize;
    if (mprotect(data, mappedSize, PROT_READ | PROT_WRITE) != 0) {
        munmap(base, totalSize);
        return false;
    }

    region->base = base;
    region->data = data;
    region->mappedSize = mappedSize;
    region->totalSize = totalSize;
    return true;
}

void releaseGuardedSpan(GuardedSpan* region) {
    if (region == nullptr || region->base == MAP_FAILED) return;
    munmap(region->base, region->totalSize);
    region->base = MAP_FAILED;
    region->data = nullptr;
    region->mappedSize = 0U;
    region->totalSize = 0U;
}

jlongArray resultArray(JNIEnv* env, int32_t hostStatus, uint32_t returnValue) {
    const jlong values[2] = {
        static_cast<jlong>(hostStatus),
        static_cast<jlong>(returnValue)
    };
    jlongArray result = env->NewLongArray(2);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 2, values);
    }
    return result;
}

}  // namespace

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_riftos_app_RiftNativeBufferCompilerService_nativeCompileDynamic(
    JNIEnv* env,
    jobject,
    jbyteArray compilerArray,
    jbyteArray sourceArray,
    jbyteArray outputArray
) {
#if !defined(__aarch64__) && !defined(__arm__)
    (void)compilerArray;
    (void)sourceArray;
    (void)outputArray;
    return resultArray(env, -300, 0U);
#else
    if (compilerArray == nullptr || sourceArray == nullptr || outputArray == nullptr) {
        return resultArray(env, -301, 0U);
    }

    const jsize compilerLength = env->GetArrayLength(compilerArray);
    const jsize sourceLength = env->GetArrayLength(sourceArray);
    const jsize outputLength = env->GetArrayLength(outputArray);
    if (
        compilerLength <= 0 || compilerLength > kMaxCompilerBytes ||
        sourceLength < 0 || sourceLength > kMaxSourceBytes ||
        outputLength <= 0 || outputLength > kMaxOutputBytes
    ) {
        return resultArray(env, -302, 0U);
    }

    GuardedSpan compilerRegion;
    GuardedSpan sourceRegion;
    GuardedSpan outputRegion;
    const size_t sourceRequired =
        sourceLength > 0 ? static_cast<size_t>(sourceLength) : 1U;

    if (!allocateGuardedSpan(static_cast<size_t>(compilerLength), &compilerRegion)) {
        return resultArray(env, -303, 0U);
    }
    if (!allocateGuardedSpan(sourceRequired, &sourceRegion)) {
        releaseGuardedSpan(&compilerRegion);
        return resultArray(env, -304, 0U);
    }
    if (!allocateGuardedSpan(static_cast<size_t>(outputLength), &outputRegion)) {
        releaseGuardedSpan(&sourceRegion);
        releaseGuardedSpan(&compilerRegion);
        return resultArray(env, -305, 0U);
    }

    uint8_t* compilerBytes =
        compilerRegion.data + compilerRegion.mappedSize - static_cast<size_t>(compilerLength);
    uint8_t* sourceBytes =
        sourceRegion.data + sourceRegion.mappedSize - sourceRequired;
    uint8_t* outputBytes =
        outputRegion.data + outputRegion.mappedSize - static_cast<size_t>(outputLength);

    env->GetByteArrayRegion(
        compilerArray,
        0,
        compilerLength,
        reinterpret_cast<jbyte*>(compilerBytes)
    );
    if (sourceLength > 0) {
        env->GetByteArrayRegion(
            sourceArray,
            0,
            sourceLength,
            reinterpret_cast<jbyte*>(sourceBytes)
        );
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        releaseGuardedSpan(&outputRegion);
        releaseGuardedSpan(&sourceRegion);
        releaseGuardedSpan(&compilerRegion);
        return resultArray(env, -306, 0U);
    }

    memset(outputRegion.data, kCanary, outputRegion.mappedSize);
    if (
        mprotect(compilerRegion.data, compilerRegion.mappedSize, PROT_READ | PROT_EXEC) != 0 ||
        mprotect(sourceRegion.data, sourceRegion.mappedSize, PROT_READ) != 0
    ) {
        releaseGuardedSpan(&outputRegion);
        releaseGuardedSpan(&sourceRegion);
        releaseGuardedSpan(&compilerRegion);
        return resultArray(env, -307, 0U);
    }

    __builtin___clear_cache(
        reinterpret_cast<char*>(compilerBytes),
        reinterpret_cast<char*>(compilerBytes) + compilerLength
    );

    auto compiler = reinterpret_cast<CompilerFn>(compilerBytes);
    const uint32_t compilerResult = compiler(
        sourceBytes,
        static_cast<uint32_t>(sourceLength),
        outputBytes,
        static_cast<uint32_t>(outputLength)
    );

    const size_t prefixLength =
        outputRegion.mappedSize - static_cast<size_t>(outputLength);
    for (size_t i = 0; i < prefixLength; ++i) {
        if (outputRegion.data[i] != kCanary) {
            releaseGuardedSpan(&outputRegion);
            releaseGuardedSpan(&sourceRegion);
            releaseGuardedSpan(&compilerRegion);
            return resultArray(env, -308, compilerResult);
        }
    }

    if (
        compilerResult != 0xffffffffU &&
        compilerResult > static_cast<uint32_t>(outputLength)
    ) {
        releaseGuardedSpan(&outputRegion);
        releaseGuardedSpan(&sourceRegion);
        releaseGuardedSpan(&compilerRegion);
        return resultArray(env, -309, compilerResult);
    }

    if (compilerResult != 0xffffffffU && compilerResult > 0U) {
        env->SetByteArrayRegion(
            outputArray,
            0,
            static_cast<jsize>(compilerResult),
            reinterpret_cast<const jbyte*>(outputBytes)
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            releaseGuardedSpan(&outputRegion);
            releaseGuardedSpan(&sourceRegion);
            releaseGuardedSpan(&compilerRegion);
            return resultArray(env, -310, compilerResult);
        }
    }

    releaseGuardedSpan(&outputRegion);
    releaseGuardedSpan(&sourceRegion);
    releaseGuardedSpan(&compilerRegion);
    return resultArray(env, 0, compilerResult);
#endif
}
