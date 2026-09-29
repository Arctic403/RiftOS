#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <new>

namespace {

using ProgramFn = uint32_t (*)(const uint8_t*, uint32_t, uint8_t*, uint32_t);

struct ExecRegion {
    uint8_t* base = nullptr;
    size_t mapped = 0;
};

bool allocateExec(size_t payloadSize, ExecRegion* out) {
    if (out == nullptr || payloadSize == 0) return false;
    const long rawPage = sysconf(_SC_PAGESIZE);
    if (rawPage <= 0) return false;
    const size_t page = static_cast<size_t>(rawPage);
    const size_t mapped = ((payloadSize + page - 1U) / page) * page;
    void* memory = mmap(
        nullptr,
        mapped,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (memory == MAP_FAILED) return false;
    out->base = static_cast<uint8_t*>(memory);
    out->mapped = mapped;
    return true;
}

void releaseExec(ExecRegion* region) {
    if (region != nullptr && region->base != nullptr && region->mapped > 0) {
        munmap(region->base, region->mapped);
        region->base = nullptr;
        region->mapped = 0;
    }
}

}  // namespace

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_riftpp_editor_RiftppNativeBridge_run(
    JNIEnv* env,
    jobject,
    jbyteArray programArray,
    jbyteArray inputArray,
    jint outputCapacity
) {
#if !defined(__arm__) && !defined(__aarch64__)
    (void)env;
    (void)programArray;
    (void)inputArray;
    (void)outputCapacity;
    return nullptr;
#else
    if (programArray == nullptr || inputArray == nullptr || outputCapacity <= 0) {
        return nullptr;
    }

    const jsize programSize = env->GetArrayLength(programArray);
    const jsize inputSize = env->GetArrayLength(inputArray);
    if (programSize <= 0 || inputSize < 0) return nullptr;
    if (programSize > 1024 * 1024 || outputCapacity > 4 * 1024 * 1024) return nullptr;

    ExecRegion region;
    if (!allocateExec(static_cast<size_t>(programSize), &region)) return nullptr;

    env->GetByteArrayRegion(
        programArray,
        0,
        programSize,
        reinterpret_cast<jbyte*>(region.base)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        releaseExec(&region);
        return nullptr;
    }

    if (mprotect(region.base, region.mapped, PROT_READ | PROT_EXEC) != 0) {
        releaseExec(&region);
        return nullptr;
    }
    __builtin___clear_cache(
        reinterpret_cast<char*>(region.base),
        reinterpret_cast<char*>(region.base) + programSize
    );

    uint8_t* input = nullptr;
    if (inputSize > 0) {
        input = new (std::nothrow) uint8_t[static_cast<size_t>(inputSize)];
        if (input == nullptr) {
            releaseExec(&region);
            return nullptr;
        }
        env->GetByteArrayRegion(
            inputArray,
            0,
            inputSize,
            reinterpret_cast<jbyte*>(input)
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            delete[] input;
            releaseExec(&region);
            return nullptr;
        }
    }

    uint8_t* output = new (std::nothrow) uint8_t[static_cast<size_t>(outputCapacity)]();
    if (output == nullptr) {
        delete[] input;
        releaseExec(&region);
        return nullptr;
    }

    auto program = reinterpret_cast<ProgramFn>(region.base);
    const uint32_t returned = program(
        input,
        static_cast<uint32_t>(inputSize),
        output,
        static_cast<uint32_t>(outputCapacity)
    );

    jbyteArray result = nullptr;
    if (returned != 0xffffffffU && returned <= static_cast<uint32_t>(outputCapacity)) {
        result = env->NewByteArray(static_cast<jsize>(returned));
        if (result != nullptr && returned > 0) {
            env->SetByteArrayRegion(
                result,
                0,
                static_cast<jsize>(returned),
                reinterpret_cast<const jbyte*>(output)
            );
        }
    }

    delete[] output;
    delete[] input;
    releaseExec(&region);
    return result;
#endif
}
