cmake_minimum_required(VERSION 3.22.1)

project(riftcli LANGUAGES CXX)

add_library(
    riftcli
    SHARED
    riftcli/rift_cli_core.cpp
    riftcli/rift_cli_jni.cpp
)

target_compile_features(riftcli PRIVATE cxx_std_17)

target_compile_options(
    riftcli
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fvisibility=hidden
)

target_include_directories(
    riftcli
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/riftcli
)


find_library(android-lib android)
find_library(log-lib log)



add_library(
    rift_native_buffer_compiler_host
    SHARED
    compiler/rift_native_buffer_compiler_host.cpp
)

target_compile_features(rift_native_buffer_compiler_host PRIVATE cxx_std_17)

target_compile_options(
    rift_native_buffer_compiler_host
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
    -fno-exceptions
    -fno-rtti
)


add_library(
    codynex_editor_vm
    SHARED
    editor/editor_vm_bridge.cpp
)

target_compile_features(codynex_editor_vm PRIVATE cxx_std_17)

target_compile_options(
    codynex_editor_vm
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
    -fno-exceptions
    -fno-rtti
)

add_library(
    riftpp_editor_bridge
    SHARED
    editor/riftpp_editor_bridge.cpp
)

target_compile_features(riftpp_editor_bridge PRIVATE cxx_std_17)

target_compile_options(
    riftpp_editor_bridge
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Werror
    -fno-exceptions
    -fno-rtti
)
