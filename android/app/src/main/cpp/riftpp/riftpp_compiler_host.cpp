#include <jni.h>

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

constexpr jsize kCompilerBytes = 276;
constexpr jsize kMaxSourceBytes = 4096;
constexpr jsize kMaxOutputBytes = 4096;
constexpr uint8_t kCanary = 0xA5;
constexpr uint32_t kSeedBundleBytes = 32U;
constexpr size_t kSeedPayloadBytes = 8U;
#if defined(__aarch64__)
constexpr size_t kHostPayloadOffset = 16U;
#elif defined(__arm__)
constexpr size_t kHostPayloadOffset = 24U;
#endif

using CompilerFn = uint32_t (*)(
    const uint8_t* source,
    uint32_t sourceLength,
    uint8_t* output,
    uint32_t outputCapacity
);
using GeneratedPayloadFn = uint32_t (*)();

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
    region->mappedSize = 0;
    region->totalSize = 0;
}

int32_t runCompilerLarge(
    CompilerFn compiler,
    const uint8_t* source,
    size_t sourceLength,
    uint8_t* target,
    size_t targetLength,
    uint32_t* resultLength
) {
    if (
        compiler == nullptr ||
        source == nullptr ||
        target == nullptr ||
        resultLength == nullptr ||
        sourceLength == 0U ||
        sourceLength > UINT32_MAX ||
        targetLength == 0U ||
        targetLength > UINT32_MAX
    ) {
        return -270;
    }

    GuardedSpan outputRegion;
    if (!allocateGuardedSpan(targetLength, &outputRegion)) {
        return -271;
    }
    memset(outputRegion.data, kCanary, outputRegion.mappedSize);
    uint8_t* output =
        outputRegion.data + outputRegion.mappedSize - targetLength;
    const uint32_t value = compiler(
        source,
        static_cast<uint32_t>(sourceLength),
        output,
        static_cast<uint32_t>(targetLength)
    );
    const size_t prefixLength = outputRegion.mappedSize - targetLength;
    for (size_t i = 0; i < prefixLength; ++i) {
        if (outputRegion.data[i] != kCanary) {
            releaseGuardedSpan(&outputRegion);
            return -272;
        }
    }
    if (value == 0xffffffffU || value > targetLength) {
        releaseGuardedSpan(&outputRegion);
        return -273;
    }
    memcpy(target, output, static_cast<size_t>(value));
    *resultLength = value;
    releaseGuardedSpan(&outputRegion);
    return 0;
}

jlongArray resultArray(
    JNIEnv* env,
    int32_t hostStatus,
    uint32_t returnValue,
    int32_t generatedPayloadProofStatus = 0,
    uint32_t generatedPayloadReturnValue = 0U
) {
    const jlong values[4] = {
        static_cast<jlong>(hostStatus),
        static_cast<jlong>(returnValue),
        static_cast<jlong>(generatedPayloadProofStatus),
        static_cast<jlong>(generatedPayloadReturnValue)
    };
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 4, values);
    }
    return result;
}


constexpr jsize kStage1Arm64SourceBytes = 2342;
constexpr jsize kStage1Arm32SourceBytes = 2357;
constexpr jsize kStage1Arm64ImageBytes = 336;
constexpr jsize kStage1Arm32ImageBytes = 340;
constexpr size_t kStage1RecordMaxBytes = 8U;

jlongArray stage1ResultArray(
    JNIEnv* env,
    int32_t hostStatus,
    uint32_t bootstrapArm32Bytes,
    uint32_t bootstrapArm64Bytes,
    uint32_t selfArm32Bytes,
    uint32_t selfArm64Bytes
) {
    const jlong values[5] = {
        static_cast<jlong>(hostStatus),
        static_cast<jlong>(bootstrapArm32Bytes),
        static_cast<jlong>(bootstrapArm64Bytes),
        static_cast<jlong>(selfArm32Bytes),
        static_cast<jlong>(selfArm64Bytes)
    };
    jlongArray result = env->NewLongArray(5);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 5, values);
    }
    return result;
}


constexpr jsize kS2GenAArm32SourceBytes = 22809;
constexpr jsize kS2GenAArm64SourceBytes = 20230;
constexpr jsize kS2GenAArm32ImageBytes = 3128;
constexpr jsize kS2GenAArm64ImageBytes = 2884;
constexpr jsize kS2ProofSourceBytes = 24;
constexpr jsize kS2ProofOutputBytes = 96;
constexpr jsize kS2VectorSourceBytes = 560;
constexpr jsize kS2VectorOutputBytes = 2240;
constexpr jsize kS2CanonicalCompilerSourceBytes = 11072;
constexpr jsize kS2SelfHostImageBytes = 44288;

jlongArray s2SelfHostResultArray(
    JNIEnv* env,
    int32_t hostStatus,
    uint32_t generationBArm32Bytes,
    uint32_t generationBArm64Bytes,
    uint32_t generationCArm32Bytes,
    uint32_t generationCArm64Bytes,
    bool arm32FixedPoint,
    bool arm64FixedPoint,
    uint32_t proofArm32Bytes,
    uint32_t proofArm64Bytes,
    int32_t proofExecutionStatus,
    uint32_t proofReturnValue,
    uint32_t diagnosticReturnValue = 0xffffffffU
) {
    const jlong values[12] = {
        static_cast<jlong>(hostStatus),
        static_cast<jlong>(generationBArm32Bytes),
        static_cast<jlong>(generationBArm64Bytes),
        static_cast<jlong>(generationCArm32Bytes),
        static_cast<jlong>(generationCArm64Bytes),
        static_cast<jlong>(arm32FixedPoint ? 1 : 0),
        static_cast<jlong>(arm64FixedPoint ? 1 : 0),
        static_cast<jlong>(proofArm32Bytes),
        static_cast<jlong>(proofArm64Bytes),
        static_cast<jlong>(proofExecutionStatus),
        static_cast<jlong>(proofReturnValue),
        static_cast<jlong>(diagnosticReturnValue)
    };
    jlongArray result = env->NewLongArray(12);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 12, values);
    }
    return result;
}

jintArray s2VectorResultArray(
    JNIEnv* env,
    int32_t hostStatus,
    uint32_t arm32Bytes,
    uint32_t arm64Bytes
) {
    const jint values[3] = {
        static_cast<jint>(hostStatus),
        static_cast<jint>(arm32Bytes),
        static_cast<jint>(arm64Bytes)
    };
    jintArray result = env->NewIntArray(3);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, 3, values);
    }
    return result;
}

jlongArray s2ResultArray(
    JNIEnv* env,
    int32_t hostStatus,
    uint32_t genAArm32Bytes,
    uint32_t genAArm64Bytes,
    uint32_t proofArm32Bytes,
    uint32_t proofArm64Bytes,
    int32_t proofExecutionStatus,
    uint32_t proofReturnValue
) {
    const jlong values[7] = {
        static_cast<jlong>(hostStatus),
        static_cast<jlong>(genAArm32Bytes),
        static_cast<jlong>(genAArm64Bytes),
        static_cast<jlong>(proofArm32Bytes),
        static_cast<jlong>(proofArm64Bytes),
        static_cast<jlong>(proofExecutionStatus),
        static_cast<jlong>(proofReturnValue)
    };
    jlongArray result = env->NewLongArray(7);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 7, values);
    }
    return result;
}

int32_t verifyOutputPrefix(
    const GuardedPage& outputRegion,
    size_t outputLength
) {
    const size_t prefixLength = outputRegion.pageSize - outputLength;
    for (size_t i = 0; i < prefixLength; ++i) {
        if (outputRegion.page[i] != kCanary) return -1;
    }
    return 0;
}

int32_t bootstrapStage1Image(
    CompilerFn seedCompiler,
    const uint8_t* source,
    size_t sourceLength,
    uint8_t* target,
    size_t targetLength
) {
    if (
        seedCompiler == nullptr ||
        source == nullptr ||
        target == nullptr ||
        sourceLength == 0U ||
        targetLength == 0U
    ) {
        return -201;
    }

    GuardedPage bundleRegion;
    GuardedPage payloadRegion;
    if (
        !allocateGuarded(&bundleRegion) ||
        !allocateGuarded(&payloadRegion) ||
        bundleRegion.pageSize < kSeedBundleBytes ||
        payloadRegion.pageSize < kSeedPayloadBytes
    ) {
        releaseGuarded(&payloadRegion);
        releaseGuarded(&bundleRegion);
        return -202;
    }

    uint8_t* bundle =
        bundleRegion.page + bundleRegion.pageSize - kSeedBundleBytes;
    uint8_t* payload =
        payloadRegion.page + payloadRegion.pageSize - kSeedPayloadBytes;

    size_t cursor = 0U;
    size_t outputCount = 0U;
    while (cursor < sourceLength) {
        size_t end = cursor;
        while (end < sourceLength && source[end] != static_cast<uint8_t>('\n')) {
            ++end;
        }
        if (end >= sourceLength) {
            releaseGuarded(&payloadRegion);
            releaseGuarded(&bundleRegion);
            return -203;
        }

        const size_t recordLength = end - cursor + 1U;
        if (recordLength == 0U || recordLength > kStage1RecordMaxBytes) {
            releaseGuarded(&payloadRegion);
            releaseGuarded(&bundleRegion);
            return -204;
        }
        if (outputCount >= targetLength) {
            releaseGuarded(&payloadRegion);
            releaseGuarded(&bundleRegion);
            return -205;
        }

        memset(bundleRegion.page, kCanary, bundleRegion.pageSize);
        const uint32_t seedResult = seedCompiler(
            source + cursor,
            static_cast<uint32_t>(recordLength),
            bundle,
            kSeedBundleBytes
        );
        if (
            seedResult != kSeedBundleBytes ||
            verifyOutputPrefix(bundleRegion, kSeedBundleBytes) != 0
        ) {
            releaseGuarded(&payloadRegion);
            releaseGuarded(&bundleRegion);
            return -206;
        }

        memcpy(
            payload,
            bundle + kHostPayloadOffset,
            kSeedPayloadBytes
        );
        if (
            mprotect(
                payloadRegion.page,
                payloadRegion.pageSize,
                PROT_READ | PROT_EXEC
            ) != 0
        ) {
            releaseGuarded(&payloadRegion);
            releaseGuarded(&bundleRegion);
            return -207;
        }
        __builtin___clear_cache(
            reinterpret_cast<char*>(payload),
            reinterpret_cast<char*>(payload) + kSeedPayloadBytes
        );
        auto payloadFn = reinterpret_cast<GeneratedPayloadFn>(payload);
        const uint32_t value = payloadFn();
        if (
            mprotect(
                payloadRegion.page,
                payloadRegion.pageSize,
                PROT_READ | PROT_WRITE
            ) != 0
        ) {
            releaseGuarded(&payloadRegion);
            releaseGuarded(&bundleRegion);
            return -208;
        }
        if (value > 255U) {
            releaseGuarded(&payloadRegion);
            releaseGuarded(&bundleRegion);
            return -209;
        }

        target[outputCount++] = static_cast<uint8_t>(value);
        cursor = end + 1U;
    }

    releaseGuarded(&payloadRegion);
    releaseGuarded(&bundleRegion);
    return outputCount == targetLength ? 0 : -210;
}

