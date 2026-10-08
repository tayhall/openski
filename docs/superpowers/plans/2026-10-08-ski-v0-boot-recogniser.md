# ski_v0 Boot Recogniser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The boot sensor detects ski half-turns itself and sends small events plus a 1 Hz state frame, with a diagnostics mode (Wi-Fi and raw stream on) and a production mode (Wi-Fi and raw stream off), and the Android app can decode the frames and switch modes.

**Architecture:** A portable, host-tested `SkiTracker` (gravity fusion, roll and pitch from a zeroed pose, half-turn segments) lives in `lib/Motion` beside `TiltTracker`. `SkiFrames.h` encodes the version 2 and 3 frames and parses the new commands, also host-tested. `MotionService` owns the tracker; `BluetoothService` sends frames and handles commands on the existing movement and recorder-control characteristics; a tiny `ModeService` switches Wi-Fi on and off. The Android client gets new decoders, a version dispatch and a small Test lab card.

**Tech Stack:** C++17 header-only library code (host g++ for tests, PlatformIO/Arduino/NimBLE-Arduino for the board), Kotlin native Android (JUnit unit tests, Gradle).

**Spec:** `docs/superpowers/specs/2026-10-08-ski-v0-boot-recogniser-design.md` (approved 8 October 2026, then updated for the two modes). Read it first.

## Global Constraints

- Firmware is C++17 on the ESP32 DevKit V1 (default env `esp32-devkit-v1`); the `esp32-s3-supermini` env must also build.
- `loop()` is cooperative with a 2 ms delay; never block inside a `tick()`. The IMU samples at 100 Hz.
- Raw samples (BLE live frame, recorder, `/api/v1/imu`) stay uncorrected; only recogniser inputs get `SensorCalibration.h` correction.
- All multibyte fields are little-endian. Movement characteristic `b1e7a100-3c31-4d59-a2c8-1e9f2f810006`: version 1 is the existing 15-byte `tilt_v1` event (unchanged), version 2 is the 18-byte half-turn event, version 3 is the 16-byte state frame.
- Recorder-control opcodes `07` (zero) and `08 mode` (`0` diagnostics, `1` production); the 16-byte response is unchanged except flag bit 6 (set = production). Both work without recorder storage.
- Mode is volatile: every boot starts in diagnostics. Flash recording is independent of mode and stays at 100 Hz.
- `tilt_v1` keeps running unchanged.
- Android: minSdk 26, no Compose or AndroidX beyond the platform, hand-built Views; the Test lab uses `SkiUi` (dark), not `Snow`.
- Keep the experimental framing everywhere: cuff lean in a sensor-derived frame, not ski edge angle; no turn classification claim.
- Branch work happens on `feat/ski-v0-recogniser`; `main` is the PR target. End every commit message with the two lines `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z`.
- Update the relevant docs when behaviour changes (Task 8).

## Review Focus

Failure modes the spec implies but nothing in the happy path exercises, most likely first. Each has a pinning test in the task named.

1. **Garbled or unknown frames on the movement characteristic** (truncated v2, v3 with reserved bits set, version 4, a v2 length with a v1 version byte) must be dropped, never decoded as the wrong type. Task 6, `SkiEventProtocolTest`.
2. **The 32-bit microsecond clock wraps (every 71 minutes) in the middle of a half-turn.** The event must survive, with a correct duration. Task 1, the clock-wrap case.
3. **A bad zero:** zeroing while moving, while sideways, or twice in a row must never produce events from a bogus pose. Task 1, the zero cases.
4. **Malformed mode or zero commands** (`08` with no argument, `08 02`, `07` with a payload) and commands sent while flash storage is unavailable must still get a response and change nothing. Task 2, `parseMotionCommand` cases; the storage ordering is a code-structure requirement in Task 5.
5. **Wi-Fi off then on leaves OTA or the HTTP server dead.** Only hardware can show this. Task 8, the single on-board check.

---

### Task 1: SkiTracker

**Files:**
- Create: `lib/Motion/src/SkiTracker.h`
- Create: `tools/test_ski_tracker.cpp`

**Interfaces:**
- Produces (used by Tasks 2, 3): in `namespace openski::motion`:
  - `struct AxisMap { uint8_t axis; int8_t sign; }`, `struct Mounting { AxisMap up, forward, lateral; }`
  - `struct SkiEvent { uint32_t sequence, startUs, endUs; float peakRollDegrees, peakRateDps, pitchDegrees; bool positive, outsideEnvelope, pitchOutside, skippedSamples; }`
  - `struct SkiHeartbeat { float vibrationMps2, gyroDps; }`
  - `class SkiTracker` with `explicit SkiTracker(Mounting)`, `update(uint32_t timestampUs, float ax, float ay, float az, float gx, float gy, float gz)` (accel m/s², gyro rad/s), `zero()`, `zeroed()`, `zeroing()`, `rollDegrees()`, `pitchDegrees()`, `count()`, `rejected()`, `gaps()`, `takeGapSeen()`, `recent(SkiEvent*, uint8_t) -> uint8_t`, `takeHeartbeat() -> SkiHeartbeat`.
- Roll is positive when the leg leans toward the lateral axis `+S`; pitch is positive leaning forward.

The code below was written and run on the host before this plan was finalised (all cases pass, and breaking the pull limit, impact gate, gap limit, band and upright check each makes a case fail). Use it as written; if a case fails on your machine, fix the cause rather than loosening the case.

- [ ] **Step 1: Create the branch and commit the spec and plan**

```bash
git switch -c feat/ski-v0-recogniser
git add docs/superpowers/specs docs/superpowers/plans
git commit -m "$(cat <<'EOF'
Add ski_v0 boot recogniser spec and plan

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

- [ ] **Step 2: Write the failing test**

Create `tools/test_ski_tracker.cpp`:

```cpp
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
```

- [ ] **Step 3: Run it to confirm it fails**

Put the WinLibs `mingw64/bin` directory on PATH if `g++` is not found (see CLAUDE.md).

Run: `mkdir -p .pio/host && g++ -std=gnu++17 -Wall -Wextra -Ilib/Motion/src tools/test_ski_tracker.cpp -o .pio/host/test_ski_tracker.exe`
Expected: FAIL with `SkiTracker.h: No such file or directory`.

- [ ] **Step 4: Write the implementation**

Create `lib/Motion/src/SkiTracker.h`:

```cpp
#pragma once
#include <cmath>
#include <cstdint>

namespace openski::motion {
// A sensor axis (0=x, 1=y, 2=z) and the sign that makes it point the stated way on the leg.
struct AxisMap { uint8_t axis; int8_t sign; };
// Which sensor axis points up the leg (L), forward (F) and sideways (S).
struct Mounting { AxisMap up, forward, lateral; };

struct SkiEvent {
  uint32_t sequence = 0, startUs = 0, endUs = 0;
  float peakRollDegrees = 0;  // signed; positive = the leg leans toward +S
  float peakRateDps = 0;      // magnitude of the smoothed roll rate
  float pitchDegrees = 0;     // at the roll peak; positive = leaning forward
  bool positive = false;      // side of the half-turn (roll > 0)
  bool outsideEnvelope = false, pitchOutside = false, skippedSamples = false;
};

struct SkiHeartbeat {
  float vibrationMps2 = 0;  // RMS of high-passed |a| over the interval
  float gyroDps = 0;        // RMS of angular speed over the interval
};

// Half-turn recogniser for a boot-cuff sensor. Inputs are corrected sensor-frame values:
// accel in m/s^2 (reads +g along whichever axis points up), gyro in rad/s.
// Roll is lean about the forward axis, pitch is lean about the lateral axis, both measured
// from a pose captured by zero(). Experimental: cuff lean in a sensor-derived frame, not ski edge angle.
class SkiTracker {
 public:
  static constexpr float kGravity = 9.80665f;
  static constexpr float kDegrees = 57.2957795f;

  explicit SkiTracker(Mounting mounting) : map_(mounting) {}

  void update(uint32_t t, float ax, float ay, float az, float gx, float gy, float gz) {
    const float mag = std::sqrt(ax*ax + ay*ay + az*az);
    const float rate = std::sqrt(gx*gx + gy*gy + gz*gz);
    meter(mag, rate, t);
    // Impacts and free fall carry no usable gravity direction, and a shock can spike the gyro.
    // Hold the state through them rather than integrate garbage.
    if (mag > kImpactG*kGravity || mag < kFreeFallG*kGravity || rate > kMaxRateRadps) {
      skipped_ = true;
      return;
    }
    const float a[3] = {ax/mag, ay/mag, az/mag};
    if (!hasTime_) { seed(t, a); return; }
    const uint32_t delta = t - lastTime_;
    if (delta == 0) return;
    lastTime_ = t;
    if (delta > kGapUs) {  // sample loss: the gyro bridge is no longer trustworthy
      ++gaps_; gapSeen_ = true;
      abandon();
      seed(t, a);
      return;
    }
    const float dt = delta/1000000.0f;
    const float w[3] = {gx, gy, gz};
    float next[3] = {
      up_[0] - (w[1]*up_[2] - w[2]*up_[1])*dt,
      up_[1] - (w[2]*up_[0] - w[0]*up_[2])*dt,
      up_[2] - (w[0]*up_[1] - w[1]*up_[0])*dt};
    // The accelerometer pull fades out as |a| leaves 1 g, so hard turns follow the gyro alone.
    const float error = std::fabs(mag - kGravity)/kGravity;
    const float weight = error < kPullLimit ? 1.0f - error/kPullLimit : 0.0f;
    if (weight > 0) {
      const float alpha = dt/(0.5f + dt)*weight;
      for (int i = 0; i < 3; ++i) next[i] += alpha*(a[i] - next[i]);
    }
    normalise(next);
    for (int i = 0; i < 3; ++i) up_[i] = next[i];
    if (!neutralReady_) { trackNeutral(t, rate, error); return; }
    measure(dt);
    runSegment(t);
  }

  // Recapture the neutral pose from the next stationary second.
  void zero() {
    neutralReady_ = false; stillSince_ = false; zeroing_ = true;
    abandon(); roll_ = pitch_ = rollRate_ = 0; hasRoll_ = false;
  }

