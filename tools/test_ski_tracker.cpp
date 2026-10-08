#include "SkiTracker.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>

using namespace openski::motion;

namespace {
constexpr float kPi = 3.14159265f;
constexpr float kG = 9.80665f;
constexpr float kRad = kPi/180.0f;

void check(bool ok, const char* what) {
  if (!ok) { std::printf("FAILED: %s\n", what); std::exit(1); }
}

struct Pose { float roll, pitch; };  // degrees from the neutral pose
using Profile = std::function<Pose(float)>;

struct Options {
  Mounting mounting{{0, 1}, {1, 1}, {2, -1}};  // x up the leg, y forward, -z lateral
  float bias[3] = {0, 0, 0};                   // rad/s added to the gyro
  float load = 0;                              // lateral push, in g per unit of sin(roll)/sin(30 deg)
  float magnitudeScale = 1;                    // constant scale on |a|
  float neutralPitchDeg = 0;                   // lean of the pose captured by zero()
  float spikeAt = -1, gapFrom = -1, gapTo = -1;
  float wobbleG = 0;                           // accel noise amplitude in g
  uint32_t clockOffsetUs = 0;                  // added to every timestamp (wraps like the ESP32 clock)
};

void cross(const float* a, const float* b, float* out) {
  out[0] = a[1]*b[2]-a[2]*b[1]; out[1] = a[2]*b[0]-a[0]*b[2]; out[2] = a[0]*b[1]-a[1]*b[0];
}

// World-up in the sensor frame for a pose relative to the neutral pose.
void upVector(const Options& o, Pose p, float* sensor) {
  const float r = p.roll*kRad, q = p.pitch*kRad;
  const float u[3] = {std::cos(r)*std::cos(q), -std::cos(r)*std::sin(q), -std::sin(r)};  // L, F, S
  // Rotate by the minimal rotation taking the leg's up axis onto the neutral pose.
  const float n = o.neutralPitchDeg*kRad;
  const float neutral[3] = {std::cos(n), -std::sin(n), 0};
  const float e[3] = {1, 0, 0};
  float k[3]; cross(e, neutral, k);
  float kxu[3]; cross(k, u, kxu);
  float kkxu[3]; cross(k, kxu, kkxu);
  float leg[3];
  for (int i = 0; i < 3; ++i) leg[i] = u[i] + kxu[i] + kkxu[i]/(1.0f + neutral[0]);
  sensor[o.mounting.up.axis] = o.mounting.up.sign*leg[0];
  sensor[o.mounting.forward.axis] = o.mounting.forward.sign*leg[1];
  sensor[o.mounting.lateral.axis] = o.mounting.lateral.sign*leg[2];
}

// 100 Hz trace: 2 s still (zero() is called first), then the profile.
void run(SkiTracker& tracker, const Profile& profile, float seconds, const Options& o = {}) {
  const float dt = 0.01f;
  float previous[3];
  upVector(o, profile(0), previous);
  tracker.zero();
  for (int i = 1; i <= static_cast<int>(seconds/dt); ++i) {
    const float t = i*dt;
    const Pose pose = t < 2 ? Pose{0, 0} : profile(t - 2);
    float up[3], mid[3], dUp[3], omega[3];
    upVector(o, pose, up);
    for (int k = 0; k < 3; ++k) { dUp[k] = (up[k]-previous[k])/dt; mid[k] = 0.5f*(up[k]+previous[k]); }
    cross(dUp, mid, omega);  // omega = d(up)/dt x up, perpendicular to up
    for (int k = 0; k < 3; ++k) previous[k] = up[k];
    float accel[3];
    const float push = o.load*std::sin(pose.roll*kRad)/std::sin(30*kRad)*kG;
    for (int k = 0; k < 3; ++k) accel[k] = up[k]*kG*o.magnitudeScale;
    accel[o.mounting.lateral.axis] += o.mounting.lateral.sign*(-push);
    if (o.wobbleG > 0) accel[0] += o.wobbleG*kG*std::sin(2*kPi*13*t);
    float gyro[3] = {omega[0]+o.bias[0], omega[1]+o.bias[1], omega[2]+o.bias[2]};
    if (o.spikeAt >= 0 && t >= o.spikeAt && t < o.spikeAt+0.02f) {
      accel[0] += 6*kG; gyro[1] += 15;
    }
    if (o.gapFrom >= 0 && t >= o.gapFrom && t < o.gapTo) continue;
    tracker.update(o.clockOffsetUs + static_cast<uint32_t>(t*1e6f + 0.5f), accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2]);
  }
}

Profile swings(float amplitude, float period, float periods, float pitch = 0, float offset = 0) {
  return [=](float t) {
    const float ramp = t < 1 ? t : 1;  // settle into the stance over one second
    if (t < 1) return Pose{0, pitch*ramp};
    const float s = t - 1;
    if (s > period*periods) return Pose{offset, pitch};
    return Pose{offset + amplitude*std::sin(2*kPi*s/period), pitch};
  };
}

