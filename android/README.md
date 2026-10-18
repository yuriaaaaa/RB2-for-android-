# Dragon Ball Raging Blast 2 for Android: building

This folder holds the Android (arm64-v8a) port of Dragon Ball Raging Blast 2: the launcher app,
the Android CMake project and JNI glue, the port's overlays on the ReXGlue SDK, Vortek, the game
project for the SDK's code generator (`rb2/`) and the build scripts. It is built the same way as
the WET and Rumble Roses XX Android ports, which it is based on.

The desktop project in the rest of this repository (`src/`, `rb2_config.toml`, `CMakeLists.txt`)
is not used by the Android build: it targets the February 2026 ReXGlue SDK, which has no Android
support. See "How this differs from the desktop build" below.

**Status: experimental.** This is the first Android build. It compiles; it has not been run on a
phone yet.

## You need your own copy of the game

**This repository contains no game files, no code recompiled from the game and no prebuilt APK.**
To build and play, you need your own legally obtained copy of Dragon Ball Raging Blast 2 for
Xbox 360 (Title ID `4E4D0826`), as a disc image (ISO) you made yourself or as an extracted game
folder.

- The build reads only `default.xex` from your copy and recompiles it on your computer
  (`rexglue codegen`). The output (`rb2/generated/default`) and your `default.xex`
  (`rb2/assets/`) are git-ignored and stay on your machine. The tested build is Media ID
  `45ACA4A0`, version 0.0.0.4; the script warns if your `default.xex` differs.
- On the phone, the launcher plays the game from your own ISO or extracted folder.
- Do not share the APK you build: it contains code recompiled from your copy of the game.

## Requirements

- Linux x86_64 with clang 19 or newer (set `CC`/`CXX`), CMake, Ninja, Git and Python 3
- The X11, Wayland, ALSA and PulseAudio development headers for the host code generator's SDL
  build (on Ubuntu: `libx11-xcb-dev libx11-dev libxext-dev libxrandr-dev libxcursor-dev
  libxi-dev libxss-dev libxfixes-dev libwayland-dev wayland-protocols libxkbcommon-dev
  libasound2-dev libpulse-dev`)
- Android SDK with platform 35 and build-tools 35, and **NDK 27.2.12479018**
- JDK 17

## Build

```sh
git clone https://github.com/yuriaaaaa/RB2
cd RB2
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
export CC=clang-19 CXX=clang++-19

android/build.sh --xex /path/to/your/default.xex
```

The APK is written to `android/build/out/RagingBlast2-android-arm64.apk`. If your game is an ISO,
extract `default.xex` from it first with an Xbox 360 ISO extraction tool. The native build of the
recompiled code takes a long time (about 30 minutes on 2 cores).

`android/build.sh --skip-game` runs only what doesn't need the game: the launcher unit tests, the
Android builds of AdrenoTools, Vortek and the SDK libraries with the port's overlays, and a
launcher-only APK, which has no game library and can't start the game. It is a check that the
sources build.

The steps can also be run one by one:

```sh
git submodule update --init --depth 1 --recursive android/rexglue-sdk android/native/thirdparty/libadrenotools
bash android/scripts/apply_overlays.sh          # port overlays onto android/rexglue-sdk
RB2_XEX=/path/to/default.xex bash android/scripts/build_host_codegen.sh
bash android/scripts/build_android_native.sh    # librestuff.so, Xenos plugin, AdrenoTools, Vortek
(cd android && ./gradlew :app:assembleRelease)
```

`.github/workflows/android.yml` runs the checks that don't need the game on GitHub Actions
(manual trigger). The game can't be built there: game files are never put in this repository.

### Signing

The release APK is signed with the debug key unless you provide your own:

```sh
export RB2_KEYSTORE=/path/to/your.keystore   # or put it at android/keystore/release.keystore
export RB2_STORE_PASSWORD=... RB2_KEY_ALIAS=... RB2_KEY_PASSWORD=...
```

## On the phone

