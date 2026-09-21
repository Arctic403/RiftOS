#include <jni.h>

#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

struct VmContext {
    const uint8_t* source;
    uint32_t sourceLength;
    uint8_t* output;
    uint32_t outputCapacity;
    uint32_t result;
};

#if defined(__arm__)
static_assert(sizeof(void*) == 4, "E0 VM bridge requires ARM32");
static_assert(sizeof(VmContext) == 20, "VM1 context layout drift");
#endif

using VmFn = int32_t (*)(const uint8_t*, uint32_t, VmContext*, uint32_t);

constexpr jsize kMaxVmBytes = 64 * 1024;
constexpr jsize kMaxProgramBytes = 64 * 1024;
constexpr jsize kMaxSourceBytes = 1024 * 1024;
constexpr jsize kMaxOutputBytes = 1024 * 1024;
constexpr jint kMaxSteps = 10 * 1000 * 1000;

void* mapExecutable(
    const uint8_t* bytes,
    size_t size,
    size_t* mappedSize
) {
    if (bytes == nullptr || size == 0 || mappedSize == nullptr) {
        return nullptr;
    }

    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) {
        return nullptr;
    }

    const size_t pageSize = static_cast<size_t>(page);
    const size_t rounded =
        ((size + pageSize - 1U) / pageSize) * pageSize;

    void* memory = mmap(
        nullptr,
        rounded,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );

    if (memory == MAP_FAILED) {
        return nullptr;
    }

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

jintArray resultArray(
    JNIEnv* env,
    jint status,
    jint result
) {
    jint values[2] = { status, result };
    jintArray output = env->NewIntArray(2);
    if (output != nullptr) {
        env->SetIntArrayRegion(output, 0, 2, values);
    }
    return output;
}

}  // namespace

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_codynex_editorapp_Vm1Bridge_run(
    JNIEnv* env,
    jobject,
    jbyteArray vmArray,
    jbyteArray programArray,
    jbyteArray sourceArray,
    jbyteArray outputArray,
    jint stepBudget
) {
#if !defined(__arm__)
    (void)vmArray;
    (void)programArray;
    (void)sourceArray;
    (void)outputArray;
    (void)stepBudget;
    return resultArray(env, -90, 0);
#else
    if (
        vmArray == nullptr ||
        programArray == nullptr ||
        sourceArray == nullptr ||
        outputArray == nullptr
    ) {
        return resultArray(env, -91, 0);
    }

    const jsize vmLength = env->GetArrayLength(vmArray);
    const jsize programLength = env->GetArrayLength(programArray);
    const jsize sourceLength = env->GetArrayLength(sourceArray);
    const jsize outputLength = env->GetArrayLength(outputArray);

    if (
        vmLength <= 0 || vmLength > kMaxVmBytes ||
        programLength <= 0 || programLength > kMaxProgramBytes ||
        sourceLength < 0 || sourceLength > kMaxSourceBytes ||
        outputLength < 0 || outputLength > kMaxOutputBytes ||
        stepBudget <= 0 || stepBudget > kMaxSteps
    ) {
        return resultArray(env, -92, 0);
    }

    jboolean vmCopy = JNI_FALSE;
    jboolean programCopy = JNI_FALSE;
    jboolean sourceCopy = JNI_FALSE;
    jboolean outputCopy = JNI_FALSE;

    jbyte* vmBytes = env->GetByteArrayElements(vmArray, &vmCopy);
    jbyte* programBytes =
        env->GetByteArrayElements(programArray, &programCopy);
    jbyte* sourceBytes =
        env->GetByteArrayElements(sourceArray, &sourceCopy);
    jbyte* outputBytes =
        env->GetByteArrayElements(outputArray, &outputCopy);

    if (
        vmBytes == nullptr ||
        programBytes == nullptr ||
        sourceBytes == nullptr ||
        outputBytes == nullptr
    ) {
        if (vmBytes != nullptr) {
            env->ReleaseByteArrayElements(vmArray, vmBytes, JNI_ABORT);
        }
        if (programBytes != nullptr) {
            env->ReleaseByteArrayElements(
                programArray,
                programBytes,
                JNI_ABORT
            );
        }
        if (sourceBytes != nullptr) {
            env->ReleaseByteArrayElements(
                sourceArray,
                sourceBytes,
                JNI_ABORT
            );
        }
        if (outputBytes != nullptr) {
            env->ReleaseByteArrayElements(
                outputArray,
                outputBytes,
                0
            );
        }
        return resultArray(env, -93, 0);
    }

    size_t mappedSize = 0;
    void* executable = mapExecutable(
        reinterpret_cast<const uint8_t*>(vmBytes),
        static_cast<size_t>(vmLength),
        &mappedSize
    );

    jint status = -94;
    jint compilerResult = 0;

    if (executable != nullptr) {
        VmContext context{
            reinterpret_cast<const uint8_t*>(sourceBytes),
            static_cast<uint32_t>(sourceLength),
            reinterpret_cast<uint8_t*>(outputBytes),
            static_cast<uint32_t>(outputLength),
            0U
        };

        auto vm = reinterpret_cast<VmFn>(executable);
        status = static_cast<jint>(
            vm(
                reinterpret_cast<const uint8_t*>(programBytes),
                static_cast<uint32_t>(programLength),
                &context,
                static_cast<uint32_t>(stepBudget)
            )
        );
        compilerResult = static_cast<jint>(context.result);

        munmap(executable, mappedSize);
    }

    env->ReleaseByteArrayElements(vmArray, vmBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(
        programArray,
        programBytes,
        JNI_ABORT
    );
    env->ReleaseByteArrayElements(
        sourceArray,
        sourceBytes,
        JNI_ABORT
    );
    env->ReleaseByteArrayElements(outputArray, outputBytes, 0);

    return resultArray(env, status, compilerResult);
#endif
}
