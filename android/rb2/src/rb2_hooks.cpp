// Game hooks carried over from upstream's src/hooks.cpp.
//
// Upstream's memory and string hooks never took effect (see rb2_rexcrt.toml). What is left is
// the frame pacing fix from upstream commit 81b0922 ("fixed frame rate issues by replacing
// vsync").

#include <chrono>
#include <thread>

#include <rex/cvar.h>
#include <rex/hook.h>

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
