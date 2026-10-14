// ReShade effects on the guest output (Android port).
//
// A small Vulkan runtime for ReShade .fx effects: the presenter hands it each new
// guest frame, it runs the preset's techniques in order and writes the result into
// an image the presenter then scales to the screen as usual. What ReShade itself
// does through its overlay (picking effects, tuning values) comes from the preset
// the launcher writes. There is no depth buffer, so depth-based effects see none.
#include "reshade_runtime.h"

#include <rex/ui/vulkan/presenter.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iterator>
#include <system_error>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/ui/vulkan/instance.h>
#include <rex/ui/vulkan/util.h>

#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#define STBI_ONLY_BMP
#define STBI_ONLY_TGA
#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#include <stb_image.h>

REXCVAR_DEFINE_BOOL(reshade_enabled, false, "UI/ReShade",
                    "Run ReShade effects (.fx) on the game's output");
REXCVAR_DEFINE_STRING(reshade_path, "", "UI/ReShade",
                      "ReShade folder: Shaders/ and Textures/ inside it");
REXCVAR_DEFINE_STRING(reshade_preset, "", "UI/ReShade",
                      "ReShade preset (.ini): which techniques run, and their settings");

namespace rex::ui::vulkan::reshade {

namespace {

// Full-screen copy of the effect output into the presenter's image.
constexpr const char* kCopyEffect = R"(
texture2D BackBufferTex : COLOR;
sampler2D BackBuffer { Texture = BackBufferTex; };
void CopyVS(uint id : SV_VertexID, out float4 position : SV_Position, out float2 texcoord : TEXCOORD)
{
	texcoord.x = (id == 2) ? 2.0 : 0.0;
	texcoord.y = (id == 1) ? 2.0 : 0.0;
	position = float4(texcoord * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
}
float4 CopyPS(float4 position : SV_Position, float2 texcoord : TEXCOORD) : SV_Target
{
	return tex2D(BackBuffer, texcoord);
}
technique Copy { pass { VertexShader = CopyVS; PixelShader = CopyPS; } }
)";

VkFormat FormatFor(reshadefx::texture_format format) {
  using f = reshadefx::texture_format;
  switch (format) {
    case f::r8: return VK_FORMAT_R8_UNORM;
    case f::r16f: return VK_FORMAT_R16_SFLOAT;
    case f::r16: return VK_FORMAT_R16_UNORM;
    case f::r32f: return VK_FORMAT_R32_SFLOAT;
    case f::r32u: return VK_FORMAT_R32_UINT;
    case f::r32i: return VK_FORMAT_R32_SINT;
    case f::rg8: return VK_FORMAT_R8G8_UNORM;
    case f::rg16f: return VK_FORMAT_R16G16_SFLOAT;
    case f::rg16: return VK_FORMAT_R16G16_UNORM;
    case f::rg32f: return VK_FORMAT_R32G32_SFLOAT;
    case f::rgba16f: return VK_FORMAT_R16G16B16A16_SFLOAT;
    case f::rgba16: return VK_FORMAT_R16G16B16A16_UNORM;
    case f::rgba32f: return VK_FORMAT_R32G32B32A32_SFLOAT;
    case f::rgba32u: return VK_FORMAT_R32G32B32A32_UINT;
    case f::rgba32i: return VK_FORMAT_R32G32B32A32_SINT;
    case f::rgb10a2: return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    case f::rg11b10f: return VK_FORMAT_B10G11R11_UFLOAT_PACK32;
    case f::rgba8:
    default: return VK_FORMAT_R8G8B8A8_UNORM;
  }
}

// Formats a phone may not render to, and what to use instead.
VkFormat FallbackFor(VkFormat format) {
  switch (format) {
    case VK_FORMAT_R16_UNORM: return VK_FORMAT_R16_SFLOAT;
    case VK_FORMAT_R16G16_UNORM: return VK_FORMAT_R16G16_SFLOAT;
    case VK_FORMAT_R16G16B16A16_UNORM: return VK_FORMAT_R16G16B16A16_SFLOAT;
    case VK_FORMAT_B10G11R11_UFLOAT_PACK32: return VK_FORMAT_R16G16B16A16_SFLOAT;
    case VK_FORMAT_R32G32_SFLOAT: return VK_FORMAT_R32G32B32A32_SFLOAT;
    default: return VK_FORMAT_R16G16B16A16_SFLOAT;
  }
}

VkBlendFactor BlendFactor(reshadefx::blend_factor f) {
  using b = reshadefx::blend_factor;
  switch (f) {
    case b::zero: return VK_BLEND_FACTOR_ZERO;
    case b::one: return VK_BLEND_FACTOR_ONE;
    case b::source_color: return VK_BLEND_FACTOR_SRC_COLOR;
    case b::one_minus_source_color: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
    case b::dest_color: return VK_BLEND_FACTOR_DST_COLOR;
    case b::one_minus_dest_color: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
    case b::source_alpha: return VK_BLEND_FACTOR_SRC_ALPHA;
    case b::one_minus_source_alpha: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    case b::dest_alpha: return VK_BLEND_FACTOR_DST_ALPHA;
    case b::one_minus_dest_alpha: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
  }
  return VK_BLEND_FACTOR_ONE;
}

VkBlendOp BlendOp(reshadefx::blend_op op) {
  using o = reshadefx::blend_op;
  switch (op) {
    case o::subtract: return VK_BLEND_OP_SUBTRACT;
    case o::reverse_subtract: return VK_BLEND_OP_REVERSE_SUBTRACT;
    case o::min: return VK_BLEND_OP_MIN;
    case o::max: return VK_BLEND_OP_MAX;
    case o::add:
    default: return VK_BLEND_OP_ADD;
  }
}

VkPrimitiveTopology Topology(reshadefx::primitive_topology t) {
  using p = reshadefx::primitive_topology;
  switch (t) {
    case p::point_list: return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
    case p::line_list: return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
    case p::line_strip: return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
    case p::triangle_strip: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    case p::triangle_list:
    default: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
  }
}

VkSamplerAddressMode AddressMode(reshadefx::texture_address_mode m) {
  using a = reshadefx::texture_address_mode;
  switch (m) {
    case a::wrap: return VK_SAMPLER_ADDRESS_MODE_REPEAT;
    case a::mirror: return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    case a::border: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
    case a::clamp:
    default: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  }
}

const reshadefx::annotation* FindAnnotation(const std::vector<reshadefx::annotation>& list,
                                            const char* name) {
  for (const auto& a : list) {
    if (a.name == name) return &a;
  }
  return nullptr;
}

std::string AnnotationString(const std::vector<reshadefx::annotation>& list, const char* name) {
  const reshadefx::annotation* a = FindAnnotation(list, name);
  return a ? a->value.string_data : std::string();
}

float AnnotationFloat(const std::vector<reshadefx::annotation>& list, const char* name,
                      size_t index = 0, float fallback = 0.0f) {
  const reshadefx::annotation* a = FindAnnotation(list, name);
  if (!a || index >= 16) return fallback;
  if (a->type.is_floating_point()) return a->value.as_float[index];
  if (a->type.is_signed()) return float(a->value.as_int[index]);
  return float(a->value.as_uint[index]);
}

bool IsSrgbCapable(VkFormat format) { return format == VK_FORMAT_R8G8B8A8_UNORM; }

std::string ToLower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
  return s;
}

// Writes `count` 4-byte values into a uniform, following the codegen's layout (each
// array element and matrix row starts on 16 bytes).
void WriteUniformRaw(std::vector<uint8_t>& storage, const reshadefx::uniform& u,
                     const uint32_t* data, size_t count) {
  const size_t array_length = u.type.is_array() ? u.type.array_length : 1;
  auto put = [&](size_t byte_offset, uint32_t value) {
    if (byte_offset + 4 <= u.size && u.offset + byte_offset + 4 <= storage.size()) {
      std::memcpy(storage.data() + u.offset + byte_offset, &value, 4);
    }
  };
  size_t i = 0;
  if (u.type.is_matrix()) {
    for (size_t a = 0; a < array_length; ++a)
      for (size_t row = 0; row < u.type.rows; ++row)
        for (size_t col = 0; col < u.type.cols && i < count; ++col, ++i)
          put((a * u.type.rows * 4 + row * 4 + col) * 4, data[i]);
  } else if (array_length > 1) {
    for (size_t a = 0; a < array_length; ++a)
      for (size_t row = 0; row < u.type.rows && i < count; ++row, ++i)
        put((a * 4 + row) * 4, data[i]);
  } else {
    for (; i < count && i < u.type.components(); ++i) put(i * 4, data[i]);
  }
}

void WriteUniform(std::vector<uint8_t>& storage, const reshadefx::uniform& u,
                  const double* values, size_t count) {
  std::vector<uint32_t> raw(count);
  for (size_t i = 0; i < count; ++i) {
    if (u.type.is_floating_point()) {
      float f = float(values[i]);
      std::memcpy(&raw[i], &f, 4);
    } else if (u.type.is_boolean()) {
      raw[i] = values[i] != 0.0 ? 1u : 0u;
    } else if (u.type.is_signed()) {
      int32_t v = int32_t(std::lround(values[i]));
      std::memcpy(&raw[i], &v, 4);
    } else {
      raw[i] = uint32_t(std::max<long>(0, std::lround(values[i])));
    }
  }
  WriteUniformRaw(storage, u, raw.data(), raw.size());
}

void ReadUniformFloats(const std::vector<uint8_t>& storage, const reshadefx::uniform& u,
                       float* out, size_t count) {
  for (size_t i = 0; i < count; ++i) {
    out[i] = 0.0f;
    if (u.offset + (i + 1) * 4 > storage.size() || (i + 1) * 4 > u.size) continue;
    uint32_t raw;
    std::memcpy(&raw, storage.data() + u.offset + i * 4, 4);
    if (u.type.is_floating_point()) {
      std::memcpy(&out[i], &raw, 4);
    } else if (u.type.is_signed()) {
      out[i] = float(int32_t(raw));
    } else {
      out[i] = float(raw);
    }
  }
}

// True when `source` has "technique <name>" outside a // comment.
bool DeclaresTechnique(const std::string& source, const std::string& name) {
  auto ident = [](char c) { return std::isalnum(static_cast<unsigned char>(c)) || c == '_'; };
  for (size_t at = source.find("technique"); at != std::string::npos;
       at = source.find("technique", at + 1)) {
    if (at > 0 && ident(source[at - 1])) continue;
    size_t line = source.rfind('\n', at);
    line = line == std::string::npos ? 0 : line + 1;
    if (source.substr(line, at - line).find("//") != std::string::npos) continue;
    size_t p = at + 9;
    if (p >= source.size() || !std::isspace(static_cast<unsigned char>(source[p]))) continue;
    while (p < source.size() && std::isspace(static_cast<unsigned char>(source[p]))) ++p;
    if (source.compare(p, name.size(), name) == 0 &&
        (p + name.size() == source.size() || !ident(source[p + name.size()]))) {
      return true;
    }
  }
  return false;
}

}  // namespace

