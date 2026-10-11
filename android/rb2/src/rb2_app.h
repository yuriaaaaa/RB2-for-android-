// Dragon Ball Raging Blast 2 (4E4D0826): Android port on the ReXGlue SDK.
//
// The app class of the Rumble Roses XX Android port (launcher-provided paths, Vulkan GPU
// plugin) plus the one host piece of upstream's bdengine that rexcrt doesn't cover: a
// writable cache: partition. Upstream kept it in RAM (bdengine/ramdisk.cpp); here it is a
// folder next to the saves, emptied at every start so it behaves the same way.

#pragma once

#include <cstdlib>
#include <filesystem>
#include <memory>

#include <rex/cvar.h>
#include <rex/filesystem/devices/host_path_device.h>
#include <rex/filesystem/vfs.h>
#include <rex/logging.h>
#include <rex/rex_app.h>
#include <rex/runtime.h>
#include <rex/system/gpu_plugin.h>

// The launcher writes graphics_backend = "any" (WET's game_cvars.h defines it the same way).
REXCVAR_DEFINE_STRING(graphics_backend, "any", "GPU",
                      "Graphics API backend: any, d3d12, vulkan")
    .allowed({"any", "d3d12", "vulkan"});

REXCVAR_DEFINE_BOOL(rb2_cache_partition, true, "RB2",
                    "Mount a writable cache: partition (emptied at start), like upstream's ramdisk");

class Rb2App : public rex::ReXApp {
 public:
  using rex::ReXApp::ReXApp;

  static std::unique_ptr<rex::ui::WindowedApp> Create(rex::ui::WindowedAppContext& ctx) {
    return std::unique_ptr<Rb2App>(new Rb2App(ctx, "rb2", PPCImageConfig));
  }

  void OnPreSetup(rex::RuntimeConfig& config) override {
    std::string backend = REXCVAR_GET(graphics_backend);
    if (backend != "any" && !config.gpu_plugin.empty()) {
      config.graphics = rex::system::LoadGpuPlugin(config.gpu_plugin, backend);
      if (!config.graphics) {
        REXLOG_WARN("graphics_backend '{}' unavailable, falling back to automatic selection", backend);
      }
    }
  }

  void OnConfigurePaths(rex::PathConfig& paths) override {
    // The launcher (GameActivity) passes --game_data_root (the ISO or an extracted folder),
    // --user_data_root, --cache_root and --config (a toml it regenerates every start).
    if (const char* cfg = std::getenv("REX_CONFIG_PATH"); cfg && *cfg) {
      paths.config_path = cfg;
    }
  }

  void OnPostSetup() override {
    if (!REXCVAR_GET(rb2_cache_partition)) {
      return;
    }
    auto* fs = runtime()->file_system();
    const auto cache_root = runtime()->user_data_root().parent_path() / "cache_partition";
    std::error_code ec;
    std::filesystem::remove_all(cache_root, ec);
    std::filesystem::create_directories(cache_root, ec);
    auto cache = std::make_unique<rex::filesystem::HostPathDevice>("\\Device\\Rb2Cache", cache_root,
                                                                   false, true);
    if (!cache->Initialize() || !fs->RegisterDevice(std::move(cache)) ||
        !fs->RegisterSymbolicLink("cache:", "\\Device\\Rb2Cache")) {
      REXLOG_ERROR("Couldn't mount the cache: partition at {}", cache_root.string());
    } else {
      REXLOG_INFO("Mounted writable cache: partition at {}", cache_root.string());
    }
  }
};
