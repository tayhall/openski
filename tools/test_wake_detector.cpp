#include "WakeDetector.h"
#include <cstdio>
#include <cstdlib>

using namespace openski::motion;

namespace {
void check(bool ok, const char* what) {
  if (!ok) { std::printf("FAILED: %s\n", what); std::exit(1); }
}
// Bench rest values from the left boot (27 Oct 2026): gravity on the tilted board, small gyro bias.
constexpr float kAx = 1.393f, kAy = 2.605f, kAz = 9.509f, kGx = -0.001f, kGy = 0.002f, kGz = -0.0133f;

WakeDetector settled() {
  WakeDetector d;
  for (int i = 0; i < WakeDetector::kSettleSamples; ++i) check(!d.update(kAx, kAy, kAz, kGx, kGy, kGz), "baseline never wakes");
  check(d.armed(), "armed after the settle samples");
  return d;
}
}  // namespace

int main() {
  {  // Bench noise (peak to peak 0.043 m/s^2 and 0.0024 rad/s) never wakes.
    WakeDetector d = settled();
    for (int i = 0; i < 1000; ++i) {
      const float n = (i % 2 ? 0.0215f : -0.0215f), g = (i % 2 ? 0.0012f : -0.0012f);
      check(!d.update(kAx + n, kAy - n, kAz + n, kGx + g, kGy - g, kGz + g), "rest noise does not wake");
    }
  }
  {  // Just under and just over the accelerometer threshold, on each axis and sign.
    for (int axis = 0; axis < 3; ++axis) {
      for (int sign = -1; sign <= 1; sign += 2) {
        float a[3] = {kAx, kAy, kAz};
        WakeDetector under = settled();
        a[axis] += sign * 0.09f;
        check(!under.update(a[0], a[1], a[2], kGx, kGy, kGz), "0.09 m/s^2 does not wake");
        WakeDetector over = settled();
        a[axis] += sign * 0.02f;
        check(over.update(a[0], a[1], a[2], kGx, kGy, kGz), "0.11 m/s^2 wakes");
        check(!over.triggeredByGyro() && over.triggerDelta() > 0.1f, "accelerometer trigger reported");
      }
    }
  }
  {  // Gyro threshold, measured from the baseline so a resting bias does not count.
    WakeDetector under = settled();
    check(!under.update(kAx, kAy, kAz, kGx + 0.09f, kGy, kGz), "0.09 rad/s does not wake");
    WakeDetector over = settled();
    check(over.update(kAx, kAy, kAz, kGx, kGy - 0.11f, kGz), "0.11 rad/s wakes");
    check(over.triggeredByGyro(), "gyro trigger reported");
  }
  {  // A pick-up tilts the board: a 10 degree tilt moves gravity by about 1.7 m/s^2.
    WakeDetector d = settled();
    check(d.update(kAx + 1.7f, kAy, kAz - 0.1f, kGx, kGy, kGz), "tilt wakes");
  }
  {  // reset() starts a new baseline.
    WakeDetector d = settled();
    d.reset();
    check(!d.armed(), "reset disarms");
    check(!d.update(kAx + 5, kAy, kAz, 0, 0, 0), "first sample after reset is baseline");
  }
  std::puts("wake detector tests passed");
}