std::unique_ptr<Runtime> Runtime::CreateIfEnabled(const VulkanDevice* device) {
  if (!REXCVAR_GET(reshade_enabled) || REXCVAR_GET(reshade_preset).empty()) return nullptr;
  if (device->properties().apiVersion < VK_MAKE_API_VERSION(0, 1, 1, 0)) {
    REXLOG_WARN("ReShade: needs Vulkan 1.1; effects are off");
    return nullptr;
  }
  std::unique_ptr<Runtime> runtime(new Runtime(device));
  if (!runtime->cmd_blit_image_ || !runtime->cmd_copy_image_) return nullptr;
  return runtime;
}

Runtime::Runtime(const VulkanDevice* device) : device_(device) {
  auto get = device->vulkan_instance()->functions().vkGetDeviceProcAddr;
  cmd_blit_image_ =
      reinterpret_cast<PFN_vkCmdBlitImage>(get(device->device(), "vkCmdBlitImage"));
  cmd_copy_image_ =
      reinterpret_cast<PFN_vkCmdCopyImage>(get(device->device(), "vkCmdCopyImage"));
  root_ = REXCVAR_GET(reshade_path);
  preset_path_ = REXCVAR_GET(reshade_preset);
  if (root_.empty()) root_ = preset_path_.parent_path();
  start_ = last_ = std::chrono::steady_clock::now();
}

Runtime::~Runtime() { Release(); }

void RuntimeDeleter::operator()(Runtime* runtime) const { delete runtime; }

void Runtime::Log(const std::string& line) {
  log_ += line;
  if (log_.empty() || log_.back() != '\n') log_ += '\n';
}

void Runtime::WriteLog() {
  std::error_code ec;
  std::ofstream out(root_ / "ReShade.log", std::ios::binary | std::ios::trunc);
  if (out) out << log_;
}

void Runtime::Release() {
  const auto& dfn = device_->functions();
  VkDevice device = device_->device();
  auto release_effect = [&](Effect& e) {
    for (Technique& t : e.techniques) {
      for (Pass& p : t.passes) {
        util::DestroyAndNullHandle(dfn.vkDestroyFramebuffer, device, p.framebuffer);
        util::DestroyAndNullHandle(dfn.vkDestroyRenderPass, device, p.render_pass);
        util::DestroyAndNullHandle(dfn.vkDestroyPipeline, device, p.pipeline);
        util::DestroyAndNullHandle(dfn.vkDestroyPipelineLayout, device, p.layout);
        util::DestroyAndNullHandle(dfn.vkDestroyDescriptorSetLayout, device, p.samplers_layout);
        util::DestroyAndNullHandle(dfn.vkDestroyDescriptorSetLayout, device, p.storages_layout);
      }
    }
    for (auto& [name, module] : e.modules) dfn.vkDestroyShaderModule(device, module, nullptr);
    e.modules.clear();
    for (Buffer& b : e.ubos) DestroyBuffer(b);
    e.ubos.clear();
    e.techniques.clear();
  };
  for (auto& e : effects_) release_effect(*e);
  effects_.clear();
  if (copy_) release_effect(*copy_);
  copy_.reset();
  copy_view_ = VK_NULL_HANDLE;
  order_.clear();
  for (auto& [name, image] : textures_) DestroyImage(*image);
  textures_.clear();
  for (auto& [key, sampler] : samplers_) dfn.vkDestroySampler(device, sampler, nullptr);
  samplers_.clear();
  for (Buffer& b : uploads_) DestroyBuffer(b);
  uploads_.clear();
  pending_uploads_.clear();
  DestroyImage(color_);
  DestroyImage(target_);
  DestroyImage(depth_dummy_);
  util::DestroyAndNullHandle(dfn.vkDestroyDescriptorPool, device, pool_);
  loaded_ = false;
}

