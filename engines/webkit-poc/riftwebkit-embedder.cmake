# RiftWebKit Safari probe target.
# Included by the WebKit Emscripten port after WebCore has been defined.

get_filename_component(RIFTWEBKIT_PROBE_DIR "${EMSCRIPTEN_EMBEDDER_CMAKE}" DIRECTORY)

add_executable(RiftWebKitProbe
    "${RIFTWEBKIT_PROBE_DIR}/riftwebkit-probe.cpp"
)
set_target_properties(RiftWebKitProbe PROPERTIES OUTPUT_NAME riftwebkit)

target_include_directories(RiftWebKitProbe PRIVATE
    ${WebCore_INCLUDE_DIRECTORIES}
    ${WebCore_PRIVATE_INCLUDE_DIRECTORIES}
)
target_include_directories(RiftWebKitProbe SYSTEM PRIVATE
    ${WebCore_SYSTEM_INCLUDE_DIRECTORIES}
)

target_link_libraries(RiftWebKitProbe PRIVATE WebCore Skia::Skia)

# Keep the first iPhone probe deliberately conservative and diagnosable.
# Unlike the Gecko experiment this is single-threaded, non-shared memory.
target_link_options(RiftWebKitProbe PRIVATE
    "SHELL:-sSTACK_SIZE=8MB"
    "SHELL:-sINITIAL_MEMORY=256MB"
    "SHELL:-sALLOW_MEMORY_GROWTH=1"
    "SHELL:-sMAXIMUM_MEMORY=1GB"
    "SHELL:-sEXIT_RUNTIME=0"
    "SHELL:-sASSERTIONS=1"
    "SHELL:--profiling-funcs"
)

# JSC/WebCore use the pinned ICU data at runtime.
if (RIFTWEBKIT_ICU_DATA_FILE)
    target_link_options(RiftWebKitProbe PRIVATE
        "SHELL:--embed-file ${RIFTWEBKIT_ICU_DATA_FILE}@${RIFTWEBKIT_ICU_DATA_FILE}")
endif ()

# Minimal fontconfig tree + one DejaVu face. The probe page intentionally has
# no visible text, but WebCore style/font setup is safer with a valid default.
if (RIFTWEBKIT_FONTCONFIG_ETC_DIR)
    target_link_options(RiftWebKitProbe PRIVATE
        "SHELL:--embed-file ${RIFTWEBKIT_FONTCONFIG_ETC_DIR}@/etc/fonts")
endif ()
if (RIFTWEBKIT_FONTS_DIR)
    target_link_options(RiftWebKitProbe PRIVATE
        "SHELL:--embed-file ${RIFTWEBKIT_FONTS_DIR}@/usr/share/fonts")
endif ()
