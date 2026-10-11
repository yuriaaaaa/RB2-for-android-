// Game hooks carried over from upstream's src/hooks.cpp.
//
// Upstream's memory and string hooks never took effect (see rb2_rexcrt.toml). What is left is
// the frame pacing fix from upstream commit 81b0922 ("fixed frame rate issues by replacing
// vsync").

#include <atomic>
#include <chrono>
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