bool Runtime::CreateImage(Image& image, VkFormat format, uint32_t width, uint32_t height,
                          uint32_t levels, bool render_target, bool storage, bool srgb_views) {
  const auto& dfn = device_->functions();
  const auto& ifn = device_->vulkan_instance()->functions();
  VkDevice device = device_->device();

  VkFormatProperties props;
  ifn.vkGetPhysicalDeviceFormatProperties(device_->physical_device(), format, &props);
  VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
  if (render_target) need |= VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
  if (storage) need |= VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
  if (levels > 1) need |= VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
  if ((props.optimalTilingFeatures & need) != need) {
    VkFormat fallback = FallbackFor(format);
    if (fallback == format) return false;
    Log("note: format " + std::to_string(int(format)) + " isn't supported here, using " +
        std::to_string(int(fallback)));
    return CreateImage(image, fallback, width, height, levels, render_target, storage, false);
  }

  srgb_views = srgb_views && IsSrgbCapable(format);
  VkImageCreateInfo info = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  info.flags = srgb_views ? VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0;
  info.imageType = VK_IMAGE_TYPE_2D;
  info.format = format;
  info.extent = {std::max(1u, width), std::max(1u, height), 1};
  info.mipLevels = std::max(1u, levels);
  info.arrayLayers = 1;
  info.samples = VK_SAMPLE_COUNT_1_BIT;
  info.tiling = VK_IMAGE_TILING_OPTIMAL;
  info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
               VK_IMAGE_USAGE_TRANSFER_DST_BIT;
  if (render_target) info.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
  if (storage) info.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
  info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (!util::CreateDedicatedAllocationImage(device_, info, util::MemoryPurpose::kDeviceLocal,
                                            image.image, image.memory)) {
    return false;
  }
  image.format = format;
  image.width = info.extent.width;
  image.height = info.extent.height;
  image.levels = info.mipLevels;
  image.layout = VK_IMAGE_LAYOUT_UNDEFINED;
  image.storage = storage;

  auto make_view = [&](VkFormat f, uint32_t level_count, VkImageView& out) {
    VkImageViewCreateInfo v = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    v.image = image.image;
    v.viewType = VK_IMAGE_VIEW_TYPE_2D;
    v.format = f;
    v.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, level_count, 0, 1};
    return dfn.vkCreateImageView(device, &v, nullptr, &out) == VK_SUCCESS;
  };
  if (!make_view(format, image.levels, image.view)) return false;
  if (render_target && !make_view(format, 1, image.rtv)) return false;
  if (srgb_views) {
    if (!make_view(VK_FORMAT_R8G8B8A8_SRGB, image.levels, image.view_srgb)) return false;
    if (render_target && !make_view(VK_FORMAT_R8G8B8A8_SRGB, 1, image.rtv_srgb)) return false;
  }
  return true;
}

void Runtime::DestroyImage(Image& image) {
  const auto& dfn = device_->functions();
  VkDevice device = device_->device();
  util::DestroyAndNullHandle(dfn.vkDestroyImageView, device, image.view);
  util::DestroyAndNullHandle(dfn.vkDestroyImageView, device, image.view_srgb);
  util::DestroyAndNullHandle(dfn.vkDestroyImageView, device, image.rtv);
  util::DestroyAndNullHandle(dfn.vkDestroyImageView, device, image.rtv_srgb);
  util::DestroyAndNullHandle(dfn.vkDestroyImage, device, image.image);
  util::DestroyAndNullHandle(dfn.vkFreeMemory, device, image.memory);
  image = Image();
}

bool Runtime::CreateBuffer(Buffer& buffer, VkDeviceSize size, VkBufferUsageFlags usage) {
  const auto& dfn = device_->functions();
  buffer.size = std::max<VkDeviceSize>(size, 16);
  if (!util::CreateDedicatedAllocationBuffer(device_, buffer.size, usage,
                                             util::MemoryPurpose::kUpload, buffer.buffer,
                                             buffer.memory, &buffer.memory_type)) {
    return false;
  }
  return dfn.vkMapMemory(device_->device(), buffer.memory, 0, VK_WHOLE_SIZE, 0,
                         &buffer.mapping) == VK_SUCCESS;
}

void Runtime::DestroyBuffer(Buffer& buffer) {
  const auto& dfn = device_->functions();
  VkDevice device = device_->device();
  if (buffer.mapping) dfn.vkUnmapMemory(device, buffer.memory);
  util::DestroyAndNullHandle(dfn.vkDestroyBuffer, device, buffer.buffer);
  util::DestroyAndNullHandle(dfn.vkFreeMemory, device, buffer.memory);
  buffer = Buffer();
}

VkSampler Runtime::SamplerFor(const reshadefx::sampler_desc& desc) {
  uint64_t key = uint64_t(desc.filter) | uint64_t(desc.address_u) << 8 |
                 uint64_t(desc.address_v) << 16 | uint64_t(desc.address_w) << 24;
  float lods[3] = {desc.min_lod, desc.max_lod, desc.lod_bias};
  uint64_t h = 1469598103934665603ull;
  for (float f : lods) {
    uint32_t bits;
    std::memcpy(&bits, &f, 4);
    h = (h ^ bits) * 1099511628211ull;
  }
  key ^= h << 32;
  auto it = samplers_.find(key);
  if (it != samplers_.end()) return it->second;

  const uint32_t filter = uint32_t(desc.filter);
  VkSamplerCreateInfo info = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  info.magFilter = (filter & 0x04) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
  info.minFilter = (filter & 0x10) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
  info.mipmapMode = (filter & 0x01) ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
  info.addressModeU = AddressMode(desc.address_u);
  info.addressModeV = AddressMode(desc.address_v);
  info.addressModeW = AddressMode(desc.address_w);
  info.mipLodBias = desc.lod_bias;
  info.minLod = std::max(desc.min_lod, 0.0f);
  info.maxLod = std::min(desc.max_lod, 1000.0f);
  info.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
  VkSampler sampler = VK_NULL_HANDLE;
  device_->functions().vkCreateSampler(device_->device(), &info, nullptr, &sampler);
  samplers_[key] = sampler;
  return sampler;
}

bool Runtime::LoadSourceTexture(Image& image, const std::string& source) {
  // Search Textures/ (and the whole ReShade folder) for the file, like ReShade does.
  std::filesystem::path found;
  std::error_code ec;
  const std::string wanted = ToLower(std::filesystem::path(source).filename().string());
  for (const auto& dir : {root_ / "Textures", root_}) {
    if (std::filesystem::exists(dir / source, ec)) {
      found = dir / source;
      break;
    }
    for (auto it = std::filesystem::recursive_directory_iterator(
             dir, std::filesystem::directory_options::skip_permission_denied, ec);
         !ec && it != std::filesystem::recursive_directory_iterator(); it.increment(ec)) {
      if (it->is_regular_file(ec) && ToLower(it->path().filename().string()) == wanted) {
        found = it->path();
        break;
      }
    }
    if (!found.empty()) break;
  }
  if (found.empty()) {
    Log("warning: texture file \"" + source + "\" not found in Textures/");
    return false;
  }
  int w = 0, h = 0, n = 0;
  stbi_uc* pixels = stbi_load(found.string().c_str(), &w, &h, &n, 4);
  if (!pixels) {
    Log("warning: couldn't read " + found.filename().string());
    return false;
  }
  // Resize (nearest) to the texture's size when the file differs, as ReShade does.
  std::vector<uint8_t> data(size_t(image.width) * image.height * 4);
  for (uint32_t y = 0; y < image.height; ++y) {
    uint32_t sy = uint32_t(uint64_t(y) * h / image.height);
    for (uint32_t x = 0; x < image.width; ++x) {
      uint32_t sx = uint32_t(uint64_t(x) * w / image.width);
      std::memcpy(&data[(size_t(y) * image.width + x) * 4], &pixels[(size_t(sy) * w + sx) * 4], 4);
    }
  }
  stbi_image_free(pixels);
  if (image.format != VK_FORMAT_R8G8B8A8_UNORM) {
    Log("warning: " + source + " needs an RGBA8 texture; left blank");
    return false;
  }
  pending_uploads_.emplace_back(&image, std::move(data));
  return true;
}

