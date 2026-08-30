#!/usr/bin/env python3
"""Create RiftEngine's minimal WebCore Emscripten port in a pinned WebKit tree.

This is a bring-up port, not a fork of WebKit. It adds only the CMake port
selection/profile needed to let upstream WebCore compile far enough for us to
burn down wasm-specific platform errors in CI. JSC compatibility edits remain
owned by port-jsc-emscripten.py.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
common = root / "Source/cmake/WebKitCommon.cmake"
options = root / "Source/cmake/OptionsEmscripten.cmake"

text = common.read_text()
if "    Emscripten\n" not in text:
    marker = "set(ALL_PORTS\n"
    if text.count(marker) != 1:
        raise SystemExit("port drift: WebKitCommon ALL_PORTS marker missing")
    text = text.replace(marker, marker + "    Emscripten\n", 1)
    common.write_text(text)

options.write_text(r'''# RiftEngine WebCore wasm bring-up profile.
# Generated after checkout; upstream WebKit remains pinned and unvendored.

find_package(Threads REQUIRED)

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(PROJECT_VERSION_MAJOR 1)
set(PROJECT_VERSION_MINOR 0)
set(PROJECT_VERSION_MICRO 0)
set(PROJECT_VERSION ${PROJECT_VERSION_MAJOR}.${PROJECT_VERSION_MINOR}.${PROJECT_VERSION_MICRO})

WEBKIT_OPTION_BEGIN()
WEBKIT_OPTION_DEFINE(ENABLE_STATIC_JSC "Build JavaScriptCore statically for RiftEngine." PUBLIC ON)
WEBKIT_OPTION_DEFINE(USE_LIBBACKTRACE "Use libbacktrace." PUBLIC OFF)
WEBKIT_OPTION_DEFINE(USE_SYSTEM_UNIFDEF "Use system unifdef." PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_REMOTE_INSPECTOR PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_WEBGL PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_WEBGPU PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_VIDEO PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_WEB_AUDIO PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_MEDIA_SOURCE PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_MEDIA_CAPTURE PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_WEB_RTC PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_WEBDRIVER PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_SPEECH_SYNTHESIS PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(ENABLE_NOTIFICATIONS PRIVATE OFF)
WEBKIT_OPTION_DEFAULT_PORT_VALUE(USE_LCMS PUBLIC OFF)
WEBKIT_OPTION_END()

set(ENABLE_WEBCORE ON)
set(ENABLE_WEBKIT OFF)
set(ENABLE_WEBKIT_LEGACY OFF)
set(ENABLE_WEBINSPECTORUI OFF)
set(ENABLE_API_TESTS OFF)
set(ENABLE_TOOLS OFF)

# First bring-up is intentionally single-threaded and renderer-neutral. The
# paint backend is added only after the HTML/DOM/layout core compiles.
set(ENABLE_WEBGL OFF)
set(ENABLE_WEBGPU OFF)
set(ENABLE_VIDEO OFF)
set(ENABLE_WEB_AUDIO OFF)
set(ENABLE_MEDIA_SOURCE OFF)
set(ENABLE_MEDIA_CAPTURE OFF)
set(ENABLE_WEB_RTC OFF)
set(ENABLE_WEBDRIVER OFF)
set(ENABLE_XSLT OFF)
set(USE_GLIB OFF)
set(USE_LIBBACKTRACE OFF)
set(USE_SYSTEM_MALLOC ON)
set(USE_GENERIC_EVENT_LOOP ON)
SET_AND_EXPOSE_TO_BUILD(USE_GENERIC_EVENT_LOOP 1)
SET_AND_EXPOSE_TO_BUILD(WTF_DEFAULT_EVENT_LOOP 0)

set(JavaScriptCore_LIBRARY_TYPE STATIC)
set(bmalloc_LIBRARY_TYPE STATIC)
set(WTF_LIBRARY_TYPE STATIC)
set(PAL_LIBRARY_TYPE STATIC)
set(WebCore_LIBRARY_TYPE STATIC)

# WebCore links imported dependency targets, not just legacy cache variables.
# Resolve every Gate-2 dependency from RiftEngine's wasm sysroot before
# WebCore's framework targets are generated. Imported targets created by
# find_package() are directory scoped unless promoted to IMPORTED_GLOBAL; this
# port options file is loaded from Source/cmake while WebCore is generated from
# sibling directories later in configure, so promote them here.
find_package(ICU 70.1 REQUIRED COMPONENTS data i18n uc)
find_package(LibXml2 REQUIRED)
find_package(SQLite3 3.7.17 REQUIRED)
find_package(ZLIB REQUIRED)

function(rift_promote_imported_target target_name)
    if (TARGET ${target_name})
        get_target_property(_rift_imported ${target_name} IMPORTED)
        if (_rift_imported)
            set_property(TARGET ${target_name} PROPERTY IMPORTED_GLOBAL TRUE)
        endif ()
    endif ()
endfunction()

rift_promote_imported_target(ICU::data)
rift_promote_imported_target(ICU::i18n)
rift_promote_imported_target(ICU::uc)
rift_promote_imported_target(LibXml2::LibXml2)
rift_promote_imported_target(SQLite::SQLite3)
rift_promote_imported_target(ZLIB::ZLIB)

# WebCore's generic target graph still names OpenGL::GLES during Gate 2 even
# with WebGL/WebGPU disabled. Emscripten supplies the GLES headers/runtime at
# the toolchain level, so provide the CMake interface target without enabling a
# graphics feature or introducing a native host OpenGL dependency.
if (NOT TARGET OpenGL::GLES)
    add_library(OpenGL::GLES INTERFACE IMPORTED GLOBAL)
endif ()

# Older CMake exposes SQLite::SQLite3 while this WebKit revision asks for
# SQLite3::SQLite3. Promote the real imported target first, then create the
# alias so the alias resolves globally through the promoted target.
if (TARGET SQLite::SQLite3 AND NOT TARGET SQLite3::SQLite3)
    add_library(SQLite3::SQLite3 ALIAS SQLite::SQLite3)
endif ()
if (NOT TARGET SQLite3::SQLite3)
    message(FATAL_ERROR "SQLite3 was found but no SQLite3::SQLite3 imported target is available")
endif ()
if (NOT TARGET LibXml2::LibXml2)
    message(FATAL_ERROR "LibXml2 was found but no LibXml2::LibXml2 imported target is available")
endif ()
if (NOT TARGET ZLIB::ZLIB)
    message(FATAL_ERROR "zlib was found but no ZLIB::ZLIB imported target is available")
endif ()
if (NOT TARGET OpenGL::GLES)
    message(FATAL_ERROR "WebCore requires an OpenGL::GLES target for Gate 2 generation")
endif ()

message(STATUS "RIFT_WEBCORE_DEP_TARGETS=global")
message(STATUS "RIFT_WEBCORE_GLES_TARGET=emscripten-interface")
message(STATUS "RIFT_WEBCORE_LCMS=disabled")
''')

print("RIFT_WEBCORE_PORT=emscripten-scaffold-ready")
