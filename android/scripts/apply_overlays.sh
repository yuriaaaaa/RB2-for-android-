#!/usr/bin/env bash
# Applies the Android port overlays on top of the submodule trees:
#   - rexglue-sdk (WetRecomp's pin bd833a2): the naughtybear-restuff-android
#     SDK overlays (written against v0.10.0), with the 5 files that changed in
#     WetRecomp's newer SDK 3-way merged, plus gpu_plugin_loader.cpp (load the
#     Xenos plugin from nativeLibraryDir on Android).
#     From the Rumble Roses XX port, mmio_handler.cpp (zero-page fault mapping and fault
#     reports) and xmemory.cpp (rate-limited access-violation log) carry further
#     changes, and content_manager.cpp (DLC packages mounted in place) is added.
#
# Idempotent: each overlay file replaces the original in the submodule tree.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OVERLAY="$ROOT/native/overlay"

replace() {
    local src="$1" dst="$2"
    if [[ ! -f "$src" ]]; then
        echo "overlay faltando: $src" >&2
        exit 1
    fi
    if [[ ! -f "$dst" ]]; then
        echo "arquivo original faltando: $dst (submódulo inicializado?)" >&2
        exit 1
    fi
    # Skip identical files: rewriting them bumps mtimes on SDK headers that
    # the 248 MB of recompiled code includes, forcing a full native rebuild.
    if cmp -s "$src" "$dst"; then
        return 0
    fi
    cp "$src" "$dst"
    echo "  replaced: ${dst#$ROOT/}"
}

addnew() {
    local src="$1" dst="$2"
    if [[ ! -f "$src" ]]; then
        echo "overlay faltando: $src" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$dst")"
    if [[ -f "$dst" ]] && cmp -s "$src" "$dst"; then
        return 0
    fi
    cp "$src" "$dst"
    echo "  added:    ${dst#$ROOT/}"
}

echo "Aplicando overlays do port…"

# --- rexglue-sdk: substituições ------------------------------------------
for rel in \
    src/ui/window_sdl.cpp \
    src/ui/CMakeLists.txt \
    cmake/rex_pch.cmake \
    include/rex/string/numeric.h \
    include/rex/chrono/chrono.h \
    src/core/timer_queue.cpp \
    src/core/CMakeLists.txt \
    include/rex/thread/fiber.h \
    src/core/memory_posix.cpp \
    src/core/threading_posix.cpp \
    src/core/filesystem_posix.cpp \
    src/core/logging.cpp \
    src/input/sdl/sdl_input_driver.cpp \
    src/ui/vulkan/vulkan_instance.cpp \
    src/core/dynlib_posix.cpp \
    include/rex/platform/dynlib.h \
    src/system/CMakeLists.txt \
    include/rex/ui/vulkan/device.h \
    include/rex/ui/vulkan/functions/device_1_0.inc \
    src/ui/vulkan/vulkan_device.cpp \
    src/ui/vulkan/vulkan_presenter.cpp \
    src/audio/xma_decoder.cpp \
    src/audio/sdl/sdl_audio_driver.cpp \
    src/kernel/xboxkrnl/xboxkrnl_io.cpp \
    src/kernel/xboxkrnl/xboxkrnl_audio_xma.cpp \
    src/system/xthread.cpp \
    src/system/runtime.cpp \
    src/ui/rex_app.cpp \
    src/filesystem/devices/disc_image_device.cpp \
    src/system/gpu_plugin_loader.cpp \
    src/graphics/vulkan/command_processor.cpp \
    src/graphics/vulkan/pipeline_cache.cpp \
    src/graphics/vulkan/texture_cache.cpp \
    src/graphics/pipeline/shader/spirv_translator_fetch.cpp \
    src/graphics/pipeline/shader/spirv_translator.cpp \
    src/graphics/pipeline/shader/spirv_translator_rb.cpp \
    include/rex/graphics/pipeline/shader/spirv_translator.h \
    src/ui/presenter.cpp \
    src/graphics/shared_memory.cpp \
    src/input/input_system.cpp \
    src/system/function_dispatcher.cpp \
    src/system/mmio_handler.cpp \
    src/system/xmemory.cpp \
    src/system/xam/content_manager.cpp
do
    replace "$OVERLAY/rexglue-sdk/$rel" "$ROOT/rexglue-sdk/$rel"
done

# --- rexglue-sdk: arquivos novos (suporte Android) ------------------------
for rel in \
    include/rex/ui/surface_android.h \
    src/ui/surface_android.cpp \
    src/core/fiber_android.cpp \
    include/rex/main_android.h \
    src/system/main_android.cpp \
    include/renderdoc/renderdoc_app.h \
    src/graphics/vulkan/wet_tex_probe.h
do
    addnew "$OVERLAY/rexglue-sdk/$rel" "$ROOT/rexglue-sdk/$rel"
done

echo "Overlays applied."