Runtime::Image* Runtime::TextureFor(const reshadefx::texture& texture) {
  if (texture.semantic == "COLOR") return &color_;
  if (texture.semantic == "DEPTH") return &depth_dummy_;
  if (!texture.semantic.empty()) return &depth_dummy_;
  auto it = textures_.find(texture.unique_name);
  if (it != textures_.end()) return it->second.get();
  auto image = std::make_unique<Image>();
  if (!CreateImage(*image, FormatFor(texture.format), texture.width, texture.height,
                   texture.levels, true, texture.storage_access, true)) {
    Log("error: couldn't create texture " + texture.unique_name);
    return nullptr;
  }
  std::string source = AnnotationString(texture.annotations, "source");
  if (!source.empty()) LoadSourceTexture(*image, source);
  Image* raw = image.get();
  textures_[texture.unique_name] = std::move(image);
  return raw;
}

void Runtime::InitUniforms(Effect& effect, const Preset& preset) {
  const auto& module = effect.compiled.module;
  effect.uniforms.assign((module.total_uniform_size + 15) & ~15u, 0);
  const std::map<std::string, std::string>* values = nullptr;
  auto section = preset.sections.find(effect.section);
  if (section != preset.sections.end()) values = &section->second;
  for (const reshadefx::uniform& u : module.uniforms) {
    if (u.has_initializer_value) {
      std::vector<uint32_t> raw;
      if (u.type.is_array()) {
        for (const auto& element : u.initializer_value.array_data)
          for (uint32_t i = 0; i < u.type.components(); ++i) raw.push_back(element.as_uint[i]);
      } else {
        for (uint32_t i = 0; i < u.type.components(); ++i) raw.push_back(u.initializer_value.as_uint[i]);
      }
      if (u.type.is_boolean()) for (auto& r : raw) r = r ? 1u : 0u;
      WriteUniformRaw(effect.uniforms, u, raw.data(), raw.size());
    }
    if (!values) continue;
    auto v = values->find(u.name);
    if (v == values->end()) continue;
    std::vector<double> numbers;
    for (const std::string& part : SplitList(v->second)) {
      char* end = nullptr;
      double d = std::strtod(part.c_str(), &end);
      if (end != part.c_str()) numbers.push_back(d);
    }
    if (!numbers.empty()) WriteUniform(effect.uniforms, u, numbers.data(), numbers.size());
  }
}

void Runtime::UpdateSpecialUniforms(Effect& effect, float frame_ms) {
  for (const reshadefx::uniform& u : effect.compiled.module.uniforms) {
    const std::string source = AnnotationString(u.annotations, "source");
    if (source.empty()) continue;
    if (source == "frametime") {
      double v = frame_ms;
      WriteUniform(effect.uniforms, u, &v, 1);
    } else if (source == "framecount") {
      double v = u.type.is_boolean() ? double(frame_count_ % 2 == 0) : double(frame_count_ % 0xFFFFFFFFull);
      WriteUniform(effect.uniforms, u, &v, 1);
    } else if (source == "timer") {
      double v = double(std::chrono::duration_cast<std::chrono::milliseconds>(last_ - start_).count());
      WriteUniform(effect.uniforms, u, &v, 1);
    } else if (source == "random") {
      int lo = int(AnnotationFloat(u.annotations, "min", 0, 0.0f));
      int hi = int(AnnotationFloat(u.annotations, "max", 0, 32767.0f));
      double v = lo + int(random_() % uint32_t(std::abs(hi - lo) + 1));
      WriteUniform(effect.uniforms, u, &v, 1);
    } else if (source == "pingpong") {
      float lo = AnnotationFloat(u.annotations, "min", 0, 0.0f);
      float hi = AnnotationFloat(u.annotations, "max", 0, 1.0f);
      float step_lo = AnnotationFloat(u.annotations, "step", 0);
      float step_hi = AnnotationFloat(u.annotations, "step", 1);
      float smoothing = AnnotationFloat(u.annotations, "smoothing");
      float increment = step_hi == 0 ? step_lo
                                     : step_lo + std::fmod(float(random_() % 32768), step_hi - step_lo + 1);
      float value[2];
      ReadUniformFloats(effect.uniforms, u, value, 2);
      if (value[1] >= 0) {
        increment = std::max(increment - std::max(0.0f, smoothing - (hi - value[0])), 0.05f);
        if ((value[0] += increment * frame_ms * 1e-3f) >= hi) value[0] = hi, value[1] = -1;
      } else {
        increment = std::max(increment - std::max(0.0f, smoothing - (value[0] - lo)), 0.05f);
        if ((value[0] -= increment * frame_ms * 1e-3f) <= lo) value[0] = lo, value[1] = +1;
      }
      double v[2] = {value[0], value[1]};
      WriteUniform(effect.uniforms, u, v, 2);
    } else if (source == "date") {
      std::time_t t = std::time(nullptr);
      std::tm tm = {};
      localtime_r(&t, &tm);
      double v[4] = {double(tm.tm_year + 1900), double(tm.tm_mon + 1), double(tm.tm_mday),
                     double(tm.tm_hour * 3600 + tm.tm_min * 60 + tm.tm_sec)};
      WriteUniform(effect.uniforms, u, v, 4);
    } else if (source == "bufready_depth" || source == "overlay_open" || source == "key" ||
               source == "mouse_button" || source == "screenshot") {
      double v = 0;
      WriteUniform(effect.uniforms, u, &v, 1);
    }
  }
}

