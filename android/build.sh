#!/usr/bin/env bash
# Builds the Dragon Ball Raging Blast 2 Android APK (arm64-v8a) from this repository.
#
# The game is not part of this repository. You need your own legally obtained copy of
# Dragon Ball Raging Blast 2 (Xbox 360, Title ID 4E4D0826): the build reads its default.xex and
# recompiles it on your machine. See android/README.md.
#
# Usage:
#   android/build.sh --xex /path/to/default.xex   full build
#   android/build.sh                               full build, xex already at android/rb2/assets/default.xex
#   android/build.sh --skip-game                   only what doesn't need the game: launcher unit
#                                                  tests, AdrenoTools, Vortek, the SDK libraries
#                                                  with the port's overlays, and a launcher-only
#                                                  APK (no game library; it can't start the game)
#
# Environment: ANDROID_HOME (SDK with platform 35 and build-tools 35), ANDROID_NDK_HOME
# (NDK 27.2.12479018), clang 19 or newer as CC/CXX for the host code generator, Python 3, and
# the optional RB2_KEYSTORE / RB2_STORE_PASSWORD / RB2_KEY_ALIAS / RB2_KEY_PASSWORD for signing.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SKIP_GAME=0
while [ $# -gt 0 ]; do
    case "$1" in
        --xex) export RB2_XEX="$2"; shift 2 ;;
        --xex=*) export RB2_XEX="${1#--xex=}"; shift ;;
        --skip-game) SKIP_GAME=1; shift ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

echo "==> Submodules (android/rexglue-sdk, android/native/thirdparty/libadrenotools)"
git -C "$ROOT" submodule update --init --depth 1 --recursive android/rexglue-sdk android/native/thirdparty/libadrenotools

echo "==> SDK overlays"
bash "$HERE/scripts/apply_overlays.sh"

echo "==> Launcher unit tests"
(cd "$HERE" && ./gradlew --no-daemon :app:testDebugUnitTest)

# Last build made for the device: 0.3.1 (code 15).
export RESTUFF_VERSION_CODE="${RESTUFF_VERSION_CODE:-15}"
export RESTUFF_VERSION_NAME="${RESTUFF_VERSION_NAME:-0.3.1}"
mkdir -p "$HERE/build/out"
if [ "$SKIP_GAME" = 1 ]; then
    echo "==> Native libraries without the game (AdrenoTools, Vortek, SDK with the port's overlays)"
    RB2_SKIP_GAME=1 bash "$HERE/scripts/build_android_native.sh"
    echo "==> Launcher-only APK (--skip-game)"
    (cd "$HERE" && ./gradlew --no-daemon :app:assembleRelease)
    cp "$HERE"/app/build/outputs/apk/release/*.apk "$HERE/build/out/RagingBlast2-android-arm64-launcher-only.apk"
    echo "Done: android/build/out/RagingBlast2-android-arm64-launcher-only.apk (no game library; it can't start the game)"
    exit 0
fi

if [ -z "${RB2_XEX:-}" ] && [ ! -f "$HERE/rb2/assets/default.xex" ]; then
    echo "error: no default.xex. Pass --xex /path/to/default.xex from your own copy of Dragon Ball Raging Blast 2." >&2
    exit 1
fi

echo "==> Recompiling default.xex (rexglue codegen)"
bash "$HERE/scripts/build_host_codegen.sh"

echo "==> Native libraries (NDK)"
bash "$HERE/scripts/build_android_native.sh"

echo "==> APK"
(cd "$HERE" && ./gradlew --no-daemon :app:assembleRelease)
cp "$HERE"/app/build/outputs/apk/release/*.apk "$HERE/build/out/RagingBlast2-android-arm64.apk"
echo "Done: android/build/out/RagingBlast2-android-arm64.apk"
