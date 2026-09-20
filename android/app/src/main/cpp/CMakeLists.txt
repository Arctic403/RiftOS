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


add_library(
    codynex_mc0_host
    SHARED
    mc0/codynex_mc0_host.cpp
)

target_compile_features(codynex_mc0_host PRIVATE cxx_std_17)

target_compile_options(
    codynex_mc0_host
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
)

find_library(android-lib android)
find_library(log-lib log)

target_link_libraries(
    codynex_mc0_host
    PRIVATE
    ${android-lib}
    ${log-lib}
)


add_library(
    codynex_mc1a_host
    SHARED
    mc1/codynex_mc1a_host.cpp
)

target_compile_features(codynex_mc1a_host PRIVATE cxx_std_17)

target_compile_options(
    codynex_mc1a_host
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
)

target_link_libraries(
    codynex_mc1a_host
    PRIVATE
    ${android-lib}
    ${log-lib}
)


add_library(
    codynex_mc1b_host
    SHARED
    mc1/codynex_mc1b_host.cpp
)

target_compile_features(codynex_mc1b_host PRIVATE cxx_std_17)

target_compile_options(
    codynex_mc1b_host
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
)

target_link_libraries(
    codynex_mc1b_host
    PRIVATE
    ${android-lib}
    ${log-lib}
)


add_library(
    codynex_m2_vm0_host
    SHARED
    m2/codynex_m2_vm0_host.cpp
)

target_compile_features(codynex_m2_vm0_host PRIVATE cxx_std_17)

target_compile_options(
    codynex_m2_vm0_host
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -fno-exceptions
    -fno-rtti
)

target_link_libraries(
    codynex_m2_vm0_host
    PRIVATE
    ${android-lib}
    ${log-lib}
)