bool Runtime::CreatePass(Effect& effect, Pass& pass) {
  const auto& dfn = device_->functions();
  VkDevice device = device_->device();
  const reshadefx::pass& info = *pass.info;
  const auto& module = effect.compiled.module;
  const bool is_copy = &effect == copy_.get();
  pass.compute = !info.cs_entry_point.empty();
  pass.to_back_buffer = !pass.compute && info.render_target_names[0].empty();

  // Set 1: combined image samplers, at the bindings the codegen gave this pass.
  std::vector<VkDescriptorSetLayoutBinding> sampler_bindings;
  for (const auto& b : info.sampler_bindings) {
    sampler_bindings.push_back({b.entry_point_binding, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
                                VK_SHADER_STAGE_ALL, nullptr});
  }
  std::vector<VkDescriptorSetLayoutBinding> storage_bindings;
  for (const auto& b : info.storage_bindings) {
    storage_bindings.push_back({b.entry_point_binding, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1,
                                VK_SHADER_STAGE_ALL, nullptr});
  }
  auto make_layout = [&](const std::vector<VkDescriptorSetLayoutBinding>& bindings,
                         VkDescriptorSetLayout& out) {
    VkDescriptorSetLayoutCreateInfo l = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    l.bindingCount = uint32_t(bindings.size());
    l.pBindings = bindings.data();
    return dfn.vkCreateDescriptorSetLayout(device, &l, nullptr, &out) == VK_SUCCESS;
  };
  if (!make_layout(sampler_bindings, pass.samplers_layout) ||
      !make_layout(storage_bindings, pass.storages_layout)) {
    return false;
  }

  // Set 0 layout is shared by all passes of all effects (one UBO at binding 0).
  VkDescriptorSetLayoutBinding ubo_binding = {0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
                                              VK_SHADER_STAGE_ALL, nullptr};
  VkDescriptorSetLayout ubo_layout = VK_NULL_HANDLE;
  {
    VkDescriptorSetLayoutCreateInfo l = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    l.bindingCount = 1;
    l.pBindings = &ubo_binding;
    if (dfn.vkCreateDescriptorSetLayout(device, &l, nullptr, &ubo_layout) != VK_SUCCESS) return false;
  }
  VkDescriptorSetLayout set_layouts[3] = {ubo_layout, pass.samplers_layout, pass.storages_layout};
  VkPipelineLayoutCreateInfo pl = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
  pl.setLayoutCount = 3;
  pl.pSetLayouts = set_layouts;
  VkResult layout_result = dfn.vkCreatePipelineLayout(device, &pl, nullptr, &pass.layout);
  if (layout_result != VK_SUCCESS) {
    dfn.vkDestroyDescriptorSetLayout(device, ubo_layout, nullptr);
    return false;
  }

  // Descriptor sets for the pass's samplers and storages (fixed for the effect's life).
  VkDescriptorSetAllocateInfo alloc = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  alloc.descriptorPool = pool_;
  alloc.descriptorSetCount = 1;
  alloc.pSetLayouts = &pass.samplers_layout;
  if (dfn.vkAllocateDescriptorSets(device, &alloc, &pass.samplers_set) != VK_SUCCESS) return false;
  alloc.pSetLayouts = &pass.storages_layout;
  if (dfn.vkAllocateDescriptorSets(device, &alloc, &pass.storages_set) != VK_SUCCESS) return false;

  std::vector<VkDescriptorImageInfo> image_infos(info.sampler_bindings.size() + info.storage_bindings.size());
  std::vector<VkWriteDescriptorSet> writes;
  size_t k = 0;
  for (const auto& b : info.sampler_bindings) {
    const reshadefx::sampler& s = module.samplers[b.index];
    const reshadefx::texture* tex = nullptr;
    for (const auto& t : module.textures) {
      if (t.unique_name == s.texture_name) tex = &t;
    }
    Image* image = tex ? TextureFor(*tex) : nullptr;
    if (!image) return false;
    VkDescriptorImageInfo& ii = image_infos[k++];
    ii.sampler = SamplerFor(s);
    ii.imageView = (s.srgb && image->view_srgb) ? image->view_srgb : image->view;
    ii.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkWriteDescriptorSet w = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = pass.samplers_set;
    w.dstBinding = b.entry_point_binding;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &ii;
    writes.push_back(w);
  }
  for (const auto& b : info.storage_bindings) {
    const reshadefx::storage& s = module.storages[b.index];
    const reshadefx::texture* tex = nullptr;
    for (const auto& t : module.textures) {
      if (t.unique_name == s.texture_name) tex = &t;
    }
    Image* image = tex ? TextureFor(*tex) : nullptr;
    if (!image || !image->storage) return false;
    pass.storages.push_back(image);
    VkDescriptorImageInfo& ii = image_infos[k++];
    ii.sampler = VK_NULL_HANDLE;
    // Storage views must cover one level: the codegen writes level 0 (s.level is rare).
    ii.imageView = image->rtv ? image->rtv : image->view;
    ii.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet w = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = pass.storages_set;
    w.dstBinding = b.entry_point_binding;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w.pImageInfo = &ii;
    writes.push_back(w);
  }
  if (!writes.empty()) dfn.vkUpdateDescriptorSets(device, uint32_t(writes.size()), writes.data(), 0, nullptr);

  auto module_for = [&](const std::string& entry) -> VkShaderModule {
    auto it = effect.modules.find(entry);
    if (it != effect.modules.end()) return it->second;
    auto code = effect.compiled.spirv.find(entry);
    if (code == effect.compiled.spirv.end()) return VK_NULL_HANDLE;
    VkShaderModule m = util::CreateShaderModule(device_, code->second.data(), code->second.size() * 4);
    effect.modules[entry] = m;
    return m;
  };

  if (pass.compute) {
    VkShaderModule cs = module_for(info.cs_entry_point);
    if (!cs) return false;
    VkComputePipelineCreateInfo c = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    c.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0,
               VK_SHADER_STAGE_COMPUTE_BIT, cs, info.cs_entry_point.c_str(), nullptr};
    c.layout = pass.layout;
    bool ok = dfn.vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &c, nullptr, &pass.pipeline) == VK_SUCCESS;
    dfn.vkDestroyDescriptorSetLayout(device, ubo_layout, nullptr);
    return ok;
  }
  dfn.vkDestroyDescriptorSetLayout(device, ubo_layout, nullptr);

  // Render targets: the back buffer (the effect's working copy, or the presenter's
  // image for the final copy) or up to 8 textures.
  std::vector<VkFormat> formats;
  std::vector<VkImageView> views;
  if (pass.to_back_buffer) {
    if (is_copy) {
      formats.push_back(dst_format_);
    } else {
      pass.targets.push_back(&target_);
      formats.push_back(info.srgb_write_enable ? VK_FORMAT_R8G8B8A8_SRGB : target_.format);
      views.push_back(info.srgb_write_enable ? target_.rtv_srgb : target_.rtv);
    }
    pass.viewport_width = info.viewport_width ? info.viewport_width : width_;
    pass.viewport_height = info.viewport_height ? info.viewport_height : height_;
  } else {
    for (int i = 0; i < 8 && !info.render_target_names[i].empty(); ++i) {
      const reshadefx::texture* tex = nullptr;
      for (const auto& t : module.textures) {
        if (t.unique_name == info.render_target_names[i]) tex = &t;
      }
      Image* image = tex ? TextureFor(*tex) : nullptr;
      if (!image || !image->rtv) return false;
      pass.targets.push_back(image);
      const bool srgb = info.srgb_write_enable && image->rtv_srgb;
      formats.push_back(srgb ? VK_FORMAT_R8G8B8A8_SRGB : image->format);
      views.push_back(srgb ? image->rtv_srgb : image->rtv);
    }
    pass.viewport_width = info.viewport_width ? info.viewport_width : pass.targets[0]->width;
    pass.viewport_height = info.viewport_height ? info.viewport_height : pass.targets[0]->height;
  }

  std::vector<VkAttachmentDescription> attachments;
  std::vector<VkAttachmentReference> refs;
  for (size_t i = 0; i < formats.size(); ++i) {
    VkAttachmentDescription a = {};
    a.format = formats[i];
    a.samples = VK_SAMPLE_COUNT_1_BIT;
    a.loadOp = info.clear_render_targets ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    a.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    a.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    a.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a.initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    a.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    attachments.push_back(a);
    refs.push_back({uint32_t(i), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL});
  }
  VkSubpassDescription subpass = {};
  subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
  subpass.colorAttachmentCount = uint32_t(refs.size());
  subpass.pColorAttachments = refs.data();
  VkRenderPassCreateInfo rp = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
  rp.attachmentCount = uint32_t(attachments.size());
  rp.pAttachments = attachments.data();
  rp.subpassCount = 1;
  rp.pSubpasses = &subpass;
  if (dfn.vkCreateRenderPass(device, &rp, nullptr, &pass.render_pass) != VK_SUCCESS) return false;
  if (!views.empty()) {
    VkFramebufferCreateInfo fb = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fb.renderPass = pass.render_pass;
    fb.attachmentCount = uint32_t(views.size());
    fb.pAttachments = views.data();
    fb.width = pass.targets[0]->width;
    fb.height = pass.targets[0]->height;
    fb.layers = 1;
    if (dfn.vkCreateFramebuffer(device, &fb, nullptr, &pass.framebuffer) != VK_SUCCESS) return false;
  }

  VkShaderModule vs = module_for(info.vs_entry_point);
  VkShaderModule ps = info.ps_entry_point.empty() ? VK_NULL_HANDLE : module_for(info.ps_entry_point);
  if (!vs || (!info.ps_entry_point.empty() && !ps)) return false;
  VkPipelineShaderStageCreateInfo stages[2] = {
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT,
       vs, info.vs_entry_point.c_str(), nullptr},
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0,
       VK_SHADER_STAGE_FRAGMENT_BIT, ps, info.ps_entry_point.c_str(), nullptr}};
  VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
  VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
  ia.topology = Topology(info.topology);
  VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
  vp.viewportCount = 1;
  vp.scissorCount = 1;
  VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
  rs.polygonMode = VK_POLYGON_MODE_FILL;
  rs.cullMode = VK_CULL_MODE_NONE;
  rs.frontFace = VK_FRONT_FACE_CLOCKWISE;
  rs.lineWidth = 1.0f;
  VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
  ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
  std::vector<VkPipelineColorBlendAttachmentState> blends(formats.size());
  for (size_t i = 0; i < blends.size(); ++i) {
    VkPipelineColorBlendAttachmentState& b = blends[i];
    b.blendEnable = info.blend_enable[i] ? VK_TRUE : VK_FALSE;
    b.srcColorBlendFactor = BlendFactor(info.source_color_blend_factor[i]);
    b.dstColorBlendFactor = BlendFactor(info.dest_color_blend_factor[i]);
    b.colorBlendOp = BlendOp(info.color_blend_op[i]);
    b.srcAlphaBlendFactor = BlendFactor(info.source_alpha_blend_factor[i]);
    b.dstAlphaBlendFactor = BlendFactor(info.dest_alpha_blend_factor[i]);
    b.alphaBlendOp = BlendOp(info.alpha_blend_op[i]);
    b.colorWriteMask = info.render_target_write_mask[i] & 0xF;
  }
  VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
  cb.attachmentCount = uint32_t(blends.size());
  cb.pAttachments = blends.data();
  VkDynamicState dynamic[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
  VkPipelineDynamicStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
  ds.dynamicStateCount = 2;
  ds.pDynamicStates = dynamic;
  VkGraphicsPipelineCreateInfo g = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
  g.stageCount = ps ? 2 : 1;
  g.pStages = stages;
  g.pVertexInputState = &vi;
  g.pInputAssemblyState = &ia;
  g.pViewportState = &vp;
  g.pRasterizationState = &rs;
  g.pMultisampleState = &ms;
  g.pColorBlendState = &cb;
  g.pDynamicState = &ds;
  g.layout = pass.layout;
  g.renderPass = pass.render_pass;
  if (info.stencil_enable) Log("note: " + info.name + ": stencil isn't supported, drawn without it");
  return dfn.vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &g, nullptr, &pass.pipeline) == VK_SUCCESS;
}

