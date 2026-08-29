#!/usr/bin/env python3
"""Apply RiftEngine-owned, narrowly-scoped JSCOnly/Emscripten compatibility edits.

This script intentionally touches upstream WebKit after checkout instead of
vendoring WebKit source. Every edit is marker-checked so upstream drift fails
loudly rather than silently producing a different engine.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()


def read(rel):
    return (root / rel).read_text()


def write(rel, text):
    (root / rel).write_text(text)


def replace_once(rel, old, new):
    text = read(rel)
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"port drift: {rel}: expected one marker, found {count}: {old[:90]!r}")
    write(rel, text.replace(old, new, 1))


# Emscripten emits executable launcher JS plus a companion .wasm. JSC's
# offlineasm generators must inspect the binary, not the launcher text.
cmake_rel = "Source/JavaScriptCore/CMakeLists.txt"
cmake = read(cmake_rel)
if "RIFT_JSC_SETTINGS_BINARY" not in cmake:
    marker = "add_custom_command(\n    OUTPUT ${JavaScriptCore_DERIVED_SOURCES_DIR}/LLIntDesiredOffsets.h"
    if marker not in cmake:
        raise SystemExit("port drift: LLIntDesiredOffsets command marker missing")
    block = """if (EMSCRIPTEN)\n    set(RIFT_JSC_SETTINGS_BINARY $<TARGET_FILE_DIR:LLIntSettingsExtractor>/LLIntSettingsExtractor.wasm)\nelse ()\n    set(RIFT_JSC_SETTINGS_BINARY $<TARGET_FILE:LLIntSettingsExtractor>)\nendif ()\n\n"""
    cmake = cmake.replace(marker, block + marker, 1)
    cmake = cmake.replace("$<TARGET_FILE:LLIntSettingsExtractor> ${JavaScriptCore_DERIVED_SOURCES_DIR}/LLIntDesiredOffsets.h", "${RIFT_JSC_SETTINGS_BINARY} ${JavaScriptCore_DERIVED_SOURCES_DIR}/LLIntDesiredOffsets.h", 1)

    marker2 = "add_custom_command(\n    OUTPUT ${JavaScriptCore_DERIVED_SOURCES_DIR}/${LLIntOutput}"
    if marker2 not in cmake:
        raise SystemExit("port drift: LLInt output command marker missing")
    block2 = """if (EMSCRIPTEN)\n    set(RIFT_JSC_OFFSETS_BINARY $<TARGET_FILE_DIR:LLIntOffsetsExtractor>/LLIntOffsetsExtractor.wasm)\nelse ()\n    set(RIFT_JSC_OFFSETS_BINARY $<TARGET_FILE:LLIntOffsetsExtractor>)\nendif ()\n\n"""
    cmake = cmake.replace(marker2, block2 + marker2, 1)
    cmake = cmake.replace("$<TARGET_FILE:LLIntOffsetsExtractor> ${JavaScriptCore_DERIVED_SOURCES_DIR}/${LLIntOutput}", "${RIFT_JSC_OFFSETS_BINARY} ${JavaScriptCore_DERIVED_SOURCES_DIR}/${LLIntOutput}", 1)

    # Keep the tiny extractor binaries unoptimized: Binaryen data packing can
    # change the byte layout that offlineasm scans for magic values.
    settings_marker = "WEBKIT_EXECUTABLE(LLIntSettingsExtractor)"
    offsets_marker = "WEBKIT_EXECUTABLE(LLIntOffsetsExtractor)"
    if settings_marker not in cmake or offsets_marker not in cmake:
        raise SystemExit("port drift: LLInt extractor target marker missing")
    cmake = cmake.replace(settings_marker, settings_marker + "\nif (EMSCRIPTEN)\n    target_link_options(LLIntSettingsExtractor PRIVATE \"-O0\")\nendif ()", 1)
    cmake = cmake.replace(offsets_marker, offsets_marker + "\nif (EMSCRIPTEN)\n    target_link_options(LLIntOffsetsExtractor PRIVATE \"-O0\")\nendif ()", 1)
    write(cmake_rel, cmake)

# wasm32's C integer typedefs do not line up with WebKit's usual ADDRESS32
# assumptions. Extend the already-existing overload/specialization guards.
replace_once(
    "Source/WTF/wtf/RawHex.h",
    "#if CPU(ADDRESS64) || OS(DARWIN) || OS(HAIKU)",
    "#if CPU(ADDRESS64) || OS(DARWIN) || OS(HAIKU) || defined(__EMSCRIPTEN__)"
)
replace_once(
    "Source/JavaScriptCore/runtime/Options.cpp",
    "#if CPU(ADDRESS64) || OS(DARWIN) || OS(HAIKU)",
    "#if CPU(ADDRESS64) || OS(DARWIN) || OS(HAIKU) || defined(__EMSCRIPTEN__)"
)

# Emscripten has POSIX-style file handles but does not define WebKit's Linux OS
# policy macro in every JSCOnly configuration.
replace_once(
    "Source/WTF/wtf/FileHandle.h",
    "#elif OS(LINUX)\n#include <sys/types.h>",
    "#elif OS(LINUX) || defined(__EMSCRIPTEN__)\n#include <sys/types.h>"
)

# dladdr is not a meaningful way to fingerprint a wasm module.
replace_once(
    "Source/JavaScriptCore/runtime/JSCBytecodeCacheVersion.cpp",
    "#elif OS(UNIX) && !PLATFORM(PLAYSTATION) && !OS(HAIKU) && !OS(QNX)",
    "#elif OS(UNIX) && !PLATFORM(PLAYSTATION) && !OS(HAIKU) && !OS(QNX) && !defined(__EMSCRIPTEN__)"
)

# libc coverage differs on wasm; use WebKit's fallback round-even helper there.
replace_once(
    "Source/JavaScriptCore/runtime/MathCommon.cpp",
    "#if (OS(LINUX) && !defined(__GLIBC__)) || OS(HAIKU)",
    "#if (OS(LINUX) && !defined(__GLIBC__)) || OS(HAIKU) || defined(__EMSCRIPTEN__)"
)

# Browser-targeted wasm in this phase is deliberately single-threaded. WTF's
# POSIX backend normally suspends peer threads with Unix signals and sigsuspend,
# but Emscripten does not provide sigsuspend and fabricating it would make GC
# register scanning unsafe. Keep the APIs present, but make suspension report
# ENOTSUP and compile the signal/semaphore machinery out of the wasm target.
threading_rel = "Source/WTF/wtf/posix/ThreadingPOSIX.cpp"
replace_once(
    threading_rel,
    "#if !OS(DARWIN)\nclass Semaphore final {",
    "#if !OS(DARWIN) && !defined(__EMSCRIPTEN__)\nclass Semaphore final {"
)
replace_once(
    threading_rel,
    "#if !OS(DARWIN)\n    globalSemaphoreForSuspendResume.construct(0);",
    "#if !OS(DARWIN) && !defined(__EMSCRIPTEN__)\n    globalSemaphoreForSuspendResume.construct(0);"
)
replace_once(
    threading_rel,
    "#if !OS(DARWIN)\n    RELEASE_ASSERT(g_wtfConfig.isThreadSuspendResumeSignalConfigured);",
    "#if !OS(DARWIN) && !defined(__EMSCRIPTEN__)\n    RELEASE_ASSERT(g_wtfConfig.isThreadSuspendResumeSignalConfigured);"
)
replace_once(
    threading_rel,
    "    return { };\n#else\n    if (!m_suspendCount) {",
    "    return { };\n#elif defined(__EMSCRIPTEN__)\n    return makeUnexpected(ENOTSUP);\n#else\n    if (!m_suspendCount) {"
)
replace_once(
    threading_rel,
    "#if OS(DARWIN)\n    thread_resume(m_platformThread);\n#else\n    if (m_suspendCount == 1) {",
    "#if OS(DARWIN)\n    thread_resume(m_platformThread);\n#elif defined(__EMSCRIPTEN__)\n    return;\n#else\n    if (m_suspendCount == 1) {"
)
replace_once(
    threading_rel,
    "    return metadata.userCount * sizeof(uintptr_t);\n#else\n    ASSERT_WITH_MESSAGE(m_suspendCount, \"We can get registers only if the thread is suspended.\");",
    "    return metadata.userCount * sizeof(uintptr_t);\n#elif defined(__EMSCRIPTEN__)\n    UNUSED_PARAM(registers);\n    RELEASE_ASSERT_NOT_REACHED();\n    return 0;\n#else\n    ASSERT_WITH_MESSAGE(m_suspendCount, \"We can get registers only if the thread is suspended.\");"
)

# Build the JSC shell with enough stack/heap to make a smoke test meaningful.
# ICU archive data is embedded only into this diagnostic shell; later the
# RiftEngine host owns the profile/data mounting policy.
shell_rel = "Source/JavaScriptCore/shell/CMakeLists.txt"
shell = read(shell_rel)
if "RIFT_JSC_WASM_RUNTIME" not in shell:
    marker = "WEBKIT_EXECUTABLE(jsc)"
    if shell.count(marker) != 1:
        raise SystemExit("port drift: jsc executable marker missing")
    extra = r'''

if (EMSCRIPTEN)
    set(RIFT_JSC_WASM_RUNTIME ON)
    target_link_options(jsc PRIVATE
        "SHELL:-sSTACK_SIZE=4MB"
        "SHELL:-sINITIAL_MEMORY=128MB"
        "SHELL:-sALLOW_MEMORY_GROWTH=1"
        "SHELL:-sMAXIMUM_MEMORY=1GB")
    if (JSC_EMBED_ICU_DATA_FILE)
        target_link_options(jsc PRIVATE "SHELL:--embed-file ${JSC_EMBED_ICU_DATA_FILE}@${JSC_EMBED_ICU_DATA_FILE}")
    endif ()
endif ()
'''
    write(shell_rel, shell.replace(marker, marker + extra, 1))

print("RiftEngine: JSCOnly Emscripten compatibility edits applied")