Install the APK, open **Raging Blast 2**, tap **Select ISO** and pick your own disc image or
extracted folder (it is read in place; nothing is copied). Logs go to
`Internal storage/Raging Blast 2/logs` once "All files access" is granted.

- **Settings:** graphics driver (system or an imported Turnip driver), FPS counter, touch
  controls and Engine options (advanced).
- Every pad and the touch overlay control player 1. For local 2-player, add
  `input_merge_controllers = false` under Engine options (advanced).
- `rb2_vsync_wait = false` under Engine options runs the game's original vsync wait instead of
  upstream's 60 Hz sleep; `rb2_cache_partition = false` turns off the `cache:` partition.

## How this differs from the desktop build

Upstream (WistfulHopes/RB2) was generated with the ReXGlue SDK of February 2026. This port uses
the SDK pin of the WET and Rumble Roses XX Android ports (`bd833a2`, v0.10.0 plus Android
overlays), where much of upstream's host code has moved into the SDK:

- `rb2/rb2_functions.toml` is upstream's function list (addresses and sizes from
  `rb2_config.toml`, written by `rb2/tools/import_upstream_functions.py`), and
  `rb2/rb2_functions_extra.toml` adds one function upstream's list leaves out.
- Upstream's `src/bdengine` heap (dlmalloc) and file I/O hooks become the SDK's native CRT
  (`rb2/rb2_rexcrt.toml`, `[rexcrt]`), at the same addresses. The SDK's file functions go
  through its virtual file system, which is what lets the launcher play from an ISO.
- Upstream's in-memory `cache:` ramdisk becomes a folder next to the saves, emptied at every
  start (`rb2/src/rb2_app.h`).
- Upstream's vsync replacement (`SpGameServer::_vsyncWait`, 0x8249B680) is kept
  (`rb2/src/rb2_hooks.cpp`).
- Upstream's memory and string hooks (`src/hooks.cpp`) are not carried over: their addresses
  fall inside larger CRT blocks in upstream's own function list, so they never bound there
  either.

## Layout

```
android/
  app/            Kotlin launcher (internal package com.deivid22srk.restuff, kept because the JNI
                  symbols bind to it; installed app id com.rexglue.rb2) and SDL3's Java layer
  native/         Android CMake project, jni/android_main.cpp
    overlay/rexglue-sdk/   the port's changes to the SDK, copied over android/rexglue-sdk by
                           scripts/apply_overlays.sh (Android surface, fibers, file system, input,
                           Vulkan fixes for Adreno, GPU plugin loading, custom drivers, guest
                           fault handling)
    thirdparty/vortek, vortekrenderer   Vortek (brunodev85, LGPL-2.1), with local changes
    thirdparty/libadrenotools           submodule, bylaws/libadrenotools @ 8fae8ce
  rexglue-sdk/    submodule, rexglue/rexglue-sdk @ bd833a2 (the WET port's pin)
  rb2/            the game project for the code generator
    src/          app class (main.cpp, rb2_app.h) and the vsync hook (rb2_hooks.cpp)
    rb2_manifest.toml, rb2_functions.toml, rb2_functions_extra.toml, rb2_rexcrt.toml
    tools/import_upstream_functions.py
    assets/       put your default.xex here if you don't use --xex (git-ignored)
  scripts/        apply_overlays.sh, build_host_codegen.sh, build_android_native.sh
  build.sh        runs everything
```

## Credits

- RB2 (Dragon Ball Raging Blast 2 recompiled): WistfulHopes
- ReXGlue SDK: Tom Clay and contributors (BSD-3-Clause, Xenia-derived)
- Android wrapper, SDK overlays and the AdrenoTools/Vortek integration this port starts from:
  Hailgames, [naughtybear-restuff-android](https://github.com/deivid22srk/naughtybear-restuff-android),
  by way of the WET and Rumble Roses XX Android ports
- Vortek: brunodev85 (LGPL-2.1); libadrenotools: bylaws (BSD-2-Clause)
