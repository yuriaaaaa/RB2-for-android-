#!/usr/bin/env bash
# Android native build of Dragon Ball Raging Blast 2 (adapted from naughtybear-restuff-android and the
# WET Android port):
#   [1d/1e] AdrenoTools + Vortek (custom Turnip driver / Vulkan compat layer)
#   [2] librestuff.so (Dragon Ball Raging Blast 2 recompiled code + app class + SDK + JNI)
#       + librexgpu-xenos.so (Xenos GPU plugin, Vulkan)
#   [3] copy into app/src/main/jniLibs/arm64-v8a
#
# Requer: ANDROID_NDK_HOME (ou NDK via ANDROID_HOME/ndk/<ver>), cmake, ninja.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RB2_SRC="${RB2_SRC:-$ROOT/rb2}"
REXSDK_SRC="${REXSDK_SRC:-$ROOT/rexglue-sdk}"
ABI="${ABI:-arm64-v8a}"
API="${API:-26}"
JNILIBS_DIR="$ROOT/app/src/main/jniLibs/$ABI"

# --- Localizar o NDK ------------------------------------------------------
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
        NDK_DIR=$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -1)
    elif [[ -d "$HOME/Android/Sdk/ndk" ]]; then
        NDK_DIR=$(ls -d "$HOME"/Android/Sdk/ndk/* | sort | tail -1)
    else
        echo "ANDROID_NDK_HOME não definido e nenhum NDK encontrado" >&2
        exit 1
    fi
else
    NDK_DIR="$ANDROID_NDK_HOME"
fi
export ANDROID_NDK_HOME="$NDK_DIR"
TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"
echo "NDK: $NDK_DIR"

BUILD_OUT="$ROOT/build/android/$ABI"
mkdir -p "$BUILD_OUT" "$JNILIBS_DIR"

# --- [1d] AdrenoTools (libadrenotools + hooks p/ driver Turnip custom) ------
# Motor do carregamento REAL do driver customizado: adrenotools_open_libvulkan
# devolve o handle do loader do sistema com hooks que redirecionam a abertura
# do driver para o .so importado (namespace ligado ao sphal, onde
# libcutils/libhardware resolvem). Requer:
#   - submódulo native/thirdparty/libadrenotools (com lib/linkernsbypass)
#   - useLegacyPackaging=true no APK (hooks como ARQUIVOS em nativeLibraryDir)
# API mínima 28 (linkernsbypass); em API < 28 o adrenotools devolve nullptr e
# o port cai no driver do sistema (fallback logado).
ADRENOTOOLS_SRC="${ADRENOTOOLS_SRC:-$ROOT/native/thirdparty/libadrenotools}"
if [[ ! -d "$ADRENOTOOLS_SRC/lib/linkernsbypass" ]]; then
    echo "ERRO: $ADRENOTOOLS_SRC sem lib/linkernsbypass (submódulos inicializados?)" >&2
    exit 1
fi
ADRENOTOOLS_BUILD="$ROOT/build/adrenotools-$ABI"
if [[ ! -f "$ADRENOTOOLS_BUILD/libadrenotools.so" ]]; then
    echo "== adrenotools: build Android ($ABI) =="
    # BUILD_SHARED_LIBS=ON: add_library(adrenotools) do upstream não declara
    # tipo → sem isso vira .a (e o --exclude-libs do upstream exige shared).
    # CMAKE_LIBRARY_OUTPUT_DIRECTORY: os 4 hooks vivem em src/hook/ sem isso —
    # unifica tudo na raiz do build p/ o loop de cópia abaixo.
    cmake -S "$ADRENOTOOLS_SRC" -B "$ADRENOTOOLS_BUILD" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-28" \
        -DANDROID_STL=c++_shared \
        -DBUILD_SHARED_LIBS=ON \
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$ADRENOTOOLS_BUILD" \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$ADRENOTOOLS_BUILD" --parallel "$(nproc)"
fi
# libadrenotools + hooks (precisam existir COMO ARQUIVOS — useLegacyPackaging)
for adrlib in libadrenotools.so libmain_hook.so libhook_impl.so \
              libfile_redirect_hook.so libgsl_alloc_hook.so; do
    if [[ ! -f "$ADRENOTOOLS_BUILD/$adrlib" ]]; then
        echo "ERRO FATAL: $adrlib não construído (build adrenotools)" >&2
        exit 1
    fi
    cp "$ADRENOTOOLS_BUILD/$adrlib" "$JNILIBS_DIR/"
done
echo "  adrenotools: 5 libs copiadas para jniLibs"

# --- [1e] Vortek (cliente libvulkan_vortek.so + servidor libvortekrenderer.so)
# Camada de compatibilidade Vulkan do Vortek (brunodev85/Winlator, LGPL-2.1):
# o cliente é um ICD que o motor dlopena (exporta vkGetInstanceProcAddr
# padrão); o servidor executa as chamadas no driver Vulkan do HOST com fixups
# (emulação de formato, BC decode, timeline semaphores, pipelines async).
# Cliente e servidor vivem no MESMO processo do jogo — ver
# native/thirdparty/vortek{,renderer}/README-PROVENANCE.md.
VORTEK_BUILD="$ROOT/build/vortek-$ABI"
if [[ ! -f "$VORTEK_BUILD/client/libvulkan_vortek.so" || ! -f "$VORTEK_BUILD/server/libvortekrenderer.so" ]]; then
    echo "== vortek: build Android ($ABI) =="
    cmake -S "$ROOT/native/thirdparty/vortek" -B "$VORTEK_BUILD/client" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-28" \
        -DANDROID_STL=none \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$VORTEK_BUILD/client" --parallel "$(nproc)"
    cmake -S "$ROOT/native/thirdparty/vortekrenderer" -B "$VORTEK_BUILD/server" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-28" \
        -DANDROID_STL=none \
        -DCMAKE_BUILD_TYPE=Release \
        -DADRENOTOOLS_INCLUDE_DIR="$ADRENOTOOLS_SRC/include" \
        -DADRENOTOOLS_LIBRARY="$ADRENOTOOLS_BUILD/libadrenotools.so"
    cmake --build "$VORTEK_BUILD/server" --parallel "$(nproc)"
fi
for vtlib in libvulkan_vortek.so libvortekrenderer.so; do
    VTLIB_PATH=$(find "$VORTEK_BUILD" -name "$vtlib" -type f 2>/dev/null | head -1)
    if [[ -z "$VTLIB_PATH" ]]; then
        echo "ERRO FATAL: $vtlib não construído (build vortek)" >&2
        exit 1
    fi
    cp "$VTLIB_PATH" "$JNILIBS_DIR/"
done
echo "  vortek: 2 libs copiadas para jniLibs"

# --- [2] librestuff.so -----------------------------------------------------
# RB2_SKIP_GAME=1: build only the SDK libraries with the port's overlays (no default.xex
# needed), as a build check. Nothing is packaged.
if [[ "${RB2_SKIP_GAME:-0}" == "1" ]]; then
    echo "== SDK libraries only (RB2_SKIP_GAME=1) =="
    cmake -S "$ROOT/native" -B "$BUILD_OUT-sdkonly" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE="${CONFIGURATION:-Release}" \
        -DRB2_SOURCE_DIR="$RB2_SRC" \
        -DREXSDK_SOURCE_DIR="$REXSDK_SRC" \
        -DRB2_SKIP_GAME=ON
    cmake --build "$BUILD_OUT-sdkonly" --parallel "$(nproc)" --target rexruntime rexgpu-xenos rexui
    echo "SDK libraries built (no game library; nothing packaged)."
    exit 0
fi
echo "== librestuff.so ($ABI) =="
cmake -S "$ROOT/native" -B "$BUILD_OUT" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE="${CONFIGURATION:-Release}" \
    -DRB2_SOURCE_DIR="$RB2_SRC" \
    -DREXSDK_SOURCE_DIR="$REXSDK_SRC"
cmake --build "$BUILD_OUT" --parallel "$(nproc)"

# ARM PERF/diagnóstico: o alvo restuff compila com -gline-tables-only
# (crash backtraces resolvíveis offline). As tabelas (~+85MB neste código
# recompilado) NÃO podem ir para o APK de todo usuário — e o AGP deste
# runner não consegue stripar nada (log: "Unable to strip ... packaging
# them as are"). Então: preserva o build COM debug num artefato separado
# (librestuff.so.unstripped — o CI o upa como "debug-symbols") e entrega ao
# APK a versão stripada. llvm-strip --strip-debug remove SÓ .debug_*;
# .dynsym (símbolos JNI), .eh_frame (unwind do crash handler) e todo o
# código/rodata ficam intactos — sem efeito em runtime.
# NOTA: no NDK r27 os binários de toolchain são SYMLINKS — o find NÃO pode
# filtrar por -type f (primeira iteração perdeu o llvm-strip por isso);
# fallbacks: llvm-strip/llvm-strip-19 do PATH (o CI instala LLVM 19 via
# llvm.sh; strip de ELF é independente do target arch).
LLVM_STRIP=$(find "$NDK_DIR/toolchains/llvm/prebuilt" -name llvm-strip 2>/dev/null | head -1)
if [[ -z "$LLVM_STRIP" ]]; then
    for cand in llvm-strip llvm-strip-19; do
        if command -v "$cand" >/dev/null 2>&1; then LLVM_STRIP="$cand"; break; fi
    done
fi
if [[ -n "$LLVM_STRIP" ]]; then
    # Strip into a separate file: stripping librestuff.so in place made a
    # rebuild that did not relink overwrite the .unstripped copy with the
    # already-stripped library.
    cp "$BUILD_OUT/librestuff.so" "$BUILD_OUT/librestuff.so.unstripped"
    "$LLVM_STRIP" --strip-debug -o "$BUILD_OUT/librestuff.stripped.so" "$BUILD_OUT/librestuff.so"
    echo "  librestuff stripada ($("$LLVM_STRIP" --version | head -1)):"
    stat -c '    APK recebe: %s bytes' "$BUILD_OUT/librestuff.stripped.so"
    stat -c '    símbolos (unstripped): %s bytes' "$BUILD_OUT/librestuff.so.unstripped"
else
    echo "AVISO: llvm-strip não encontrado (NDK nem PATH) — APK incluirá as line tables (+~85MB)" >&2
fi

# --- [3] Empacotar jniLibs -------------------------------------------------
if [[ -f "$BUILD_OUT/librestuff.stripped.so" ]]; then
    cp "$BUILD_OUT/librestuff.stripped.so" "$JNILIBS_DIR/librestuff.so"
else
    cp "$BUILD_OUT/librestuff.so" "$JNILIBS_DIR/"
fi
# O SDK constrói rexruntime como SHARED e despeja binários em
# rexglue-sdk/out/<REX_PLATFORM>/ (no NDK a detecção do SDK não tem branch
# Android → vira "linux-arm64"). Buscar nos dois lugares:
# Only out/linux-arm64: the host code generator's build (build_host_codegen.sh) writes an
# x86-64 librexruntime.so to out/linux-amd64 of the same tree.
REXRUNTIME=$(find "$REXSDK_SRC/out/linux-arm64" "$BUILD_OUT" -name "librexruntime.so" -type f 2>/dev/null | head -1)
if [[ -n "$REXRUNTIME" ]]; then
    # Same treatment as librestuff: the SDK builds rexruntime with ~80 MB of
    # debug info. Keep the full copy for offline symbolization, ship stripped.
    cp "$REXRUNTIME" "$BUILD_OUT/librexruntime.so.unstripped"
    if [[ -n "${LLVM_STRIP:-}" ]]; then
        "$LLVM_STRIP" --strip-debug -o "$JNILIBS_DIR/librexruntime.so" "$REXRUNTIME"
    else
        cp "$REXRUNTIME" "$JNILIBS_DIR/"
    fi
    echo "  librexruntime: $REXRUNTIME"
else
    echo "ERRO FATAL: librexruntime.so not found" >&2
    exit 1
fi
# Xenos GPU plugin (dlopen()ed by rexruntime from nativeLibraryDir — see the
# gpu_plugin_loader.cpp overlay). Without it the game cannot render.
XENOS=$(find "$REXSDK_SRC/out/linux-arm64" "$BUILD_OUT" -name "librexgpu-xenos*.so" -type f 2>/dev/null | head -1)
if [[ -z "$XENOS" ]]; then
    echo "ERRO FATAL: librexgpu-xenos.so not found" >&2
    exit 1
fi
if [[ -n "${LLVM_STRIP:-}" ]]; then
    "$LLVM_STRIP" --strip-debug -o "$JNILIBS_DIR/$(basename "$XENOS")" "$XENOS"
else
    cp "$XENOS" "$JNILIBS_DIR/"
fi
echo "  xenos GPU plugin: $XENOS"
# STL compartilhada: OBRIGATÓRIA com c++_shared (rexruntime SHARED e
# librestuff passam std::string/vector entre si — duas libc++ estáticas
# num mesmo processo = heaps duplicados = crash em tempo de execução).
# ⚠️ O sysroot do NDK usa o TRIPLE LLVM, não o nome do ABI Android:
#    arm64-v8a → aarch64-linux-android (procurar por "arm64-v8a" falha
#    silenciosamente e o APK sai sem libc++_shared.so → dlopen crash).
case "$ABI" in
    arm64-v8a)   TRIPLE="aarch64-linux-android" ;;
    armeabi-v7a) TRIPLE="armv7a-linux-androideabi" ;;
    x86_64)      TRIPLE="x86_64-linux-android" ;;
    *)           TRIPLE="i686-linux-android" ;;
esac
SYSROOT_LIB="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE"
LIBCXX_SHARED="$SYSROOT_LIB/libc++_shared.so"
if [[ ! -f "$LIBCXX_SHARED" ]]; then
    # Fallback: busca genérica pelo triple no prebuilt
    LIBCXX_SHARED=$(find "$NDK_DIR/toolchains/llvm/prebuilt" \
        -path "*/sysroot/usr/lib/$TRIPLE/libc++_shared.so" -type f 2>/dev/null | head -1)
fi
if [[ -z "$LIBCXX_SHARED" || ! -f "$LIBCXX_SHARED" ]]; then
    echo "ERRO FATAL: libc++_shared.so não encontrada no NDK (triple: $TRIPLE)" >&2
    echo "  Sem ela o APK crasha no device: dlopen failed: libc++_shared.so not found" >&2
    exit 1
fi
cp "$LIBCXX_SHARED" "$JNILIBS_DIR/"
echo "  libc++_shared: $LIBCXX_SHARED"
# Garantia final: a lib TEM que estar no jniLibs
test -f "$JNILIBS_DIR/libc++_shared.so" || { echo "ERRO FATAL: cp falhou" >&2; exit 1; }
# Every packaged library must be AArch64 (a host library here would only fail on the phone).
READELF=$(find "$NDK_DIR/toolchains/llvm/prebuilt" -name llvm-readelf 2>/dev/null | head -1)
if [[ -n "$READELF" ]]; then
    for so in "$JNILIBS_DIR"/*.so; do
        if ! "$READELF" -h "$so" | grep -q "AArch64"; then
            echo "ERROR: $so is not an AArch64 library" >&2
            exit 1
        fi
    done
fi
ls -la "$JNILIBS_DIR/"
echo "Build nativo Android concluído."
