#include "HeadingTracker.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

using namespace openski::motion;

namespace {
constexpr float kPi = 3.14159265f;
constexpr float kRad = kPi/180.0f;
void check(bool ok, const char* what) {
  if (!ok) { std::printf("FAILED: %s\n", what); std::exit(1); }
}
// Alternating turns: heading rate = amplitude * sin(2 pi t / period), 100 Hz.
void feedSine(HeadingTracker& h, uint32_t& t, float seconds, float amplitudeDps, float periodS,
              uint8_t flags = 0) {
  const int n = static_cast<int>(seconds*100);
  for (int i = 0; i < n; ++i, t += 10000U)
    h.update(t, amplitudeDps*std::sin(2*kPi*i/(periodS*100))*kRad, flags);
}
}  // namespace

int main() {
  {  // Eight alternating 2 s lobes at 60 deg/s peak are found, with alternating sign.
    HeadingTracker h; uint32_t t = 1000000U;
    feedSine(h, t, 16.0f, 60.0f, 4.0f);
    feedSine(h, t, 1.0f, 0.0f, 4.0f);  // let the last lobe close
    HeadingTurn turns[16];
    const uint8_t n = h.recent(turns, 16);
    check(n >= 6 && n <= 8, "alternating lobes are counted");
    for (uint8_t i = 1; i < n; ++i) check(turns[i].positive != turns[i-1].positive, "signs alternate");
    check(turns[2].peakRateDps > 40 && turns[2].peakRateDps < 70, "peak rate in range");
    // A half sine of 60 deg/s over 2 s integrates to 60*2/pi*... about 76 degrees.
    check(std::fabs(turns[2].changeDegrees) > 55 && std::fabs(turns[2].changeDegrees) < 95, "heading change");
    const uint32_t duration = turns[2].endUs - turns[2].startUs;
    check(duration > 1500000U && duration < 2500000U, "duration near 2 s");
  }
  {  // Standing still with gyro noise produces nothing (absolute floor).
    HeadingTracker h; uint32_t t = 0;
    unsigned seed = 7;
    for (int i = 0; i < 6000; ++i, t += 10000U) {
      seed = seed*1664525u + 1013904223u;
      const float noise = (static_cast<int>((seed >> 16) & 0xff) - 128)/128.0f*0.02f;  // +-1.1 deg/s
      h.update(t, noise);
    }
    check(h.count() == 0, "no turns when standing");
  }
  {  // Walking-like 2 Hz, 10 deg/s wobble stays under the 15 deg/s floor.
    HeadingTracker h; uint32_t t = 0;
    feedSine(h, t, 30.0f, 10.0f, 0.5f);
    check(h.count() == 0, "no turns for small fast wobble");
  }
  {  // Lobes shorter than 0.4 s are rejected and counted.
    HeadingTracker h; uint32_t t = 0;
    feedSine(h, t, 20.0f, 120.0f, 0.5f);  // half-lobes of 0.25 s
    check(h.count() == 0 && h.rejected() > 0, "short lobes rejected");
  }
  {  // A gap inside a lobe abandons it: nothing spans the gap.
    HeadingTracker h; uint32_t t = 0;
    feedSine(h, t, 6.0f, 60.0f, 4.0f);
    const uint32_t before = h.count();
    t += 500000U;  // 0.5 s hole
    feedSine(h, t, 6.0f, 60.0f, 4.0f);
    HeadingTurn turns[16];
    const uint8_t n = h.recent(turns, 16);
    for (uint8_t i = 0; i < n; ++i) {
      const uint32_t gapStart = 6000000U;  // end of the first block
      check(!(turns[i].startUs < gapStart && turns[i].endUs > gapStart + 500000U), "no turn spans the gap");
    }
    check(h.count() >= before, "counting continues after a gap");
  }
  {  // Flags set during a segment reach the emitted turn.
    HeadingTracker h; uint32_t t = 0;
    feedSine(h, t, 4.0f, 60.0f, 4.0f);
    h.skip();
    feedSine(h, t, 1.0f, 60.0f, 4.0f, HeadingTracker::kNearRail);
    feedSine(h, t, 6.0f, 60.0f, 4.0f);
    HeadingTurn turns[16];
    const uint8_t n = h.recent(turns, 16);
    bool skipped = false, rail = false;
    for (uint8_t i = 0; i < n; ++i) { skipped |= turns[i].skippedSamples; rail |= turns[i].nearRail; }
    check(skipped && rail, "quality flags are carried");
  }
  {  // The 32-bit microsecond clock may wrap mid-run.
    HeadingTracker h; uint32_t t = 4294967295U - 3000000U;
    feedSine(h, t, 16.0f, 60.0f, 4.0f);
    check(h.count() >= 5, "turns are found across a clock wrap");
  }
  std::printf("heading tracker tests passed\n");
  return 0;
}