int32_t runStage1Compiler(
    CompilerFn compiler,
    const uint8_t* source,
    size_t sourceLength,
    uint8_t* target,
    size_t targetLength,
    uint32_t* resultLength
) {
    if (
        compiler == nullptr ||
        source == nullptr ||
        target == nullptr ||
        resultLength == nullptr ||
        targetLength == 0U
    ) {
        return -220;
    }

    GuardedPage outputRegion;
    if (
        !allocateGuarded(&outputRegion) ||
        outputRegion.pageSize < targetLength
    ) {
        releaseGuarded(&outputRegion);
        return -221;
    }

    memset(outputRegion.page, kCanary, outputRegion.pageSize);
    uint8_t* output =
        outputRegion.page + outputRegion.pageSize - targetLength;
    const uint32_t value = compiler(
        source,
        static_cast<uint32_t>(sourceLength),
        output,
        static_cast<uint32_t>(targetLength)
    );
    if (
        verifyOutputPrefix(outputRegion, targetLength) != 0 ||
        value == 0xffffffffU ||
        value > targetLength
    ) {
        releaseGuarded(&outputRegion);
        return -222;
    }

    memcpy(target, output, static_cast<size_t>(value));
    *resultLength = value;
    releaseGuarded(&outputRegion);
    return 0;
}

}  // namespace

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_riftos_app_RiftppCompilerService_nativeCompile(
    JNIEnv* env,
    jobject,
    jbyteArray compilerArray,
    jbyteArray sourceArray,
    jbyteArray outputArray,
    jboolean proveGeneratedPayload
) {
#if !defined(__aarch64__) && !defined(__arm__)
    (void)compilerArray;
    (void)sourceArray;
    (void)outputArray;
    (void)proveGeneratedPayload;
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
    int32_t generatedPayloadProofStatus = 0;
    uint32_t generatedPayloadReturnValue = 0U;

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

    if (proveGeneratedPayload == JNI_TRUE && compilerResult != 0xffffffffU) {
        if (compilerResult != kSeedBundleBytes) {
            generatedPayloadProofStatus = -102;
        } else {
            GuardedPage payloadRegion;
            if (!allocateGuarded(&payloadRegion) || payloadRegion.pageSize < kSeedPayloadBytes) {
                releaseGuarded(&payloadRegion);
                generatedPayloadProofStatus = -103;
            } else {
                uint8_t* payloadBytes =
                    payloadRegion.page + payloadRegion.pageSize - kSeedPayloadBytes;
                memcpy(
                    payloadBytes,
                    outputBytes + kHostPayloadOffset,
                    kSeedPayloadBytes
                );
                if (mprotect(
                        payloadRegion.page,
                        payloadRegion.pageSize,
                        PROT_READ | PROT_EXEC
                    ) != 0) {
                    generatedPayloadProofStatus = -104;
                } else {
                    __builtin___clear_cache(
                        reinterpret_cast<char*>(payloadBytes),
                        reinterpret_cast<char*>(payloadBytes) + kSeedPayloadBytes
                    );
                    auto generatedPayload = reinterpret_cast<GeneratedPayloadFn>(payloadBytes);
                    generatedPayloadReturnValue = generatedPayload();
                }
                releaseGuarded(&payloadRegion);
            }
        }
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
    return resultArray(
        env,
        0,
        compilerResult,
        generatedPayloadProofStatus,
        generatedPayloadReturnValue
    );
#endif
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_riftos_app_RiftppCompilerService_nativeStage1SelfHost(
    JNIEnv* env,
    jobject,
    jbyteArray seedCompilerArray,
    jbyteArray arm32SourceArray,
    jbyteArray arm64SourceArray,
    jbyteArray bootstrapArm32Array,
    jbyteArray bootstrapArm64Array,
    jbyteArray selfArm32Array,
    jbyteArray selfArm64Array
) {
#if !defined(__aarch64__) && !defined(__arm__)
    (void)seedCompilerArray;
    (void)arm32SourceArray;
    (void)arm64SourceArray;
    (void)bootstrapArm32Array;
    (void)bootstrapArm64Array;
    (void)selfArm32Array;
    (void)selfArm64Array;
    return stage1ResultArray(env, -230, 0U, 0U, 0U, 0U);
#else
    if (
        seedCompilerArray == nullptr ||
        arm32SourceArray == nullptr ||
        arm64SourceArray == nullptr ||
        bootstrapArm32Array == nullptr ||
        bootstrapArm64Array == nullptr ||
        selfArm32Array == nullptr ||
        selfArm64Array == nullptr
    ) {
        return stage1ResultArray(env, -231, 0U, 0U, 0U, 0U);
    }

    if (
        env->GetArrayLength(seedCompilerArray) != kCompilerBytes ||
        env->GetArrayLength(arm32SourceArray) != kStage1Arm32SourceBytes ||
        env->GetArrayLength(arm64SourceArray) != kStage1Arm64SourceBytes ||
        env->GetArrayLength(bootstrapArm32Array) != kStage1Arm32ImageBytes ||
        env->GetArrayLength(bootstrapArm64Array) != kStage1Arm64ImageBytes ||
        env->GetArrayLength(selfArm32Array) != kStage1Arm32ImageBytes ||
        env->GetArrayLength(selfArm64Array) != kStage1Arm64ImageBytes
    ) {
        return stage1ResultArray(env, -232, 0U, 0U, 0U, 0U);
    }

    uint8_t arm32Source[kStage1Arm32SourceBytes] = {};
    uint8_t arm64Source[kStage1Arm64SourceBytes] = {};
    uint8_t bootstrapArm32[kStage1Arm32ImageBytes] = {};
    uint8_t bootstrapArm64[kStage1Arm64ImageBytes] = {};
    uint8_t selfArm32[kStage1Arm32ImageBytes] = {};
    uint8_t selfArm64[kStage1Arm64ImageBytes] = {};

    env->GetByteArrayRegion(
        arm32SourceArray,
        0,
        kStage1Arm32SourceBytes,
        reinterpret_cast<jbyte*>(arm32Source)
    );
    env->GetByteArrayRegion(
        arm64SourceArray,
        0,
        kStage1Arm64SourceBytes,
        reinterpret_cast<jbyte*>(arm64Source)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return stage1ResultArray(env, -233, 0U, 0U, 0U, 0U);
    }

    GuardedPage seedRegion;
    if (
        !allocateGuarded(&seedRegion) ||
        seedRegion.pageSize < static_cast<size_t>(kCompilerBytes)
    ) {
        releaseGuarded(&seedRegion);
        return stage1ResultArray(env, -234, 0U, 0U, 0U, 0U);
    }

    uint8_t* seedCompiler =
        seedRegion.page +
        seedRegion.pageSize -
        static_cast<size_t>(kCompilerBytes);
    env->GetByteArrayRegion(
        seedCompilerArray,
        0,
        kCompilerBytes,
        reinterpret_cast<jbyte*>(seedCompiler)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        releaseGuarded(&seedRegion);
        return stage1ResultArray(env, -235, 0U, 0U, 0U, 0U);
    }
    if (
        mprotect(
            seedRegion.page,
            seedRegion.pageSize,
            PROT_READ | PROT_EXEC
        ) != 0
    ) {
        releaseGuarded(&seedRegion);
        return stage1ResultArray(env, -236, 0U, 0U, 0U, 0U);
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(seedCompiler),
        reinterpret_cast<char*>(seedCompiler) + kCompilerBytes
    );
    auto seed = reinterpret_cast<CompilerFn>(seedCompiler);

    const int32_t bootstrap32Status = bootstrapStage1Image(
        seed,
        arm32Source,
        static_cast<size_t>(kStage1Arm32SourceBytes),
        bootstrapArm32,
        static_cast<size_t>(kStage1Arm32ImageBytes)
    );
    if (bootstrap32Status != 0) {
        releaseGuarded(&seedRegion);
        return stage1ResultArray(
            env,
            bootstrap32Status,
            0U,
            0U,
            0U,
            0U
        );
    }

    const int32_t bootstrap64Status = bootstrapStage1Image(
        seed,
        arm64Source,
        static_cast<size_t>(kStage1Arm64SourceBytes),
        bootstrapArm64,
        static_cast<size_t>(kStage1Arm64ImageBytes)
    );
    releaseGuarded(&seedRegion);
    if (bootstrap64Status != 0) {
        return stage1ResultArray(
            env,
            bootstrap64Status,
            kStage1Arm32ImageBytes,
            0U,
            0U,
            0U
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostStage1 = bootstrapArm64;
    constexpr size_t hostStage1Bytes =
        static_cast<size_t>(kStage1Arm64ImageBytes);
#else
    const uint8_t* hostStage1 = bootstrapArm32;
    constexpr size_t hostStage1Bytes =
        static_cast<size_t>(kStage1Arm32ImageBytes);
#endif

    GuardedPage stage1Region;
    if (
        !allocateGuarded(&stage1Region) ||
        stage1Region.pageSize < hostStage1Bytes
    ) {
        releaseGuarded(&stage1Region);
        return stage1ResultArray(
            env,
            -237,
            kStage1Arm32ImageBytes,
            kStage1Arm64ImageBytes,
            0U,
            0U
        );
    }

    uint8_t* stage1Bytes =
        stage1Region.page + stage1Region.pageSize - hostStage1Bytes;
    memcpy(stage1Bytes, hostStage1, hostStage1Bytes);
    if (
        mprotect(
            stage1Region.page,
            stage1Region.pageSize,
            PROT_READ | PROT_EXEC
        ) != 0
    ) {
        releaseGuarded(&stage1Region);
        return stage1ResultArray(
            env,
            -238,
            kStage1Arm32ImageBytes,
            kStage1Arm64ImageBytes,
            0U,
            0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(stage1Bytes),
        reinterpret_cast<char*>(stage1Bytes) + hostStage1Bytes
    );
    auto stage1 = reinterpret_cast<CompilerFn>(stage1Bytes);

    uint32_t self32Length = 0U;
    const int32_t self32Status = runStage1Compiler(
        stage1,
        arm32Source,
        static_cast<size_t>(kStage1Arm32SourceBytes),
        selfArm32,
        static_cast<size_t>(kStage1Arm32ImageBytes),
        &self32Length
    );
    if (self32Status != 0) {
        releaseGuarded(&stage1Region);
        return stage1ResultArray(
            env,
            self32Status,
            kStage1Arm32ImageBytes,
            kStage1Arm64ImageBytes,
            self32Length,
            0U
        );
    }

    uint32_t self64Length = 0U;
    const int32_t self64Status = runStage1Compiler(
        stage1,
        arm64Source,
        static_cast<size_t>(kStage1Arm64SourceBytes),
        selfArm64,
        static_cast<size_t>(kStage1Arm64ImageBytes),
        &self64Length
    );
    releaseGuarded(&stage1Region);
    if (self64Status != 0) {
        return stage1ResultArray(
            env,
            self64Status,
            kStage1Arm32ImageBytes,
            kStage1Arm64ImageBytes,
            self32Length,
            self64Length
        );
    }

    env->SetByteArrayRegion(
        bootstrapArm32Array,
        0,
        kStage1Arm32ImageBytes,
        reinterpret_cast<const jbyte*>(bootstrapArm32)
    );
    env->SetByteArrayRegion(
        bootstrapArm64Array,
        0,
        kStage1Arm64ImageBytes,
        reinterpret_cast<const jbyte*>(bootstrapArm64)
    );
    env->SetByteArrayRegion(
        selfArm32Array,
        0,
        kStage1Arm32ImageBytes,
        reinterpret_cast<const jbyte*>(selfArm32)
    );
    env->SetByteArrayRegion(
        selfArm64Array,
        0,
        kStage1Arm64ImageBytes,
        reinterpret_cast<const jbyte*>(selfArm64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return stage1ResultArray(
            env,
            -239,
            kStage1Arm32ImageBytes,
            kStage1Arm64ImageBytes,
            self32Length,
            self64Length
        );
    }

    return stage1ResultArray(
        env,
        0,
        kStage1Arm32ImageBytes,
        kStage1Arm64ImageBytes,
        self32Length,
        self64Length
    );
#endif
}


extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_riftos_app_RiftppCompilerService_nativeS2Bootstrap(
    JNIEnv* env,
    jobject,
    jbyteArray seedCompilerArray,
    jbyteArray stage1Arm32SourceArray,
    jbyteArray stage1Arm64SourceArray,
    jbyteArray genAArm32SourceArray,
    jbyteArray genAArm64SourceArray,
    jbyteArray proofArm32SourceArray,
    jbyteArray proofArm64SourceArray,
    jbyteArray genAArm32Array,
    jbyteArray genAArm64Array,
    jbyteArray proofArm32Array,
    jbyteArray proofArm64Array
) {
#if !defined(__aarch64__) && !defined(__arm__)
    (void)seedCompilerArray;
    (void)stage1Arm32SourceArray;
    (void)stage1Arm64SourceArray;
    (void)genAArm32SourceArray;
    (void)genAArm64SourceArray;
    (void)proofArm32SourceArray;
    (void)proofArm64SourceArray;
    (void)genAArm32Array;
    (void)genAArm64Array;
    (void)proofArm32Array;
    (void)proofArm64Array;
    return s2ResultArray(env, -250, 0U, 0U, 0U, 0U, -1, 0U);
#else
    if (
        seedCompilerArray == nullptr ||
        stage1Arm32SourceArray == nullptr ||
        stage1Arm64SourceArray == nullptr ||
        genAArm32SourceArray == nullptr ||
        genAArm64SourceArray == nullptr ||
        proofArm32SourceArray == nullptr ||
        proofArm64SourceArray == nullptr ||
        genAArm32Array == nullptr ||
        genAArm64Array == nullptr ||
        proofArm32Array == nullptr ||
        proofArm64Array == nullptr
    ) {
        return s2ResultArray(env, -251, 0U, 0U, 0U, 0U, -1, 0U);
    }

    if (
        env->GetArrayLength(seedCompilerArray) != kCompilerBytes ||
        env->GetArrayLength(stage1Arm32SourceArray) != kStage1Arm32SourceBytes ||
        env->GetArrayLength(stage1Arm64SourceArray) != kStage1Arm64SourceBytes ||
        env->GetArrayLength(genAArm32SourceArray) != kS2GenAArm32SourceBytes ||
        env->GetArrayLength(genAArm64SourceArray) != kS2GenAArm64SourceBytes ||
        env->GetArrayLength(proofArm32SourceArray) != kS2ProofSourceBytes ||
        env->GetArrayLength(proofArm64SourceArray) != kS2ProofSourceBytes ||
        env->GetArrayLength(genAArm32Array) != kS2GenAArm32ImageBytes ||
        env->GetArrayLength(genAArm64Array) != kS2GenAArm64ImageBytes ||
        env->GetArrayLength(proofArm32Array) != kS2ProofOutputBytes ||
        env->GetArrayLength(proofArm64Array) != kS2ProofOutputBytes
    ) {
        return s2ResultArray(env, -252, 0U, 0U, 0U, 0U, -1, 0U);
    }

    uint8_t stage1Arm32Source[kStage1Arm32SourceBytes] = {};
    uint8_t stage1Arm64Source[kStage1Arm64SourceBytes] = {};
    uint8_t genAArm32Source[kS2GenAArm32SourceBytes] = {};
    uint8_t genAArm64Source[kS2GenAArm64SourceBytes] = {};
    uint8_t proofArm32Source[kS2ProofSourceBytes] = {};
    uint8_t proofArm64Source[kS2ProofSourceBytes] = {};
    uint8_t stage1Arm32[kStage1Arm32ImageBytes] = {};
    uint8_t stage1Arm64[kStage1Arm64ImageBytes] = {};
    uint8_t genAArm32[kS2GenAArm32ImageBytes] = {};
    uint8_t genAArm64[kS2GenAArm64ImageBytes] = {};
    uint8_t proofArm32[kS2ProofOutputBytes] = {};
    uint8_t proofArm64[kS2ProofOutputBytes] = {};

    env->GetByteArrayRegion(
        stage1Arm32SourceArray,
        0,
        kStage1Arm32SourceBytes,
        reinterpret_cast<jbyte*>(stage1Arm32Source)
    );
    env->GetByteArrayRegion(
        stage1Arm64SourceArray,
        0,
        kStage1Arm64SourceBytes,
        reinterpret_cast<jbyte*>(stage1Arm64Source)
    );
    env->GetByteArrayRegion(
        genAArm32SourceArray,
        0,
        kS2GenAArm32SourceBytes,
        reinterpret_cast<jbyte*>(genAArm32Source)
    );
    env->GetByteArrayRegion(
        genAArm64SourceArray,
        0,
        kS2GenAArm64SourceBytes,
        reinterpret_cast<jbyte*>(genAArm64Source)
    );
    env->GetByteArrayRegion(
        proofArm32SourceArray,
        0,
        kS2ProofSourceBytes,
        reinterpret_cast<jbyte*>(proofArm32Source)
    );
    env->GetByteArrayRegion(
        proofArm64SourceArray,
        0,
        kS2ProofSourceBytes,
        reinterpret_cast<jbyte*>(proofArm64Source)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return s2ResultArray(env, -253, 0U, 0U, 0U, 0U, -1, 0U);
    }

    GuardedPage seedRegion;
    if (
        !allocateGuarded(&seedRegion) ||
        seedRegion.pageSize < static_cast<size_t>(kCompilerBytes)
    ) {
        releaseGuarded(&seedRegion);
        return s2ResultArray(env, -254, 0U, 0U, 0U, 0U, -1, 0U);
    }
    uint8_t* seedCompiler =
        seedRegion.page +
        seedRegion.pageSize -
        static_cast<size_t>(kCompilerBytes);
    env->GetByteArrayRegion(
        seedCompilerArray,
        0,
        kCompilerBytes,
        reinterpret_cast<jbyte*>(seedCompiler)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        releaseGuarded(&seedRegion);
        return s2ResultArray(env, -255, 0U, 0U, 0U, 0U, -1, 0U);
    }
    if (mprotect(seedRegion.page, seedRegion.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&seedRegion);
        return s2ResultArray(env, -256, 0U, 0U, 0U, 0U, -1, 0U);
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(seedCompiler),
        reinterpret_cast<char*>(seedCompiler) + kCompilerBytes
    );
    auto seed = reinterpret_cast<CompilerFn>(seedCompiler);

    const int32_t stage1Arm32Status = bootstrapStage1Image(
        seed,
        stage1Arm32Source,
        static_cast<size_t>(kStage1Arm32SourceBytes),
        stage1Arm32,
        static_cast<size_t>(kStage1Arm32ImageBytes)
    );
    if (stage1Arm32Status != 0) {
        releaseGuarded(&seedRegion);
        return s2ResultArray(env, stage1Arm32Status, 0U, 0U, 0U, 0U, -1, 0U);
    }
    const int32_t stage1Arm64Status = bootstrapStage1Image(
        seed,
        stage1Arm64Source,
        static_cast<size_t>(kStage1Arm64SourceBytes),
        stage1Arm64,
        static_cast<size_t>(kStage1Arm64ImageBytes)
    );
    releaseGuarded(&seedRegion);
    if (stage1Arm64Status != 0) {
        return s2ResultArray(env, stage1Arm64Status, 0U, 0U, 0U, 0U, -1, 0U);
    }

#if defined(__aarch64__)
    const uint8_t* hostStage1 = stage1Arm64;
    constexpr size_t hostStage1Bytes = static_cast<size_t>(kStage1Arm64ImageBytes);
#else
    const uint8_t* hostStage1 = stage1Arm32;
    constexpr size_t hostStage1Bytes = static_cast<size_t>(kStage1Arm32ImageBytes);
#endif

    GuardedPage stage1Region;
    if (
        !allocateGuarded(&stage1Region) ||
        stage1Region.pageSize < hostStage1Bytes
    ) {
        releaseGuarded(&stage1Region);
        return s2ResultArray(env, -257, 0U, 0U, 0U, 0U, -1, 0U);
    }
    uint8_t* stage1Bytes =
        stage1Region.page + stage1Region.pageSize - hostStage1Bytes;
    memcpy(stage1Bytes, hostStage1, hostStage1Bytes);
    if (mprotect(stage1Region.page, stage1Region.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&stage1Region);
        return s2ResultArray(env, -258, 0U, 0U, 0U, 0U, -1, 0U);
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(stage1Bytes),
        reinterpret_cast<char*>(stage1Bytes) + hostStage1Bytes
    );
    auto stage1 = reinterpret_cast<CompilerFn>(stage1Bytes);

    uint32_t genAArm32Length = 0U;
    const int32_t genAArm32Status = runStage1Compiler(
        stage1,
        genAArm32Source,
        static_cast<size_t>(kS2GenAArm32SourceBytes),
        genAArm32,
        static_cast<size_t>(kS2GenAArm32ImageBytes),
        &genAArm32Length
    );
    if (genAArm32Status != 0) {
        releaseGuarded(&stage1Region);
        return s2ResultArray(
            env, genAArm32Status, genAArm32Length, 0U, 0U, 0U, -1, 0U
        );
    }

    uint32_t genAArm64Length = 0U;
    const int32_t genAArm64Status = runStage1Compiler(
        stage1,
        genAArm64Source,
        static_cast<size_t>(kS2GenAArm64SourceBytes),
        genAArm64,
        static_cast<size_t>(kS2GenAArm64ImageBytes),
        &genAArm64Length
    );
    releaseGuarded(&stage1Region);
    if (genAArm64Status != 0) {
        return s2ResultArray(
            env,
            genAArm64Status,
            genAArm32Length,
            genAArm64Length,
            0U,
            0U,
            -1,
            0U
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostGenA = genAArm64;
    constexpr size_t hostGenABytes = static_cast<size_t>(kS2GenAArm64ImageBytes);
#else
    const uint8_t* hostGenA = genAArm32;
    constexpr size_t hostGenABytes = static_cast<size_t>(kS2GenAArm32ImageBytes);
#endif

    GuardedPage genARegion;
    if (!allocateGuarded(&genARegion) || genARegion.pageSize < hostGenABytes) {
        releaseGuarded(&genARegion);
        return s2ResultArray(
            env, -259, genAArm32Length, genAArm64Length, 0U, 0U, -1, 0U
        );
    }
    uint8_t* genABytes = genARegion.page + genARegion.pageSize - hostGenABytes;
    memcpy(genABytes, hostGenA, hostGenABytes);
    if (mprotect(genARegion.page, genARegion.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&genARegion);
        return s2ResultArray(
            env, -260, genAArm32Length, genAArm64Length, 0U, 0U, -1, 0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(genABytes),
        reinterpret_cast<char*>(genABytes) + hostGenABytes
    );
    auto genA = reinterpret_cast<CompilerFn>(genABytes);

    uint32_t proofArm32Length = 0U;
    const int32_t proofArm32Status = runStage1Compiler(
        genA,
        proofArm32Source,
        static_cast<size_t>(kS2ProofSourceBytes),
        proofArm32,
        static_cast<size_t>(kS2ProofOutputBytes),
        &proofArm32Length
    );
    if (proofArm32Status != 0) {
        releaseGuarded(&genARegion);
        return s2ResultArray(
            env,
            proofArm32Status,
            genAArm32Length,
            genAArm64Length,
            proofArm32Length,
            0U,
            -1,
            0U
        );
    }

    uint32_t proofArm64Length = 0U;
    const int32_t proofArm64Status = runStage1Compiler(
        genA,
        proofArm64Source,
        static_cast<size_t>(kS2ProofSourceBytes),
        proofArm64,
        static_cast<size_t>(kS2ProofOutputBytes),
        &proofArm64Length
    );
    releaseGuarded(&genARegion);
    if (proofArm64Status != 0) {
        return s2ResultArray(
            env,
            proofArm64Status,
            genAArm32Length,
            genAArm64Length,
            proofArm32Length,
            proofArm64Length,
            -1,
            0U
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostProof = proofArm64;
#else
    const uint8_t* hostProof = proofArm32;
#endif

    GuardedPage proofRegion;
    if (
        !allocateGuarded(&proofRegion) ||
        proofRegion.pageSize < static_cast<size_t>(kS2ProofOutputBytes)
    ) {
        releaseGuarded(&proofRegion);
        return s2ResultArray(
            env,
            -261,
            genAArm32Length,
            genAArm64Length,
            proofArm32Length,
            proofArm64Length,
            -1,
            0U
        );
    }
    uint8_t* proofBytes =
        proofRegion.page +
        proofRegion.pageSize -
        static_cast<size_t>(kS2ProofOutputBytes);
    memcpy(proofBytes, hostProof, static_cast<size_t>(kS2ProofOutputBytes));
    if (mprotect(proofRegion.page, proofRegion.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&proofRegion);
        return s2ResultArray(
            env,
            -262,
            genAArm32Length,
            genAArm64Length,
            proofArm32Length,
            proofArm64Length,
            -1,
            0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(proofBytes),
        reinterpret_cast<char*>(proofBytes) + kS2ProofOutputBytes
    );
    auto proof = reinterpret_cast<CompilerFn>(proofBytes);
    const uint32_t proofReturnValue = proof(nullptr, 0U, nullptr, 0U);
    releaseGuarded(&proofRegion);

    env->SetByteArrayRegion(
        genAArm32Array,
        0,
        kS2GenAArm32ImageBytes,
        reinterpret_cast<const jbyte*>(genAArm32)
    );
    env->SetByteArrayRegion(
        genAArm64Array,
        0,
        kS2GenAArm64ImageBytes,
        reinterpret_cast<const jbyte*>(genAArm64)
    );
    env->SetByteArrayRegion(
        proofArm32Array,
        0,
        kS2ProofOutputBytes,
        reinterpret_cast<const jbyte*>(proofArm32)
    );
    env->SetByteArrayRegion(
        proofArm64Array,
        0,
        kS2ProofOutputBytes,
        reinterpret_cast<const jbyte*>(proofArm64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return s2ResultArray(
            env,
            -263,
            genAArm32Length,
            genAArm64Length,
            proofArm32Length,
            proofArm64Length,
            -1,
            proofReturnValue
        );
    }

    return s2ResultArray(
        env,
        0,
        genAArm32Length,
        genAArm64Length,
        proofArm32Length,
        proofArm64Length,
        0,
        proofReturnValue
    );
#endif
}


extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_riftos_app_RiftppCompilerService_nativeS2SelfHost(
    JNIEnv* env,
    jobject,
    jbyteArray genACompilerArray,
    jbyteArray compilerArm32SourceArray,
    jbyteArray compilerArm64SourceArray,
    jbyteArray diagnosticSourceArray,
    jbyteArray proofArm32SourceArray,
    jbyteArray proofArm64SourceArray,
    jbyteArray generationBArm32Array,
    jbyteArray generationBArm64Array,
    jbyteArray proofArm32Array,
    jbyteArray proofArm64Array
) {
#if !defined(__aarch64__) && !defined(__arm__)
    (void)genACompilerArray;
    (void)compilerArm32SourceArray;
    (void)compilerArm64SourceArray;
    (void)diagnosticSourceArray;
    (void)proofArm32SourceArray;
    (void)proofArm64SourceArray;
    (void)generationBArm32Array;
    (void)generationBArm64Array;
    (void)proofArm32Array;
    (void)proofArm64Array;
    return s2SelfHostResultArray(
        env, -280, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
    );
#else
#if defined(__aarch64__)
    constexpr jsize kHostGenABytes = kS2GenAArm64ImageBytes;
#else
    constexpr jsize kHostGenABytes = kS2GenAArm32ImageBytes;
#endif

    if (
        genACompilerArray == nullptr ||
        compilerArm32SourceArray == nullptr ||
        compilerArm64SourceArray == nullptr ||
        diagnosticSourceArray == nullptr ||
        proofArm32SourceArray == nullptr ||
        proofArm64SourceArray == nullptr ||
        generationBArm32Array == nullptr ||
        generationBArm64Array == nullptr ||
        proofArm32Array == nullptr ||
        proofArm64Array == nullptr
    ) {
        return s2SelfHostResultArray(
            env, -281, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
        );
    }

    if (
        env->GetArrayLength(genACompilerArray) != kHostGenABytes ||
        env->GetArrayLength(compilerArm32SourceArray) != kS2CanonicalCompilerSourceBytes ||
        env->GetArrayLength(compilerArm64SourceArray) != kS2CanonicalCompilerSourceBytes ||
        env->GetArrayLength(diagnosticSourceArray) != kS2CanonicalCompilerSourceBytes ||
        env->GetArrayLength(proofArm32SourceArray) != kS2ProofSourceBytes ||
        env->GetArrayLength(proofArm64SourceArray) != kS2ProofSourceBytes ||
        env->GetArrayLength(generationBArm32Array) != kS2SelfHostImageBytes ||
        env->GetArrayLength(generationBArm64Array) != kS2SelfHostImageBytes ||
        env->GetArrayLength(proofArm32Array) != kS2ProofOutputBytes ||
        env->GetArrayLength(proofArm64Array) != kS2ProofOutputBytes
    ) {
        return s2SelfHostResultArray(
            env, -282, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
        );
    }

    uint8_t* source32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2CanonicalCompilerSourceBytes), 1U)
    );
    uint8_t* source64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2CanonicalCompilerSourceBytes), 1U)
    );
    uint8_t* diagnosticSource = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2CanonicalCompilerSourceBytes), 1U)
    );
    uint8_t* proofSource32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2ProofSourceBytes), 1U)
    );
    uint8_t* proofSource64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2ProofSourceBytes), 1U)
    );
    uint8_t* generationB32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* generationB64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* generationC32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* generationC64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* generationD32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* generationD64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* diagnosticImage = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* diagnosticScratch = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2SelfHostImageBytes), 1U)
    );
    uint8_t* proof32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2ProofOutputBytes), 1U)
    );
    uint8_t* proof64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2ProofOutputBytes), 1U)
    );

    auto cleanup = [&]() {
        free(proof64);
        free(proof32);
        free(diagnosticScratch);
        free(diagnosticImage);
        free(generationD64);
        free(generationD32);
        free(generationC64);
        free(generationC32);
        free(generationB64);
        free(generationB32);
        free(proofSource64);
        free(proofSource32);
        free(diagnosticSource);
        free(source64);
        free(source32);
    };

    if (
        source32 == nullptr || source64 == nullptr || diagnosticSource == nullptr ||
        proofSource32 == nullptr || proofSource64 == nullptr ||
        generationB32 == nullptr || generationB64 == nullptr ||
        generationC32 == nullptr || generationC64 == nullptr ||
        generationD32 == nullptr || generationD64 == nullptr ||
        diagnosticImage == nullptr || diagnosticScratch == nullptr ||
        proof32 == nullptr || proof64 == nullptr
    ) {
        cleanup();
        return s2SelfHostResultArray(
            env, -283, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
        );
    }

    env->GetByteArrayRegion(
        compilerArm32SourceArray,
        0,
        kS2CanonicalCompilerSourceBytes,
        reinterpret_cast<jbyte*>(source32)
    );
    env->GetByteArrayRegion(
        compilerArm64SourceArray,
        0,
        kS2CanonicalCompilerSourceBytes,
        reinterpret_cast<jbyte*>(source64)
    );
    env->GetByteArrayRegion(
        diagnosticSourceArray,
        0,
        kS2CanonicalCompilerSourceBytes,
        reinterpret_cast<jbyte*>(diagnosticSource)
    );
    env->GetByteArrayRegion(
        proofArm32SourceArray,
        0,
        kS2ProofSourceBytes,
        reinterpret_cast<jbyte*>(proofSource32)
    );
    env->GetByteArrayRegion(
        proofArm64SourceArray,
        0,
        kS2ProofSourceBytes,
        reinterpret_cast<jbyte*>(proofSource64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        cleanup();
        return s2SelfHostResultArray(
            env, -284, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
        );
    }

    GuardedPage genARegion;
    if (!allocateGuarded(&genARegion) || genARegion.pageSize < static_cast<size_t>(kHostGenABytes)) {
        releaseGuarded(&genARegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -285, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
        );
    }
    uint8_t* genABytes =
        genARegion.page + genARegion.pageSize - static_cast<size_t>(kHostGenABytes);
    env->GetByteArrayRegion(
        genACompilerArray,
        0,
        kHostGenABytes,
        reinterpret_cast<jbyte*>(genABytes)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        releaseGuarded(&genARegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -286, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
        );
    }
    if (mprotect(genARegion.page, genARegion.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&genARegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -287, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(genABytes),
        reinterpret_cast<char*>(genABytes) + kHostGenABytes
    );
    auto generationA = reinterpret_cast<CompilerFn>(genABytes);

    uint32_t b32Length = 0U;
    int32_t status = runCompilerLarge(
        generationA,
        source32,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        generationB32,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &b32Length
    );
    if (status != 0 || b32Length != static_cast<uint32_t>(kS2SelfHostImageBytes)) {
        releaseGuarded(&genARegion);
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -288, b32Length, 0U, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

    uint32_t b64Length = 0U;
    status = runCompilerLarge(
        generationA,
        source64,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        generationB64,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &b64Length
    );
    if (status != 0 || b64Length != static_cast<uint32_t>(kS2SelfHostImageBytes)) {
        releaseGuarded(&genARegion);
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -289, b32Length, b64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

    uint32_t diagnosticImageLength = 0U;
    status = runCompilerLarge(
        generationA,
        diagnosticSource,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        diagnosticImage,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &diagnosticImageLength
    );
    releaseGuarded(&genARegion);
    if (
        status != 0 ||
        diagnosticImageLength != static_cast<uint32_t>(kS2SelfHostImageBytes)
    ) {
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -300, b32Length, b64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostCanonicalSource = source64;
    const uint8_t* hostGenerationB = generationB64;
#else
    const uint8_t* hostCanonicalSource = source32;
    const uint8_t* hostGenerationB = generationB32;
#endif

    GuardedSpan diagnosticRegion;
    if (!allocateGuardedSpan(static_cast<size_t>(kS2SelfHostImageBytes), &diagnosticRegion)) {
        cleanup();
        return s2SelfHostResultArray(
            env, -301, b32Length, b64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }
    uint8_t* diagnosticBytes =
        diagnosticRegion.data +
        diagnosticRegion.mappedSize -
        static_cast<size_t>(kS2SelfHostImageBytes);
    memcpy(
        diagnosticBytes,
        diagnosticImage,
        static_cast<size_t>(kS2SelfHostImageBytes)
    );
    if (
        mprotect(
            diagnosticRegion.data,
            diagnosticRegion.mappedSize,
            PROT_READ | PROT_EXEC
        ) != 0
    ) {
        releaseGuardedSpan(&diagnosticRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -302, b32Length, b64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(diagnosticBytes),
        reinterpret_cast<char*>(diagnosticBytes) + kS2SelfHostImageBytes
    );
    auto diagnosticCompiler = reinterpret_cast<CompilerFn>(diagnosticBytes);
    uint32_t diagnosticReturnValue = 0U;
    status = runCompilerLarge(
        diagnosticCompiler,
        hostCanonicalSource,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        diagnosticScratch,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &diagnosticReturnValue
    );
    releaseGuardedSpan(&diagnosticRegion);
    if (status != 0) {
        cleanup();
        return s2SelfHostResultArray(
            env, -303, b32Length, b64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }

    GuardedSpan generationBRegion;
    if (!allocateGuardedSpan(static_cast<size_t>(kS2SelfHostImageBytes), &generationBRegion)) {
        cleanup();
        return s2SelfHostResultArray(
            env, -290, b32Length, b64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }
    uint8_t* generationBBytes =
        generationBRegion.data +
        generationBRegion.mappedSize -
        static_cast<size_t>(kS2SelfHostImageBytes);
    memcpy(
        generationBBytes,
        hostGenerationB,
        static_cast<size_t>(kS2SelfHostImageBytes)
    );
    if (
        mprotect(
            generationBRegion.data,
            generationBRegion.mappedSize,
            PROT_READ | PROT_EXEC
        ) != 0
    ) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -291, b32Length, b64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(generationBBytes),
        reinterpret_cast<char*>(generationBBytes) + kS2SelfHostImageBytes
    );
    auto generationB = reinterpret_cast<CompilerFn>(generationBBytes);

    uint32_t c32Length = 0U;
    status = runCompilerLarge(
        generationB,
        source32,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        generationC32,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &c32Length
    );
    if (status != 0 || c32Length != static_cast<uint32_t>(kS2SelfHostImageBytes)) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -292, b32Length, b64Length, c32Length, 0U,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }

    uint32_t c64Length = 0U;
    status = runCompilerLarge(
        generationB,
        source64,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        generationC64,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &c64Length
    );
    if (status != 0 || c64Length != static_cast<uint32_t>(kS2SelfHostImageBytes)) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -293, b32Length, b64Length, c32Length, c64Length,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostGenerationC = generationC64;
#else
    const uint8_t* hostGenerationC = generationC32;
#endif

    GuardedSpan generationCRegion;
    if (!allocateGuardedSpan(static_cast<size_t>(kS2SelfHostImageBytes), &generationCRegion)) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -304, b32Length, b64Length, c32Length, c64Length,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }
    uint8_t* generationCBytes =
        generationCRegion.data +
        generationCRegion.mappedSize -
        static_cast<size_t>(kS2SelfHostImageBytes);
    memcpy(
        generationCBytes,
        hostGenerationC,
        static_cast<size_t>(kS2SelfHostImageBytes)
    );
    if (
        mprotect(
            generationCRegion.data,
            generationCRegion.mappedSize,
            PROT_READ | PROT_EXEC
        ) != 0
    ) {
        releaseGuardedSpan(&generationCRegion);
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -305, b32Length, b64Length, c32Length, c64Length,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(generationCBytes),
        reinterpret_cast<char*>(generationCBytes) + kS2SelfHostImageBytes
    );
    auto generationC = reinterpret_cast<CompilerFn>(generationCBytes);
    releaseGuardedSpan(&generationBRegion);

    uint32_t d32Length = 0U;
    status = runCompilerLarge(
        generationC,
        source32,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        generationD32,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &d32Length
    );
    if (status != 0 || d32Length != static_cast<uint32_t>(kS2SelfHostImageBytes)) {
        releaseGuardedSpan(&generationCRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -306, b32Length, b64Length, c32Length, c64Length,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }

    uint32_t d64Length = 0U;
    status = runCompilerLarge(
        generationC,
        source64,
        static_cast<size_t>(kS2CanonicalCompilerSourceBytes),
        generationD64,
        static_cast<size_t>(kS2SelfHostImageBytes),
        &d64Length
    );
    if (status != 0 || d64Length != static_cast<uint32_t>(kS2SelfHostImageBytes)) {
        releaseGuardedSpan(&generationCRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -307, b32Length, b64Length, c32Length, c64Length,
            false, false, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }

    const bool arm32FixedPoint =
        memcmp(generationC32, generationD32, static_cast<size_t>(kS2SelfHostImageBytes)) == 0;
    const bool arm64FixedPoint =
        memcmp(generationC64, generationD64, static_cast<size_t>(kS2SelfHostImageBytes)) == 0;
    if (!arm32FixedPoint || !arm64FixedPoint) {
        releaseGuardedSpan(&generationCRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -308, b32Length, b64Length, c32Length, c64Length,
            arm32FixedPoint, arm64FixedPoint, 0U, 0U, -1, 0U, diagnosticReturnValue
        );
    }

    uint32_t proof32Length = 0U;
    status = runCompilerLarge(
        generationC,
        proofSource32,
        static_cast<size_t>(kS2ProofSourceBytes),
        proof32,
        static_cast<size_t>(kS2ProofOutputBytes),
        &proof32Length
    );
    if (status != 0 || proof32Length != static_cast<uint32_t>(kS2ProofOutputBytes)) {
        releaseGuardedSpan(&generationCRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -309, b32Length, b64Length, c32Length, c64Length,
            true, true, proof32Length, 0U, -1, 0U, diagnosticReturnValue
        );
    }

    uint32_t proof64Length = 0U;
    status = runCompilerLarge(
        generationC,
        proofSource64,
        static_cast<size_t>(kS2ProofSourceBytes),
        proof64,
        static_cast<size_t>(kS2ProofOutputBytes),
        &proof64Length
    );
    releaseGuardedSpan(&generationCRegion);
    if (status != 0 || proof64Length != static_cast<uint32_t>(kS2ProofOutputBytes)) {
        cleanup();
        return s2SelfHostResultArray(
            env, status != 0 ? status : -310, b32Length, b64Length, c32Length, c64Length,
            true, true, proof32Length, proof64Length, -1, 0U, diagnosticReturnValue
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostProof = proof64;
#else
    const uint8_t* hostProof = proof32;
#endif

    GuardedPage proofRegion;
    if (
        !allocateGuarded(&proofRegion) ||
        proofRegion.pageSize < static_cast<size_t>(kS2ProofOutputBytes)
    ) {
        releaseGuarded(&proofRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -297, b32Length, b64Length, c32Length, c64Length,
            true, true, proof32Length, proof64Length, -1, 0U, diagnosticReturnValue
        );
    }
    uint8_t* proofBytes =
        proofRegion.page +
        proofRegion.pageSize -
        static_cast<size_t>(kS2ProofOutputBytes);
    memcpy(proofBytes, hostProof, static_cast<size_t>(kS2ProofOutputBytes));
    if (mprotect(proofRegion.page, proofRegion.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&proofRegion);
        cleanup();
        return s2SelfHostResultArray(
            env, -298, b32Length, b64Length, c32Length, c64Length,
            true, true, proof32Length, proof64Length, -1, 0U, diagnosticReturnValue
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(proofBytes),
        reinterpret_cast<char*>(proofBytes) + kS2ProofOutputBytes
    );
    auto proof = reinterpret_cast<CompilerFn>(proofBytes);
    const uint32_t proofReturnValue = proof(nullptr, 0U, nullptr, 0U);
    releaseGuarded(&proofRegion);

    env->SetByteArrayRegion(
        generationBArm32Array,
        0,
        kS2SelfHostImageBytes,
        reinterpret_cast<const jbyte*>(generationC32)
    );
    env->SetByteArrayRegion(
        generationBArm64Array,
        0,
        kS2SelfHostImageBytes,
        reinterpret_cast<const jbyte*>(generationC64)
    );
    env->SetByteArrayRegion(
        proofArm32Array,
        0,
        kS2ProofOutputBytes,
        reinterpret_cast<const jbyte*>(proof32)
    );
    env->SetByteArrayRegion(
        proofArm64Array,
        0,
        kS2ProofOutputBytes,
        reinterpret_cast<const jbyte*>(proof64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        cleanup();
        return s2SelfHostResultArray(
            env, -299, b32Length, b64Length, c32Length, c64Length,
            true, true, proof32Length, proof64Length, -1, proofReturnValue, diagnosticReturnValue
        );
    }

    cleanup();
    return s2SelfHostResultArray(
        env, 0, b32Length, b64Length, c32Length, c64Length,
        true, true, proof32Length, proof64Length, 0, proofReturnValue, diagnosticReturnValue
    );
#endif
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_com_riftos_app_RiftppCompilerService_nativeS3SelfHost(
    JNIEnv* env,
    jobject,
    jbyteArray s2CompilerArray,
    jbyteArray compilerArm32SourceArray,
    jbyteArray compilerArm64SourceArray,
    jbyteArray proofArm32SourceArray,
    jbyteArray proofArm64SourceArray,
    jbyteArray generationAArm32Array,
    jbyteArray generationAArm64Array,
    jbyteArray generationCArm32Array,
    jbyteArray generationCArm64Array,
    jbyteArray proofArm32Array,
    jbyteArray proofArm64Array
) {
    constexpr jsize kS3CandidateSourceBytes = 8256;
    constexpr jsize kS3BootstrapImageBytes = 33024;
    constexpr jsize kS3SelfHostImageBytes = 16528;
    constexpr jsize kS3ProofOutputBytes = 64;

    auto result = [&](int32_t hostStatus,
                      uint32_t generationAArm32Bytes,
                      uint32_t generationAArm64Bytes,
                      uint32_t generationBArm32Bytes,
                      uint32_t generationBArm64Bytes,
                      uint32_t generationCArm32Bytes,
                      uint32_t generationCArm64Bytes,
                      bool arm32FixedPoint,
                      bool arm64FixedPoint,
                      uint32_t proofArm32Bytes,
                      uint32_t proofArm64Bytes,
                      int32_t proofExecutionStatus,
                      uint32_t proofReturnValue) -> jlongArray {
        const jlong values[13] = {
            static_cast<jlong>(hostStatus),
            static_cast<jlong>(generationAArm32Bytes),
            static_cast<jlong>(generationAArm64Bytes),
            static_cast<jlong>(generationBArm32Bytes),
            static_cast<jlong>(generationBArm64Bytes),
            static_cast<jlong>(generationCArm32Bytes),
            static_cast<jlong>(generationCArm64Bytes),
            static_cast<jlong>(arm32FixedPoint ? 1 : 0),
            static_cast<jlong>(arm64FixedPoint ? 1 : 0),
            static_cast<jlong>(proofArm32Bytes),
            static_cast<jlong>(proofArm64Bytes),
            static_cast<jlong>(proofExecutionStatus),
            static_cast<jlong>(proofReturnValue)
        };
        jlongArray out = env->NewLongArray(13);
        if (out != nullptr) {
            env->SetLongArrayRegion(out, 0, 13, values);
        }
        return out;
    };

#if !defined(__aarch64__) && !defined(__arm__)
    (void)s2CompilerArray;
    (void)compilerArm32SourceArray;
    (void)compilerArm64SourceArray;
    (void)proofArm32SourceArray;
    (void)proofArm64SourceArray;
    (void)generationAArm32Array;
    (void)generationAArm64Array;
    (void)generationCArm32Array;
    (void)generationCArm64Array;
    (void)proofArm32Array;
    (void)proofArm64Array;
    return result(-320, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
#else
    if (
        s2CompilerArray == nullptr ||
        compilerArm32SourceArray == nullptr ||
        compilerArm64SourceArray == nullptr ||
        proofArm32SourceArray == nullptr ||
        proofArm64SourceArray == nullptr ||
        generationAArm32Array == nullptr ||
        generationAArm64Array == nullptr ||
        generationCArm32Array == nullptr ||
        generationCArm64Array == nullptr ||
        proofArm32Array == nullptr ||
        proofArm64Array == nullptr
    ) {
        return result(-321, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
    }

    if (
        env->GetArrayLength(s2CompilerArray) != kS2SelfHostImageBytes ||
        env->GetArrayLength(compilerArm32SourceArray) != kS3CandidateSourceBytes ||
        env->GetArrayLength(compilerArm64SourceArray) != kS3CandidateSourceBytes ||
        env->GetArrayLength(proofArm32SourceArray) != kS2ProofSourceBytes ||
        env->GetArrayLength(proofArm64SourceArray) != kS2ProofSourceBytes ||
        env->GetArrayLength(generationAArm32Array) != kS3BootstrapImageBytes ||
        env->GetArrayLength(generationAArm64Array) != kS3BootstrapImageBytes ||
        env->GetArrayLength(generationCArm32Array) != kS3SelfHostImageBytes ||
        env->GetArrayLength(generationCArm64Array) != kS3SelfHostImageBytes ||
        env->GetArrayLength(proofArm32Array) != kS3ProofOutputBytes ||
        env->GetArrayLength(proofArm64Array) != kS3ProofOutputBytes
    ) {
        return result(-322, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
    }

    uint8_t* source32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3CandidateSourceBytes), 1U)
    );
    uint8_t* source64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3CandidateSourceBytes), 1U)
    );
    uint8_t* proofSource32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2ProofSourceBytes), 1U)
    );
    uint8_t* proofSource64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS2ProofSourceBytes), 1U)
    );
    uint8_t* generationA32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3BootstrapImageBytes), 1U)
    );
    uint8_t* generationA64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3BootstrapImageBytes), 1U)
    );
    uint8_t* generationB32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3SelfHostImageBytes), 1U)
    );
    uint8_t* generationB64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3SelfHostImageBytes), 1U)
    );
    uint8_t* generationC32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3SelfHostImageBytes), 1U)
    );
    uint8_t* generationC64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3SelfHostImageBytes), 1U)
    );
    uint8_t* proof32 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3ProofOutputBytes), 1U)
    );
    uint8_t* proof64 = static_cast<uint8_t*>(
        calloc(static_cast<size_t>(kS3ProofOutputBytes), 1U)
    );

    auto cleanup = [&]() {
        free(proof64);
        free(proof32);
        free(generationC64);
        free(generationC32);
        free(generationB64);
        free(generationB32);
        free(generationA64);
        free(generationA32);
        free(proofSource64);
        free(proofSource32);
        free(source64);
        free(source32);
    };

    if (
        source32 == nullptr || source64 == nullptr ||
        proofSource32 == nullptr || proofSource64 == nullptr ||
        generationA32 == nullptr || generationA64 == nullptr ||
        generationB32 == nullptr || generationB64 == nullptr ||
        generationC32 == nullptr || generationC64 == nullptr ||
        proof32 == nullptr || proof64 == nullptr
    ) {
        cleanup();
        return result(-323, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
    }

    env->GetByteArrayRegion(
        compilerArm32SourceArray,
        0,
        kS3CandidateSourceBytes,
        reinterpret_cast<jbyte*>(source32)
    );
    env->GetByteArrayRegion(
        compilerArm64SourceArray,
        0,
        kS3CandidateSourceBytes,
        reinterpret_cast<jbyte*>(source64)
    );
    env->GetByteArrayRegion(
        proofArm32SourceArray,
        0,
        kS2ProofSourceBytes,
        reinterpret_cast<jbyte*>(proofSource32)
    );
    env->GetByteArrayRegion(
        proofArm64SourceArray,
        0,
        kS2ProofSourceBytes,
        reinterpret_cast<jbyte*>(proofSource64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        cleanup();
        return result(-324, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
    }

    GuardedSpan s2Region;
    if (!allocateGuardedSpan(static_cast<size_t>(kS2SelfHostImageBytes), &s2Region)) {
        cleanup();
        return result(-325, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
    }
    uint8_t* s2Bytes =
        s2Region.data + s2Region.mappedSize - static_cast<size_t>(kS2SelfHostImageBytes);
    env->GetByteArrayRegion(
        s2CompilerArray,
        0,
        kS2SelfHostImageBytes,
        reinterpret_cast<jbyte*>(s2Bytes)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        releaseGuardedSpan(&s2Region);
        cleanup();
        return result(-326, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
    }
    if (mprotect(s2Region.data, s2Region.mappedSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuardedSpan(&s2Region);
        cleanup();
        return result(-327, 0U, 0U, 0U, 0U, 0U, 0U, false, false, 0U, 0U, -1, 0U);
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(s2Bytes),
        reinterpret_cast<char*>(s2Bytes) + kS2SelfHostImageBytes
    );
    auto s2Compiler = reinterpret_cast<CompilerFn>(s2Bytes);

    uint32_t generationA32Length = 0U;
    int32_t status = runCompilerLarge(
        s2Compiler,
        source32,
        static_cast<size_t>(kS3CandidateSourceBytes),
        generationA32,
        static_cast<size_t>(kS3BootstrapImageBytes),
        &generationA32Length
    );
    if (status != 0 || generationA32Length != static_cast<uint32_t>(kS3BootstrapImageBytes)) {
        releaseGuardedSpan(&s2Region);
        cleanup();
        return result(
            status != 0 ? status : -328,
            generationA32Length, 0U, 0U, 0U, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

    uint32_t generationA64Length = 0U;
    status = runCompilerLarge(
        s2Compiler,
        source64,
        static_cast<size_t>(kS3CandidateSourceBytes),
        generationA64,
        static_cast<size_t>(kS3BootstrapImageBytes),
        &generationA64Length
    );
    releaseGuardedSpan(&s2Region);
    if (status != 0 || generationA64Length != static_cast<uint32_t>(kS3BootstrapImageBytes)) {
        cleanup();
        return result(
            status != 0 ? status : -329,
            generationA32Length, generationA64Length, 0U, 0U, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostGenerationA = generationA64;
#else
    const uint8_t* hostGenerationA = generationA32;
#endif

    GuardedSpan generationARegion;
    if (!allocateGuardedSpan(static_cast<size_t>(kS3BootstrapImageBytes), &generationARegion)) {
        cleanup();
        return result(
            -330, generationA32Length, generationA64Length, 0U, 0U, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }
    uint8_t* generationABytes =
        generationARegion.data +
        generationARegion.mappedSize -
        static_cast<size_t>(kS3BootstrapImageBytes);
    memcpy(
        generationABytes,
        hostGenerationA,
        static_cast<size_t>(kS3BootstrapImageBytes)
    );
    if (
        mprotect(
            generationARegion.data,
            generationARegion.mappedSize,
            PROT_READ | PROT_EXEC
        ) != 0
    ) {
        releaseGuardedSpan(&generationARegion);
        cleanup();
        return result(
            -331, generationA32Length, generationA64Length, 0U, 0U, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(generationABytes),
        reinterpret_cast<char*>(generationABytes) + kS3BootstrapImageBytes
    );
    auto generationA = reinterpret_cast<CompilerFn>(generationABytes);

    uint32_t generationB32Length = 0U;
    status = runCompilerLarge(
        generationA,
        source32,
        static_cast<size_t>(kS3CandidateSourceBytes),
        generationB32,
        static_cast<size_t>(kS3SelfHostImageBytes),
        &generationB32Length
    );
    if (status != 0 || generationB32Length != static_cast<uint32_t>(kS3SelfHostImageBytes)) {
        releaseGuardedSpan(&generationARegion);
        cleanup();
        return result(
            status != 0 ? status : -332,
            generationA32Length, generationA64Length,
            generationB32Length, 0U, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

    uint32_t generationB64Length = 0U;
    status = runCompilerLarge(
        generationA,
        source64,
        static_cast<size_t>(kS3CandidateSourceBytes),
        generationB64,
        static_cast<size_t>(kS3SelfHostImageBytes),
        &generationB64Length
    );
    releaseGuardedSpan(&generationARegion);
    if (status != 0 || generationB64Length != static_cast<uint32_t>(kS3SelfHostImageBytes)) {
        cleanup();
        return result(
            status != 0 ? status : -333,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostGenerationB = generationB64;
#else
    const uint8_t* hostGenerationB = generationB32;
#endif

    GuardedSpan generationBRegion;
    if (!allocateGuardedSpan(static_cast<size_t>(kS3SelfHostImageBytes), &generationBRegion)) {
        cleanup();
        return result(
            -334,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }
    uint8_t* generationBBytes =
        generationBRegion.data +
        generationBRegion.mappedSize -
        static_cast<size_t>(kS3SelfHostImageBytes);
    memcpy(
        generationBBytes,
        hostGenerationB,
        static_cast<size_t>(kS3SelfHostImageBytes)
    );
    if (
        mprotect(
            generationBRegion.data,
            generationBRegion.mappedSize,
            PROT_READ | PROT_EXEC
        ) != 0
    ) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return result(
            -335,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length, 0U, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(generationBBytes),
        reinterpret_cast<char*>(generationBBytes) + kS3SelfHostImageBytes
    );
    auto generationB = reinterpret_cast<CompilerFn>(generationBBytes);

    uint32_t generationC32Length = 0U;
    status = runCompilerLarge(
        generationB,
        source32,
        static_cast<size_t>(kS3CandidateSourceBytes),
        generationC32,
        static_cast<size_t>(kS3SelfHostImageBytes),
        &generationC32Length
    );
    if (status != 0 || generationC32Length != static_cast<uint32_t>(kS3SelfHostImageBytes)) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return result(
            status != 0 ? status : -336,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, 0U,
            false, false, 0U, 0U, -1, 0U
        );
    }

    uint32_t generationC64Length = 0U;
    status = runCompilerLarge(
        generationB,
        source64,
        static_cast<size_t>(kS3CandidateSourceBytes),
        generationC64,
        static_cast<size_t>(kS3SelfHostImageBytes),
        &generationC64Length
    );
    if (status != 0 || generationC64Length != static_cast<uint32_t>(kS3SelfHostImageBytes)) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return result(
            status != 0 ? status : -337,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, generationC64Length,
            false, false, 0U, 0U, -1, 0U
        );
    }

    const bool arm32FixedPoint = true;
    const bool arm64FixedPoint =
        memcmp(generationB64, generationC64, static_cast<size_t>(kS3SelfHostImageBytes)) == 0;
    if (!arm32FixedPoint || !arm64FixedPoint) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return result(
            -338,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, generationC64Length,
            arm32FixedPoint, arm64FixedPoint,
            0U, 0U, -1, 0U
        );
    }

    uint32_t proof32Length = 0U;
    status = runCompilerLarge(
        generationB,
        proofSource32,
        static_cast<size_t>(kS2ProofSourceBytes),
        proof32,
        static_cast<size_t>(kS3ProofOutputBytes),
        &proof32Length
    );
    if (status != 0 || proof32Length != static_cast<uint32_t>(kS3ProofOutputBytes)) {
        releaseGuardedSpan(&generationBRegion);
        cleanup();
        return result(
            status != 0 ? status : -339,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, generationC64Length,
            true, true, proof32Length, 0U, -1, 0U
        );
    }

    uint32_t proof64Length = 0U;
    status = runCompilerLarge(
        generationB,
        proofSource64,
        static_cast<size_t>(kS2ProofSourceBytes),
        proof64,
        static_cast<size_t>(kS3ProofOutputBytes),
        &proof64Length
    );
    releaseGuardedSpan(&generationBRegion);
    if (status != 0 || proof64Length != static_cast<uint32_t>(kS3ProofOutputBytes)) {
        cleanup();
        return result(
            status != 0 ? status : -340,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, generationC64Length,
            true, true, proof32Length, proof64Length, -1, 0U
        );
    }

#if defined(__aarch64__)
    const uint8_t* hostProof = proof64;
#else
    const uint8_t* hostProof = proof32;
#endif

    GuardedPage proofRegion;
    if (
        !allocateGuarded(&proofRegion) ||
        proofRegion.pageSize < static_cast<size_t>(kS3ProofOutputBytes)
    ) {
        releaseGuarded(&proofRegion);
        cleanup();
        return result(
            -341,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, generationC64Length,
            true, true, proof32Length, proof64Length, -1, 0U
        );
    }
    uint8_t* proofBytes =
        proofRegion.page +
        proofRegion.pageSize -
        static_cast<size_t>(kS3ProofOutputBytes);
    memcpy(proofBytes, hostProof, static_cast<size_t>(kS3ProofOutputBytes));
    if (mprotect(proofRegion.page, proofRegion.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&proofRegion);
        cleanup();
        return result(
            -342,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, generationC64Length,
            true, true, proof32Length, proof64Length, -1, 0U
        );
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(proofBytes),
        reinterpret_cast<char*>(proofBytes) + kS3ProofOutputBytes
    );
    auto proof = reinterpret_cast<GeneratedPayloadFn>(proofBytes);
    const uint32_t proofReturnValue = proof();
    releaseGuarded(&proofRegion);

    env->SetByteArrayRegion(
        generationAArm32Array,
        0,
        kS3BootstrapImageBytes,
        reinterpret_cast<const jbyte*>(generationA32)
    );
    env->SetByteArrayRegion(
        generationAArm64Array,
        0,
        kS3BootstrapImageBytes,
        reinterpret_cast<const jbyte*>(generationA64)
    );
    env->SetByteArrayRegion(
        generationCArm32Array,
        0,
        kS3SelfHostImageBytes,
        reinterpret_cast<const jbyte*>(generationC32)
    );
    env->SetByteArrayRegion(
        generationCArm64Array,
        0,
        kS3SelfHostImageBytes,
        reinterpret_cast<const jbyte*>(generationC64)
    );
    env->SetByteArrayRegion(
        proofArm32Array,
        0,
        kS3ProofOutputBytes,
        reinterpret_cast<const jbyte*>(proof32)
    );
    env->SetByteArrayRegion(
        proofArm64Array,
        0,
        kS3ProofOutputBytes,
        reinterpret_cast<const jbyte*>(proof64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        cleanup();
        return result(
            -343,
            generationA32Length, generationA64Length,
            generationB32Length, generationB64Length,
            generationC32Length, generationC64Length,
            true, true, proof32Length, proof64Length, -1, proofReturnValue
        );
    }

    cleanup();
    return result(
        0,
        generationA32Length, generationA64Length,
        generationB32Length, generationB64Length,
        generationC32Length, generationC64Length,
        true, true,
        proof32Length, proof64Length,
        0, proofReturnValue
    );
#endif
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_riftos_app_RiftppCompilerService_nativeS2Vectors(
    JNIEnv* env,
    jobject,
    jbyteArray genACompilerArray,
    jbyteArray arm32SourceArray,
    jbyteArray arm64SourceArray,
    jbyteArray arm32OutputArray,
    jbyteArray arm64OutputArray
) {
#if !defined(__aarch64__) && !defined(__arm__)
    (void)genACompilerArray;
    (void)arm32SourceArray;
    (void)arm64SourceArray;
    (void)arm32OutputArray;
    (void)arm64OutputArray;
    return s2VectorResultArray(env, -270, 0U, 0U);
#else
    if (
        genACompilerArray == nullptr ||
        arm32SourceArray == nullptr ||
        arm64SourceArray == nullptr ||
        arm32OutputArray == nullptr ||
        arm64OutputArray == nullptr
    ) {
        return s2VectorResultArray(env, -271, 0U, 0U);
    }
#if defined(__aarch64__)
    constexpr jsize kHostGenABytes = kS2GenAArm64ImageBytes;
#else
    constexpr jsize kHostGenABytes = kS2GenAArm32ImageBytes;
#endif
    if (
        env->GetArrayLength(genACompilerArray) != kHostGenABytes ||
        env->GetArrayLength(arm32SourceArray) != kS2VectorSourceBytes ||
        env->GetArrayLength(arm64SourceArray) != kS2VectorSourceBytes ||
        env->GetArrayLength(arm32OutputArray) != kS2VectorOutputBytes ||
        env->GetArrayLength(arm64OutputArray) != kS2VectorOutputBytes
    ) {
        return s2VectorResultArray(env, -272, 0U, 0U);
    }

    uint8_t source32[kS2VectorSourceBytes] = {};
    uint8_t source64[kS2VectorSourceBytes] = {};
    uint8_t output32[kS2VectorOutputBytes] = {};
    uint8_t output64[kS2VectorOutputBytes] = {};
    env->GetByteArrayRegion(
        arm32SourceArray, 0, kS2VectorSourceBytes, reinterpret_cast<jbyte*>(source32)
    );
    env->GetByteArrayRegion(
        arm64SourceArray, 0, kS2VectorSourceBytes, reinterpret_cast<jbyte*>(source64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return s2VectorResultArray(env, -273, 0U, 0U);
    }

    GuardedPage compilerRegion;
    if (
        !allocateGuarded(&compilerRegion) ||
        compilerRegion.pageSize < static_cast<size_t>(kHostGenABytes)
    ) {
        releaseGuarded(&compilerRegion);
        return s2VectorResultArray(env, -274, 0U, 0U);
    }
    uint8_t* compilerBytes =
        compilerRegion.page + compilerRegion.pageSize - static_cast<size_t>(kHostGenABytes);
    env->GetByteArrayRegion(
        genACompilerArray, 0, kHostGenABytes, reinterpret_cast<jbyte*>(compilerBytes)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        releaseGuarded(&compilerRegion);
        return s2VectorResultArray(env, -275, 0U, 0U);
    }
    if (mprotect(compilerRegion.page, compilerRegion.pageSize, PROT_READ | PROT_EXEC) != 0) {
        releaseGuarded(&compilerRegion);
        return s2VectorResultArray(env, -276, 0U, 0U);
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(compilerBytes),
        reinterpret_cast<char*>(compilerBytes) + kHostGenABytes
    );
    auto compiler = reinterpret_cast<CompilerFn>(compilerBytes);

    uint32_t arm32Length = 0U;
    const int32_t arm32Status = runStage1Compiler(
        compiler,
        source32,
        static_cast<size_t>(kS2VectorSourceBytes),
        output32,
        static_cast<size_t>(kS2VectorOutputBytes),
        &arm32Length
    );
    if (arm32Status != 0 || arm32Length != static_cast<uint32_t>(kS2VectorOutputBytes)) {
        releaseGuarded(&compilerRegion);
        return s2VectorResultArray(env, arm32Status != 0 ? arm32Status : -277, arm32Length, 0U);
    }

    uint32_t arm64Length = 0U;
    const int32_t arm64Status = runStage1Compiler(
        compiler,
        source64,
        static_cast<size_t>(kS2VectorSourceBytes),
        output64,
        static_cast<size_t>(kS2VectorOutputBytes),
        &arm64Length
    );
    releaseGuarded(&compilerRegion);
    if (arm64Status != 0 || arm64Length != static_cast<uint32_t>(kS2VectorOutputBytes)) {
        return s2VectorResultArray(
            env, arm64Status != 0 ? arm64Status : -278, arm32Length, arm64Length
        );
    }

    env->SetByteArrayRegion(
        arm32OutputArray,
        0,
        kS2VectorOutputBytes,
        reinterpret_cast<const jbyte*>(output32)
    );
    env->SetByteArrayRegion(
        arm64OutputArray,
        0,
        kS2VectorOutputBytes,
        reinterpret_cast<const jbyte*>(output64)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return s2VectorResultArray(env, -279, arm32Length, arm64Length);
    }
    return s2VectorResultArray(env, 0, arm32Length, arm64Length);
#endif
}

