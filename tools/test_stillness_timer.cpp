#include "StillnessTimer.h"
#include <cstdio>
#include <cstdlib>

using namespace openski::motion;

namespace {
void check(bool ok, const char* what) {
  if (!ok) { std::printf("FAILED: %s\n", what); std::exit(1); }
}
constexpr float kAx = 1.393f, kAy = 2.605f, kAz = 9.509f, kGx = -0.001f, kGy = 0.002f, kGz = -0.0133f;
constexpr uint32_t kIdleMs = 600000;

// Feeds rest samples every 10 ms from `fromMs` to `toMs`.
void rest(StillnessTimer& t, uint32_t fromMs, uint32_t toMs) {
  for (uint32_t ms = fromMs; ms < toMs; ms += 10) t.update(ms, kAx, kAy, kAz, kGx, kGy, kGz);
}
}  // namespace

int main() {
  {  // Still for the whole period: idle at the period, not before.
    StillnessTimer t(kIdleMs);
    t.restart(0);
    rest(t, 0, kIdleMs - 10);
    check(!t.idle(kIdleMs - 10), "not idle just before the period");
    rest(t, kIdleMs - 10, kIdleMs + 10);
    check(t.idle(kIdleMs), "idle at the period");
  }
  {  // Movement restarts the period from that moment.
    StillnessTimer t(kIdleMs);
    t.restart(0);
    rest(t, 0, 300000);
    t.update(300000, kAx + 0.5f, kAy, kAz, kGx, kGy, kGz);
    rest(t, 300010, 900000);
    check(!t.idle(899990), "movement at 300 s pushes idle past 600 s");
    rest(t, 900000, 900100);
    check(t.idle(900100), "idle 600 s after the movement");
  }
  {  // A rotation alone (gyro) is movement too.
    StillnessTimer t(kIdleMs);
    t.restart(0);
    rest(t, 0, 1000);
    t.update(1000, kAx, kAy, kAz, kGx + 0.3f, kGy, kGz);
    check(!t.idle(kIdleMs), "gyro movement restarts the period");
  }
  {  // The board holds its new resting pose: it does not keep restarting after being put down.
    StillnessTimer t(kIdleMs);
    t.restart(0);
    rest(t, 0, 1000);
    for (uint32_t ms = 1000; ms < 1000 + kIdleMs + 100; ms += 10) t.update(ms, kAx + 4.0f, kAy, kAz, kGx, kGy, kGz);
    check(t.idle(1010 + kIdleMs + 100), "idle after settling in a new pose");
  }
  {  // millis() rollover.
    StillnessTimer t(1000);
    t.restart(0xFFFFFF00u);
    check(!t.idle(0xFFFFFF00u + 500), "not idle across rollover");
    check(t.idle(0xFFFFFF00u + 1000), "idle across rollover");
  }
  std::printf("stillness timer ok\n");
  return 0;
}
