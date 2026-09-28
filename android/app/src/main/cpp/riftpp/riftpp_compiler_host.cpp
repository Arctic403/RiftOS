#include <jni.h>

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

constexpr jsize kCompilerBytes = 276;
constexpr jsize kMaxSourceBytes = 4096;
constexpr jsize kMaxOutputBytes = 4096;
constexpr uint8_t kCanary = 0xA5;

using CompilerFn = uint32_t (*)(
    const uint8_t* source,
    uint32_t sourceLength,
    uint8_t* output,
    uint32_t outputCapacity
);

struct GuardedPage {
    void* base = MAP_FAILED;
    uint8_t* page = nullptr;
    size_t pageSize = 0;
    size_t totalSize = 0;
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
    region->pageSize = 0;
    region->totalSize = 0;
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
Java_com_riftos_app_RiftppCompilerService_nativeCompile(
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
    return resultArray(env, -90, 0U);
#else
    if (compilerArray == nullptr || sourceArray == nullptr || outputArray == nullptr) {
        return resultArray(env, -91, 0U);
    }

    const jsize compilerLength = env->GetArrayLength(compilerArray);
    const jsize sourceLength = env->GetArrayLength(sourceArray);
    const jsize outputLength = env->GetArrayLength(outputArray);
    if (
        compilerLength != kCompilerBytes ||
        sourceLength < 0 || sourceLength > kMaxSourceBytes ||
        outputLength <= 0 || outputLength > kMaxOutputBytes
    ) {
        return resultArray(env, -92, 0U);
    }

    GuardedPage compilerRegion;
    GuardedPage sourceRegion;
    GuardedPage outputRegion;
    if (!allocateGuarded(&compilerRegion)) {
        return resultArray(env, -93, 0U);
    }
    if (!allocateGuarded(&sourceRegion)) {
        releaseGuarded(&compilerRegion);
        return resultArray(env, -94, 0U);
    }
    if (!allocateGuarded(&outputRegion)) {
        releaseGuarded(&sourceRegion);
        releaseGuarded(&compilerRegion);
        return resultArray(env, -95, 0U);
    }

    if (
        compilerRegion.pageSize < static_cast<size_t>(compilerLength) ||
        sourceRegion.pageSize < static_cast<size_t>(sourceLength) ||
        outputRegion.pageSize < static_cast<size_t>(outputLength)
    ) {
        releaseGuarded(&outputRegion);
        releaseGuarded(&sourceRegion);
        releaseGuarded(&compilerRegion);
        return resultArray(env, -96, 0U);
    }

    uint8_t* compilerBytes =
        compilerRegion.page + compilerRegion.pageSize - static_cast<size_t>(compilerLength);
    uint8_t* sourceBytes =
        sourceRegion.page + sourceRegion.pageSize - static_cast<size_t>(sourceLength);
    uint8_t* outputBytes =
        outputRegion.page + outputRegion.pageSize - static_cast<size_t>(outputLength);

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
        releaseGuarded(&outputRegion);
        releaseGuarded(&sourceRegion);
        releaseGuarded(&compilerRegion);
        return resultArray(env, -97, 0U);
    }

    memset(outputRegion.page, kCanary, outputRegion.pageSize);

    if (
        mprotect(compilerRegion.page, compilerRegion.pageSize, PROT_READ | PROT_EXEC) != 0 ||
        mprotect(sourceRegion.page, sourceRegion.pageSize, PROT_READ) != 0
    ) {
        releaseGuarded(&outputRegion);
        releaseGuarded(&sourceRegion);
        releaseGuarded(&compilerRegion);
        return resultArray(env, -98, 0U);
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
        outputRegion.pageSize - static_cast<size_t>(outputLength);
    for (size_t i = 0; i < prefixLength; ++i) {
        if (outputRegion.page[i] != kCanary) {
            releaseGuarded(&outputRegion);
            releaseGuarded(&sourceRegion);
            releaseGuarded(&compilerRegion);
            return resultArray(env, -99, compilerResult);
        }
    }

    if (
        compilerResult != 0xffffffffU &&
        compilerResult > static_cast<uint32_t>(outputLength)
    ) {
        releaseGuarded(&outputRegion);
        releaseGuarded(&sourceRegion);
        releaseGuarded(&compilerRegion);
        return resultArray(env, -100, compilerResult);
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
            releaseGuarded(&outputRegion);
            releaseGuarded(&sourceRegion);
            releaseGuarded(&compilerRegion);
            return resultArray(env, -101, compilerResult);
        }
    }

    releaseGuarded(&outputRegion);
    releaseGuarded(&sourceRegion);
    releaseGuarded(&compilerRegion);
    return resultArray(env, 0, compilerResult);
#endif
}
