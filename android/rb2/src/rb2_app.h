// Dragon Ball Raging Blast 2 (4E4D0826): Android port on the ReXGlue SDK.
//
// The app class of the Rumble Roses XX Android port (launcher-provided paths, Vulkan GPU
// plugin) plus the one host piece of upstream's bdengine that rexcrt doesn't cover: a
// writable cache: partition. Upstream kept it in RAM (bdengine/ramdisk.cpp); here it is a
// folder next to the saves, emptied at every start so it behaves the same way.

#pragma once

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include <rex/cvar.h>
#include <rex/filesystem/devices/host_path_device.h>
#include <rex/filesystem/vfs.h>
#include <rex/logging.h>
#include <rex/rex_app.h>
#include <rex/runtime.h>
#include <rex/system/gpu_plugin.h>
#include <rex/system/xmemory.h>

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

  void OnPostLoadXexImage() override { DumpPackIndex(); }

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

 private:
  // The .afs packs carry no file names: the game finds an entry by hashing its logical path
  // (h = h * 137 + byte, e.g. "battle/character/model/chara_000_2p_model_i.pak") and looking
  // the hash up in a per-pack table compiled into the xex, where the position is the AFS
  // index. The launcher's mod installer needs those tables to put a mod file in the right
  // slot, and only the running game has the decrypted image, so they are written next to
  // the saves as pack-hashes.bin ("RB2H", pack count, then per pack: name length, name,
  // entry count, hashes; little-endian).
  static constexpr uint32_t kPackTable = 0x82613628;  // {flags, name*, count, hashes*} x 6
  static constexpr uint32_t kPackCount = 6;

  void DumpPackIndex() {
    auto* mem = runtime()->memory();
    auto be32 = [mem](uint32_t addr) {
      uint32_t v;
      std::memcpy(&v, mem->TranslateVirtual(addr), 4);
      return __builtin_bswap32(v);
    };
    auto le32 = [](std::string& out, uint32_t v) {
      for (int i = 0; i < 4; i++) out.push_back(char((v >> (8 * i)) & 0xFF));
    };
    std::string out = "RB2H";
    le32(out, kPackCount);
    for (uint32_t i = 0; i < kPackCount; i++) {
      const uint32_t rec = kPackTable + i * 16;
      const uint32_t name_ptr = be32(rec + 4), count = be32(rec + 8), hashes = be32(rec + 12);
      if (name_ptr < 0x82000000 || hashes < 0x82000000 || count == 0 || count > 1000000) {
        REXLOG_WARN("pack index: record {} looks wrong; this xex isn't the one the mod installer knows", i);
        return;
      }
      std::string name(mem->TranslateVirtual<const char*>(name_ptr));
      if (name.size() > 64 || name.find(".afs") == std::string::npos) {
        REXLOG_WARN("pack index: record {} has no pack name; skipping the dump", i);
        return;
      }
      out.push_back(char(name.size()));
      out += name;
      le32(out, count);
      for (uint32_t k = 0; k < count; k++) le32(out, be32(hashes + k * 4));
    }
    const auto path = runtime()->user_data_root().parent_path() / "pack-hashes.bin";
    const auto tmp = path.string() + ".tmp";
    {
      std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
      f.write(out.data(), std::streamsize(out.size()));
      if (!f) {
        REXLOG_WARN("pack index: couldn't write {}", tmp);
        return;
      }
    }
    std::error_code ec;
    std::filesystem::rename(tmp, path, ec);
    if (ec) REXLOG_WARN("pack index: couldn't move it to {}: {}", path.string(), ec.message());
    else REXLOG_INFO("pack index: wrote {} ({} bytes)", path.string(), out.size());
  }
};
