// Game hooks carried over from upstream's src/hooks.cpp.
//
// Upstream's memory and string hooks never took effect (see rb2_rexcrt.toml). What is left is
// the frame pacing fix from upstream commit 81b0922 ("fixed frame rate issues by replacing
// vsync").

#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <thread>

#include <rex/cvar.h>
#include <rex/hook.h>
#include <rex/logging.h>
#include <rex/memory.h>

REXCVAR_DEFINE_BOOL(rb2_vsync_wait, true, "RB2",
                    "Replace the game's vsync wait (SpGameServer::_vsyncWait) with a sleep to the "
                    "next 1/60 s boundary, as upstream does; false runs the original wait")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

REX_EXTERN(__imp__sub_8249B680);

// SpGameServer::_vsyncWait
REX_HOOK_RAW(sub_8249B680) {
  if (!REXCVAR_GET(rb2_vsync_wait)) {
    __imp__sub_8249B680(ctx, base);
    return;
  }
  using namespace std::chrono_literals;
  constexpr auto kInterval = std::chrono::nanoseconds(1s) / 60;
  const auto now = std::chrono::steady_clock::now();
  const auto next = now + (kInterval - now.time_since_epoch() % kInterval);
  std::this_thread::sleep_until(next);
}

// 0.1.1 diagnostic. In 0.1.0 a job thread crashed here on every boot, after the autosave notice:
// the job's list head (+8) was null while its count (+16) was not, and the loop read 20(null).
// Log the job once (up to 8 times) when that happens, then skip it instead of faulting.
REX_EXTERN(__imp__sub_823ECE58);

REX_HOOK_RAW(sub_823ECE58) {
  static std::atomic<int> reports{0};
  const uint32_t job = ctx.r3.u32;
  if (job) {
    const uint32_t head = rex::memory::load_and_swap<uint32_t>(base + job + 8);
    const int32_t count = static_cast<int32_t>(rex::memory::load_and_swap<uint32_t>(base + job + 16));
    if (head == 0 && count > 0) {
      if (reports.fetch_add(1) < 8) {
        REXLOG_ERROR("RB2DBG job {:08X}: list head null with count {} (+0 {:08X}, +4 {:08X}, "
                     "+12 {:08X}, lr {:08X}); skipped",
                     job, count, rex::memory::load_and_swap<uint32_t>(base + job),
                     rex::memory::load_and_swap<uint32_t>(base + job + 4),
                     rex::memory::load_and_swap<uint32_t>(base + job + 12),
                     static_cast<uint32_t>(ctx.lr));
      }
      return;
    }
  }
  __imp__sub_823ECE58(ctx, base);
}

// 0.2.6 diagnostic for converted (PS3 -> 360) character mods. Picking one froze the game: a
// .spr object's table allocation came back null and the memset after it wrote to address 0.
// Log what each .spr object is built from, and every failed allocation from the game heaps,
// so the next log says which file and how much memory was asked for.
REX_EXTERN(__imp__sub_823BEDA0);
REX_EXTERN(__imp__sub_823B8A68);

namespace {
std::string GuestString(uint8_t* base, uint32_t addr, size_t max = 48) {
  std::string s;
  if (!addr) return s;
  for (size_t i = 0; i < max; ++i) {
    const char c = static_cast<char>(base[addr + i]);
    if (!c) break;
    s.push_back(c >= 32 && c < 127 ? c : '?');
  }
  return s;
}
}  // namespace

