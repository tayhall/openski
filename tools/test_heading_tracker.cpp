#include "HeadingTracker.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>

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
    // Raw lobes end on multiples of 2 s after 1.0 s; the 0.25 s shift cancels most of the filter delay.
    const uint32_t endPhase = (turns[2].endUs - 1000000U) % 2000000U;
    check(endPhase < 150000U || endPhase > 1850000U, "end time compensated for filter delay");
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
  {  // Wobble with 0.5 s lobes (so the 0.4 s rule is not what drops it) stays under the 15 deg/s floor.
    HeadingTracker h; uint32_t t = 0;
    feedSine(h, t, 30.0f, 14.0f, 1.0f);
    check(h.count() == 0 && h.rejected() == 0, "wobble under the floor is dropped by amplitude");
  }
  {  // The same wobble at 40 deg/s (filtered peak about 21) is detected: the floor is the discriminator.
    HeadingTracker h; uint32_t t = 0;
    feedSine(h, t, 30.0f, 40.0f, 1.0f);
    check(h.count() > 0, "wobble above the floor is detected");
  }
  {  // Lobes shorter than 0.4 s are rejected and counted.
    HeadingTracker h; uint32_t t = 0;
    feedSine(h, t, 20.0f, 120.0f, 0.5f);  // half-lobes of 0.25 s
    check(h.count() == 0 && h.rejected() > 0, "short lobes rejected");
  }
  {  // A gap inside a lobe abandons it: nothing spans the hole.
    HeadingTracker h; uint32_t t = 10000000U;
    feedSine(h, t, 5.5f, 60.0f, 4.0f);  // ends 0.5 s before the end of a positive 2 s lobe
    const uint32_t before = h.count();
    const uint32_t holeStart = t, holeEnd = t + 500000U;
    t = holeEnd;
    feedSine(h, t, 6.0f, 60.0f, 4.0f);  // resumes positive
    check(h.count() > before, "counting continues after a gap");
    HeadingTurn turns[16];
    const uint8_t n = h.recent(turns, 16);
    for (uint8_t i = 0; i < n; ++i)
      check(!(turns[i].startUs < holeStart && turns[i].endUs > holeEnd), "no turn spans the gap");
    for (uint8_t i = 0; i < n; ++i)
      if (turns[i].sequence > before)
        check(turns[i].startUs >= holeEnd - 250000U, "post-gap turns start at the resume time");
  }
  {  // The running SD survives a gap: a quiet resume does not look like a big turn against a decayed SD.
    HeadingTracker h; uint32_t t = 10000000U;
    feedSine(h, t, 40.0f, 300.0f, 4.0f);
    const uint32_t before = h.count();
    check(before > 0, "strong turns found before the gap");
    t += 100000000U;  // 100 s hole
    feedSine(h, t, 8.0f, 100.0f, 4.0f);
    check(h.count() == before, "threshold is not decayed by a gap");
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
    const uint32_t t0 = 4294967295U - 3000000U;
    HeadingTurn turns[16];
    const uint8_t n = h.recent(turns, 16);
    bool straddles = false;
    for (uint8_t i = 0; i < n; ++i) {
      const uint32_t s = turns[i].startUs - t0, e = turns[i].endUs - t0;  // wrap-safe offsets
      if (s < 3000000U && e > 3000000U && e < 8000000U) {
        straddles = true;
        check(e - s > 1500000U && e - s < 2500000U, "turn straddling the wrap keeps its duration");
      }
    }
    check(straddles, "a turn straddles the clock wrap");
  }
  std::printf("heading tracker tests passed\n");
  return 0;
}
