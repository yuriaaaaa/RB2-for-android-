# rb2-android — native build of Dragon Ball Raging Blast 2 for Android (arm64-v8a)
#
# Same structure as the WET Android port (itself adapted from
# naughtybear-restuff-android by Hailgames). Inputs:
#   -DRB2_SOURCE_DIR=<rb2/ with src/ and generated/default/ (codegen done)>
#   -DREXSDK_SOURCE_DIR=<rexglue-sdk bd833a2 with the Android overlays applied>
#
# The recompiled code (generated/default/) is produced BEFORE this build by the
# host rexglue CLI (scripts/build_host_codegen.sh) from your default.xex.
#
# Outputs (copied to app/src/main/jniLibs by scripts/build_android_native.sh):
#   librestuff.so       — game + JNI glue (name kept from the upstream wrapper,
#                         because the Kotlin launcher loads "restuff")
#   librexruntime.so    — rexglue runtime (shared)
#   librexgpu-xenos.so  — Xenos GPU emulation plugin (Vulkan backend)

cmake_minimum_required(VERSION 3.25)
project(rb2_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(RB2_SOURCE_DIR "" CACHE PATH "Dragon Ball Raging Blast 2 project (src/, generated/)")
set(REXSDK_SOURCE_DIR "" CACHE PATH "Source tree of rexglue-sdk")

if(NOT RB2_SOURCE_DIR OR NOT REXSDK_SOURCE_DIR)
    message(FATAL_ERROR "RB2_SOURCE_DIR and REXSDK_SOURCE_DIR are required")
endif()

# ----------------------------------------------------------------------
# SDK: Vulkan only (no D3D12 on Android), no tests/profilers.
# ----------------------------------------------------------------------
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_PERF_COUNTERS OFF CACHE BOOL "" FORCE)
add_subdirectory("${REXSDK_SOURCE_DIR}" rexglue-sdk)

if(NOT TARGET rexgpu-xenos)
    message(FATAL_ERROR "rexgpu-xenos target missing — is REXGLUE_USE_VULKAN on?")
endif()

# RB2_SKIP_GAME=ON stops here: only the SDK (with the port's overlays) is configured, so its
# Android libraries can be built and checked without default.xex. Nothing it builds can run
# the game.
option(RB2_SKIP_GAME "Configure only the SDK libraries, without the recompiled game" OFF)
if(RB2_SKIP_GAME)
    return()
endif()

# ----------------------------------------------------------------------
# Recompiled code (host codegen output)
# ----------------------------------------------------------------------
set(GENERATED_DIR "${RB2_SOURCE_DIR}/generated/default")
set(GENERATED_SOURCES_CMAKE "${GENERATED_DIR}/sources.cmake")
if(NOT EXISTS "${GENERATED_SOURCES_CMAKE}")
    message(FATAL_ERROR
        "${GENERATED_SOURCES_CMAKE} not found. Run scripts/build_host_codegen.sh "
        "first (it runs 'rexglue codegen' on your default.xex).")
endif()
include("${GENERATED_SOURCES_CMAKE}")

# Same recompiled-code options as the SDK's generated/rexglue.cmake on
# non-Windows targets: line tables only (backtraces stay symbolizable from the
# unstripped .so; the APK gets a stripped copy).
add_library(rb2_recomp OBJECT ${GENERATED_SOURCES})
target_include_directories(rb2_recomp PRIVATE
    "${RB2_SOURCE_DIR}"
    "${RB2_SOURCE_DIR}/src"
    "${GENERATED_DIR}"
)
target_link_libraries(rb2_recomp PRIVATE rex::runtime)
target_precompile_headers(rb2_recomp PRIVATE "${GENERATED_DIR}/rb2_pch.h")
# The SDK compiles its own code with -fno-strict-aliasing -ffp-model=strict
# -fno-char8_t (rexglue-sdk/CMakeLists.txt add_compile_options), but those are
# directory-scoped and never reached the targets defined here. The SDK's inline
# PPC helpers (FPSCR rounding, type punning) are compiled into this code, so
# give it the same semantics.
set(SDK_SEMANTICS_FLAGS -fno-strict-aliasing -ffp-model=strict -fno-char8_t)
target_compile_options(rb2_recomp PRIVATE
    $<$<CXX_COMPILER_ID:Clang,AppleClang>:-gline-tables-only>
    -march=armv8-a
    -fno-omit-frame-pointer
    ${SDK_SEMANTICS_FLAGS}
)
set_target_properties(rb2_recomp PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ----------------------------------------------------------------------
# Game + JNI glue
# ----------------------------------------------------------------------
add_library(restuff SHARED
    "${RB2_SOURCE_DIR}/src/main.cpp"
    "${RB2_SOURCE_DIR}/src/rb2_hooks.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/android_main.cpp"
    # ReXApp base class (rexglue_configure_target adds this on desktop).
    "${REXSDK_SOURCE_DIR}/src/ui/rex_app.cpp"
)
target_include_directories(restuff PRIVATE
    "${RB2_SOURCE_DIR}"
    "${RB2_SOURCE_DIR}/src"
    "${GENERATED_DIR}"
    "${REXSDK_SOURCE_DIR}/thirdparty/imgui"
    "${REXSDK_SOURCE_DIR}/include/renderdoc"
)
target_compile_definitions(restuff PRIVATE REXGLUE_BUILD_CONFIG=\"$<CONFIG>\")
target_compile_options(restuff PRIVATE
    ${SDK_SEMANTICS_FLAGS}
    $<$<CXX_COMPILER_ID:Clang,AppleClang>:-gline-tables-only>
    -fno-omit-frame-pointer
    -Wno-unused-parameter
    -Wno-unused-variable
)

# rex::runtime brings rexcore/rexfilesystem/rexaudio/rexinput (+ static SDL3).
# rex::ui is an OBJECT library (window_sdl, imgui, overlays) linked in here.
target_link_libraries(restuff PRIVATE
    rb2_recomp
    rex::runtime
    rex::ui
    android
    log
)

# The Xenos GPU plugin is dlopen()ed at runtime (gpu_plugin = "xenos");
# make sure it is always built alongside the game library.
add_dependencies(restuff rexgpu-xenos)

# NOTE: do NOT use -Wl,--exclude-libs: SDL3's JNI symbols
# (Java_org_libsdl_app_*) must stay exported from librestuff.so for the
# org.libsdl.app Java classes to bind.
