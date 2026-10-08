cmake_minimum_required(VERSION 3.22.1)

project(riftos_native LANGUAGES CXX)


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
