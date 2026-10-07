#include "TiltTracker.h"
#include <cassert>
#include <cmath>
#include <cstdio>

using openski::motion::Excursion;
using openski::motion::TiltTracker;

namespace {
constexpr float kPi = 3.14159265f;
constexpr float kBias[3] = {-0.0332f, 0.0040f, 0.0070f};  // residual gyro bias, rad/s

// Body rotated by `angle` about `axis`: world up seen in the body frame is up0 rotated by -angle.
void rotate(const float* v, const float* k, float angle, float* out) {
  const float c = std::cos(angle), s = std::sin(angle);
  const float cross[3] = {k[1]*v[2]-k[2]*v[1], k[2]*v[0]-k[0]*v[2], k[0]*v[1]-k[1]*v[0]};
  const float dot = k[0]*v[0] + k[1]*v[1] + k[2]*v[2];
  for (int i = 0; i < 3; ++i) out[i] = v[i]*c + cross[i]*s + k[i]*dot*(1-c);
}

// 2 s rest, rise, hold, fall, then rest, at 100 Hz.
void run(TiltTracker& tracker, const float* up0, const float* axis, float peak, float rise, float hold,
         float fall, float seconds, bool addBias = true) {
  const float dt = 0.01f;
  float previous = 0;
  for (int i = 1; i <= static_cast<int>(seconds/dt); ++i) {
    const float t = i*dt;
    float angle = 0;
    if (t >= 2 && t < 2+rise) angle = peak*(t-2)/rise;
    else if (t >= 2+rise && t < 2+rise+hold) angle = peak;
    else if (t >= 2+rise+hold && t < 2+rise+hold+fall) angle = peak*(1-(t-2-rise-hold)/fall);
    const float omega = (angle-previous)/dt;
    previous = angle;
    float up[3];
    rotate(up0, axis, -angle, up);
    tracker.update(static_cast<uint32_t>(t*1e6f), up[0]*9.80665f, up[1]*9.80665f, up[2]*9.80665f,
                   omega*axis[0] + (addBias ? kBias[0] : 0), omega*axis[1] + (addBias ? kBias[1] : 0),
                   omega*axis[2] + (addBias ? kBias[2] : 0));
  }
}
}  // namespace

int main() {
  const float x[3] = {1,0,0}, y[3] = {0,1,0}, z[3] = {0,0,1};
  const float fortyFive = 45*kPi/180;
  Excursion out[16];
  {  // About X from z up, then about Y from x up: signed angle follows the sensor rotation.
    TiltTracker tracker; run(tracker, z, x, fortyFive, 1.0f, 0.5f, 1.0f, 8);
    assert(tracker.recent(out, 16) == 1 && out[0].axis == 0);
    assert(std::fabs(out[0].aboutDegrees - 45) < 2 && std::fabs(out[0].peakDegrees - 45) < 2);
  }
  {
    TiltTracker tracker; run(tracker, z, x, -fortyFive, 1.0f, 0.5f, 1.0f, 8);
    assert(tracker.recent(out, 16) == 1 && out[0].aboutDegrees < -43);
  }
  {
    TiltTracker tracker; run(tracker, x, y, fortyFive, 1.0f, 0.5f, 1.0f, 8);
    assert(tracker.recent(out, 16) == 1 && out[0].axis == 1 && std::fabs(out[0].aboutDegrees - 45) < 2);
  }
  {  // Slow and fast movements report the same angle.
    TiltTracker slow; run(slow, x, y, fortyFive, 2.0f, 1.0f, 2.0f, 10);
    TiltTracker fast; run(fast, x, y, fortyFive, 0.15f, 0.1f, 0.15f, 8);
    assert(slow.recent(out, 16) == 1 && std::fabs(out[0].peakDegrees - 45) < 2);
    assert(fast.recent(out, 16) == 1 && std::fabs(out[0].peakDegrees - 45) < 2);
  }
  {  // Rotation about the vertical axis is invisible to gravity.
    TiltTracker tracker; run(tracker, z, z, fortyFive, 1.0f, 0.5f, 1.0f, 8);
    assert(tracker.count() == 0 && tracker.verticalAxis() == 2);
  }
  {  // Small wobble stays under the 20 degree trigger.
    TiltTracker tracker; run(tracker, x, y, 10*kPi/180, 0.5f, 0.2f, 0.5f, 8);
    assert(tracker.count() == 0);
  }
  {  // Holding the tilt for over 15 s is rejected, not reported as a movement.
    TiltTracker tracker; run(tracker, x, y, fortyFive, 1.0f, 20.0f, 1.0f, 30);
    assert(tracker.count() == 0 && tracker.rejected() == 1);
  }
  {  // At rest with bias the tilt stays near zero.
    TiltTracker tracker; run(tracker, x, y, 0, 1.0f, 0, 1.0f, 60);
    assert(tracker.neutralReady() && tracker.count() == 0 && tracker.tiltDegrees() < 0.5f);
  }
  {  // zero() recaptures the neutral pose.
    TiltTracker tracker; run(tracker, x, y, 0, 1.0f, 0, 1.0f, 5);
    tracker.zero(); assert(!tracker.neutralReady());
    run(tracker, x, y, 0, 1.0f, 0, 1.0f, 5);
    assert(tracker.neutralReady());
  }
  std::puts("tilt tracker tests passed");
}
