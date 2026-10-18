// ReShade effects for the Android port: preset reading and .fx compilation.
#include "reshade_effects.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <fstream>
#include <memory>
#include <sstream>
#include <system_error>

#include "reshadefx/effect_codegen.hpp"
#include "reshadefx/effect_parser.hpp"
#include "reshadefx/effect_preprocessor.hpp"

namespace rex::ui::vulkan::reshade {

namespace {

std::string Trim(const std::string& s) {
  size_t a = 0, b = s.size();
  while (a < b && std::isspace(static_cast<unsigned char>(s[a]))) ++a;
  while (b > a && std::isspace(static_cast<unsigned char>(s[b - 1]))) --b;
  return s.substr(a, b - a);
}

}  // namespace

std::vector<std::string> SplitList(const std::string& value) {
  std::vector<std::string> out;
  std::string item;
  for (char c : value) {
    if (c == ',') {
      if (!Trim(item).empty()) out.push_back(Trim(item));
      item.clear();
    } else {
      item += c;
    }
  }
  if (!Trim(item).empty()) out.push_back(Trim(item));
  return out;
}

Preset ReadPreset(const std::filesystem::path& path) {
  Preset preset;
  std::ifstream in(path, std::ios::binary);
  if (!in) return preset;
  std::string line, section;
  while (std::getline(in, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    // UTF-8 byte order mark on the first line.
    if (line.size() >= 3 && static_cast<unsigned char>(line[0]) == 0xEF) line = line.substr(3);
    std::string t = Trim(line);
    if (t.empty() || t[0] == ';' || t[0] == '#') continue;
    if (t.front() == '[' && t.back() == ']') {
      section = Trim(t.substr(1, t.size() - 2));
      continue;
    }
    size_t eq = t.find('=');
    if (eq == std::string::npos) continue;
    std::string key = Trim(t.substr(0, eq)), value = Trim(t.substr(eq + 1));
    if (section.empty()) {
      preset.globals[key] = value;
    } else {
      preset.sections[section][key] = value;
    }
  }
  auto techniques = preset.globals.find("Techniques");
  if (techniques != preset.globals.end()) preset.techniques = SplitList(techniques->second);
  auto definitions = preset.globals.find("PreprocessorDefinitions");
  if (definitions != preset.globals.end()) {
    for (const std::string& d : SplitList(definitions->second)) {
      size_t eq = d.find('=');
      if (eq == std::string::npos) {
        preset.definitions.emplace_back(d, "1");
      } else {
        preset.definitions.emplace_back(Trim(d.substr(0, eq)), Trim(d.substr(eq + 1)));
      }
    }
  }
  return preset;
}

std::vector<std::filesystem::path> FindEffects(const std::filesystem::path& shaders_dir) {
  std::vector<std::filesystem::path> out;
  std::error_code ec;
  for (auto it = std::filesystem::recursive_directory_iterator(
           shaders_dir, std::filesystem::directory_options::skip_permission_denied, ec);
       !ec && it != std::filesystem::recursive_directory_iterator(); it.increment(ec)) {
    if (it->is_regular_file(ec) && it->path().extension() == ".fx") out.push_back(it->path());
  }
  std::sort(out.begin(), out.end());
  return out;
}

namespace {

CompiledEffect Compile(const std::filesystem::path& file, const std::string* source,
                       const CompileOptions& options) {
  CompiledEffect result;
  result.file = file;

  reshadefx::preprocessor pp;
  if (!source) pp.add_include_path(file.parent_path());
  for (const auto& dir : options.include_dirs) pp.add_include_path(dir);
  // Preset definitions first: add_macro_definition keeps the first one.
  for (const auto& [name, value] : options.definitions) {
    pp.add_macro_definition(name, value.empty() ? "1" : value);
  }
  pp.add_macro_definition("__RESHADE__", std::to_string(kReshadeVersion));
  pp.add_macro_definition("__RESHADE_PERMUTATION__", "0");
  pp.add_macro_definition("__RESHADE_PERFORMANCE_MODE__", "1");
  pp.add_macro_definition("__VENDOR__", std::to_string(options.vendor_id));
  pp.add_macro_definition("__DEVICE__", std::to_string(options.device_id));
  // Vulkan 1.0 (ReShade sets bit 0x20000 for Vulkan).
  pp.add_macro_definition("__RENDERER__", std::to_string(0x20000 | 0x1000));
  pp.add_macro_definition("__APPLICATION__", "0");
  pp.add_macro_definition("BUFFER_WIDTH", std::to_string(options.width));
  pp.add_macro_definition("BUFFER_HEIGHT", std::to_string(options.height));
  pp.add_macro_definition("BUFFER_RCP_WIDTH", "(1.0 / BUFFER_WIDTH)");
  pp.add_macro_definition("BUFFER_RCP_HEIGHT", "(1.0 / BUFFER_HEIGHT)");
  // sRGB color space, RGBA8 back buffer (reshade::api::format::r8g8b8a8_unorm = 28).
  pp.add_macro_definition("BUFFER_COLOR_SPACE", "1");
  pp.add_macro_definition("BUFFER_COLOR_FORMAT", "28");
  pp.add_macro_definition("BUFFER_COLOR_BIT_DEPTH", "8");

  // ReShade's conversion macros for effects written for older versions.
  pp.append_string(
      "#define tex2Doffset(s, coords, offset) tex2D(s, coords, offset)\n"
      "#define tex2Dlodoffset(s, coords, offset) tex2Dlod(s, coords, offset)\n"
      "#define tex2Dgather(s, t, c) tex2Dgather##c(s, t)\n"
      "#define tex2Dgatheroffset(s, t, o, c) tex2Dgather##c(s, t, o)\n"
      "#define tex2Dgather0 tex2DgatherR\n"
      "#define tex2Dgather1 tex2DgatherG\n"
      "#define tex2Dgather2 tex2DgatherB\n"
      "#define tex2Dgather3 tex2DgatherA\n");

  bool preprocessed = source ? pp.append_string(*source, file) : pp.append_file(file);
  result.errors += pp.errors();
  if (!preprocessed) return result;

  std::unique_ptr<reshadefx::codegen> codegen(
      reshadefx::create_codegen_spirv(true, false, false, false, false));
  reshadefx::parser parser;
  bool parsed = parser.parse(pp.output(), codegen.get());
  result.errors += parser.errors();
  if (!parsed) return result;

  result.module = codegen->module();
  for (const auto& [name, type] : result.module.entry_points) {
    std::string binary, assembly, errors;
    if (!codegen->assemble_code_for_entry_point(name, binary, assembly, errors) ||
        binary.size() % 4) {
      result.errors += errors.empty() ? "error: " + name + ": code generation failed\n" : errors;
      return result;
    }
    std::vector<uint32_t>& words = result.spirv[name];
    words.resize(binary.size() / 4);
    std::memcpy(words.data(), binary.data(), binary.size());
  }
  result.ok = true;
  return result;
}

}  // namespace

CompiledEffect CompileEffect(const std::filesystem::path& file, const CompileOptions& options) {
  return Compile(file, nullptr, options);
}

CompiledEffect CompileEffectSource(const std::string& source, const std::string& name,
                                   const CompileOptions& options) {
  return Compile(name, &source, options);
}

}  // namespace rex::ui::vulkan::reshade
