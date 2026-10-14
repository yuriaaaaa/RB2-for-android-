// ReShade effects on the guest output (Android port). Runs the techniques of a
// ReShade preset on each new frame before the presenter scales it to the screen.
#pragma once

#include <chrono>
#include <cstdint>
#include <filesystem>
#include <map>
#include <memory>
#include <random>
#include <string>
#include <vector>

#include <rex/ui/vulkan/device.h>

#include "reshade_effects.h"

namespace rex::ui::vulkan::reshade {

// The launcher's in-game switch: it writes "1" or "0" to <reshade_path>/.live and
// edits the preset while the game runs. Poll() checks both about twice a second.
class LiveSwitch {
 public:
  // True when ReShade must be rebuilt; `enabled` then holds the wanted state.
  bool Poll(bool* enabled);

 private:
  uint32_t calls_ = 0;
  bool started_ = false;
  std::filesystem::file_time_type live_time_{}, preset_time_{};
};

class Runtime {
 public:
  // Null when ReShade is off (cvar reshade_enabled) or the device can't run it.
  static std::unique_ptr<Runtime> CreateIfEnabled(const VulkanDevice* device);
  // Same, ignoring reshade_enabled (the in-game switch turned it on).
  static std::unique_ptr<Runtime> Create(const VulkanDevice* device);
  ~Runtime();

  // Records the effects for the frame in `src` (kept in SHADER_READ_ONLY_OPTIMAL) and
  // writes the result to `dst` (left in SHADER_READ_ONLY_OPTIMAL). `slot` cycles through
  // [0, slots) with the presenter's submissions, so per-frame data isn't overwritten
  // while the GPU still reads it. Returns false (dst untouched) when nothing ran.
  bool Apply(VkCommandBuffer cb, VkImage src, VkImage dst, VkImageView dst_view,
             VkFormat dst_format, uint32_t width, uint32_t height, uint32_t slot, uint32_t slots);

  // Destroys everything; the caller makes sure the GPU no longer uses it.
  void Release();

  // True when the next Apply for this frame size will rebuild (so the GPU must be idle).
  bool NeedsRebuild(uint32_t width, uint32_t height) const {
    return loaded_ && (width != width_ || height != height_);
  }

 private:
  struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;       // all levels
    VkImageView view_srgb = VK_NULL_HANDLE;  // all levels, sRGB (rgba8 only)
    VkImageView rtv = VK_NULL_HANDLE;        // level 0
    VkImageView rtv_srgb = VK_NULL_HANDLE;   // level 0, sRGB
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t width = 0, height = 0, levels = 1;
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
    bool storage = false;
  };
  struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapping = nullptr;
    VkDeviceSize size = 0;
    uint32_t memory_type = 0;
  };
  struct Pass {
    const reshadefx::pass* info = nullptr;
    bool compute = false;
    bool to_back_buffer = false;
    VkDescriptorSetLayout samplers_layout = VK_NULL_HANDLE;
    VkDescriptorSetLayout storages_layout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkRenderPass render_pass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkDescriptorSet samplers_set = VK_NULL_HANDLE;
    VkDescriptorSet storages_set = VK_NULL_HANDLE;
    std::vector<Image*> targets;
    std::vector<Image*> storages;
    uint32_t viewport_width = 0, viewport_height = 0;
  };
  struct Technique {
    std::string name;
    std::vector<Pass> passes;
  };
  struct Effect {
    CompiledEffect compiled;
    std::string section;  // "File.fx" in the preset
    std::vector<uint8_t> uniforms;
    std::vector<Buffer> ubos;  // one per slot
    std::vector<VkDescriptorSet> ubo_sets;
    std::map<std::string, VkShaderModule> modules;
    std::vector<Technique> techniques;  // only the ones that run
    bool ready = false;
  };
  struct Order {
    Effect* effect;
    Technique* technique;
  };

  Runtime(const VulkanDevice* device);
  bool Load(uint32_t width, uint32_t height, uint32_t slots, VkFormat dst_format);
  bool LoadEffect(Effect& effect, const Preset& preset, const std::vector<std::string>& wanted);
  bool CreatePass(Effect& effect, Pass& pass);
  Image* TextureFor(const reshadefx::texture& texture);
  bool CreateImage(Image& image, VkFormat format, uint32_t width, uint32_t height, uint32_t levels,
                   bool render_target, bool storage, bool srgb_views);
  void DestroyImage(Image& image);
  bool CreateBuffer(Buffer& buffer, VkDeviceSize size, VkBufferUsageFlags usage);
  void DestroyBuffer(Buffer& buffer);
  VkSampler SamplerFor(const reshadefx::sampler_desc& desc);
  bool LoadSourceTexture(Image& image, const std::string& source);
  void InitUniforms(Effect& effect, const Preset& preset);
  void UpdateSpecialUniforms(Effect& effect, float frame_ms);
  void Transition(VkCommandBuffer cb, Image& image, VkImageLayout layout);
  void Barrier(VkCommandBuffer cb, VkImage image, VkImageLayout from, VkImageLayout to,
               uint32_t levels = VK_REMAINING_MIP_LEVELS);
  void GenerateMips(VkCommandBuffer cb, Image& image);
  void RunPass(VkCommandBuffer cb, Effect& effect, Pass& pass, uint32_t slot);
  void Log(const std::string& line);
  void WriteLog();

  const VulkanDevice* device_;
  PFN_vkCmdBlitImage cmd_blit_image_ = nullptr;
  PFN_vkCmdCopyImage cmd_copy_image_ = nullptr;

  bool failed_ = false;
  bool loaded_ = false;
  uint32_t width_ = 0, height_ = 0, slots_ = 0;
  VkFormat dst_format_ = VK_FORMAT_UNDEFINED;
  std::filesystem::path root_, preset_path_;

  Image color_, target_, depth_dummy_;
  std::map<std::string, std::unique_ptr<Image>> textures_;
  std::map<uint64_t, VkSampler> samplers_;
  std::vector<std::unique_ptr<Effect>> effects_;
  std::vector<Order> order_;
  std::vector<Buffer> uploads_;  // staging for source textures, freed after a few frames
  uint32_t uploads_frames_left_ = 0;
  std::vector<std::pair<Image*, std::vector<uint8_t>>> pending_uploads_;
  VkDescriptorPool pool_ = VK_NULL_HANDLE;

  // The final copy into the presenter's image.
  std::unique_ptr<Effect> copy_;
  VkImageView copy_view_ = VK_NULL_HANDLE;

  std::chrono::steady_clock::time_point start_, last_;
  uint64_t frame_count_ = 0;
  std::mt19937 random_{12345};
  std::string log_;
};

}  // namespace rex::ui::vulkan::reshade