  bool zeroed() const { return neutralReady_; }
  bool zeroing() const { return zeroing_; }
  float rollDegrees() const { return roll_*kDegrees; }
  float pitchDegrees() const { return pitch_*kDegrees; }
  uint32_t count() const { return count_; }
  uint32_t rejected() const { return rejected_; }
  uint32_t gaps() const { return gaps_; }
  // True if a sample gap happened since the last call (for the heartbeat flag).
  bool takeGapSeen() { const bool seen = gapSeen_; gapSeen_ = false; return seen; }
  uint8_t recent(SkiEvent* output, uint8_t capacity) const {
    const uint8_t available = count_ < 16 ? static_cast<uint8_t>(count_) : 16;
    const uint8_t copied = available < capacity ? available : capacity;
    for (uint8_t i = 0; i < copied; ++i) output[i] = history_[(count_ - copied + i) % 16];
    return copied;
  }
  // RMS vibration and gyro activity since the last call.
  SkiHeartbeat takeHeartbeat() {
    SkiHeartbeat beat;
    if (meterCount_ > 0) {
      beat.vibrationMps2 = std::sqrt(vibrationSum_/meterCount_);
      beat.gyroDps = std::sqrt(gyroSum_/meterCount_)*kDegrees;
    }
    vibrationSum_ = gyroSum_ = 0; meterCount_ = 0;
    return beat;
  }

 private:
  static constexpr float kImpactG = 2.5f;       // |a| above this: skip the sample
  static constexpr float kFreeFallG = 0.3f;
  static constexpr float kMaxRateRadps = 12.0f; // about 690 deg/s, beyond any plausible turn
  static constexpr float kPullLimit = 0.15f;    // fractional |a| error where the accel pull reaches zero
  static constexpr uint32_t kGapUs = 100000U;
  static constexpr uint32_t kStillUs = 1000000U;
  static constexpr uint32_t kMinimumUs = 150000U;
  static constexpr float kDeadbandRadians = 1.5f/57.2957795f;
  static constexpr float kBandRadians = 8.0f/57.2957795f;
  static constexpr float kEnvelopeRollDegrees = 60.0f, kEnvelopeRateDps = 300.0f;
  static constexpr uint32_t kEnvelopeMinUs = 300000U, kEnvelopeMaxUs = 4000000U;
  static constexpr float kEnvelopePitchMin = -15.0f, kEnvelopePitchMax = 45.0f;

  static void normalise(float* v) {
    const float length = std::sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
    if (length > 0) { v[0] /= length; v[1] /= length; v[2] /= length; }
  }
  // Leg-frame components (L, F, S) of a sensor-frame vector.
  void toLeg(const float* v, float* leg) const {
    leg[0] = map_.up.sign*v[map_.up.axis];
    leg[1] = map_.forward.sign*v[map_.forward.axis];
    leg[2] = map_.lateral.sign*v[map_.lateral.axis];
  }
  void seed(uint32_t t, const float* a) {
    for (int i = 0; i < 3; ++i) up_[i] = a[i];
    lastTime_ = t; hasTime_ = true; stillSince_ = false; hasRoll_ = false;
  }
  void trackNeutral(uint32_t t, float rate, float error) {
    if (!zeroing_) return;
    if (rate < 0.12f && error < 0.05f) {
      if (!stillSince_) { stillSince_ = true; stillSinceUs_ = t; }
      if (t - stillSinceUs_ >= kStillUs) {
        float leg[3];
        toLeg(up_, leg);
        if (leg[0] < 0.5f) { stillSince_ = false; return; }  // not roughly upright on the leg
        // Minimal rotation taking the neutral pose onto the leg's up axis (Rodrigues).
        kx_[0] = 0; kx_[1] = leg[2]; kx_[2] = -leg[1];
        cosine_ = leg[0];
        neutralReady_ = true; zeroing_ = false;
      }
    } else stillSince_ = false;
  }
  void rotateToNeutral(const float* u, float* out) const {
    const float* k = kx_;
    const float kxu[3] = {k[1]*u[2]-k[2]*u[1], k[2]*u[0]-k[0]*u[2], k[0]*u[1]-k[1]*u[0]};
    const float kkxu[3] = {k[1]*kxu[2]-k[2]*kxu[1], k[2]*kxu[0]-k[0]*kxu[2], k[0]*kxu[1]-k[1]*kxu[0]};
    for (int i = 0; i < 3; ++i) out[i] = u[i] + kxu[i] + kkxu[i]/(1.0f + cosine_);
  }
  void measure(float dt) {
    float leg[3], u[3];
    toLeg(up_, leg);
    rotateToNeutral(leg, u);
    const float previous = roll_;
    // Leaning toward +S makes world-up gain a -S component in the leg frame.
    roll_ = -std::asin(u[2] > 1 ? 1.0f : (u[2] < -1 ? -1.0f : u[2]));
    pitch_ = std::atan2(-u[1], u[0]);
    if (hasRoll_) {
      const float alpha = dt/(0.025f + dt);
      rollRate_ += alpha*((roll_ - previous)/dt - rollRate_);
    } else { rollRate_ = 0; hasRoll_ = true; }
  }
  void meter(float mag, float rate, uint32_t) {
    // High-pass |a| against a slow average so gravity and slow lean drop out.
    meanMag_ += 0.02f*(mag - meanMag_);
    const float hp = mag - meanMag_;
    vibrationSum_ += hp*hp; gyroSum_ += rate*rate; ++meterCount_;
  }
  void beginSegment(uint32_t t, int sign) {
    side_ = sign; startUs_ = t; peak_ = 0; peakRate_ = 0; pitchAtPeak_ = 0; skippedInSegment_ = false;
  }
  void abandon() { side_ = 0; peak_ = 0; peakRate_ = 0; skippedInSegment_ = false; skipped_ = false; }
  void runSegment(uint32_t t) {
    if (skipped_) { skippedInSegment_ = true; skipped_ = false; }
    const int sign = roll_ >= 0 ? 1 : -1;
    if (sign != lastSign_) { zeroUs_ = t; lastSign_ = sign; }
    if (side_ == 0) {
      if (std::fabs(roll_) > kDeadbandRadians) beginSegment(t, sign);
    } else if (roll_*side_ < -kDeadbandRadians) {
      finish(zeroUs_);
      beginSegment(zeroUs_, sign);
    }
    if (side_ != 0) {
      if (std::fabs(roll_) > peak_) { peak_ = std::fabs(roll_); pitchAtPeak_ = pitch_; }
      const float rate = std::fabs(rollRate_);
      if (rate > peakRate_) peakRate_ = rate;
    }
  }
  void finish(uint32_t endUs) {
    if (peak_ < kBandRadians) return;  // wobble, not a half-turn
    const uint32_t duration = endUs - startUs_;
    if (duration < kMinimumUs) { ++rejected_; return; }
    SkiEvent e;
    e.sequence = ++count_;
    e.startUs = startUs_; e.endUs = endUs;
    e.positive = side_ > 0;
    e.peakRollDegrees = side_*peak_*kDegrees;
    e.peakRateDps = peakRate_*kDegrees;
    e.pitchDegrees = pitchAtPeak_*kDegrees;
    e.pitchOutside = e.pitchDegrees < kEnvelopePitchMin || e.pitchDegrees > kEnvelopePitchMax;
    e.outsideEnvelope = e.pitchOutside || std::fabs(e.peakRollDegrees) > kEnvelopeRollDegrees ||
                        e.peakRateDps > kEnvelopeRateDps || duration < kEnvelopeMinUs ||
                        duration > kEnvelopeMaxUs;
    e.skippedSamples = skippedInSegment_;
    history_[(count_ - 1) % 16] = e;
  }

  Mounting map_;
  bool hasTime_ = false, neutralReady_ = false, zeroing_ = false, stillSince_ = false;
  bool hasRoll_ = false, skipped_ = false, skippedInSegment_ = false, gapSeen_ = false;
  uint32_t lastTime_ = 0, stillSinceUs_ = 0, startUs_ = 0, zeroUs_ = 0;
  uint32_t count_ = 0, rejected_ = 0, gaps_ = 0;
  int side_ = 0, lastSign_ = 1;
  float up_[3]{0, 0, 1}, kx_[3]{}, cosine_ = 1;
  float roll_ = 0, pitch_ = 0, rollRate_ = 0, peak_ = 0, peakRate_ = 0, pitchAtPeak_ = 0;
  float meanMag_ = kGravity, vibrationSum_ = 0, gyroSum_ = 0;
  uint32_t meterCount_ = 0;
  SkiEvent history_[16]{};
};
}  // namespace openski::motion
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `g++ -std=gnu++17 -Wall -Wextra -Ilib/Motion/src tools/test_ski_tracker.cpp -o .pio/host/test_ski_tracker.exe && .pio/host/test_ski_tracker.exe`
Expected: `ski tracker tests passed` and no compiler warnings.

- [ ] **Step 6: Prove the shock case bites**

Run: `sed "s/kImpactG = 2.5f/kImpactG = 50.0f/;s/rate > kMaxRateRadps/false/" lib/Motion/src/SkiTracker.h > .pio/host/SkiTracker.h && cp tools/test_ski_tracker.cpp .pio/host/ && g++ -std=gnu++17 -I.pio/host .pio/host/test_ski_tracker.cpp -o .pio/host/mutant.exe && .pio/host/mutant.exe`
Expected: `FAILED: shock does not move the roll`. (If it prints "passed", the test has stopped checking anything; fix the test.)

- [ ] **Step 7: Commit**

```bash
git add lib/Motion/src/SkiTracker.h tools/test_ski_tracker.cpp
git commit -m "$(cat <<'EOF'
Add SkiTracker half-turn recogniser with host tests

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 2: Frame encoders and command parser

**Files:**
- Create: `lib/Motion/src/SkiFrames.h`
- Create: `tools/test_ski_frames.cpp`

**Interfaces:**
- Consumes: `SkiEvent` from Task 1.
- Produces (used by Tasks 3, 5, and the Kotlin golden bytes in Task 6): `kSkiEventVersion = 2`, `kSkiStateVersion = 3`, `kSkiEventSize = 18`, `kSkiStateSize = 16`; `void encodeSkiEvent(const SkiEvent&, uint8_t* out18)`; `struct SkiStateFrame { bool zeroed, production, gap; uint16_t sequence; uint32_t timeMs; float rollDegrees, pitchDegrees, vibrationMps2, gyroDps; }`; `void encodeSkiState(const SkiStateFrame&, uint8_t* out16)`; `enum class MotionCommandKind { kNone, kZero, kDiagnostics, kProduction, kInvalid }`; `MotionCommandKind parseMotionCommand(const uint8_t* bytes, size_t length)`.

- [ ] **Step 1: Write the failing test**

Create `tools/test_ski_frames.cpp`:

```cpp
#include "SkiFrames.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>

using namespace openski::motion;