// SPR object constructor: r3 = object, r4 = .spr data, r5 = its pack entry name.
REX_HOOK_RAW(sub_823BEDA0) {
  static std::atomic<int> reports{0};
  const uint32_t spr = ctx.r4.u32;
  if (spr && reports.fetch_add(1) < 400) {
    auto u32 = [&](uint32_t a) { return rex::memory::load_and_swap<uint32_t>(base + a); };
    const uint32_t types = u32(spr + 8);
    uint64_t entries = 0;
    for (uint32_t t = 0; t < types && t < 64; ++t) entries += u32(spr + 64 + t * 12 + 8);
    REXLOG_INFO("RB2SPR '{}' at {:08X}: magic {:08X} types {} entries {} io {:X} vram {:X} lr {:08X}",
                GuestString(base, ctx.r5.u32), spr, u32(spr), types, entries, u32(spr + 0x28),
                u32(spr + 0x30), static_cast<uint32_t>(ctx.lr));
  }
  __imp__sub_823BEDA0(ctx, base);
}

// Game heap allocation (size in r3); returns null when the heap has no room.
REX_HOOK_RAW(sub_823B8A68) {
  static std::atomic<int> reports{0};
  const uint32_t size = ctx.r3.u32;
  const uint32_t lr = static_cast<uint32_t>(ctx.lr);
  __imp__sub_823B8A68(ctx, base);
  if (ctx.r3.u32 == 0 && reports.fetch_add(1) < 64) {
    REXLOG_ERROR("RB2HEAP allocation of {} bytes ({:X}) failed, lr {:08X}", size, size, lr);
  }
}

// 0.2.7: the 0.2.6 log showed the character model's and face's .spr pointers were garbage
// (unaligned, zero header), so the pack data they point into was not what the game expected.
// Dump the file objects those two builders read: buffer, sizes, flags and the first bytes.
REX_EXTERN(__imp__sub_82175FC8);
REX_EXTERN(__imp__sub_82176890);

namespace {
void LogPackFile(uint8_t* base, const char* what, uint32_t file) {
  auto u32 = [&](uint32_t a) { return rex::memory::load_and_swap<uint32_t>(base + a); };
  if (!file) {
    REXLOG_INFO("RB2FILE {}: null", what);
    return;
  }
  const uint32_t buf = u32(file + 48), hdr = u32(file + 68);
  std::string head;
  if (hdr) {
    for (int i = 0; i < 64; ++i) {
      char b[3];
      std::snprintf(b, sizeof(b), "%02x", base[hdr + i]);
      head += b;
    }
  }
  REXLOG_INFO("RB2FILE {} obj {:08X} name '{}' buf {:08X} hdr {:08X} size44 {:X} size92 {:X} "
              "raw {} packed {} mode {} head {}",
              what, file, GuestString(base, u32(file + 40), 96), buf, hdr, u32(file + 44),
              u32(file + 92), u32(file + 84), u32(file + 88), u32(file + 60), head);
}
}  // namespace

REX_HOOK_RAW(sub_82175FC8) {
  static std::atomic<int> reports{0};
  const uint32_t obj = ctx.r3.u32;
  if (obj && reports.fetch_add(1) < 16) {
    auto u32 = [&](uint32_t a) { return rex::memory::load_and_swap<uint32_t>(base + a); };
    LogPackFile(base, "model_m", u32(obj + 4 * (u32(obj + 364) + 5)));
    LogPackFile(base, "model_v", u32(obj + 4 * (u32(obj + 368) + 5)));
    LogPackFile(base, "model_i", u32(obj + 4 * (u32(obj + 372) + 5)));
  }
  __imp__sub_82175FC8(ctx, base);
}

REX_HOOK_RAW(sub_82176890) {
  static std::atomic<int> reports{0};
  const uint32_t obj = ctx.r3.u32;
  auto u32 = [&](uint32_t a) { return rex::memory::load_and_swap<uint32_t>(base + a); };
  // Only when it is about to build the face (the branch that calls the .spr constructor).
  if (obj && u32(obj + 196) == 0 && u32(obj + 200) == 0 && reports.fetch_add(1) < 16) {
    LogPackFile(base, "face_m", u32(obj + 28));
    LogPackFile(base, "face_v", u32(obj + 68));
  }
  __imp__sub_82176890(ctx, base);
}
