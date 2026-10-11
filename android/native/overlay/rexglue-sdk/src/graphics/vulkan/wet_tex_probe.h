/**
 * WET Android port: texture-path diagnostics shared between the Vulkan
 * texture cache and the command processor (both overlay files).
 *
 * Include after <rex/graphics/vulkan/texture_cache.h> (needs the Vulkan types).
 * Everything here is only touched on the GPU command processor thread.
 */
#pragma once

#include <cstdint>

namespace rex::graphics::vulkan {

// The command processor sets `request` to fetch constant index + 1 and calls
// VulkanTextureCache::GetActiveBindingOrNullImageView for that index; the
// texture cache fills the rest from its private binding state.
struct WetTexProbe {
  uint32_t request = 0;
  bool filled = false;
  bool binding_valid = false;
  bool dimensions_compatible = false;
  uint32_t key_format = 0;
  uint32_t key_dimension = 0;
  uint32_t key_width = 0;
  uint32_t key_height = 0;
  uint32_t key_depth_or_array = 0;
  uint32_t key_mip_max_level = 0;
  uint32_t key_base_address = 0;
  uint32_t key_mip_address = 0;
  uint32_t key_tiled = 0;
  uint32_t key_endianness = 0;
  uint32_t key_scaled_resolve = 0;
  uint32_t key_signed_separate = 0;
  uint32_t host_swizzle = 0;
  uint32_t swizzled_signs = 0;
  bool has_texture = false;
  bool has_texture_signed = false;
  VkImage image = VK_NULL_HANDLE;
  VkFormat host_format = VK_FORMAT_UNDEFINED;
  bool host_block_compressed = false;
  // VulkanTexture::Usage (0 undefined, 1 transfer dst, 2 sampled, 3 swap).
  int usage = -1;
  bool base_outdated = false;
  bool mips_outdated = false;
  uint32_t guest_base_size = 0;
  VkImageView view_unsigned = VK_NULL_HANDLE;
  VkImageView view_signed = VK_NULL_HANDLE;
};

struct WetTexCounters {
  uint64_t create_ok = 0;
  uint64_t create_fail_format = 0;
  uint64_t create_fail_alloc = 0;
  int32_t last_alloc_result = 0;
  uint32_t last_fail_guest_format = 0;
  uint32_t last_fail_vk_format = 0;
  uint32_t last_fail_width = 0;
  uint32_t last_fail_height = 0;
  uint64_t load_ok = 0;
  // GetActiveBindingOrNullImageView results (descriptor writes).
  uint64_t view_ok = 0;
  uint64_t view_null_no_binding = 0;
  uint64_t view_null_dimension = 0;
  uint64_t view_null_no_view = 0;
  // Indexed by the order of the early returns in
  // LoadTextureDataFromResidentMemoryImpl.
  uint64_t load_fail[16] = {};
  // Cumulative totals (never reset) for the per-window summary.
  uint64_t total_create_ok = 0;
  uint64_t total_create_fail = 0;
  uint64_t total_load_ok = 0;
  uint64_t total_load_fail = 0;
};

extern WetTexProbe g_wet_tex_probe;
extern WetTexCounters g_wet_tex_counters;
// SpirvShaderTranslator::Modification::pixel.wet_param_gen_variant for pixel
// shaders using PsParamGen (Qualcomm proprietary driver workaround), chosen by
// the command processor and applied by the pipeline cache.
extern uint32_t g_wet_param_gen_variant;
// Set by the command processor for the current draw when it added a dummy
// interpolator to the vertex and pixel shader interpolator masks.
extern bool g_wet_param_gen_dummy_added;
// Set from the wet_tex_probe_readback cvar: textures get TRANSFER_SRC usage so
// the probe can copy them back.
extern bool g_wet_tex_transfer_src;

}  // namespace rex::graphics::vulkan
