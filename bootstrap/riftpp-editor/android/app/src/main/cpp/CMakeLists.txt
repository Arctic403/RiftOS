cmake_minimum_required(VERSION 3.22.1)
project(riftpp_editor_bridge)

add_library(
    riftpp_editor_bridge
    SHARED
    riftpp_editor_bridge.cpp
)

target_compile_features(riftpp_editor_bridge PRIVATE cxx_std_17)
