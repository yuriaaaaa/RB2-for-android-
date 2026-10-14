// ReShade effects for the Android port: preset reading and .fx compilation
// (ReShadeFX, from crosire/reshade 6.8, BSD-3-Clause; see reshadefx/LICENSE.md).
// Nothing here touches Vulkan, so it also builds on the host for tests.
#pragma once

#include <cstdint>
#include <filesystem>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "reshadefx/effect_module.hpp"

namespace rex::ui::vulkan::reshade {

// The ReShade version effects see in __RESHADE__.
constexpr int kReshadeVersion = 60800;

// A ReShade preset (.ini): the techniques to run, in order, and the values of
// each effect's uniforms ([File.fx] sections) and preprocessor definitions.
struct Preset {
  // "Name@File.fx" (or just "Name"), in the order they run.
  std::vector<std::string> techniques;
  std::vector<std::pair<std::string, std::string>> definitions;
  // Section ("File.fx") -> key -> value as written.
  std::map<std::string, std::map<std::string, std::string>> sections;
  // Global (no section) keys.
  std::map<std::string, std::string> globals;
};

Preset ReadPreset(const std::filesystem::path& path);

struct CompiledEffect {
  std::filesystem::path file;
  bool ok = false;
  std::string errors;
  reshadefx::effect_module module;
  // Entry point name -> SPIR-V words.
  std::map<std::string, std::vector<uint32_t>> spirv;
};

struct CompileOptions {
  uint32_t width = 1280;
  uint32_t height = 720;
  uint32_t vendor_id = 0;
  uint32_t device_id = 0;
  std::vector<std::filesystem::path> include_dirs;
  std::vector<std::pair<std::string, std::string>> definitions;
};

CompiledEffect CompileEffect(const std::filesystem::path& file, const CompileOptions& options);
CompiledEffect CompileEffectSource(const std::string& source, const std::string& name,
                                   const CompileOptions& options);

// The effect files a folder holds (*.fx, searched recursively).
std::vector<std::filesystem::path> FindEffects(const std::filesystem::path& shaders_dir);

// Splits "a,b,c" (ReShade's list syntax) into its parts.
std::vector<std::string> SplitList(const std::string& value);

}  // namespace rex::ui::vulkan::reshade