float absf(float v) { return std::fabs(v); }
}  // namespace

int main() {
  const Options base;
  SkiEvent out[16];
  for (float period : {1.0f, 2.0f, 3.0f}) {  // +-30 deg at three cadences
    SkiTracker tracker(base.mounting);
    run(tracker, swings(30, period, 5), 3 + 5*period + 2);
    const uint8_t n = tracker.recent(out, 16);
    check(tracker.count() == 9 && n == 9, "five periods give nine completed half-turns");
    for (uint8_t i = 0; i < n; ++i) {
      check(out[i].positive == (i % 2 == 0), "signs alternate starting positive");
      check(absf(absf(out[i].peakRollDegrees) - 30) < 3, "peak roll within 3 degrees");
      check(!out[i].outsideEnvelope, "plausible turn is inside the envelope");
    }
  }
  {  // 20 degrees of forward flex all the time must not hide the turns or leak into roll
    SkiTracker tracker(base.mounting);
    run(tracker, swings(30, 2, 5, 20), 16);
    const uint8_t n = tracker.recent(out, 16);
    check(n == 9, "events found with 20 degrees of forward flex");
    for (uint8_t i = 0; i < n; ++i) {
      check(absf(absf(out[i].peakRollDegrees) - 30) < 3, "roll not corrupted by flex");
      check(absf(out[i].pitchDegrees - 20) < 3, "pitch reported at the peak");
    }
  }
  {  // a 5 degree slope offset makes the two sides unequal
    SkiTracker tracker(base.mounting);
    run(tracker, swings(30, 2, 5, 0, 5), 16);
    const uint8_t n = tracker.recent(out, 16);
    check(n >= 8, "events found with a slope offset");
    for (uint8_t i = 0; i < n; ++i)
      check(absf(out[i].peakRollDegrees - (out[i].positive ? 35.0f : -25.0f)) < 3, "peaks follow the offset");
  }
  {  // hard turns: |a| well above 1 g with lateral load must not drop samples or move the roll
    Options o; o.load = 0.9f;
    SkiTracker tracker(o.mounting);
    run(tracker, swings(30, 2, 5), 16, o);
    check(tracker.recent(out, 16) == 9, "events found under centripetal load");
    for (uint8_t i = 0; i < 9; ++i) check(absf(absf(out[i].peakRollDegrees) - 30) < 3, "roll unaffected by load");
  }
  {  // gyro bias over a minute of turning stays bounded because the accelerometer corrects near the crossings
    Options o; o.load = 0.9f; o.bias[0] = o.bias[1] = o.bias[2] = 0.02f;  // 1.15 deg/s on each axis
    SkiTracker tracker(o.mounting);
    run(tracker, swings(30, 2, 30), 3 + 60 + 2, o);
    check(tracker.count() >= 57, "turns still counted after a minute with gyro bias");
    const uint8_t n = tracker.recent(out, 16);
    for (uint8_t i = 0; i < n; ++i) check(absf(absf(out[i].peakRollDegrees) - 30) < 5, "drift stays bounded");
  }
  {  // a short 6 g shock must not create a phantom half-turn
    Options o; o.spikeAt = 8.0f;
    SkiTracker clean(o.mounting), shocked(o.mounting);
    Options quiet; run(clean, swings(30, 2, 5), 16, quiet);
    run(shocked, swings(30, 2, 5), 16, o);
    check(shocked.count() == clean.count(), "no phantom event from an impact");
  }
  {  // right after a shock the roll estimate must not have jumped (15 rad/s for 20 ms is 17 degrees)
    Options o; o.spikeAt = 8.0f;
    SkiTracker tracker(o.mounting);
    run(tracker, swings(0, 1, 0), 8.1f, o);
    check(absf(tracker.rollDegrees()) < 3 && tracker.count() == 0, "shock does not move the roll");
  }
  {  // a sample gap abandons the half-turn in progress
    Options o; o.gapFrom = 8.0f; o.gapTo = 8.15f;
    SkiTracker tracker(o.mounting);
    run(tracker, swings(30, 2, 5), 16, o);
    check(tracker.gaps() == 1 && tracker.count() < 9, "gap abandons the event");
  }
  {  // the same sensor data read through a mirrored lateral axis flips every sign and nothing else
    Options mirrored; mirrored.mounting.lateral.sign = 1;
    SkiTracker a(base.mounting), b(mirrored.mounting);
    SkiEvent outA[16], outB[16];
    run(a, swings(30, 2, 5), 16, base);
    run(b, swings(30, 2, 5), 16, base);  // identical trace, different mapping
    const uint8_t na = a.recent(outA, 16), nb = b.recent(outB, 16);
    check(na == nb && na == 9, "both mappings detect the turns");
    for (uint8_t i = 0; i < na; ++i) {
      check(outA[i].positive != outB[i].positive, "sign flips");
      check(absf(outA[i].peakRollDegrees + outB[i].peakRollDegrees) < 0.5f, "magnitude unchanged");
    }
  }
  {  // the 32-bit microsecond clock wraps after 71 minutes; a turn spanning the wrap is still one event
    Options o; o.clockOffsetUs = 0xFFFFFFFFU - 9500000U;  // wraps 9.5 s into the trace, mid-run
    SkiTracker tracker(o.mounting);
    run(tracker, swings(30, 2, 5), 16, o);
    check(tracker.count() == 9 && tracker.gaps() == 0, "clock wrap does not split or drop events");
    tracker.recent(out, 16);
    for (uint8_t i = 0; i < 9; ++i)
      check(out[i].endUs - out[i].startUs > 900000U && out[i].endUs - out[i].startUs < 1100000U, "durations survive the wrap");
  }
  {  // walking tremor and a small wobble produce no events
    SkiTracker tracker(base.mounting);
    run(tracker, swings(5, 0.33f, 30), 14);
    check(tracker.count() == 0, "5 degree wobble ignored");
  }
  {  // outside the envelope: very fast and very large turns are flagged, not hidden
    SkiTracker fast(base.mounting);
    run(fast, swings(30, 0.4f, 12), 10);
    check(fast.count() > 5, "fast turns still reported");
    fast.recent(out, 16);
    check(out[0].outsideEnvelope, "rate and duration outside the envelope");
    SkiTracker big(base.mounting);
    run(big, swings(70, 3, 4), 17);
    big.recent(out, 16);
    check(big.count() >= 6 && out[0].outsideEnvelope, "70 degrees of roll is flagged");
  }
  {  // the neutral pose can be a stance leaning forward
    Options o; o.neutralPitchDeg = 12;
    SkiTracker tracker(o.mounting);
    run(tracker, swings(30, 2, 5, 15), 16, o);
    check(tracker.zeroed(), "zeroed");
    const uint8_t n = tracker.recent(out, 16);
    check(n == 9, "events found from a leaning neutral");
    for (uint8_t i = 0; i < n; ++i) {
      check(absf(absf(out[i].peakRollDegrees) - 30) < 3, "roll relative to the leaning neutral");
      check(absf(out[i].pitchDegrees - 15) < 3, "pitch relative to the leaning neutral");
    }
  }
  {  // no events until the pose is zeroed, and a new zero() discards the old one
    SkiTracker tracker(base.mounting);
    check(!tracker.zeroed(), "not zeroed at start");
    tracker.zero();
    for (int i = 0; i < 50; ++i) tracker.update(i*10000U, kG, 0, 0, 0, 0, 0);
    check(!tracker.zeroed() && tracker.zeroing(), "needs a full second of stillness");
    for (int i = 50; i < 150; ++i) tracker.update(i*10000U, kG, 0, 0, 0, 0, 0);
    check(tracker.zeroed() && !tracker.zeroing(), "zeroed after a second");
    tracker.zero();
    check(!tracker.zeroed(), "zero() discards the old pose");
  }
  {  // zeroing while the leg is still slowly moving (under the rate limit) must not capture a drifting pose
    SkiTracker tracker(base.mounting);
    tracker.zero();
    for (int i = 0; i < 150; ++i) {  // 1.5 s leaning forward at 0.1 rad/s: 8.6 degrees of travel
      const float angle = 0.1f*i*0.01f;
      tracker.update(i*10000U, kG*std::cos(angle), kG*std::sin(angle), 0, 0, 0, -0.1f);
    }
    check(!tracker.zeroed(), "a slow lean is not a still pose");
    const float rest = 0.1f*1.49f;
    for (int i = 150; i < 300; ++i)  // then genuinely still
      tracker.update(i*10000U, kG*std::cos(rest), kG*std::sin(rest), 0, 0, 0, 0);
    check(tracker.zeroed(), "zeroed once actually still");
  }
  {  // a stance that is not upright on the leg is refused
    SkiTracker tracker(base.mounting);
    tracker.zero();
    for (int i = 0; i < 300; ++i) tracker.update(i*10000U, 0, kG, 0, 0, 0, 0);  // gravity along forward
    check(!tracker.zeroed(), "refuses a sideways pose");
  }
  {  // heartbeat separates stillness from shaking
    SkiTracker still(base.mounting), shaken(base.mounting);
    Options o; o.wobbleG = 0.3f;
    run(still, swings(0, 1, 0), 3);
    run(shaken, swings(0, 1, 0), 3, o);
    const SkiHeartbeat a = still.takeHeartbeat(), b = shaken.takeHeartbeat();
    check(a.vibrationMps2 < 0.05f && b.vibrationMps2 > 1.5f, "vibration separates still from shaken");
    check(still.takeHeartbeat().vibrationMps2 == 0, "take resets the interval");
  }
  std::puts("ski tracker tests passed");
}