bool Runtime::LoadEffect(Effect& effect, const Preset& preset, const std::vector<std::string>& wanted) {
  const auto& dfn = device_->functions();
  VkDevice device = device_->device();
  const auto& module = effect.compiled.module;

  // UBO per slot and its descriptor set.
  VkDescriptorSetLayoutBinding ubo_binding = {0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1,
                                              VK_SHADER_STAGE_ALL, nullptr};
  VkDescriptorSetLayoutCreateInfo l = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  l.bindingCount = 1;
  l.pBindings = &ubo_binding;
  VkDescriptorSetLayout ubo_layout;
  if (dfn.vkCreateDescriptorSetLayout(device, &l, nullptr, &ubo_layout) != VK_SUCCESS) return false;
  InitUniforms(effect, preset);
  effect.ubos.resize(slots_);
  effect.ubo_sets.resize(slots_);
  bool ok = true;
  for (uint32_t s = 0; s < slots_ && ok; ++s) {
    ok = CreateBuffer(effect.ubos[s], effect.uniforms.size(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
    VkDescriptorSetAllocateInfo alloc = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = pool_;
    alloc.descriptorSetCount = 1;
    alloc.pSetLayouts = &ubo_layout;
    ok = ok && dfn.vkAllocateDescriptorSets(device, &alloc, &effect.ubo_sets[s]) == VK_SUCCESS;
    if (!ok) break;
    VkDescriptorBufferInfo bi = {effect.ubos[s].buffer, 0, effect.ubos[s].size};
    VkWriteDescriptorSet w = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = effect.ubo_sets[s];
    w.dstBinding = 0;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    w.pBufferInfo = &bi;
    dfn.vkUpdateDescriptorSets(device, 1, &w, 0, nullptr);
  }
  dfn.vkDestroyDescriptorSetLayout(device, ubo_layout, nullptr);
  if (!ok) return false;

  for (const reshadefx::technique& t : module.techniques) {
    if (std::find(wanted.begin(), wanted.end(), t.name) == wanted.end()) continue;
    Technique tech;
    tech.name = t.name;
    for (const reshadefx::pass& p : t.passes) {
      Pass pass;
      pass.info = &p;
      if (!CreatePass(effect, pass)) {
        Log("error: " + effect.section + ": couldn't set up pass \"" + p.name + "\" of " + t.name);
        tech.passes.push_back(std::move(pass));
        effect.techniques.push_back(std::move(tech));
        return false;
      }
      tech.passes.push_back(std::move(pass));
    }
    effect.techniques.push_back(std::move(tech));
  }
  return true;
}

bool Runtime::Load(uint32_t width, uint32_t height, uint32_t slots, VkFormat dst_format) {
  const auto& dfn = device_->functions();
  VkDevice device = device_->device();
  width_ = width;
  height_ = height;
  slots_ = slots;
  dst_format_ = dst_format;
  log_.clear();
  Log("ReShade effects for Raging Blast 2 (ReShadeFX " + std::to_string(kReshadeVersion) + ")");
  Log("preset: " + preset_path_.string());

  Preset preset = ReadPreset(preset_path_);
  if (preset.techniques.empty()) {
    Log("The preset turns on no techniques (Techniques=...); nothing to do.");
    WriteLog();
    return false;
  }

  if (!CreateImage(color_, VK_FORMAT_R8G8B8A8_UNORM, width, height, 1, true, false, true) ||
      !CreateImage(target_, VK_FORMAT_R8G8B8A8_UNORM, width, height, 1, true, false, true) ||
      !CreateImage(depth_dummy_, VK_FORMAT_R8G8B8A8_UNORM, 1, 1, 1, true, false, false)) {
    Log("error: couldn't create the frame images");
    WriteLog();
    return false;
  }

  VkDescriptorPoolSize sizes[3] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1024},
                                   {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 4096},
                                   {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1024}};
  VkDescriptorPoolCreateInfo pi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  pi.maxSets = 2048;
  pi.poolSizeCount = 3;
  pi.pPoolSizes = sizes;
  if (dfn.vkCreateDescriptorPool(device, &pi, nullptr, &pool_) != VK_SUCCESS) return false;

  CompileOptions options;
  options.width = width;
  options.height = height;
  options.vendor_id = device_->properties().vendorID;
  options.device_id = device_->properties().deviceID;
  options.definitions = preset.definitions;
  std::error_code ec;
  const std::filesystem::path shaders = root_ / "Shaders";
  options.include_dirs.push_back(shaders);
  for (auto it = std::filesystem::recursive_directory_iterator(
           shaders, std::filesystem::directory_options::skip_permission_denied, ec);
       !ec && it != std::filesystem::recursive_directory_iterator(); it.increment(ec)) {
    if (it->is_directory(ec)) options.include_dirs.push_back(it->path());
  }
  const std::vector<std::filesystem::path> files = FindEffects(shaders);

  // Group the wanted techniques by file, keeping the preset's order.
  struct Want {
    std::string technique, file;
  };
  std::vector<Want> wants;
  for (const std::string& entry : preset.techniques) {
    size_t at = entry.find('@');
    wants.push_back({entry.substr(0, at), at == std::string::npos ? std::string() : entry.substr(at + 1)});
  }

  auto find_file = [&](const std::string& name) -> std::filesystem::path {
    for (const auto& f : files) {
      if (ToLower(f.filename().string()) == ToLower(name)) return f;
    }
    return {};
  };
  // Presets from ReShade 3/4 name techniques without their file: find the file
  // that declares each one.
  for (Want& w : wants) {
    if (!w.file.empty()) continue;
    for (const auto& f : files) {
      std::ifstream in(f, std::ios::binary);
      std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
      if (DeclaresTechnique(text, w.technique)) {
        w.file = f.filename().string();
        break;
      }
    }
    if (w.file.empty()) Log("error: no effect file declares technique " + w.technique);
  }
  std::map<std::string, Effect*> by_file;
  for (const Want& w : wants) {
    if (w.file.empty() || by_file.count(ToLower(w.file))) continue;
    by_file[ToLower(w.file)] = nullptr;
    std::filesystem::path path = find_file(w.file);
    if (path.empty()) {
      Log("error: " + w.file + " isn't in Shaders/");
      continue;
    }
    auto effect = std::make_unique<Effect>();
    effect->section = path.filename().string();
    effect->compiled = CompileEffect(path, options);
    if (!effect->compiled.ok) {
      Log("error: " + effect->section + " didn't compile:\n" + effect->compiled.errors);
      continue;
    }
    if (!effect->compiled.errors.empty()) Log(effect->section + ":\n" + effect->compiled.errors);
    std::vector<std::string> names;
    for (const Want& o : wants) {
      if (ToLower(o.file) == ToLower(w.file)) names.push_back(o.technique);
    }
    if (!LoadEffect(*effect, preset, names)) {
      Log("error: " + effect->section + " couldn't be set up; it is skipped");
      effect->ready = false;
      effects_.push_back(std::move(effect));  // kept only so Release frees it
      continue;
    }
    effect->ready = true;
    by_file[ToLower(w.file)] = effect.get();
    Log("loaded " + effect->section);
    effects_.push_back(std::move(effect));
  }
  for (const Want& w : wants) {
    auto it = by_file.find(ToLower(w.file));
    if (it == by_file.end() || !it->second) continue;
    for (Technique& t : it->second->techniques) {
      if (t.name == w.technique) {
        order_.push_back({it->second, &t});
        Log("runs: " + w.technique + " (" + it->second->section + ")");
      }
    }
  }

  // The final copy into the presenter's image.
  copy_ = std::make_unique<Effect>();
  copy_->section = "copy";
  copy_->compiled = CompileEffectSource(kCopyEffect, "copy.fx", options);
  Preset none;
  if (!copy_->compiled.ok || !LoadEffect(*copy_, none, {"Copy"}) || copy_->techniques.empty()) {
    Log("error: internal copy pass failed:\n" + copy_->compiled.errors);
    WriteLog();
    return false;
  }

  if (order_.empty()) Log("No technique could run.");
  WriteLog();
  return !order_.empty();
}

