# libmodarchive.so: the launcher's archive extractor (zip, 7z, rar including RAR5, tar...) for
# mods (.rar/.7z/.zip). Links libarchive and liblzma statically; scripts/build_android_native.sh
# builds both first. From the Marathon Recompiled Android port.
cmake_minimum_required(VERSION 3.20)
project(modarchive C)

set(LIBARCHIVE_SOURCE_DIR "" CACHE PATH "libarchive source tree (v3.8.1)")
set(LIBARCHIVE_STATIC_LIB "" CACHE FILEPATH "libarchive.a built for Android")
set(LIBLZMA_STATIC_LIB "" CACHE FILEPATH "liblzma.a (xz 5.6.4) built for Android")

add_library(modarchive SHARED modarchive.c)
target_include_directories(modarchive PRIVATE "${LIBARCHIVE_SOURCE_DIR}/libarchive")
target_link_libraries(modarchive PRIVATE "${LIBARCHIVE_STATIC_LIB}" "${LIBLZMA_STATIC_LIB}" z)
target_link_options(modarchive PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
