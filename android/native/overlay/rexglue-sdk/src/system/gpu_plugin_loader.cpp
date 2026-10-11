/**
 * @file        system/gpu_plugin_loader.cpp
 * @brief       Host-side loader for GPU emulation plugin DLLs
 *
 * @copyright   Copyright (c) 2026 Tom Clay <tomc@tctechstuff.com>
 *              All rights reserved.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 */

#include <rex/system/gpu_plugin.h>

#include <filesystem>
#include <string>
#include <vector>

#include <fmt/format.h>

#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/platform.h>
#include <rex/platform/dynlib.h>

#if REX_PLATFORM_ANDROID
#include <dlfcn.h>
#include <cstdlib>
#endif

namespace rex::system {

namespace {

// Plugin binaries follow the SDK's per-config postfix convention; this TU is
// part of rexruntime, so REXGLUE_BUILD_CONFIG matches the plugin's config.
std::string PluginFileName(std::string_view name) {
  constexpr std::string_view kConfig = REXGLUE_BUILD_CONFIG;
  std::string_view postfix = "";
  if (kConfig == "Debug") {
    postfix = "d";
  } else if (kConfig == "RelWithDebInfo") {
    postfix = "rd";
  }
#if REX_PLATFORM_WIN32
  return fmt::format("rexgpu-{}{}.dll", name, postfix);
#elif REX_PLATFORM_MAC
  return fmt::format("librexgpu-{}{}.dylib", name, postfix);
#else
  return fmt::format("librexgpu-{}{}.so", name, postfix);
#endif
}

// Plugins stay loaded for process lifetime: guest threads may still be in
// plugin code pages at shutdown.
std::vector<platform::DynamicLibrary>& LoadedPlugins() {
  static std::vector<platform::DynamicLibrary> plugins;
  return plugins;
}

#if REX_PLATFORM_ANDROID
// WET Android port: GetExecutableFolder() is the app's writable files dir on
// Android (the real "executable" is /system/bin/app_process64), but GPU
// plugins ship as APK native libs and are extracted to nativeLibraryDir
// (useLegacyPackaging=true). Prefer the dir exported by the launcher
// (--native-lib-dir -> REX_ANDROID_NATIVE_LIB_DIR), else the directory that
// contains this library (librexruntime.so) as resolved by dladdr.
std::filesystem::path PluginSearchDir() {
  if (const char* dir = std::getenv("REX_ANDROID_NATIVE_LIB_DIR"); dir && *dir) {
    return std::filesystem::path(dir);
  }
  Dl_info info{};
  if (dladdr(reinterpret_cast<const void*>(&PluginFileName), &info) && info.dli_fname) {
    return std::filesystem::path(info.dli_fname).parent_path();
  }
  return rex::filesystem::GetExecutableFolder();
}
#else
std::filesystem::path PluginSearchDir() {
  return rex::filesystem::GetExecutableFolder();
}
#endif

}  // namespace

std::unique_ptr<IGraphicsSystem> LoadGpuPlugin(std::string_view name, std::string_view backend) {
  auto path = PluginSearchDir() / PluginFileName(name);
  if (!std::filesystem::exists(path)) {
    REXSYS_ERROR(
        "GPU plugin '{}' not found at {}. Stage it next to the executable "
        "(GPU_PLUGINS {} in rexglue_configure_target).",
        name, path.string(), name);
    return nullptr;
  }

  platform::DynamicLibrary library;
  if (!library.Load(path, platform::SymbolResolution::kImmediate)) {
    REXSYS_ERROR("GPU plugin '{}' failed to load: {}", name, path.string());
    return nullptr;
  }

  auto abi_version_fn = library.GetSymbol<GpuAbiVersionFn>(kGpuAbiVersionSymbol);
  auto create_fn = library.GetSymbol<GpuCreateFn>(kGpuCreateSymbol);
  if (!abi_version_fn || !create_fn) {
    REXSYS_ERROR("GPU plugin '{}' is not a rexglue GPU plugin (missing {} / {} exports): {}", name,
                 kGpuAbiVersionSymbol, kGpuCreateSymbol, path.string());
    return nullptr;
  }

  uint32_t plugin_abi = abi_version_fn();
  if (plugin_abi != kGpuPluginAbiVersion) {
    REXSYS_ERROR("GPU plugin '{}' has ABI version {}, host expects {}: {}", name, plugin_abi,
                 kGpuPluginAbiVersion, path.string());
    return nullptr;
  }

  std::string backend_str(backend);
  GpuCreateInfo info{};
  info.struct_size = sizeof(GpuCreateInfo);
  info.backend = backend_str.c_str();

  IGraphicsSystem* graphics_system = create_fn(kGpuPluginAbiVersion, &info);
  if (!graphics_system) {
    REXSYS_ERROR("GPU plugin '{}' factory returned no graphics system (backend '{}')", name,
                 backend_str);
    return nullptr;
  }

  LoadedPlugins().push_back(std::move(library));
  REXSYS_DEBUG("GPU plugin '{}' loaded ({})", name, path.filename().string());
  return std::unique_ptr<IGraphicsSystem>(graphics_system);
}

}  // namespace rex::system