void Runtime::Barrier(VkCommandBuffer cb, VkImage image, VkImageLayout from, VkImageLayout to,
                      uint32_t levels) {
  VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
  b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
  b.oldLayout = from;
  b.newLayout = to;
  b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  b.image = image;
  b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, 0, 1};
  device_->functions().vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0,
                                            nullptr, 1, &b);
}

void Runtime::Transition(VkCommandBuffer cb, Image& image, VkImageLayout layout) {
  if (image.layout == layout && layout != VK_IMAGE_LAYOUT_GENERAL &&
      layout != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
    return;
  }
  Barrier(cb, image.image, image.layout, layout);
  image.layout = layout;
}

void Runtime::GenerateMips(VkCommandBuffer cb, Image& image) {
  if (image.levels < 2) return;
  Transition(cb, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  for (uint32_t level = 1; level < image.levels; ++level) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image.image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, level - 1, 1, 0, 1};
    device_->functions().vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                              VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                                              nullptr, 1, &b);
    VkImageBlit blit = {};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, level - 1, 0, 1};
    blit.srcOffsets[1] = {int32_t(std::max(1u, image.width >> (level - 1))),
                          int32_t(std::max(1u, image.height >> (level - 1))), 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1};
    blit.dstOffsets[1] = {int32_t(std::max(1u, image.width >> level)),
                          int32_t(std::max(1u, image.height >> level)), 1};
    cmd_blit_image_(cb, image.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
  }
  // Levels 0..n-2 are TRANSFER_SRC now, the last one TRANSFER_DST.
  VkImageMemoryBarrier b[2] = {{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}, {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}};
  for (int i = 0; i < 2; ++i) {
    b[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
    b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    b[i].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b[i].srcQueueFamilyIndex = b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b[i].image = image.image;
  }
  b[0].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  b[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, image.levels - 1, 0, 1};
  b[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  b[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, image.levels - 1, 1, 0, 1};
  device_->functions().vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0,
                                            nullptr, 2, b);
  image.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
}

