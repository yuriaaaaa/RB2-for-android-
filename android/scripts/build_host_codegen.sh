#!/usr/bin/env bash
# Host (linux-x64) build of the rexglue CLI + Dragon Ball Raging Blast 2 codegen.
#
# Reads YOUR default.xex and writes rb2/generated/default/*.cpp, the recompiled
# source the Android build consumes. Both the xex and the output are git-ignored:
# they are game code and never belong in the repository.
#
# Where the xex comes from (first match wins):
#   1. RB2_XEX=/path/to/default.xex
#   2. rb2/assets/default.xex (already in place)
#
# Inputs:
#   RB2_SRC    (default: android/rb2)
#   REXSDK_SRC  (default: android/rexglue-sdk)
#   SKIP_CLI_BUILD=1  reuse an existing build/host-codegen
#   FORCE_CODEGEN=1   regenerate even if generated/default exists
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RB2_SRC="${RB2_SRC:-$ROOT/rb2}"
REXSDK_SRC="${REXSDK_SRC:-$ROOT/rexglue-sdk}"
BUILD_DIR="$ROOT/build/host-codegen"

# The build this port was made and tested with: Title ID 4E4D0826, Media ID 45ACA4A0,
# version 4 (7,151,616 bytes). rb2/rb2_functions.toml and rb2/rb2_rexcrt.toml are
# addresses in this image (the same one upstream WistfulHopes/RB2 was made from).
EXPECTED_SHA256="${RB2_XEX_SHA256:-d0291e647c33a5837a5d3521198ecb67a4e8d9bee80bbf691254a234e3ee989c}"

echo "== [1/3] default.xex =="
XEX_PATH="$RB2_SRC/assets/default.xex"
mkdir -p "$RB2_SRC/assets"
if [[ -n "${RB2_XEX:-}" ]]; then
    cp "$RB2_XEX" "$XEX_PATH"
fi
if [[ ! -f "$XEX_PATH" ]]; then
    echo "ERROR: no default.xex. Set RB2_XEX=/path/to/default.xex (from your own copy of the game)." >&2
    exit 1
fi
ACTUAL_SHA256=$(sha256sum "$XEX_PATH" | cut -d' ' -f1)
echo "  sha256: $ACTUAL_SHA256"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "WARNING: default.xex differs from the tested build (Title 4E4D0826, Media 45ACA4A0)." >&2
    echo "  rb2_functions.toml and rb2_rexcrt.toml target that image; codegen may fail or the game may crash." >&2
fi

echo "== [2/3] rexglue CLI (host) =="
HOST_ARCH_FLAGS="-march=x86-64-v2"  # same floor as the SDK's linux-base preset
if [[ "${SKIP_CLI_BUILD:-0}" != "1" || ! -d "$BUILD_DIR" ]]; then
    cmake -S "$REXSDK_SRC" -B "$BUILD_DIR" \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$HOST_ARCH_FLAGS" \
        -DCMAKE_CXX_FLAGS="$HOST_ARCH_FLAGS" \
        -DREXGLUE_BUILD_TESTS=OFF \
        -DREXGLUE_ENABLE_TRACY=OFF \
        -DREXGLUE_ENABLE_PERF_COUNTERS=OFF
fi
cmake --build "$BUILD_DIR" --target rexglue --parallel "$(nproc)"

echo "== [3/3] Codegen =="
REXGLUE_CLI=$(find "$REXSDK_SRC/out" "$BUILD_DIR" -type f -name rexglue -perm -u+x 2>/dev/null | head -1)
if [[ -z "$REXGLUE_CLI" ]]; then
    echo "ERROR: rexglue binary not found after build" >&2
    exit 1
fi
echo "  CLI: $REXGLUE_CLI"
if [[ -f "$RB2_SRC/generated/default/sources.cmake" && "${FORCE_CODEGEN:-0}" != "1" ]]; then
    echo "  codegen output present — skipping (FORCE_CODEGEN=1 to regenerate)"
else
    (cd "$RB2_SRC" && "$REXGLUE_CLI" codegen rb2_manifest.toml)
fi

ls "$RB2_SRC/generated/default/" | head -8
echo "Codegen done."