namespace {
void check(bool ok, const char* what) {
  if (!ok) { std::printf("FAILED: %s\n", what); std::exit(1); }
}
}  // namespace

int main() {
  {  // The same bytes are decoded by SkiEventProtocolTest.kt.
    SkiEvent e;
    e.sequence = 513; e.startUs = 4294966000U; e.endUs = 4294966000U + 1830000U;  // wraps the 32-bit clock
    e.positive = false; e.skippedSamples = true;
    e.peakRollDegrees = -32.5f; e.peakRateDps = 123.4f; e.pitchDegrees = 18.25f;
    uint8_t out[kSkiEventSize];
    encodeSkiEvent(e, out);
    const uint8_t golden[kSkiEventSize] = {0x02, 0x08, 0x01, 0x02, 0x36, 0x89, 0x41, 0x00, 0x26,
                                           0x07, 0x4e, 0xf3, 0xd2, 0x04, 0x21, 0x07, 0x00, 0x00};
    check(std::memcmp(out, golden, kSkiEventSize) == 0, "event frame matches the golden bytes");
  }
  {  // Saturation: long duration, huge rate and out-of-range angles clamp instead of wrapping.
    SkiEvent e;
    e.sequence = 70000; e.startUs = 0; e.endUs = 90000000U;
    e.positive = true; e.outsideEnvelope = true; e.pitchOutside = true;
    e.peakRollDegrees = 500; e.peakRateDps = 9000; e.pitchDegrees = -500;
    uint8_t out[kSkiEventSize];
    encodeSkiEvent(e, out);
    check(out[1] == 0x07, "flags");
    check(out[2] == (70000 & 0xff) && out[3] == ((70000 >> 8) & 0xff), "sequence wraps modulo 65536");
    check(out[8] == 0xff && out[9] == 0xff, "duration saturates");
    check(out[10] == 0xff && out[11] == 0x7f, "roll saturates at +327.67");
    check(out[12] == 0xff && out[13] == 0xff, "rate saturates");
    check(out[14] == 0x00 && out[15] == 0x80, "pitch saturates at -327.68");
  }
  {  // The same bytes are decoded by SkiEventProtocolTest.kt.
    SkiStateFrame s;
    s.zeroed = true; s.production = true; s.sequence = 258; s.timeMs = 3000000;
    s.rollDegrees = 12.34f; s.pitchDegrees = -3.21f; s.vibrationMps2 = 1.5f; s.gyroDps = 45.6f;
    uint8_t out[kSkiStateSize];
    encodeSkiState(s, out);
    const uint8_t golden[kSkiStateSize] = {0x03, 0x03, 0x02, 0x01, 0xc0, 0xc6, 0x2d, 0x00,
                                           0xd2, 0x04, 0xbf, 0xfe, 0x96, 0x00, 0xc8, 0x01};
    check(std::memcmp(out, golden, kSkiStateSize) == 0, "state frame matches the golden bytes");
  }
  {  // Negative vibration or gyro (never expected) clamps to zero rather than wrapping.
    SkiStateFrame s;
    s.vibrationMps2 = -1; s.gyroDps = -1;
    uint8_t out[kSkiStateSize];
    encodeSkiState(s, out);
    check(out[1] == 0 && out[12] == 0 && out[13] == 0 && out[14] == 0 && out[15] == 0, "no wrap on negatives");
  }
  {  // Zero and mode commands: exact lengths and arguments only; other opcodes belong to the recorder.
    const uint8_t zero[] = {0x07}, zeroLong[] = {0x07, 0x00};
    const uint8_t diagnostics[] = {0x08, 0x00}, production[] = {0x08, 0x01};
    const uint8_t badMode[] = {0x08, 0x02}, shortMode[] = {0x08}, longMode[] = {0x08, 0x01, 0x00};
    const uint8_t start[] = {0x01}, download[] = {0x05, 0, 0, 0, 0}, unknown[] = {0x09};
    check(parseMotionCommand(zero, 1) == MotionCommandKind::kZero, "zero");
    check(parseMotionCommand(zeroLong, 2) == MotionCommandKind::kInvalid, "zero with a payload");
    check(parseMotionCommand(diagnostics, 2) == MotionCommandKind::kDiagnostics, "diagnostics");
    check(parseMotionCommand(production, 2) == MotionCommandKind::kProduction, "production");
    check(parseMotionCommand(badMode, 2) == MotionCommandKind::kInvalid, "mode 2");
    check(parseMotionCommand(shortMode, 1) == MotionCommandKind::kInvalid, "mode without argument");
    check(parseMotionCommand(longMode, 3) == MotionCommandKind::kInvalid, "mode with extra bytes");
    check(parseMotionCommand(start, 1) == MotionCommandKind::kNone, "recorder start is not ours");
    check(parseMotionCommand(download, 5) == MotionCommandKind::kNone, "recorder download is not ours");
    check(parseMotionCommand(unknown, 1) == MotionCommandKind::kNone, "unknown opcode is left to the recorder");
    check(parseMotionCommand(zero, 0) == MotionCommandKind::kNone, "empty write");
  }
  std::puts("ski frame tests passed");
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `g++ -std=gnu++17 -Wall -Wextra -Ilib/Motion/src tools/test_ski_frames.cpp -o .pio/host/test_ski_frames.exe`
Expected: FAIL with `SkiFrames.h: No such file or directory`.

- [ ] **Step 3: Write the implementation**

Create `lib/Motion/src/SkiFrames.h`:

```cpp
#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>
#include "SkiTracker.h"

// Wire encoders for the ski_v0 frames on the movement characteristic (docs/ble-protocol.md).
// Little-endian. Kept free of Arduino and NimBLE so the layout is host-testable.
namespace openski::motion {
constexpr uint8_t kSkiEventVersion = 2;
constexpr uint8_t kSkiStateVersion = 3;
constexpr size_t kSkiEventSize = 18;
constexpr size_t kSkiStateSize = 16;

namespace frame {
inline void put16(uint8_t* out, uint16_t value) {
  out[0] = static_cast<uint8_t>(value & 0xff);
  out[1] = static_cast<uint8_t>(value >> 8);
}
inline void put32(uint8_t* out, uint32_t value) {
  put16(out, static_cast<uint16_t>(value & 0xffff));
  put16(out + 2, static_cast<uint16_t>(value >> 16));
}
inline uint16_t unsigned16(float value) {
  const long scaled = std::lround(value);
  return scaled < 0 ? 0 : (scaled > 65535 ? 65535 : static_cast<uint16_t>(scaled));
}
inline int16_t signed16(float value) {
  const long scaled = std::lround(value);
  return static_cast<int16_t>(scaled > 32767 ? 32767 : (scaled < -32768 ? -32768 : scaled));
}
}  // namespace frame

// 18 bytes, one per completed half-turn.
inline void encodeSkiEvent(const SkiEvent& e, uint8_t* out) {
  for (size_t i = 0; i < kSkiEventSize; ++i) out[i] = 0;
  const uint32_t durationMs = (e.endUs - e.startUs)/1000U;
  out[0] = kSkiEventVersion;
  out[1] = (e.positive ? 0x01 : 0) | (e.outsideEnvelope ? 0x02 : 0) | (e.pitchOutside ? 0x04 : 0) |
           (e.skippedSamples ? 0x08 : 0);
  frame::put16(out + 2, static_cast<uint16_t>(e.sequence & 0xffff));
  frame::put32(out + 4, e.startUs/1000U);
  frame::put16(out + 8, durationMs > 65535U ? 65535 : static_cast<uint16_t>(durationMs));
  frame::put16(out + 10, static_cast<uint16_t>(frame::signed16(e.peakRollDegrees*100.0f)));
  frame::put16(out + 12, frame::unsigned16(e.peakRateDps*10.0f));
  frame::put16(out + 14, static_cast<uint16_t>(frame::signed16(e.pitchDegrees*100.0f)));
}

struct SkiStateFrame {
  bool zeroed = false, production = false, gap = false;
  uint16_t sequence = 0;
  uint32_t timeMs = 0;
  float rollDegrees = 0, pitchDegrees = 0, vibrationMps2 = 0, gyroDps = 0;
};

// 16 bytes, one per second.
inline void encodeSkiState(const SkiStateFrame& s, uint8_t* out) {
  out[0] = kSkiStateVersion;
  out[1] = (s.zeroed ? 0x01 : 0) | (s.production ? 0x02 : 0) | (s.gap ? 0x04 : 0);
  frame::put16(out + 2, s.sequence);
  frame::put32(out + 4, s.timeMs);
  frame::put16(out + 8, static_cast<uint16_t>(frame::signed16(s.rollDegrees*100.0f)));
  frame::put16(out + 10, static_cast<uint16_t>(frame::signed16(s.pitchDegrees*100.0f)));
  frame::put16(out + 12, frame::unsigned16(s.vibrationMps2*100.0f));
  frame::put16(out + 14, frame::unsigned16(s.gyroDps*10.0f));
}

// Recorder-control opcodes handled outside the recorder (they work without flash storage).
enum class MotionCommandKind : uint8_t { kNone, kZero, kDiagnostics, kProduction, kInvalid };
inline MotionCommandKind parseMotionCommand(const uint8_t* bytes, size_t length) {
  if (length == 0) return MotionCommandKind::kNone;
  if (bytes[0] == 0x07) return length == 1 ? MotionCommandKind::kZero : MotionCommandKind::kInvalid;
  if (bytes[0] == 0x08) {
    if (length != 2) return MotionCommandKind::kInvalid;
    if (bytes[1] == 0) return MotionCommandKind::kDiagnostics;
    if (bytes[1] == 1) return MotionCommandKind::kProduction;
    return MotionCommandKind::kInvalid;
  }
  return MotionCommandKind::kNone;
}
}  // namespace openski::motion
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `g++ -std=gnu++17 -Wall -Wextra -Ilib/Motion/src tools/test_ski_frames.cpp -o .pio/host/test_ski_frames.exe && .pio/host/test_ski_frames.exe`
Expected: `ski frame tests passed`.

- [ ] **Step 5: Commit**

```bash
git add lib/Motion/src/SkiFrames.h tools/test_ski_frames.cpp
git commit -m "$(cat <<'EOF'
Add ski_v0 frame encoders and motion command parser

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 3: Run the tracker on the board and expose it over Wi-Fi

**Files:**
- Modify: `include/AppConfig.h` (append mounting constants before the closing namespace brace)
- Modify: `include/MotionService.h`
- Modify: `src/MotionService.cpp`
- Modify: `src/TelemetryService.cpp` (`handleMotion`, `handleZero`, new `skiJson`)

**Interfaces:**
- Consumes: `SkiTracker`, `SkiEvent`, `SkiHeartbeat`, `Mounting`, `AxisMap` (Task 1).
- Produces (used by Task 5): in `openski::motion`: `struct SkiStatus { bool zeroed, zeroing; float rollDegrees, pitchDegrees; uint32_t count, rejected, gaps; }`, `SkiStatus skiStatus()`, `uint8_t recentSkiEvents(SkiEvent*, uint8_t)`, `SkiHeartbeat takeSkiHeartbeat()`, `bool takeSkiGapSeen()`, `void zeroMotion()` (zeroes both `TiltTracker` and `SkiTracker`). In `openski::config`: `kMountUpAxis`, `kMountUpSign`, `kMountForwardAxis`, `kMountForwardSign`, `kMountLateralAxis`, `kMountLateralSign`.

- [ ] **Step 1: Add the mounting constants**

In `include/AppConfig.h`, insert before the final `}  // namespace openski::config`:

```cpp
// Which sensor axis points up the leg, forward and sideways when the board sits in its cuff clip,
// and the sign that makes it point that way. Assumed for the first mounting: board length (y) up
// the leg, holes edge (x) forward, header side (z) as the lateral axis. Confirm against the real
// mounting and override per board with build flags; the right boot normally needs
// -DOPENSKI_MOUNT_LATERAL_SIGN=-1 so roll has the same meaning on both legs.
#ifndef OPENSKI_MOUNT_UP_AXIS
#define OPENSKI_MOUNT_UP_AXIS 1
#endif
#ifndef OPENSKI_MOUNT_UP_SIGN
#define OPENSKI_MOUNT_UP_SIGN 1
#endif
#ifndef OPENSKI_MOUNT_FORWARD_AXIS
#define OPENSKI_MOUNT_FORWARD_AXIS 0
#endif
#ifndef OPENSKI_MOUNT_FORWARD_SIGN
#define OPENSKI_MOUNT_FORWARD_SIGN 1
#endif
#ifndef OPENSKI_MOUNT_LATERAL_AXIS
#define OPENSKI_MOUNT_LATERAL_AXIS 2
#endif
#ifndef OPENSKI_MOUNT_LATERAL_SIGN
#define OPENSKI_MOUNT_LATERAL_SIGN 1
#endif
inline constexpr uint8_t kMountUpAxis = OPENSKI_MOUNT_UP_AXIS;
inline constexpr int8_t kMountUpSign = OPENSKI_MOUNT_UP_SIGN;
inline constexpr uint8_t kMountForwardAxis = OPENSKI_MOUNT_FORWARD_AXIS;
inline constexpr int8_t kMountForwardSign = OPENSKI_MOUNT_FORWARD_SIGN;
inline constexpr uint8_t kMountLateralAxis = OPENSKI_MOUNT_LATERAL_AXIS;
inline constexpr int8_t kMountLateralSign = OPENSKI_MOUNT_LATERAL_SIGN;
```

- [ ] **Step 2: Extend `include/MotionService.h`**

Add `#include "SkiFrames.h"` after `#include "TiltTracker.h"`. Then insert before `void tick();`:

```cpp
struct SkiStatus {
  bool zeroed = false, zeroing = false;
  float rollDegrees = 0, pitchDegrees = 0;
  uint32_t count = 0, rejected = 0, gaps = 0;
};
SkiStatus skiStatus();
uint8_t recentSkiEvents(SkiEvent* output, uint8_t capacity);
SkiHeartbeat takeSkiHeartbeat();
bool takeSkiGapSeen();
// Recapture the neutral pose of both the tilt and ski recognisers from the next still second.
void zeroMotion();
```

- [ ] **Step 3: Extend `src/MotionService.cpp`**

Add `#include "AppConfig.h"` after the `SensorCalibration.h` include. In the anonymous namespace, after `TiltTracker tilt;`, add:

```cpp
SkiTracker ski{Mounting{{config::kMountUpAxis, config::kMountUpSign},
                        {config::kMountForwardAxis, config::kMountForwardSign},
                        {config::kMountLateralAxis, config::kMountLateralSign}}};
```

In `tick()`, after the `tilt.update(...)` line, add:

```cpp
  ski.update(sample.timestampUs, a.x, a.y, a.z, g.x, g.y, g.z);
```

After `void zeroTilt() { tilt.zero(); }` add:

```cpp
SkiStatus skiStatus() {
  SkiStatus snapshot;
  snapshot.zeroed = ski.zeroed();
  snapshot.zeroing = ski.zeroing();
  snapshot.rollDegrees = ski.rollDegrees();
  snapshot.pitchDegrees = ski.pitchDegrees();
  snapshot.count = ski.count();
  snapshot.rejected = ski.rejected();
  snapshot.gaps = ski.gaps();
  return snapshot;
}
uint8_t recentSkiEvents(SkiEvent* output, uint8_t capacity) { return ski.recent(output, capacity); }
SkiHeartbeat takeSkiHeartbeat() { return ski.takeHeartbeat(); }
bool takeSkiGapSeen() { return ski.takeGapSeen(); }
void zeroMotion() { tilt.zero(); ski.zero(); }
```

- [ ] **Step 4: Expose the tracker in `src/TelemetryService.cpp`**

Add this function after `tiltJson()`:

```cpp
String skiJson() {
  const auto ski = motion::skiStatus();
  char head[320];
  snprintf(head, sizeof(head),
           "{\"algorithm\":\"ski_v0\",\"frame\":\"leg\",\"zeroed\":%s,\"zeroing\":%s,"
           "\"roll_degrees\":%.2f,\"pitch_degrees\":%.2f,\"half_turn_count\":%lu,"
           "\"rejected\":%lu,\"sample_gaps\":%lu,\"recent\":[",
           ski.zeroed ? "true" : "false", ski.zeroing ? "true" : "false", ski.rollDegrees,
           ski.pitchDegrees, static_cast<unsigned long>(ski.count),
           static_cast<unsigned long>(ski.rejected), static_cast<unsigned long>(ski.gaps));
  String json(head);
  motion::SkiEvent events[16];
  const uint8_t count = motion::recentSkiEvents(events, 16);
  for (uint8_t i = 0; i < count; ++i) {
    const auto& e = events[i];
    char body[288];
    snprintf(body, sizeof(body),
             "%s{\"sequence\":%lu,\"start_us\":%lu,\"end_us\":%lu,\"duration_ms\":%lu,"
             "\"peak_roll_degrees\":%.2f,\"peak_rate_dps\":%.1f,\"pitch_degrees\":%.2f,"
             "\"outside_envelope\":%s}",
             i ? "," : "", static_cast<unsigned long>(e.sequence), static_cast<unsigned long>(e.startUs),
             static_cast<unsigned long>(e.endUs), static_cast<unsigned long>((e.endUs - e.startUs)/1000),
             e.peakRollDegrees, e.peakRateDps, e.pitchDegrees, e.outsideEnvelope ? "true" : "false");
    json += body;
  }
  json += "]}";
  return json;
}
```

In `handleMotion()`, replace

```cpp
  response += "],\"tilt\":";
  response += tiltJson();
  response += "}";
```

with

```cpp
  response += "],\"tilt\":";
  response += tiltJson();
  response += ",\"ski\":";
  response += skiJson();
  response += "}";
```

In `handleZero()`, replace `motion::zeroTilt();` with `motion::zeroMotion();`.

- [ ] **Step 5: Build both environments**

Run: `.venv/Scripts/pio.exe run -e esp32-devkit-v1` then `.venv/Scripts/pio.exe run -e esp32-s3-supermini`
Expected: both end with `SUCCESS`. Fix any compile error in the code you just added.

- [ ] **Step 6: Run the existing host tests and the new ones**

Run: `.venv/Scripts/pio.exe test -e native` then rerun the two g++ test commands from Tasks 1 and 2, and `g++ -std=gnu++17 -Ilib/Motion/src tools/test_tilt_tracker.cpp -o .pio/host/test_tilt.exe && .pio/host/test_tilt.exe`
Expected: all pass (`tilt tracker tests passed` for the old one).

- [ ] **Step 7: Commit**

```bash
git add include/AppConfig.h include/MotionService.h src/MotionService.cpp src/TelemetryService.cpp
git commit -m "$(cat <<'EOF'
Run SkiTracker on the board and expose it over Wi-Fi

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 4: Diagnostics and production modes (Wi-Fi side)

**Files:**
- Create: `include/ModeService.h`
- Create: `src/ModeService.cpp`
- Modify: `include/WifiService.h`
- Modify: `src/WifiService.cpp`
- Modify: `src/OtaService.cpp`
- Modify: `src/TelemetryService.cpp` (`tick`)

**Interfaces:**
- Produces (used by Task 5): `openski::mode::Mode { kDiagnostics = 0, kProduction = 1 }`, `Mode current()`, `bool production()`, `void set(Mode)`; `openski::wifi::setEnabled(bool)`.
- Behaviour: `set(kProduction)` turns Wi-Fi off; `set(kDiagnostics)` rejoins. OTA and the HTTP server stop while Wi-Fi is down and restart when it returns.

- [ ] **Step 1: `include/WifiService.h`**

Replace the file with:

```cpp
#pragma once

namespace openski::wifi {
void begin();
void tick();
bool connected();
// Production mode turns the radio off to save battery; diagnostics turns it back on.
void setEnabled(bool enabled);
}  // namespace openski::wifi
```

- [ ] **Step 2: `src/WifiService.cpp`**

Replace the file with:

```cpp
#include "WifiService.h"

#include <Arduino.h>
#include <WiFi.h>

#include "AppConfig.h"

namespace openski::wifi {
namespace {
constexpr unsigned long kRetryIntervalMs = 10000;
unsigned long lastAttemptMs = 0;
bool wasConnected = false;
bool radioEnabled = true;
}  // namespace

void begin() {
  if (config::kWifiSsid[0] == '\0') return;
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(config::kHostname);
  Serial.printf("Connecting to Wi-Fi SSID: %s\n", config::kWifiSsid);
  WiFi.begin(config::kWifiSsid, config::kWifiPassword);
  lastAttemptMs = millis();
}

bool connected() { return radioEnabled && WiFi.status() == WL_CONNECTED; }

void setEnabled(bool enabled) {
  if (enabled == radioEnabled) return;
  radioEnabled = enabled;
  wasConnected = false;
  if (!enabled) {
    WiFi.disconnect(true, false);
    WiFi.mode(WIFI_OFF);
    Serial.println("Wi-Fi off (production mode)");
  } else {
    begin();
  }
}

void tick() {
  if (!radioEnabled || config::kWifiSsid[0] == '\0') return;

  const bool isConnected = connected();
  if (isConnected != wasConnected) {
    wasConnected = isConnected;
    if (isConnected) {
      Serial.printf("Wi-Fi connected. IP: %s, RSSI: %d dBm\n",
                    WiFi.localIP().toString().c_str(), WiFi.RSSI());
    } else {
      Serial.println("Wi-Fi disconnected");
    }
  }

  const unsigned long now = millis();
  if (!isConnected && now - lastAttemptMs >= kRetryIntervalMs) {
    Serial.println("Retrying Wi-Fi connection");
    WiFi.reconnect();
    lastAttemptMs = now;
  }
}
}  // namespace openski::wifi
```

- [ ] **Step 3: `src/OtaService.cpp`**

Replace the `tick()` function with:

```cpp
void tick() {
  if (config::kOtaPassword[0] == '\0') return;
  if (!wifi::connected()) {
    // Wi-Fi went away (production mode or a drop); restart OTA cleanly when it returns.
    if (started) {
      ArduinoOTA.end();
      started = false;
    }
    return;
  }

  if (!started) {
    ArduinoOTA.setHostname(config::kHostname);
    ArduinoOTA.setPassword(config::kOtaPassword);
    ArduinoOTA.onStart([]() { Serial.println("OTA update started"); });
    ArduinoOTA.onEnd([]() { Serial.println("OTA update finished"); });
    ArduinoOTA.onError([](ota_error_t error) {
      Serial.printf("OTA error: %u\n", static_cast<unsigned>(error));
    });
    ArduinoOTA.begin();
    started = true;
    Serial.printf("OTA ready: %s:3232\n", config::kHostname);
  }

  ArduinoOTA.handle();
}
```

- [ ] **Step 4: `src/TelemetryService.cpp`**

Replace the start of `tick()`:

```cpp
void tick() {
  if (!wifi::connected()) return;
```

with

```cpp
void tick() {
  if (!wifi::connected()) {
    // Close the listener so it is rebuilt cleanly when Wi-Fi returns.
    if (serverStarted) {
      server.stop();
      serverStarted = false;
    }
    return;
  }
```

- [ ] **Step 5: `include/ModeService.h`**

```cpp
#pragma once
#include <stdint.h>

namespace openski::mode {
// Diagnostics (default every boot): Wi-Fi, HTTP, OTA and the raw 50 Hz stream are on.
// Production: Wi-Fi and the raw stream are off; half-turn events and the state frame still flow.
enum class Mode : uint8_t { kDiagnostics = 0, kProduction = 1 };
Mode current();
inline bool production() { return current() == Mode::kProduction; }
void set(Mode mode);
}  // namespace openski::mode
```

- [ ] **Step 6: `src/ModeService.cpp`**

```cpp
#include "ModeService.h"

#include <Arduino.h>

#include "WifiService.h"

namespace openski::mode {
namespace {
Mode active = Mode::kDiagnostics;
}  // namespace

Mode current() { return active; }

void set(Mode mode) {
  if (mode == active) return;
  active = mode;
  wifi::setEnabled(mode == Mode::kDiagnostics);
  Serial.printf("Mode: %s\n", mode == Mode::kProduction ? "production" : "diagnostics");
}
}  // namespace openski::mode
```

(`set` is only called from the main loop, via the BLE control queue, so no locking is needed.)

- [ ] **Step 7: Build both environments**

Run: `.venv/Scripts/pio.exe run -e esp32-devkit-v1` and `.venv/Scripts/pio.exe run -e esp32-s3-supermini`
Expected: both `SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add include/ModeService.h src/ModeService.cpp include/WifiService.h src/WifiService.cpp src/OtaService.cpp src/TelemetryService.cpp
git commit -m "$(cat <<'EOF'
Add diagnostics and production modes controlling Wi-Fi

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 5: Send ski frames and handle the new commands over BLE

**Files:**
- Modify: `src/BluetoothService.cpp`

**Interfaces:**
- Consumes: `motion::skiStatus`, `recentSkiEvents`, `takeSkiHeartbeat`, `takeSkiGapSeen`, `zeroMotion` (Task 3); `motion::encodeSkiEvent`, `encodeSkiState`, `SkiStateFrame`, `parseMotionCommand`, `MotionCommandKind`, `kSkiEventSize`, `kSkiStateSize` (Task 2); `mode::set`, `mode::production` (Task 4).
- Produces (the wire contract the app implements in Task 6): version 2 and 3 frames on `…0006`, opcodes `07` and `08 mode` on `…0004`, response flag bit 6.

- [ ] **Step 1: Includes**

After `#include "MotionService.h"` add:

```cpp
#include "ModeService.h"
```

- [ ] **Step 2: State variables**

After `uint32_t lastMovementSequence = 0;` add:

```cpp
uint32_t lastSkiSequence = 0;
unsigned long lastHeartbeatMs = 0;
uint16_t heartbeatSequence = 0;
```

- [ ] **Step 3: Production bit in every recorder response**

In `notifyRecorderResponse`, change the flags expression so it ends:

```cpp
             (state.storageError ? 0x10 : 0x00) |
             (downloadActive ? 0x20 : 0x00) |
             (mode::production() ? 0x40 : 0x00);
```

- [ ] **Step 4: Raw stream off in production**

At the very top of `notifyLatestSample()` add:

```cpp
  if (mode::production()) return;  // production: events and the state frame only
```

- [ ] **Step 5: Make `notifyMovementEvents` report whether it sent a frame**

Replace the whole function (from its comment to the closing brace) with:

```cpp
// One notification per completed tilt excursion (see docs/ble-protocol.md). Events that
// finish while no client is connected are not replayed. Returns true if a frame was sent.
bool notifyMovementEvents() {
  if (movementCharacteristic == nullptr) return false;
  const motion::TiltStatus tilt = motion::tiltStatus();
  if (!clientConnected || tilt.count < lastMovementSequence) {
    lastMovementSequence = tilt.count;
    return false;
  }
  if (tilt.count == lastMovementSequence) return false;
  motion::Excursion events[16];
  const uint8_t count = motion::recentExcursions(events, 16);
  for (uint8_t i = 0; i < count; ++i) {
    if (events[i].sequence <= lastMovementSequence) continue;
    const motion::Excursion& e = events[i];
    const uint32_t durationMs = (e.endUs - e.startUs) / 1000U;
    uint8_t frame[kMovementEventSize]{};
    frame[0] = kMovementEventVersion;
    frame[1] = e.axis;
    putUint16(frame + 2, static_cast<uint16_t>(e.sequence));
    putUint32(frame + 4, e.startUs / 1000U);
    putUint16(frame + 8, durationMs > 65535U ? 65535U : static_cast<uint16_t>(durationMs));
    putSigned16(frame + 10, quantize(e.peakDegrees, 100.0f));
    putSigned16(frame + 12, quantize(e.aboutDegrees, 100.0f));
    frame[14] = static_cast<uint8_t>(roundf(e.axisFraction * 100.0f));
    movementCharacteristic->setValue(frame, sizeof(frame));
    movementCharacteristic->notify();
    lastMovementSequence = e.sequence;
    return true;  // one per tick; the next follows on the next loop
  }
  lastMovementSequence = tilt.count;  // the missed events fell out of the history
  return false;
}

// One notification per completed ski_v0 half-turn (version 2 frame). Events that finish while
// no client is connected are not replayed. Returns true if a frame was sent.
bool notifySkiEvents() {
  if (movementCharacteristic == nullptr) return false;
  const motion::SkiStatus ski = motion::skiStatus();
  if (!clientConnected || ski.count < lastSkiSequence) {
    lastSkiSequence = ski.count;
    return false;
  }
  if (ski.count == lastSkiSequence) return false;
  motion::SkiEvent events[16];
  const uint8_t count = motion::recentSkiEvents(events, 16);
  for (uint8_t i = 0; i < count; ++i) {
    if (events[i].sequence <= lastSkiSequence) continue;
    uint8_t frame[motion::kSkiEventSize];
    motion::encodeSkiEvent(events[i], frame);
    movementCharacteristic->setValue(frame, sizeof(frame));
    movementCharacteristic->notify();
    lastSkiSequence = events[i].sequence;
    return true;
  }
  lastSkiSequence = ski.count;  // the missed events fell out of the history
  return false;
}

// One state frame per second in both modes (version 3 frame). The accumulators reset every
// second even with no client, so the first frame after a connect covers only the last second.
bool notifyHeartbeat() {
  const unsigned long now = millis();
  if (now - lastHeartbeatMs < 1000UL) return false;
  lastHeartbeatMs = now;
  const motion::SkiHeartbeat beat = motion::takeSkiHeartbeat();
  const bool gap = motion::takeSkiGapSeen();
  if (!clientConnected || movementCharacteristic == nullptr) return false;
  const motion::SkiStatus ski = motion::skiStatus();
  motion::SkiStateFrame state;
  state.zeroed = ski.zeroed;
  state.production = mode::production();
  state.gap = gap;
  state.sequence = heartbeatSequence++;
  state.timeMs = static_cast<uint32_t>(esp_timer_get_time()) / 1000U;  // same clock as the live frame
  state.rollDegrees = ski.rollDegrees;
  state.pitchDegrees = ski.pitchDegrees;
  state.vibrationMps2 = beat.vibrationMps2;
  state.gyroDps = beat.gyroDps;
  uint8_t frame[motion::kSkiStateSize];
  motion::encodeSkiState(state, frame);
  movementCharacteristic->setValue(frame, sizeof(frame));
  movementCharacteristic->notify();
  return true;
}
```

- [ ] **Step 6: Handle zero and mode before the storage check**

In `processRecorderCommand`, replace

```cpp
  const uint8_t opcode = request.bytes[0];
  const recorder::Status state = recorder::status();
```

with

```cpp
  const uint8_t opcode = request.bytes[0];
  // Zero and mode work without flash storage, so they are handled before the storage check.
  switch (motion::parseMotionCommand(request.bytes, request.length)) {
    case motion::MotionCommandKind::kZero:
      motion::zeroMotion();
      notifyRecorderResponse(opcode, RecorderResult::kOk);
      return;
    case motion::MotionCommandKind::kDiagnostics:
      mode::set(mode::Mode::kDiagnostics);
      notifyRecorderResponse(opcode, RecorderResult::kOk);
      return;
    case motion::MotionCommandKind::kProduction:
      mode::set(mode::Mode::kProduction);
      notifyRecorderResponse(opcode, RecorderResult::kOk);
      return;
    case motion::MotionCommandKind::kInvalid:
      notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      return;
    case motion::MotionCommandKind::kNone:
      break;
  }
  const recorder::Status state = recorder::status();
```

- [ ] **Step 7: Send the frames from `tick()`**

Replace `notifyMovementEvents();` in `tick()` with:

```cpp
  // One frame on the movement characteristic per loop: half-turns first, then tilt events,
  // then the once-a-second state frame.
  if (!notifySkiEvents() && !notifyMovementEvents()) notifyHeartbeat();
```

- [ ] **Step 8: Build both environments**

Run: `.venv/Scripts/pio.exe run -e esp32-devkit-v1` and `.venv/Scripts/pio.exe run -e esp32-s3-supermini`
Expected: both `SUCCESS`, no new warnings from `BluetoothService.cpp`.

- [ ] **Step 9: Commit**

```bash
git add src/BluetoothService.cpp
git commit -m "$(cat <<'EOF'
Send ski_v0 half-turn and state frames and handle zero and mode commands

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 6: Android decoders, version dispatch and commands

**Files:**
- Create: `android/app/src/main/java/com/openski/android/SkiEventProtocol.kt`
- Create: `android/app/src/test/java/com/openski/android/SkiEventProtocolTest.kt`
- Modify: `android/app/src/main/java/com/openski/android/MovementEventProtocol.kt` (one line)
- Modify: `android/app/src/main/java/com/openski/android/BleSensorClient.kt`
- Modify: `android/app/src/main/java/com/openski/android/RecorderProtocol.kt`
- Modify: `android/app/src/test/java/com/openski/android/RecorderProtocolTest.kt`

**Interfaces:**
- Consumes: the wire contract from Task 5 and the golden bytes verified in Task 2.
- Produces (used by Task 7): `sealed interface MotionFrame`; `MotionFrames.decode(ByteArray): MotionFrame?`; `data class SkiEvent(sequence: Int, startMs: Long, durationMs: Int, peakRollDegrees: Float, peakRateDps: Float, pitchDegrees: Float, positive: Boolean, outsideEnvelope: Boolean, pitchOutside: Boolean, skippedSamples: Boolean)`; `data class SkiState(sequence: Int, timeMs: Long, rollDegrees: Float, pitchDegrees: Float, vibrationMps2: Float, gyroDps: Float, zeroed: Boolean, production: Boolean, gapInLastSecond: Boolean)`; `RecorderCommand.ZERO = 7`, `RecorderCommand.SET_MODE = 8` (mode passed as the `offset` argument of `bytes`/`command`); `RecorderStatus.production`; `BleSensorClient` constructor params `onSkiEvent`, `onSkiState`.

Run Gradle from `android/` (see CLAUDE.md).

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/com/openski/android/SkiEventProtocolTest.kt`:

```kotlin
package com.openski.android

import org.junit.Assert.*
import org.junit.Test

/** The golden frames are the bytes asserted by tools/test_ski_frames.cpp on the firmware side. */
class SkiEventProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun mutate(source: ByteArray, index: Int, value: Int) = source.copyOf().also { it[index] = value.toByte() }

    private val eventGolden = bytes(0x02, 0x08, 0x01, 0x02, 0x36, 0x89, 0x41, 0x00, 0x26,
        0x07, 0x4e, 0xf3, 0xd2, 0x04, 0x21, 0x07, 0x00, 0x00)
    private val stateGolden = bytes(0x03, 0x03, 0x02, 0x01, 0xc0, 0xc6, 0x2d, 0x00,
        0xd2, 0x04, 0xbf, 0xfe, 0x96, 0x00, 0xc8, 0x01)
    private val tiltGolden = bytes(1, 1, 1, 2, 0, 0, 0, 0, 0x26, 0x07, 0x8a, 0x11, 0x76, 0xee, 98)

    @Test fun decodesTheGoldenEvent() {
        val e = SkiEvent.decode(eventGolden)!!
        assertEquals(513, e.sequence)
        assertEquals(4294966L, e.startMs)
        assertEquals(1830, e.durationMs)
        assertEquals(-32.5f, e.peakRollDegrees, 0.001f)
        assertEquals(123.4f, e.peakRateDps, 0.001f)
        assertEquals(18.25f, e.pitchDegrees, 0.001f)
        assertFalse(e.positive)
        assertFalse(e.outsideEnvelope)
        assertFalse(e.pitchOutside)
        assertTrue(e.skippedSamples)
    }

    @Test fun decodesTheGoldenState() {
        val s = SkiState.decode(stateGolden)!!
        assertEquals(258, s.sequence)
        assertEquals(3_000_000L, s.timeMs)
        assertEquals(12.34f, s.rollDegrees, 0.001f)
        assertEquals(-3.21f, s.pitchDegrees, 0.001f)
        assertEquals(1.5f, s.vibrationMps2, 0.001f)
        assertEquals(45.6f, s.gyroDps, 0.001f)
        assertTrue(s.zeroed)
        assertTrue(s.production)
        assertFalse(s.gapInLastSecond)
    }

    @Test fun keepsFullRangeUnsignedFields() {
        val e = SkiEvent.decode(mutate(mutate(eventGolden, 8, 0xff).also { it[9] = 0xff.toByte() }, 12, 0xff).also { it[13] = 0xff.toByte() })!!
        assertEquals(65535, e.durationMs)
        assertEquals(6553.5f, e.peakRateDps, 0.01f)
        val wrapped = SkiEvent.decode(mutate(mutate(eventGolden, 2, 0xff), 3, 0xff))!!
        assertEquals(65535, wrapped.sequence)
    }

    @Test fun rejectsMalformedEvents() {
        assertNull(SkiEvent.decode(ByteArray(0)))
        assertNull(SkiEvent.decode(eventGolden.copyOf(17)))
        assertNull(SkiEvent.decode(eventGolden + 0))
        assertNull(SkiEvent.decode(mutate(eventGolden, 0, 1)))
        assertNull(SkiEvent.decode(mutate(eventGolden, 0, 3)))
        assertNull(SkiEvent.decode(mutate(eventGolden, 1, 0x18)))   // unknown flag bit
        assertNull(SkiEvent.decode(mutate(eventGolden, 1, 0x09)))   // side says + but roll is negative
        assertNull(SkiEvent.decode(mutate(eventGolden, 16, 1)))     // reserved must be zero
        assertNull(SkiEvent.decode(mutate(mutate(eventGolden, 10, 0), 11, 0)))  // a half-turn has a non-zero roll
    }

    @Test fun rejectsMalformedStates() {
        assertNull(SkiState.decode(stateGolden.copyOf(15)))
        assertNull(SkiState.decode(stateGolden + 0))
        assertNull(SkiState.decode(mutate(stateGolden, 0, 2)))
        assertNull(SkiState.decode(mutate(stateGolden, 1, 0x08)))   // unknown flag bit
        assertNull(SkiState.decode(mutate(mutate(stateGolden, 8, 0x00), 9, 0x7f)))  // roll beyond 90 degrees
    }

    @Test fun dispatchesOnTheVersionByteAndNothingElse() {
        assertTrue(MotionFrames.decode(tiltGolden) is MovementEvent)
        assertTrue(MotionFrames.decode(eventGolden) is SkiEvent)
        assertTrue(MotionFrames.decode(stateGolden) is SkiState)
        assertNull(MotionFrames.decode(ByteArray(0)))
        assertNull(MotionFrames.decode(mutate(eventGolden, 0, 4)))
        assertNull(MotionFrames.decode(eventGolden.copyOf(15)))     // a truncated v2 is not a v1
        assertNull(MotionFrames.decode(mutate(eventGolden, 0, 1)))  // an 18-byte v1 is not valid
        assertNull(MotionFrames.decode(stateGolden.copyOf(18)))
    }
}
```

Append inside `class RecorderProtocolTest` (before its closing brace) in `RecorderProtocolTest.kt`:

```kotlin

    @Test fun encodesTheMotionCommands() {
        assertArrayEquals(byteArrayOf(7), RecorderCommand.bytes(RecorderCommand.ZERO))
        assertArrayEquals(byteArrayOf(8, 0), RecorderCommand.bytes(RecorderCommand.SET_MODE, 0))
        assertArrayEquals(byteArrayOf(8, 1), RecorderCommand.bytes(RecorderCommand.SET_MODE, 1))
        assertArrayEquals(byteArrayOf(5, 4, 0, 0, 0), RecorderCommand.bytes(RecorderCommand.DOWNLOAD, 4))
        assertArrayEquals(byteArrayOf(3), RecorderCommand.bytes(RecorderCommand.INFO))
    }

    @Test fun productionIsFlagBitSix() {
        assertTrue(RecorderStatus(8, 0, 0x40, 0, 0, 60000).production)
        assertFalse(RecorderStatus(8, 0, 0x3f, 0, 0, 60000).production)
    }
```

- [ ] **Step 2: Run them to confirm they fail**

Run (from `android/`): `.\gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.SkiEventProtocolTest" --tests "com.openski.android.RecorderProtocolTest"`
Expected: FAIL to compile (`Unresolved reference: SkiEvent`, `ZERO`, `production`).

- [ ] **Step 3: Create `SkiEventProtocol.kt`**

```kotlin
package com.openski.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frames on the firmware's movement characteristic share one UUID and differ by their first byte. */
sealed interface MotionFrame

object MotionFrames {
    /** Version 1 is the `tilt_v1` excursion, 2 a `ski_v0` half-turn, 3 the `ski_v0` state frame. */
    fun decode(bytes: ByteArray): MotionFrame? = when (bytes.firstOrNull()?.toInt()) {
        1 -> MovementEvent.decode(bytes)
        2 -> SkiEvent.decode(bytes)
        3 -> SkiState.decode(bytes)
        else -> null
    }
}

/**
 * One completed half-turn from the firmware's experimental `ski_v0` recogniser (docs/ble-protocol.md,
 * frame version 2). Cuff lean in a sensor-derived frame, not a validated ski edge angle.
 * Positive roll means the leg leaned toward the sensor's lateral axis.
 */
data class SkiEvent(val sequence: Int, val startMs: Long, val durationMs: Int,
    val peakRollDegrees: Float, val peakRateDps: Float, val pitchDegrees: Float,
    val positive: Boolean, val outsideEnvelope: Boolean, val pitchOutside: Boolean,
    val skippedSamples: Boolean) : MotionFrame {
    companion object {
        const val SIZE = 18
        const val VERSION = 2

        fun decode(bytes: ByteArray): SkiEvent? {
            if (bytes.size != SIZE || bytes[0].toInt() != VERSION) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val flags = b.get().toInt() and 255
            val sequence = b.short.toInt() and 0xffff
            val start = b.int.toLong() and 0xffff_ffffL
            val duration = b.short.toInt() and 0xffff
            val roll = b.short / 100f
            val rate = (b.short.toInt() and 0xffff) / 10f
            val pitch = b.short / 100f
            val reserved = b.short.toInt()
            val positive = flags and 1 != 0
            if (flags and 0xf0 != 0 || reserved != 0 || roll == 0f || positive != (roll > 0f)) return null
            return SkiEvent(sequence, start, duration, roll, rate, pitch, positive,
                flags and 2 != 0, flags and 4 != 0, flags and 8 != 0)
        }
    }
}

/** The once-a-second `ski_v0` state frame (frame version 3), sent in both firmware modes. */
data class SkiState(val sequence: Int, val timeMs: Long, val rollDegrees: Float, val pitchDegrees: Float,
    val vibrationMps2: Float, val gyroDps: Float, val zeroed: Boolean, val production: Boolean,
    val gapInLastSecond: Boolean) : MotionFrame {
    companion object {
        const val SIZE = 16
        const val VERSION = 3

        fun decode(bytes: ByteArray): SkiState? {
            if (bytes.size != SIZE || bytes[0].toInt() != VERSION) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val flags = b.get().toInt() and 255
            val sequence = b.short.toInt() and 0xffff
            val time = b.int.toLong() and 0xffff_ffffL
            val roll = b.short / 100f
            val pitch = b.short / 100f
            val vibration = (b.short.toInt() and 0xffff) / 100f
            val gyro = (b.short.toInt() and 0xffff) / 10f
            if (flags and 0xf8 != 0 || Math.abs(roll) > 90f || Math.abs(pitch) > 180f) return null
            return SkiState(sequence, time, roll, pitch, vibration, gyro,
                flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
        }
    }
}
```

- [ ] **Step 4: Let `MovementEvent` join the sealed interface**

In `MovementEventProtocol.kt`, change the declaration's last line

```kotlin
    val peakTiltDegrees: Float, val aboutAxisDegrees: Float, val axisFraction: Float) {
```

to

```kotlin
    val peakTiltDegrees: Float, val aboutAxisDegrees: Float, val axisFraction: Float) : MotionFrame {
```

- [ ] **Step 5: Commands and the production flag in `RecorderProtocol.kt`**

In `RecorderStatus`, after `val storageError get() = flags and 16 != 0` add:

```kotlin
    val production get() = flags and 64 != 0
```

Replace `object RecorderCommand { ... }` with:

```kotlin
object RecorderCommand {
    const val START = 1
    const val STOP = 2
    const val INFO = 3
    const val ERASE = 4
    const val DOWNLOAD = 5
    const val CANCEL = 6
    const val ZERO = 7
    const val SET_MODE = 8
    /** For DOWNLOAD [offset] is the first record; for SET_MODE it is the mode (0 diagnostics, 1 production). */
    fun bytes(opcode: Int, offset: Int = 0): ByteArray = when (opcode) {
        DOWNLOAD -> ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(opcode.toByte()).putInt(offset).array()
        SET_MODE -> byteArrayOf(opcode.toByte(), offset.toByte())
        else -> byteArrayOf(opcode.toByte())
    }
}
```

- [ ] **Step 6: Dispatch in `BleSensorClient.kt`**

Add two constructor parameters after `onMovement`:

```kotlin
    private val onMovement: (MovementEvent) -> Unit = {},
    private val onSkiEvent: (SkiEvent) -> Unit = {},
    private val onSkiState: (SkiState) -> Unit = {},
```

Replace the line `MOVEMENT_UUID -> MovementEvent.decode(bytes)?.let(onMovement)` with:

```kotlin
            MOVEMENT_UUID -> when (val frame = MotionFrames.decode(bytes)) {
                is MovementEvent -> onMovement(frame)
                is SkiEvent -> onSkiEvent(frame)
                is SkiState -> onSkiState(frame)
                null -> Unit
            }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all unit tests pass, including `SkiEventProtocolTest`, `RecorderProtocolTest` and the existing `MovementEventProtocolTest`.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/openski/android/SkiEventProtocol.kt android/app/src/main/java/com/openski/android/MovementEventProtocol.kt android/app/src/main/java/com/openski/android/BleSensorClient.kt android/app/src/main/java/com/openski/android/RecorderProtocol.kt android/app/src/test/java/com/openski/android/SkiEventProtocolTest.kt android/app/src/test/java/com/openski/android/RecorderProtocolTest.kt
git commit -m "$(cat <<'EOF'
Decode ski_v0 frames in the app and add zero and mode commands

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 7: Service methods and the Test lab card

**Files:**
- Modify: `android/app/src/main/java/com/openski/android/SensorSessionService.kt`
- Modify: `android/app/src/main/java/com/openski/android/MainActivity.kt`

**Interfaces:**
- Consumes: `SkiEvent`, `SkiState`, `RecorderCommand.ZERO`, `RecorderCommand.SET_MODE`, `BleSensorClient` params (Task 6). Sides are the strings `"L"` and `"R"`.
- Produces: `SensorSessionService.Listener.onSkiEvent(side, event)` and `onSkiState(side, state)`; `SensorSessionService.zeroSensor(side): Boolean`, `setSensorMode(side, production): Boolean`, `connectedSides(): List<String>`.

- [ ] **Step 1: Listener callbacks**

In `SensorSessionService.kt`, in `interface Listener`, after `fun onMovementEvent(side: String, event: MovementEvent) {}` add:

```kotlin
        fun onSkiEvent(side: String, event: SkiEvent) {}
        fun onSkiState(side: String, state: SkiState) {}
```

- [ ] **Step 2: Pass the frames through**

Where the client is built, after `onMovement = { event -> listener?.onMovementEvent(side, event) },` add:

```kotlin
            onSkiEvent = { event -> listener?.onSkiEvent(side, event) },
            onSkiState = { state -> listener?.onSkiState(side, state) },
```

- [ ] **Step 3: Public commands**

Immediately before `fun calibrateTest(side: String, axis: Int, sign: Int, completed: (String)->Unit) {` add:

```kotlin
    /** Boots with a live, ready link; only these can take a command. */
    fun connectedSides(): List<String> = clients.filterValues { it.isReady }.keys.sorted()

    /** Ask a boot to recapture its neutral pose from the next still second. Stand upright and still first. */
    fun zeroSensor(side: String): Boolean = clients[side]?.command(RecorderCommand.ZERO) == true

    /** Production turns the boot's Wi-Fi and raw stream off to save battery; diagnostics turns them back on. */
    fun setSensorMode(side: String, production: Boolean): Boolean =
        clients[side]?.command(RecorderCommand.SET_MODE, if (production) 1 else 0) == true
```

- [ ] **Step 4: State in `MainActivity.kt`**

After `private lateinit var testFeedback: TextView` add:

```kotlin
    private lateinit var skiStatus: TextView
    private lateinit var skiCommand: TextView
    private val skiEventText = mutableMapOf<String, String>()
    private val skiStateText = mutableMapOf<String, String>()
```

- [ ] **Step 5: Listener overrides**

In `sensorListener`, after the `onTestFeedback` override add:

```kotlin
        override fun onSkiEvent(side: String, event: SkiEvent) = runOnUiThread {
            skiEventText[side] = "half-turn ${"%+.0f".format(event.peakRollDegrees)}° over " +
                "${"%.1f".format(event.durationMs / 1000f)} s, peak ${"%.0f".format(event.peakRateDps)}°/s" +
                if (event.outsideEnvelope) " (outside expected range)" else ""
            renderSki()
        }
        override fun onSkiState(side: String, state: SkiState) = runOnUiThread {
            skiStateText[side] = "${if (state.production) "production" else "diagnostics"} · " +
                "${if (state.zeroed) "zeroed" else "not zeroed"} · roll ${"%.0f".format(state.rollDegrees)}° " +
                "pitch ${"%.0f".format(state.pitchDegrees)}°"
            renderSki()
        }
```

- [ ] **Step 6: Helpers**

Add these two private functions next to `selectTab`:

```kotlin
    private fun renderSki() {
        val sides = (skiEventText.keys + skiStateText.keys).sorted()
        skiStatus.text = if (sides.isEmpty()) "No boot turn data yet." else sides.joinToString("\n") { side ->
            "$side · ${skiStateText[side] ?: "no state yet"}\n   ${skiEventText[side] ?: "no half-turn yet"}"
        }
    }

    private fun commandBoots(name: String, action: (SensorSessionService, String) -> Boolean) {
        val service = sensorService
        val sides = service?.connectedSides().orEmpty()
        skiCommand.text = if (service == null || sides.isEmpty()) "Connect a boot first."
        else "$name: " + sides.joinToString { side -> "$side ${if (action(service, side)) "sent" else "failed"}" }
    }
```

- [ ] **Step 7: The card**

Directly after the line `lab.addView(testCard, bottomMargin(dp(16)))` add:

```kotlin
        val bootCard = SkiUi.card(this)
        bootCard.addView(label("Boot sensors · experimental", 22f, WHITE, true))
        bootCard.addView(label("Stand upright in your ski stance, then zero. Production mode switches the boots' Wi-Fi and raw stream off to save battery; a boot always starts in diagnostics after a power cycle. Half-turn lean is cuff lean, not ski edge angle.", 14f, SUBTLE).apply {
            setPadding(0, dp(10), 0, dp(16))
        })
        skiCommand = label("Connect a boot to send commands.", 14f, ACCENT)
        bootCard.addView(skiCommand, bottomMargin(dp(12)))
        bootCard.addView(SkiUi.button(this, "Zero boots (stand still)", SkiUi.ButtonStyle.PRIMARY) {
            commandBoots("Zero") { service, side -> service.zeroSensor(side) }
        })
        bootCard.addView(SkiUi.button(this, "Production mode (battery)") {
            commandBoots("Production") { service, side -> service.setSensorMode(side, true) }
        })
        bootCard.addView(SkiUi.button(this, "Diagnostics mode (Wi-Fi + raw)") {
            commandBoots("Diagnostics") { service, side -> service.setSensorMode(side, false) }
        })
        skiStatus = label("No boot turn data yet.", 13f, SUBTLE)
        bootCard.addView(skiStatus, bottomMargin(dp(4)))
        lab.addView(bootCard, bottomMargin(dp(16)))
```

- [ ] **Step 8: Build, test and lint**

Run (from `android/`): `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`
Expected: `BUILD SUCCESSFUL`; no new lint errors. If lint flags `String.format` locale use, pass `java.util.Locale.US` to the `format` calls added here.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/openski/android/SensorSessionService.kt android/app/src/main/java/com/openski/android/MainActivity.kt
git commit -m "$(cat <<'EOF'
Add boot zero and mode controls to the Test lab

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

### Task 8: Docs and the one on-board check

**Files:**
- Modify: `docs/ble-protocol.md`
- Modify: `docs/bench-motion-recognition.md`
- Modify: `docs/bench-results.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: `docs/ble-protocol.md`**

Replace the last sentence of the existing "Movement event notification (experimental)" section (the one ending "...the app cannot yet.") with: "...`POST /api/v1/motion/zero`, or from the app with recorder-control opcode `07` (below)." Then insert before `## Recording and synchronization`:

```markdown
### Frame versions on this characteristic

The first byte selects the frame. Version 1 is the `tilt_v1` event above. Clients must ignore versions they do not know. One frame is sent per firmware loop at most, and the last-sent sequence is tracked per type, so one type never hides another.

### `ski_v0` half-turn (version 2, 18 bytes, experimental)

Sent when a half-turn completes: roll leaves a ±8° band around the zeroed pose and later crosses zero to the other side. The values are cuff lean in a sensor-derived frame, not ski edge angle, and nothing here classifies a turn.

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | version | `2` |
| 1 | 1 | flags | bit 0 side `+` (roll > 0), bit 1 outside the expected envelope, bit 2 pitch outside its envelope range, bit 3 samples were skipped (impact gate) during the half-turn |
| 2 | 2 | sequence | unsigned, `ski_v0` event count modulo 65536 (starts at 1 after boot) |
| 4 | 4 | start time | unsigned milliseconds, the same clock as the live frame |
| 8 | 2 | duration | unsigned milliseconds, saturating at 65535 |
| 10 | 2 | peak roll | signed, value / 100 gives degrees; positive means the leg leaned toward the sensor's lateral axis |
| 12 | 2 | peak roll rate | unsigned, value / 10 gives degrees per second |
| 14 | 2 | pitch at peak | signed, value / 100 gives degrees forward of the zeroed pose |
| 16 | 2 | reserved | `0` |

The envelope flag means peak roll above 60°, rate above 300 °/s, duration outside 0.3 to 4 s, or pitch outside −15° to +45°. It is a plausibility check, not a quality score.

### `ski_v0` state (version 3, 16 bytes, experimental)

Sent once a second while a client is connected, in both modes.

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | version | `3` |
| 1 | 1 | flags | bit 0 zeroed, bit 1 production mode, bit 2 sample gap in the last second |
| 2 | 2 | sequence | unsigned, heartbeat count modulo 65536 |
| 4 | 4 | time | unsigned milliseconds, the same clock as the live frame |
| 8 | 2 | roll now | signed, value / 100 gives degrees |
| 10 | 2 | pitch now | signed, value / 100 gives degrees |
| 12 | 2 | vibration | unsigned, value / 100 gives m/s², RMS of high-passed acceleration over the second |
| 14 | 2 | gyro activity | unsigned, value / 10 gives degrees per second, RMS over the second |

### Modes and motion commands

The boot has two runtime modes. **Diagnostics** is the default after every boot: Wi-Fi, the HTTP API, OTA and the 50 Hz live stream are on. **Production** turns Wi-Fi and the live stream off to save battery; half-turn events and the state frame still flow, and flash recording is unaffected. Mode is never stored: a power cycle returns to diagnostics.

Two commands are written to the recorder control characteristic. They work even when flash storage is unavailable, and the 16-byte response carries the usual flags plus bit 6 (set = production mode).

| Command | Bytes | Meaning |
| --- | --- | --- |
| Zero | `07` | Capture the neutral pose from the next still second (under 0.12 rad/s, within 5% of 1 g, roughly upright on the leg). Stand upright in the ski stance first. |
| Set mode | `08 mode` | `00` diagnostics, `01` production. Any other value or length returns result `6`. |
```

- [ ] **Step 2: `docs/bench-motion-recognition.md`**

Append:

```markdown
## Half-turn recognition (`ski_v0`)

`ski_v0` runs beside `tilt_v1` and is built for a sensor on the boot cuff, with the board's length up the leg. It separates **roll** (lean about the forward axis) from **pitch** (forward flex) relative to a pose captured by `POST /api/v1/motion/zero` or the app's Zero button, so a skier who holds 20° of forward flex all run still produces roll events. A half-turn runs between zero crossings of roll and is reported if its peak reaches 8°; very short ones (under 150 ms) are counted as rejected.

- **Mounting map.** `include/AppConfig.h` says which sensor axis points up the leg, forward and sideways (`OPENSKI_MOUNT_*` build flags). The defaults are assumptions for the first mounting; confirm them and flip the lateral sign on the other boot.
- **Fusion.** The accelerometer pull fades out as |a| leaves 1 g and is zero beyond 15%, so hard turns follow the gyro. Samples above 2.5 g, below 0.3 g or with a gyro reading above 12 rad/s are skipped and the state is held.
- **Expected values** (general ski mechanics, not yet measured here): cuff roll ±10° to ±25° on easy runs and ±30° to ±50° carving; roll rate 60 to 200 °/s at an edge change; forward flex 10° to 30° beyond the zeroed stance; a half-turn lasting 0.5 to 1.5 s. Events outside the envelope are flagged, not dropped.
- **Slope.** A slope does not change the lean of the leg relative to vertical in a fall-line stance, but a traverse adds an offset on one side. Symmetric turns show it as unequal left and right peaks.
- **Verification.** `tools/test_ski_tracker.cpp` and `tools/test_ski_frames.cpp` run synthetic ski-like traces and golden frame bytes on the host. They show the code matches the model, not that the model matches a skier; that needs the Phase 1 and 2 runs in the [POC plan](poc-plan.md).

Build and run on the host: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_tracker.cpp` (and `test_ski_frames.cpp`).
```

- [ ] **Step 3: `CLAUDE.md`**

In the Firmware architecture section, after the paragraph beginning "Motion recognition (`MotionService`)", add:

```markdown
- `ski_v0` (`lib/Motion/src/SkiTracker.h`, frames and command parsing in `SkiFrames.h`) detects half-turns on the boot from roll and pitch against a zeroed pose and sends 18-byte events and a 1 Hz state frame on the movement characteristic. The firmware has two runtime modes (`ModeService`): **diagnostics** (default every boot; Wi-Fi, HTTP, OTA and the raw 50 Hz stream on) and **production** (Wi-Fi and raw stream off, events and state only, for battery life), switched by recorder-control opcode `08 mode`; zero is opcode `07`. Host tests: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_tracker.cpp` (and `test_ski_frames.cpp`).
```

In the Wire protocol paragraph, after "...no screen shows them yet.", replace that clause with: "`BleSensorClient` dispatches frames on that characteristic by version byte (1 `tilt_v1` excursion, 2 `ski_v0` half-turn, 3 `ski_v0` state) to `SensorSessionService.Listener`; the Test lab's \"Boot sensors\" card shows the latest half-turn and state and sends zero and mode commands."

- [ ] **Step 4: Run every automated check**

Run: `.venv/Scripts/pio.exe test -e native`; the three g++ test commands (`test_ski_tracker`, `test_ski_frames`, `test_tilt_tracker`, plus `test_gesture_tracker`); `.venv/Scripts/pio.exe run -e esp32-devkit-v1`; `.venv/Scripts/pio.exe run -e esp32-s3-supermini`; and from `android/`: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`.
Expected: everything passes or builds. Do not continue to Step 5 otherwise.

- [ ] **Step 5: The one on-board check (about ten minutes, DevKit on the bench)**

This is the only hands-on step; the recogniser itself is covered by the host tests. It checks what only hardware can show: that Wi-Fi off then on leaves OTA and the HTTP server working, and that frames reach the app.

1. Flash over the air: `PLATFORMIO_UPLOAD_FLAGS=--auth=<OTA password from wifi_config.h> .venv/Scripts/pio.exe run -e esp32-devkit-v1-ota -t upload --upload-port ski.local` (redact the password in any output).
2. Open `http://ski.local/api/v1/motion`: it must now contain a `ski` object with `"zeroed":false`. Install the debug app (`.\gradlew.bat :app:installDebug`), connect the boot in Geek mode, open Test lab.
3. Lay the board still and press **Zero boots**. Within about 2 s `ski.zeroed` must be `true` over Wi-Fi and the card must read "zeroed".
4. Press **Production mode**. The card must switch to "production" within about 2 s, and `http://ski.local/api/v1/status` must stop answering within about 15 s. The state line keeps updating (the boot is still sending frames).
5. Press **Diagnostics mode**. `ski.local` must answer again within about 30 s, and a repeat of the OTA upload from step 1 must succeed.
6. Note the results (pass or fail, timings) in `docs/bench-results.md` as a dated entry.

If step 4 or 5 fails (HTTP or OTA dead after the round trip), stop and diagnose with the systematic-debugging skill; do not paper over it.

- [ ] **Step 6: Commit**

```bash
git add docs/ble-protocol.md docs/bench-motion-recognition.md docs/bench-results.md CLAUDE.md
git commit -m "$(cat <<'EOF'
Document ski_v0 frames, modes and commands

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01E5sxn6MpGz8DhyY2PXdR6Z
EOF
)"
```

---

## Self-review notes

- Spec coverage: SkiTracker behaviour (Task 1); frame layouts, saturation, command parsing (Task 2); mounting map, Wi-Fi `ski` object, zero (Task 3); modes and Wi-Fi/OTA/HTTP lifecycle (Task 4); frames, heartbeat, opcodes, response bit 6, stream gating (Task 5); app decoders, dispatch, opcodes, listener, service methods, Test lab card (Tasks 6 and 7); docs and hardware check (Task 8). The spec's open items (current-draw measurement, connection-interval tuning, second heartbeat rate) are deliberately not tasks; they need the Phase 0 baseline first.
- One deliberate deviation from the first spec draft, recorded in the spec: the state frame is sent in both modes, and flag bit 3 on events means "samples were skipped" rather than "sample gap" (a gap abandons the event, so a gap flag would never be set).
- Rate is derived from the fused roll with a 25 ms smoother rather than read from the gyro, which keeps the sign right in any mounting.