void Runtime::RunPass(VkCommandBuffer cb, Effect& effect, Pass& pass, uint32_t slot) {
  const auto& dfn = device_->functions();
  const reshadefx::pass& info = *pass.info;
  VkImageCopy whole = {};
  whole.srcSubresource = whole.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  whole.extent = {width_, height_, 1};

  if (pass.to_back_buffer) {
    // The pass draws over the current frame: start the target from it.
    Transition(cb, color_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    Transition(cb, target_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    cmd_copy_image_(cb, color_.image, color_.layout, target_.image, target_.layout, 1, &whole);
  }
  // Every texture the pass samples must be readable; its targets writable.
  for (auto& [name, image] : textures_) {
    if (std::find(pass.targets.begin(), pass.targets.end(), image.get()) != pass.targets.end()) continue;
    if (std::find(pass.storages.begin(), pass.storages.end(), image.get()) != pass.storages.end()) continue;
    Transition(cb, *image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  }
  Transition(cb, color_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  Transition(cb, depth_dummy_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

  VkDescriptorSet sets[3] = {effect.ubo_sets[slot], pass.samplers_set, pass.storages_set};
  if (pass.compute) {
    for (Image* s : pass.storages) Transition(cb, *s, VK_IMAGE_LAYOUT_GENERAL);
    dfn.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pass.pipeline);
    dfn.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pass.layout, 0, 3, sets, 0, nullptr);
    dfn.vkCmdDispatch(cb, std::max(1u, info.viewport_width), std::max(1u, info.viewport_height),
                      std::max(1u, info.viewport_dispatch_z));
    for (Image* s : pass.storages) {
      if (info.generate_mipmaps && s->levels > 1) GenerateMips(cb, *s);
      else Transition(cb, *s, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
    return;
  }

  for (Image* t : pass.targets) Transition(cb, *t, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
  VkClearValue clears[8] = {};
  VkRenderPassBeginInfo begin = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
  begin.renderPass = pass.render_pass;
  begin.framebuffer = pass.framebuffer;
  begin.renderArea.extent = {pass.targets[0]->width, pass.targets[0]->height};
  begin.clearValueCount = uint32_t(pass.targets.size());
  begin.pClearValues = clears;
  dfn.vkCmdBeginRenderPass(cb, &begin, VK_SUBPASS_CONTENTS_INLINE);
  // Flipped viewport: the effects are written for D3D's upward Y.
  VkViewport viewport = {0.0f, float(pass.viewport_height), float(pass.viewport_width),
                         -float(pass.viewport_height), 0.0f, 1.0f};
  VkRect2D scissor = {{0, 0}, {pass.viewport_width, pass.viewport_height}};
  dfn.vkCmdSetViewport(cb, 0, 1, &viewport);
  dfn.vkCmdSetScissor(cb, 0, 1, &scissor);
  dfn.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.pipeline);
  dfn.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.layout, 0, 3, sets, 0, nullptr);
  dfn.vkCmdDraw(cb, info.num_vertices, 1, 0, 0);
  dfn.vkCmdEndRenderPass(cb);

  if (pass.to_back_buffer) {
    Transition(cb, target_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    Transition(cb, color_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    cmd_copy_image_(cb, target_.image, target_.layout, color_.image, color_.layout, 1, &whole);
    Transition(cb, color_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  } else {
    for (Image* t : pass.targets) {
      if (info.generate_mipmaps && t->levels > 1) GenerateMips(cb, *t);
      else Transition(cb, *t, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
  }
}

bool Runtime::Apply(VkCommandBuffer cb, VkImage src, VkImage dst, VkImageView dst_view,
                    VkFormat dst_format, uint32_t width, uint32_t height, uint32_t slot,
                    uint32_t slots) {
  if (failed_) return false;
  const auto& dfn = device_->functions();
  VkDevice device = device_->device();
  if (loaded_ && (width != width_ || height != height_ || slots != slots_ || dst_format != dst_format_)) {
    // The caller made sure the GPU is done with the old frame size.
    Release();
  }
  if (!loaded_) {
    loaded_ = true;
    if (!Load(width, height, slots, dst_format)) {
      REXLOG_WARN("ReShade: no effects run (see ReShade.log in the ReShade folder)");
      failed_ = true;
      return false;
    }
    REXLOG_INFO("ReShade: {} technique(s) on {}x{}", order_.size(), width, height);
  }
  slot %= slots_;

  auto now = std::chrono::steady_clock::now();
  float frame_ms = std::chrono::duration<float, std::milli>(now - last_).count();
  last_ = now;
  ++frame_count_;

  // Source textures go up on the first frame; their staging buffers live a few frames.
  if (!pending_uploads_.empty()) {
    for (auto& [image, data] : pending_uploads_) {
      Buffer staging;
      if (!CreateBuffer(staging, data.size(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) continue;
      std::memcpy(staging.mapping, data.data(), data.size());
      util::FlushMappedMemoryRange(device_, staging.memory, staging.memory_type);
      Transition(cb, *image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
      VkBufferImageCopy copy = {};
      copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
      copy.imageExtent = {image->width, image->height, 1};
      dfn.vkCmdCopyBufferToImage(cb, staging.buffer, image->image, image->layout, 1, &copy);
      if (image->levels > 1) GenerateMips(cb, *image);
      else Transition(cb, *image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
      uploads_.push_back(staging);
    }
    pending_uploads_.clear();
    uploads_frames_left_ = slots_ + 1;
  } else if (uploads_frames_left_ && --uploads_frames_left_ == 0) {
    for (Buffer& b : uploads_) DestroyBuffer(b);
    uploads_.clear();
  }

  // The frame: guest output -> color (10-bit to 8-bit).
  Barrier(cb, src, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1);
  Transition(cb, color_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkImageBlit blit = {};
  blit.srcSubresource = blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  blit.srcOffsets[1] = blit.dstOffsets[1] = {int32_t(width), int32_t(height), 1};
  cmd_blit_image_(cb, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, color_.image, color_.layout, 1,
                  &blit, VK_FILTER_NEAREST);
  Barrier(cb, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 1);
  // Never-written textures start cleared rather than undefined.
  for (auto& [name, image] : textures_) {
    if (image->layout != VK_IMAGE_LAYOUT_UNDEFINED) continue;
    Transition(cb, *image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkClearColorValue zero = {};
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, image->levels, 0, 1};
    dfn.vkCmdClearColorImage(cb, image->image, image->layout, &zero, 1, &range);
  }
  if (depth_dummy_.layout == VK_IMAGE_LAYOUT_UNDEFINED) {
    Transition(cb, depth_dummy_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkClearColorValue zero = {};
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    dfn.vkCmdClearColorImage(cb, depth_dummy_.image, depth_dummy_.layout, &zero, 1, &range);
  }

  for (auto& e : effects_) {
    if (!e->ready) continue;
    UpdateSpecialUniforms(*e, frame_ms);
    std::memcpy(e->ubos[slot].mapping, e->uniforms.data(), e->uniforms.size());
    util::FlushMappedMemoryRange(device_, e->ubos[slot].memory, e->ubos[slot].memory_type);
  }
  for (const Order& o : order_) {
    for (Pass& pass : o.technique->passes) RunPass(cb, *o.effect, pass, slot);
  }

  // color -> the presenter's image, through the copy pass.
  Pass& copy = copy_->techniques[0].passes[0];
  if (copy_view_ != dst_view) {
    util::DestroyAndNullHandle(dfn.vkDestroyFramebuffer, device, copy.framebuffer);
    VkFramebufferCreateInfo fb = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fb.renderPass = copy.render_pass;
    fb.attachmentCount = 1;
    fb.pAttachments = &dst_view;
    fb.width = width;
    fb.height = height;
    fb.layers = 1;
    if (dfn.vkCreateFramebuffer(device, &fb, nullptr, &copy.framebuffer) != VK_SUCCESS) {
      failed_ = true;
      return false;
    }
    copy_view_ = dst_view;
  }
  Transition(cb, color_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  Barrier(cb, dst, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 1);
  VkRenderPassBeginInfo begin = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
  begin.renderPass = copy.render_pass;
  begin.framebuffer = copy.framebuffer;
  begin.renderArea.extent = {width, height};
  VkClearValue clear = {};
  begin.clearValueCount = 1;
  begin.pClearValues = &clear;
  dfn.vkCmdBeginRenderPass(cb, &begin, VK_SUBPASS_CONTENTS_INLINE);
  VkViewport viewport = {0.0f, float(height), float(width), -float(height), 0.0f, 1.0f};
  VkRect2D scissor = {{0, 0}, {width, height}};
  dfn.vkCmdSetViewport(cb, 0, 1, &viewport);
  dfn.vkCmdSetScissor(cb, 0, 1, &scissor);
  VkDescriptorSet sets[3] = {copy_->ubo_sets[slot], copy.samplers_set, copy.storages_set};
  dfn.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, copy.pipeline);
  dfn.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, copy.layout, 0, 3, sets, 0, nullptr);
  dfn.vkCmdDraw(cb, 3, 1, 0, 0);
  dfn.vkCmdEndRenderPass(cb);
  Barrier(cb, dst, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 1);
  return true;
}

}  // namespace rex::ui::vulkan::reshade
