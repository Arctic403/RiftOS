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
