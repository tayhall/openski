# Research Findings Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fold the skivids research findings into OpenSki's docs, add a causal heading-rate turn signal (firmware and Android), rest-driven gyro bias, and per-sample signal-quality flags carried through recorder protocol v3.

**Architecture:** One portable header per idea in `lib/Motion/src` (`HeadingTracker.h`, `RestBias.h`), host-tested with the existing standalone `tools/test_*.cpp` style and wired in through `SkiTracker`/`MotionService`. Wire changes are additive where possible (movement frame version 4) and breaking only for the recorder/live quality flags (live frame v2, control version 3, data marker `D1`). Android mirrors each detector in Kotlin and checks parity against a fixture emitted by the C++ tool.

**Tech Stack:** C++17 (PlatformIO/Arduino, host `g++`), Kotlin (native Android, JUnit 4 JVM tests, custom instrumentation runner), SQLite.

**Spec:** `docs/superpowers/specs/2026-10-11-research-findings-integration-design.md`

## Global Constraints

- Ski turn classification is not implemented or validated. Heading turns, half-turns and boot roll stay "experimental"/"candidates" in UI text, docs and exports.
- Raw BLE, flash, HTTP and exported samples stay uncorrected; corrections apply only to recogniser inputs.
- `docs/ble-protocol.md` is the wire contract: update it, `RecorderProtocolTest` and both code paths in the same change.
- The firmware loop delay stays 2 ms and no `tick()` may block (100 Hz sampling must not be missed).
- Unknown movement-characteristic frame versions are ignored by clients; frame version 4 is additive.
- `ski_v0` half-turn detection, the coach and the run flow are unchanged; heading turns are not fed to them.
- Database schema changes need a migration and a `StorageInstrumentation` check; schema goes 8 -> 9.
- Streaming and replay orientation code must stay equivalent (shared `BootOrientation.Tracker`).
- Per owner preference: no new manual bench steps; verification is host tests, JVM tests and emulator. Hardware checks go in `docs/android-acceptance.md` as owed items.
- Commit trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Work on branch `feat/research-findings`.

## Review Focus

- **Standing, walking or bench handling must not emit heading turns** (absolute 15 deg/s floor): Task 2 test.
- **A sample gap or impact-gate skip inside a lobe must not produce a turn spanning the gap**: Tasks 2 and 3 tests.
- **A bias that is really a slow steady rotation must not be absorbed without bound**: Task 9 tests (rate and absolute bounds).
- **Old stored sessions must replay with unchanged timestamps and no phantom quality flags**: Task 13 tests (record format 2 is never masked).
- **A transfer mixing formats or an unknown version must fail closed, not be erased**: Task 13 tests.

---

### Task 0: Prerequisite gate (no code)

The working tree holds an uncommitted firmware acquisition-profile change that Tasks 5, 10 and 12 build on (`kAccelNearRail` etc.).

- [ ] **Step 1: Ask the owner** whether to commit these files as their own commit on `feat/research-findings`, or whether they will: `lib/Imu/src/Imu.h`, `lib/Imu/src/ImuMonitor.{h,cpp}`, `lib/ImuMpu6050/src/Mpu6050Sensor.cpp`, `lib/ImuMpu6050/src/Mpu6050Profile.h`, `platformio.ini`, `src/TelemetryService.cpp`, `test/test_imu_monitor/test_main.cpp`, `docs/imu-data.md`, `docs/bench-results.md`.
- [ ] **Step 2: Run the existing host tests to confirm the baseline**

```bash
export PATH="$LOCALAPPDATA/Microsoft/WinGet/Packages/BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe/mingw64/bin:$PATH"
g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_tracker.cpp -o /tmp/ski_tracker && /tmp/ski_tracker
g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_frames.cpp -o /tmp/ski_frames && /tmp/ski_frames
.venv/Scripts/pio.exe test -e native
```

Expected: all pass. If the WinLibs directory name differs, locate it with `ls "$LOCALAPPDATA/Microsoft/WinGet/Packages" | grep WinLibs`.

- [ ] **Step 3:** Do not stage the owner's files in any later commit unless they approved Step 1. When a task edits one of them (`src/TelemetryService.cpp`, `docs/imu-data.md`), stage only your hunks with `git add -p`.

---

### Task 1: Docs fold-in

**Files:**
- Create: `docs/research-findings.md`
- Modify: `docs/ski-analysis.md`, `docs/poc-plan.md`, `docs/technique-research-integration.md`, `docs/feature-roadmap.md`

**Interfaces:** none (documentation). Later tasks add to `docs/ble-protocol.md`, `docs/imu-data.md`, `docs/android-acceptance.md`.

- [ ] **Step 1: Write `docs/research-findings.md`** with these sections, in this order, using the evidence labels CONFIRMED / REPORTED / INFERRED:
  1. *Status*: snapshot 2026-10-11, all assumptions, no boot data, author is an intermediate skier; source is `D:\Projects\skivids` (no remote; footage, data, outputs and the Python reference are not in this repo).
  2. *Findings and where they land*: copy the table from the spec's "What the research says" section verbatim.
  3. *Turn signal*: heading rate is `omega . up`; benchmark numbers (F1 0.71 to 0.74 held out at +-0.5 s; roll-like 0.12 to 0.41 skidded, 0.32 to 0.37 beginner/intermediate); the transfer caveat (calf phone, 10 Hz, 10 skiers, 38 held-out runs, not a boot IMU); timing good to about half a second in that setup.
  4. *Estimator*: 3D attitude rationale; VQF 0.4 deg median on BROAD against 1.6 deg for a complementary filter; our `SkiTracker` and `BootOrientation` are 3D complementary filters with gated accelerometer pull; VQF port deferred until boot data exists.
  5. *Sensor path after the POC* (not a POC change): ICM-45686 primary (+-32 g), LSM6DSV320X and SCH16T-K10 alternatives, nRF52840 class MCU for current, XIAO nRF52840 Sense as the zero-solder pilot, no magnetometer. Mark every vendor figure REPORTED.
  6. *Not established*: the five bullets from the spec's "Not established" list plus cold behaviour of chips and batteries (Li-ion must not be charged below 0 C).
  7. *What this repo implemented from it*: link to the spec and plan; one line per sub-project.
- [ ] **Step 2: Edit `docs/ski-analysis.md`**: in "Current experimental timing implementation" add a paragraph "Heading-rate candidates (experimental, firmware and replay)" stating that heading-rate lobes are a second candidate set alongside roll-lobe timing, with the transfer caveat, and that heading change per lobe (degrees) is reported. Replace the sentence beginning "Next: validate fixtures" with a plan to compare roll-lobe against heading-rate candidates on labelled video.
- [ ] **Step 3: Edit `docs/poc-plan.md`**: append a "Research-driven bench tests (owed)" list: Allan variance 1 to 3 h static; temperature sweep using the IMU temperature output; protractor tilt at 0/15/30/45 deg and a 25 deg off-axis mount; 90 deg gyro scale; clipping and g-sensitivity; cold soak. Reference `docs/research-findings.md`.
- [ ] **Step 4: Edit `docs/technique-research-integration.md`**: add one paragraph under the title pointing to `research-findings.md` (the imported snapshot is untouched). Edit `docs/feature-roadmap.md` "Turn detection" row: add "heading-rate candidates (experimental)".
- [ ] **Step 5: Commit**

```bash
git add docs/research-findings.md docs/ski-analysis.md docs/poc-plan.md docs/technique-research-integration.md docs/feature-roadmap.md
git commit -m "Fold research findings into the docs

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: HeadingTracker (portable detector)

**Files:**
- Create: `lib/Motion/src/HeadingTracker.h`
- Create: `tools/test_heading_tracker.cpp`

**Interfaces:**
- Produces (namespace `openski::motion`):
  - `struct HeadingTurn { uint32_t sequence=0, startUs=0, endUs=0; float peakRateDps=0; float changeDegrees=0; bool positive=false, skippedSamples=false, nearRail=false; }`
  - `class HeadingTracker` with:
    - `enum : uint8_t { kSkipped = 1, kNearRail = 2 };`
    - `void update(uint32_t timeUs, float rateRadps, uint8_t flags = 0)`
    - `void skip()` (an impact-gated sample: marks the open segment, does not touch filters)
    - `void breakSegment()` (sample gap: abandons the open segment and reseeds filters)
    - `uint32_t count() const`, `uint32_t rejected() const`
    - `uint8_t recent(HeadingTurn* out, uint8_t capacity) const` (last up to 16, oldest first, same convention as `SkiTracker::recent`)
    - `bool takeTurn(HeadingTurn& out)` is NOT provided; consumers read `recent()` and track `sequence`.

Algorithm (shared by C++ and Kotlin, Task 7): smooth with a one-pole filter (tau 0.25 s) -> subtract a one-pole baseline (tau 4 s) to get `x` -> running SD from an EMA of `x*x` (tau 20 s) -> threshold = `max(0.6 * sd, 15 deg/s)`. A *segment* is the run between zero crossings of `x`. When the sign of `x` flips, the closing segment is emitted if its peak magnitude is at least the threshold *at that moment* and it lasted at least 0.4 s; its start and end are shifted back by the smoothing tau to compensate the filter delay. `changeDegrees` is the integral of `x` over the segment. A step over 100 ms is a gap: abandon the segment, reseed the smoother and baseline from the new sample, keep the SD.

- [ ] **Step 1: Write the failing test** `tools/test_heading_tracker.cpp`

```cpp
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_heading_tracker.cpp -o /tmp/heading`
Expected: compile error, `HeadingTracker.h: No such file or directory`.

- [ ] **Step 3: Implement `lib/Motion/src/HeadingTracker.h`**

```cpp
#pragma once
#include <cmath>
#include <cstdint>

namespace openski::motion {
struct HeadingTurn {
  uint32_t sequence = 0, startUs = 0, endUs = 0;
  float peakRateDps = 0;     // signed, peak of the de-trended heading rate
  float changeDegrees = 0;   // signed integral of the de-trended rate over the lobe
  bool positive = false;     // rotation about world-up is right-handed positive
  bool skippedSamples = false, nearRail = false;
};

// Causal turn detector on heading rate (rotation about the world vertical, deg/s). It does not know
// how the vertical was obtained, so the same logic runs on the boot and in the Android replay.
// Experimental: defaults are guesses taken from a calf-phone benchmark at 10 Hz (docs/research-findings.md).
class HeadingTracker {
 public:
  enum : uint8_t { kSkipped = 1, kNearRail = 2 };
  static constexpr float kDegrees = 57.2957795f;

  void update(uint32_t timeUs, float rateRadps, uint8_t flags = 0) {
    const float rate = rateRadps*kDegrees;
    if (hasTime_ && timeUs - lastUs_ > kGapUs) breakSegment();
    if (!hasTime_ || !seeded_) {
      smooth_ = rate; base_ = rate; seeded_ = true;
      segmentStartUs_ = timeUs; area_ = 0; peak_ = 0; sign_ = 0;
      skipped_ = nearRail_ = false;
    }
    const float dt = hasTime_ ? (timeUs - lastUs_)/1000000.0f : 0.0f;
    hasTime_ = true; lastUs_ = timeUs;
    if (dt > 0) {
      smooth_ += dt/(kSmoothTauS + dt)*(rate - smooth_);
      base_ += dt/(kBaselineTauS + dt)*(smooth_ - base_);
    }
    const float x = smooth_ - base_;
    if (dt > 0) variance_ += dt/(kSdTauS + dt)*(x*x - variance_);
    if (flags & kNearRail) nearRail_ = true;
    if (flags & kSkipped) skipped_ = true;
    const int s = x >= 0 ? 1 : -1;
    if (sign_ == 0) sign_ = s;
    if (s != sign_) {
      close(timeUs);
      sign_ = s; segmentStartUs_ = timeUs; area_ = 0; peak_ = 0;
      skipped_ = nearRail_ = false;
    }
    area_ += x*dt;
    if (std::fabs(x) > std::fabs(peak_)) peak_ = x;
  }

  // An impact-gated sample: its rate is not trusted, so filters are untouched.
  void skip() { skipped_ = true; }

  // Sample loss: the open segment cannot be trusted and the filters restart from the next sample.
  void breakSegment() { seeded_ = false; sign_ = 0; }

  uint32_t count() const { return count_; }
  uint32_t rejected() const { return rejected_; }
  uint8_t recent(HeadingTurn* output, uint8_t capacity) const {
    const uint8_t available = count_ < 16 ? static_cast<uint8_t>(count_) : 16;
    const uint8_t copied = available < capacity ? available : capacity;
    for (uint8_t i = 0; i < copied; ++i) output[i] = history_[(count_ - copied + i) % 16];
    return copied;
  }

 private:
  static constexpr float kSmoothTauS = 0.25f, kBaselineTauS = 4.0f, kSdTauS = 20.0f;
  static constexpr float kAmplitudeSd = 0.6f, kFloorDps = 15.0f, kMinDurationS = 0.4f;
  static constexpr uint32_t kGapUs = 100000U;

  void close(uint32_t timeUs) {
    const float sd = std::sqrt(variance_ > 0 ? variance_ : 0.0f);
    float threshold = kAmplitudeSd*sd;
    if (threshold < kFloorDps) threshold = kFloorDps;
    if (std::fabs(peak_) < threshold) return;  // wobble, not a turn
    const uint32_t delay = static_cast<uint32_t>(kSmoothTauS*1000000.0f);
    const uint32_t duration = timeUs - segmentStartUs_;
    if (duration < static_cast<uint32_t>(kMinDurationS*1000000.0f)) { ++rejected_; return; }
    HeadingTurn turn;
    turn.sequence = ++count_;
    turn.startUs = segmentStartUs_ - delay;
    turn.endUs = timeUs - delay;
    turn.positive = sign_ > 0;
    turn.peakRateDps = peak_;
    turn.changeDegrees = area_;
    turn.skippedSamples = skipped_;
    turn.nearRail = nearRail_;
    history_[(count_ - 1) % 16] = turn;
  }

  bool hasTime_ = false, seeded_ = false, skipped_ = false, nearRail_ = false;
  uint32_t lastUs_ = 0, segmentStartUs_ = 0, count_ = 0, rejected_ = 0;
  int sign_ = 0;
  float smooth_ = 0, base_ = 0, variance_ = 0, area_ = 0, peak_ = 0;
  HeadingTurn history_[16]{};
};
}  // namespace openski::motion
```

Notes for the implementer: the standing-still test needs the floor; the short-lobe test needs the 0.4 s rule; the gap test needs `breakSegment()` to leave `sign_ == 0` so no segment closes across the hole. If the "alternating lobes" count test misses by one at the edges, widen the asserted range rather than the algorithm; do not tune the constants to the synthetic data.

- [ ] **Step 4: Run to verify it passes**

Run: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_heading_tracker.cpp -o /tmp/heading && /tmp/heading`
Expected: `heading tracker tests passed`.

- [ ] **Step 5: Commit**

```bash
git add lib/Motion/src/HeadingTracker.h tools/test_heading_tracker.cpp
git commit -m "Add causal heading-rate turn tracker

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Feed heading rate from SkiTracker

**Files:**
- Modify: `lib/Motion/src/SkiTracker.h`
- Modify: `tools/test_ski_tracker.cpp`

**Interfaces:**
- Consumes: `HeadingTracker` (Task 2).
- Produces on `SkiTracker`: `update(uint32_t t, float ax, float ay, float az, float gx, float gy, float gz, uint8_t quality = 0)` (new optional last parameter: `imu::SampleQuality` bits, 1 = accel near rail, 2 = gyro near rail); `uint32_t headingCount() const`; `uint32_t headingRejected() const`; `uint8_t recentHeading(HeadingTurn* out, uint8_t capacity) const`. Heading works before `zero()`.

- [ ] **Step 1: Write the failing test.** In `tools/test_ski_tracker.cpp` the helpers build samples from a roll/pitch `Profile`. Add a block in `main()` that drives a plain yaw motion: the sensor rotating about world-up alternately, with gravity constant. Gravity stays on one sensor axis, so use mounting `{{0,1},{1,1},{2,-1}}` (x is up): gyro x is the heading rate.

```cpp
  {  // Heading turns: alternating rotation about the vertical is found without zeroing the pose.
    SkiTracker t(Mounting{{0, 1}, {1, 1}, {2, -1}});
    uint32_t us = 0;
    for (int i = 0; i < 2000; ++i, us += 10000U) {
      const float rate = 1.0f*std::sin(2*kPi*i/400.0f);  // 57 deg/s peak, 4 s period
      t.update(us, kG, 0, 0, rate, 0, 0);
    }
    check(!t.zeroed(), "heading does not need the neutral pose");
    check(t.headingCount() >= 3, "heading turns found from vertical rotation");
    HeadingTurn turns[16];
    const uint8_t n = t.recentHeading(turns, 16);
    check(n >= 3 && turns[1].positive != turns[0].positive, "heading turn signs alternate");
  }
  {  // An impact-gated sample is marked on the open segment and does not corrupt the rate.
    SkiTracker t(Mounting{{0, 1}, {1, 1}, {2, -1}});
    uint32_t us = 0;
    for (int i = 0; i < 1200; ++i, us += 10000U) {
      const float scale = (i == 300) ? 4.0f : 1.0f;  // 4 g spike trips the impact gate
      t.update(us, kG*scale, 0, 0, 1.0f*std::sin(2*kPi*i/400.0f), 0, 0);
    }
    HeadingTurn turns[16];
    const uint8_t n = t.recentHeading(turns, 16);
    bool marked = false;
    for (uint8_t i = 0; i < n; ++i) marked |= turns[i].skippedSamples;
    check(marked, "impact skip is recorded on the heading turn");
  }
```

- [ ] **Step 2: Run to verify it fails**

Run: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_tracker.cpp -o /tmp/ski_tracker`
Expected: compile errors, `headingCount` / `recentHeading` not members.

- [ ] **Step 3: Implement.** In `SkiTracker.h`: add `#include "HeadingTracker.h"`; add the `quality` parameter to `update`; in the impact/free-fall/rate gate branch add `heading_.skip();` before `return`; in the gap branch (`delta > kGapUs`) add `heading_.breakSegment();` before `abandon();`; after `normalise(next); for (...) up_[i] = next[i];` and **before** the `if (!neutralReady_)` line, add:

```cpp
    uint8_t headingFlags = 0;
    if (quality & 0x03) headingFlags |= HeadingTracker::kNearRail;
    heading_.update(t, gx*up_[0] + gy*up_[1] + gz*up_[2], headingFlags);
```

Add public accessors `headingCount()`, `headingRejected()`, `recentHeading(...)` delegating to `heading_`, and a `HeadingTracker heading_;` member. Document in a comment that positive means right-handed rotation about world-up.

- [ ] **Step 4: Run all host tests**

```bash
g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_tracker.cpp -o /tmp/ski_tracker && /tmp/ski_tracker
g++ -std=gnu++17 -Ilib/Motion/src tools/test_heading_tracker.cpp -o /tmp/heading && /tmp/heading
```
Expected: both pass; existing `ski_v0` assertions unchanged.

- [ ] **Step 5: Commit** (`git add lib/Motion/src/SkiTracker.h tools/test_ski_tracker.cpp`; message "Feed heading rate from the ski tracker's gravity vector").

---

### Task 4: Frame version 4 encoder and protocol doc

**Files:**
- Modify: `lib/Motion/src/SkiFrames.h`
- Modify: `tools/test_ski_frames.cpp`
- Modify: `docs/ble-protocol.md`

**Interfaces:**
- Consumes: `HeadingTurn` (Task 2).
- Produces: `constexpr uint8_t kHeadingTurnVersion = 4; constexpr size_t kHeadingTurnSize = 16; void encodeHeadingTurn(const HeadingTurn&, uint8_t* out);`

Frame (little-endian, 16 bytes): `[0]` version 4; `[1]` flags: bit0 positive, bit1 samples skipped, bit2 near-rail, bit3 reserved 0 (a gap abandons the segment, so no gap bit exists; spec refinement); `[2..3]` sequence mod 65536; `[4..7]` start ms; `[8..9]` duration ms saturating; `[10..11]` peak rate `value/10` deg/s signed; `[12..13]` heading change `value/100` deg signed; `[14..15]` reserved 0.

- [ ] **Step 1: Write the failing test** (add to `test_ski_frames.cpp`; the golden bytes are also asserted by Kotlin in Task 6):

```cpp
  {  // The same bytes are decoded by HeadingTurnProtocolTest.kt.
    HeadingTurn h;
    h.sequence = 513; h.startUs = 4294966000U; h.endUs = 4294966000U + 1830000U;
    h.positive = false; h.skippedSamples = true; h.nearRail = false;
    h.peakRateDps = -61.5f; h.changeDegrees = -78.25f;
    uint8_t out[kHeadingTurnSize];
    encodeHeadingTurn(h, out);
    const uint8_t golden[kHeadingTurnSize] = {0x04, 0x02, 0x01, 0x02, 0x36, 0x89, 0x41, 0x00,
                                              0x26, 0x07, 0x99, 0xfd, 0x6f, 0xe1, 0x00, 0x00};
    check(std::memcmp(out, golden, kHeadingTurnSize) == 0, "heading frame matches the golden bytes");
  }
  {  // Saturation clamps instead of wrapping.
    HeadingTurn h;
    h.sequence = 70000; h.startUs = 0; h.endUs = 90000000U; h.positive = true; h.nearRail = true;
    h.peakRateDps = 9000; h.changeDegrees = 9000;
    uint8_t out[kHeadingTurnSize];
    encodeHeadingTurn(h, out);
    check(out[1] == 0x05, "flags positive + near rail");
    check(out[8] == 0xff && out[9] == 0xff, "duration saturates");
    check(out[10] == 0xff && out[11] == 0x7f, "peak saturates");
    check(out[12] == 0xff && out[13] == 0x7f, "change saturates");
  }
```
Golden derivation: flags `0x02` (positive false, skipped true); sequence 513 = `01 02`; start 4294966000 us = 4294966 ms = 0x418936 -> `36 89 41 00`; duration 1830 = `26 07`; -61.5 deg/s x 10 = -615 = 0xFD99 -> `99 fd`; -78.25 deg x 100 = -7825 = 0xE16F -> `6f e1`; reserved `00 00`.

- [ ] **Step 2: Run to verify it fails:** compile error, `encodeHeadingTurn` undefined.
- [ ] **Step 3: Implement** in `SkiFrames.h` (add `#include "HeadingTracker.h"`):

```cpp
constexpr uint8_t kHeadingTurnVersion = 4;
constexpr size_t kHeadingTurnSize = 16;

// 16 bytes, one per completed heading-rate lobe (experimental).
inline void encodeHeadingTurn(const HeadingTurn& h, uint8_t* out) {
  for (size_t i = 0; i < kHeadingTurnSize; ++i) out[i] = 0;
  const uint32_t durationMs = (h.endUs - h.startUs)/1000U;
  out[0] = kHeadingTurnVersion;
  out[1] = (h.positive ? 0x01 : 0) | (h.skippedSamples ? 0x02 : 0) | (h.nearRail ? 0x04 : 0);
  frame::put16(out + 2, static_cast<uint16_t>(h.sequence & 0xffff));
  frame::put32(out + 4, h.startUs/1000U);
  frame::put16(out + 8, durationMs > 65535U ? 65535 : static_cast<uint16_t>(durationMs));
  frame::put16(out + 10, static_cast<uint16_t>(frame::signed16(h.peakRateDps*10.0f)));
  frame::put16(out + 12, static_cast<uint16_t>(frame::signed16(h.changeDegrees*100.0f)));
}
```

- [ ] **Step 4: Run** `g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_frames.cpp -o /tmp/ski_frames && /tmp/ski_frames` — expected pass.
- [ ] **Step 5: Document** in `docs/ble-protocol.md`: extend "Frame versions on this characteristic" to list version 4; add a "`ski_v0` heading turn (version 4, 16 bytes, experimental)" section with the table above, a sentence that positive means right-handed rotation about world-up (counter-clockwise seen from above, a left turn), that start and end are shifted back by the 0.25 s smoothing delay, and that it is not a turn classification.
- [ ] **Step 6: Commit** ("Add the heading-turn frame (version 4)").

---

### Task 5: Firmware wiring

**Files:**
- Modify: `include/MotionService.h`, `src/MotionService.cpp`, `src/BluetoothService.cpp`, `src/TelemetryService.cpp` (stage only your hunks; the file carries owner changes)

**Interfaces:**
- Consumes: `SkiTracker::headingCount/recentHeading` (Task 3), `encodeHeadingTurn` (Task 4).
- Produces: `uint32_t motion::headingCount()`, `uint8_t motion::recentHeadingTurns(HeadingTurn*, uint8_t)`; `SkiStatus` gains `uint32_t headingCount = 0, headingRejected = 0;`. HTTP `/api/v1/motion` ski object gains `heading_count`, `heading_rejected`, and a `heading_turns` array.

- [ ] **Step 1:** In `MotionService.cpp` pass the quality flags and expose the new accessors:

```cpp
  ski.update(sample.timestampUs, a.x, a.y, a.z, g.x, g.y, g.z, sample.qualityFlags);
```
and, near `recentSkiEvents`:
```cpp
uint32_t headingCount() { return ski.headingCount(); }
uint8_t recentHeadingTurns(HeadingTurn* output, uint8_t capacity) { return ski.recentHeading(output, capacity); }
```
Set `snapshot.headingCount = ski.headingCount(); snapshot.headingRejected = ski.headingRejected();` in `skiStatus()`. Declare all of it in `MotionService.h`.
- [ ] **Step 2:** In `BluetoothService.cpp` add `uint32_t lastHeadingSequence = 0;` next to `lastSkiSequence`, and a function modelled exactly on `notifySkiEvents`:

```cpp
// One notification per completed heading-rate lobe (version 4 frame), same replay rule as half-turns.
bool notifyHeadingTurns() {
  if (movementCharacteristic == nullptr) return false;
  const uint32_t count = motion::headingCount();
  if (!clientConnected || count < lastHeadingSequence) { lastHeadingSequence = count; return false; }
  if (count == lastHeadingSequence) return false;
  motion::HeadingTurn turns[16];
  const uint8_t n = motion::recentHeadingTurns(turns, 16);
  for (uint8_t i = 0; i < n; ++i) {
    if (turns[i].sequence <= lastHeadingSequence) continue;
    uint8_t frame[motion::kHeadingTurnSize];
    motion::encodeHeadingTurn(turns[i], frame);
    movementCharacteristic->setValue(frame, sizeof(frame));
    movementCharacteristic->notify();
    lastHeadingSequence = turns[i].sequence;
    return true;
  }
  lastHeadingSequence = count;  // the missed turns fell out of the history
  return false;
}
```
and change the loop line to `if (!notifySkiEvents() && !notifyHeadingTurns() && !notifyMovementEvents()) notifyHeartbeat();` (keep the existing comment, add "then heading turns").
- [ ] **Step 3:** In `TelemetryService.cpp`, in the ski object writer (around line 61, mirroring the half-turn array at line 71), add `heading_count`, `heading_rejected` and a `heading_turns` array of `{sequence,start_ms,end_ms,positive,peak_rate_dps,change_deg,skipped,near_rail}`. Follow the surrounding `snprintf` style and buffer-size checks exactly.
- [ ] **Step 4: Build both targets**

```bash
.venv/Scripts/pio.exe run -e esp32-devkit-v1
.venv/Scripts/pio.exe run -e esp32-s3
.venv/Scripts/pio.exe test -e native
```
Expected: SUCCESS for all.
- [ ] **Step 5: Commit** the wiring (`git add -p src/TelemetryService.cpp` for your hunks). Message "Send heading turns on the movement characteristic".

---

### Task 6: Android decodes frame version 4

**Files:**
- Modify: `android/app/src/main/java/com/openski/android/SkiEventProtocol.kt`, `BleSensorClient.kt`, `SensorSessionService.kt`, `MainActivity.kt` (Test lab "Boot sensors" card)
- Create: `android/app/src/test/java/com/openski/android/HeadingTurnProtocolTest.kt`

**Interfaces:**
- Produces: `data class HeadingTurnFrame(val sequence: Int, val startMs: Long, val durationMs: Int, val peakRateDps: Float, val changeDegrees: Float, val positive: Boolean, val skippedSamples: Boolean, val nearRail: Boolean) : MotionFrame` with `companion { const val SIZE = 16; const val VERSION = 4; fun decode(bytes: ByteArray): HeadingTurnFrame? }`; `MotionFrames.decode` maps `4 -> HeadingTurnFrame.decode`; `BleSensorClient` constructor gains `onHeadingTurn: (HeadingTurnFrame) -> Unit = {}`; `SensorSessionService.Listener.onHeadingTurn(side: String, turn: HeadingTurnFrame) {}`.

- [ ] **Step 1: Write the failing test** `HeadingTurnProtocolTest.kt`:

```kotlin
package com.openski.android

import org.junit.Assert.*
import org.junit.Test

/** The golden frame is the bytes asserted by tools/test_ski_frames.cpp on the firmware side. */
class HeadingTurnProtocolTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val golden = bytes(0x04, 0x02, 0x01, 0x02, 0x36, 0x89, 0x41, 0x00,
        0x26, 0x07, 0x99, 0xfd, 0x6f, 0xe1, 0x00, 0x00)

    @Test fun decodesTheGoldenFrame() {
        val t = HeadingTurnFrame.decode(golden)!!
        assertEquals(513, t.sequence)
        assertEquals(4294966L, t.startMs)
        assertEquals(1830, t.durationMs)
        assertEquals(-61.5f, t.peakRateDps, 0.001f)
        assertEquals(-78.25f, t.changeDegrees, 0.001f)
        assertFalse(t.positive)
        assertTrue(t.skippedSamples)
        assertFalse(t.nearRail)
    }
    @Test fun dispatchesByVersionAndIgnoresUnknownOnes() {
        assertTrue(MotionFrames.decode(golden) is HeadingTurnFrame)
        assertNull(MotionFrames.decode(golden.copyOf().also { it[0] = 9 }))
    }
    @Test fun rejectsMalformedFrames() {
        assertNull(HeadingTurnFrame.decode(golden.copyOf(15)))
        assertNull(HeadingTurnFrame.decode(golden.copyOf().also { it[1] = 0x10 }))  // reserved flag bit
        assertNull(HeadingTurnFrame.decode(golden.copyOf().also { it[15] = 1 }))    // reserved bytes
        assertNull(HeadingTurnFrame.decode(golden.copyOf().also { it[1] = 0x03 }))  // sign flag disagrees with the rate
    }
}
```
(The golden bytes must equal the corrected bytes from Task 4 Step 1.)

- [ ] **Step 2: Run to verify it fails:** `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.HeadingTurnProtocolTest"` -> compile error `HeadingTurnFrame`.
- [ ] **Step 3: Implement.** Add the data class next to `SkiEvent` in `SkiEventProtocol.kt`, following `SkiEvent.decode` (size/version check, flags `and 0xf0 != 0` or reserved nonzero or `positive != (peak > 0f)` -> null; note a zero peak is never emitted by the firmware, so `peak == 0f` is also null). Update the KDoc on `MotionFrames.decode` to list version 4. In `BleSensorClient.kt` add the constructor parameter and the `is HeadingTurnFrame -> onHeadingTurn(frame)` branch beside the others (line ~133). In `SensorSessionService.kt` add the `Listener` default method (line ~22) and forward `onHeadingTurn = { turn -> listener?.onHeadingTurn(side, turn) }` where the other callbacks are wired (line ~385). In `MainActivity.kt`, in the Boots sensors card's listener (line ~118) show "Last heading turn: N deg over X.X s, peak Y deg/s (experimental)" using the same `runOnUiThread` pattern as `onSkiEvent`.
- [ ] **Step 4: Run** the new test plus `SkiEventProtocolTest` and `MovementEventProtocolTest`: all pass.
- [ ] **Step 5: Commit** ("Decode heading turns on the movement characteristic").

---

### Task 7: Kotlin heading detector with parity fixture

**Files:**
- Create: `android/app/src/main/java/com/openski/android/HeadingTurnDetector.kt`
- Create: `android/app/src/test/java/com/openski/android/HeadingTurnDetectorTest.kt`
- Create: `android/app/src/test/resources/heading_turns.txt` (generated)
- Modify: `tools/test_heading_tracker.cpp` (fixture emit/check mode)

**Interfaces:**
- Produces: `data class HeadingTurn(val startMs: Double, val endMs: Double, val positive: Boolean, val peakRateDps: Double, val changeDegrees: Double, val skippedSamples: Boolean, val nearRail: Boolean)`; `class HeadingTurnDetector { fun accept(timeMs: Double, rateRadps: Double, flags: Int = 0): HeadingTurn?; fun skip(); fun breakSegment() }` (returns the turn closed by this sample, else null). Flag bits as in C++: 1 skipped, 2 near rail. Constants identical to Task 2 (tau 0.25/4/20 s, 0.6 sd, 15 deg/s floor, 0.4 s, 100 ms gap).

Fixture format (plain text, no JSON dependency in JVM tests): per series, a header `series <name>`, a line `rates_dps <v> <v> ...` at 100 Hz starting at t = 1000 ms, then `turn <startMs> <endMs> <positive 0|1> <peak> <change>` lines, then `end`. Rates rounded to 0.01 deg/s; the tool converts deg/s to rad/s before feeding (`dps/57.2957795f`), and Kotlin does the same with a double.

- [ ] **Step 1: Extend the C++ tool** `tools/test_heading_tracker.cpp`: add `--emit <path>` (build the three synthetic series: `carved` 8 lobes of 60 deg/s over 4 s period; `mixed` periods 2 s and 3 s with a 15 deg/s wobble and a 0.3 s gap hole; `still` low noise), write the fixture; and `--check <path>` (re-run the detector on the stored rates and compare turns within 1 ms and 0.05 deg/s). With no arguments, the existing tests run. Emit once:

```bash
g++ -std=gnu++17 -Ilib/Motion/src tools/test_heading_tracker.cpp -o /tmp/heading
/tmp/heading --emit android/app/src/test/resources/heading_turns.txt
/tmp/heading --check android/app/src/test/resources/heading_turns.txt
```
Expected: `fixture matches`.

- [ ] **Step 2: Write the failing Kotlin test** `HeadingTurnDetectorTest.kt` with three tests: (a) the fixture parity test, which parses `heading_turns.txt` from `javaClass.getResourceAsStream("/heading_turns.txt")`, feeds each series (timestamps `1000 + i*10` ms, `rad = dps / 57.2957795`, a `breakSegment()` call is not needed because the detector detects the 100 ms+ gap itself) and asserts each emitted turn matches the stored one (start/end within 1 ms, peak within 0.2 deg/s, change within 0.5 deg, sign equal) and that the counts are equal; (b) standing still emits nothing for 60 s of +-1 deg/s noise; (c) `breakSegment()` then a quiet restart emits nothing spanning the break.
- [ ] **Step 3: Run to verify it fails** (`HeadingTurnDetector` unresolved).
- [ ] **Step 4: Implement** `HeadingTurnDetector` as a line-for-line port of Task 2's `update`/`close` using `Double`, with `timeMs` in milliseconds (`kSmoothTauS*1000.0` for the delay; gap = more than 100 ms; min duration 400 ms). `accept` returns the turn closed by the sign flip on this sample.
- [ ] **Step 5: Run** `./gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.HeadingTurnDetectorTest"` -> pass. If parity fails by more than tolerance only at a flip right at the threshold, investigate float-versus-double ordering before loosening tolerances.
- [ ] **Step 6: Commit** (detector, test, fixture, C++ tool change).

---

### Task 8: Replay analysis, exports and Analysis tab

**Files:**
- Modify: `BootOrientation.kt` (add field), `SessionExport.kt`, `SessionDetailActivity.kt`
- Create: `HeadingTurnAnalysis.kt`, `android/app/src/test/java/com/openski/android/HeadingTurnAnalysisTest.kt`

**Interfaces:**
- Consumes: `HeadingTurnDetector` (Task 7).
- Produces: `BootRoll.headingRateRadps: Double = 0.0` appended as the last constructor parameter, computed in `BootOrientation.Tracker.accept` as `omega.dot(g)` where `g` is the existing current-vertical vector `q.conjugate().rotate(up)`; `object HeadingTurnAnalysis { fun detect(rolls: List<BootRoll>): List<HeadingTurn> }` (feeds in time order, calls `breakSegment()` when `BootRoll.segment` changes). Export file `heading-turns.csv` with header `side,direction,positive_heading_rate,start_ms,end_ms,duration_ms,peak_heading_rate_dps,heading_change_deg,detector` (detector value `experimental_heading_rate`); `analysis-summary.json` gains per-side `heading_turns` (count, median duration ms, cadence per minute) and a `heading_detector` note string.

- [ ] **Step 1: Failing tests.** In `HeadingTurnAnalysisTest`: build `BootRoll` lists by running `BootOrientation.estimate` on synthetic samples of a rotation about world-up with gravity constant on one axis (reuse the `point(...)` helper style from `BootOrientationTest`); assert (a) `headingRateRadps` equals the commanded rate within 1e-6 while the boot is level, (b) the same rate is recovered when the boot is leaned 30 degrees (gyro rotated with the lean: this is the case that distinguishes `omega . up_current` from `omega . up_calibration`), (c) alternating rotation gives alternating `HeadingTurn`s, (d) a stream with a `segment` change does not emit a turn spanning it.
- [ ] **Step 2: Run to verify failure** (field and object missing).
- [ ] **Step 3: Implement** the field, the analysis object, the export file and summary entries (mirror the style of the `detected-movements.csv` block; direction mapping uses `positiveLeft(side)` exactly as that block does, labelled by heading sign), and an Analysis-tab line in `SessionDetailActivity` next to the existing dry detection output (around line 256): "Heading-rate candidates (experimental): N turns, median X.X s; roll-lobe candidates: M". Keep the existing framing text about candidates.
- [ ] **Step 4: Run** `HeadingTurnAnalysisTest`, `BootOrientationTest`, `SessionAnalysisTest`, `DrySkiAnalysisTest` -> pass. Streaming/replay equivalence is preserved because the field is computed inside the shared `Tracker`.
- [ ] **Step 5: Docs:** add the export file and summary keys to `docs/ski-analysis.md` and `docs/android-design.md`. Commit.

---

### Task 9: RestBias (portable, firmware)

**Files:**
- Create: `lib/Motion/src/RestBias.h`, `tools/test_rest_bias.cpp`

**Interfaces:**
- Produces: `class RestBias { void update(uint32_t timeUs, float ax, float ay, float az, float gx, float gy, float gz); float bias(int axis) const; uint32_t updates() const; uint32_t refused() const; bool resting() const; void reset(); }`. Inputs are already corrected by the static per-unit calibration (m/s^2 and rad/s). `bias(axis)` is the *additional* estimate to subtract.

Rule: a window of consecutive samples counts as still while every sample has `|g| < 0.12 rad/s`, `||a|-9.80665|/9.80665 < 0.03`, the window's per-axis gyro peak-to-peak stays under 0.05 rad/s and per-axis accelerometer peak-to-peak under 0.5 m/s^2. After 2 s of still window, move each axis estimate toward the window mean at no more than `0.002 rad/s per second` (cap 0.004 per window), then start a new window. If the window mean differs from the static calibration by more than 0.15 rad/s the update is refused and `refused()` increments. A step over 100 ms or any non-still sample resets the window. The estimate itself is clamped to +-0.15 rad/s.

- [ ] **Step 1: Failing test** `tools/test_rest_bias.cpp`: (a) constant 0.04 rad/s offset on x for 30 s at rest: the estimate converges to 0.04 within 0.005 and never moves faster than 0.002 rad/s per second (check at 5 s: at most 0.010); (b) steady rotation of 0.10 rad/s about one axis with gravity rotating in the sensor frame is never absorbed (`updates() == 0`); (c) rest with offset 0.2 rad/s is refused (`refused() > 0`, estimate 0); (d) accelerations of 1.5 g never open a window; (e) a 150 ms gap resets the window (no update completes across it); (f) noise of +-0.01 rad/s does not stop convergence.
- [ ] **Step 2: Run to verify it fails** (header missing).
- [ ] **Step 3: Implement** `RestBias.h` per the rule above (window sums and min/max per axis, fixed-size, no heap).
- [ ] **Step 4: Run** `g++ -std=gnu++17 -Ilib/Motion/src tools/test_rest_bias.cpp -o /tmp/rest_bias && /tmp/rest_bias` -> pass.
- [ ] **Step 5: Commit.**

---

### Task 10: RestBias in the firmware pipeline

**Files:**
- Modify: `src/MotionService.cpp`, `include/MotionService.h`, `src/TelemetryService.cpp` (stage your hunks only), `docs/imu-data.md` (stage your hunks only), `docs/bring-up.md`

**Interfaces:**
- Consumes: `RestBias` (Task 9).
- Produces: `MotionService` applies `rest.bias(i)` to the gyro inputs of all three recognisers; `motion::gyroBias(float out[3])`, `motion::biasUpdates()`, `motion::biasRefused()`. HTTP `/api/v1/motion` gains `gyro_bias_radps` (array of 3), `bias_updates`, `bias_refused`.

- [ ] **Step 1:** In `MotionService.cpp` declare `RestBias rest;` beside the trackers. After the static-corrected `a` and `g` are formed, call `rest.update(sample.timestampUs, a.x, a.y, a.z, g.x, g.y, g.z);` then build `gc{g.x-rest.bias(0), g.y-rest.bias(1), g.z-rest.bias(2)}` and pass `gc` to `gestures`, `tilt` and `ski` instead of `g`. Raw `sample` is never modified. The magnitude and `speed` computations use `gc` too.
- [ ] **Step 2:** Expose the three accessors; add the HTTP fields mirroring the neighbouring ones.
- [ ] **Step 3: Build and test:** `.venv/Scripts/pio.exe run -e esp32-devkit-v1`, `.venv/Scripts/pio.exe run -e esp32-s3`, then re-run all host tests from Tasks 2 to 9. Expected: success.
- [ ] **Step 4: Docs:** `docs/imu-data.md` (bias fields and the rule in two sentences, labelled experimental, "temperature compensation not included"), `docs/bring-up.md` (the new JSON fields to expect). Commit.

---

### Task 11: RestBias in the Android orientation tracker

**Files:**
- Modify: `BootOrientation.kt`; Create: `RestBias.kt`, `android/app/src/test/java/com/openski/android/RestBiasTest.kt`
- Modify: `BootOrientationTest.kt` (add cases)

**Interfaces:**
- Produces: `class RestBias { fun update(timeMs: Double, accel: Vector3, gyro: Vector3); val estimate: Vector3; val updates: Int; val refused: Int }` with the same rule and constants as Task 9 (`timeMs` in ms; 2000 ms window; 100 ms gap).

- [ ] **Step 1: Failing tests** `RestBiasTest` ports cases (a), (b), (c), (e) of Task 9 with `Vector3`. In `BootOrientationTest` add: a boot at rest with a 0.03 rad/s gyro offset *not present at calibration* (calibration taken before the offset appears) gives roll drift that, after 60 s of rest with learning, is much smaller than without it: assert learned drift is at least 5 times smaller than the drift measured in the same test with learning disabled.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** `RestBias.kt`; in `BootOrientation.Tracker` create a `RestBias`, compute `val omega = rotation(p.sample) - calibration.bias - restBias.estimate`, and call `restBias.update(p.timeMs, accel, rotation(p.sample) - calibration.bias)` before it. Gyro near-rail (`p.sample.quality and 2`, available after Task 13; until then guard with `0`) resets the window.
- [ ] **Step 4: Run** `RestBiasTest`, `BootOrientationTest`, `SessionAnalysisTest`, `RunControllerTest` -> pass (existing "stationaryBiasDoesNotAccumulate" must stay green).
- [ ] **Step 5: Commit.**

---

### Task 12: Firmware quality flags and protocol v3

**Files:**
- Create: `lib/Imu/src/RecordFlags.h`
- Modify: `src/RecorderService.cpp`, `src/BluetoothService.cpp`, `test/test_imu_monitor/test_main.cpp` (stage your hunks; the file carries owner changes), `docs/ble-protocol.md`

**Interfaces:**
- Produces (namespace `openski::imu`): `constexpr uint8_t kRecordFlagMask = 0x07; enum RecordFlag : uint8_t { kRecordAccelNearRail = 1, kRecordGyroNearRail = 2, kRecordDroppedBefore = 4 }; uint32_t packRecordTimestamp(uint32_t us, uint8_t flags); uint32_t recordTimestamp(uint32_t packed); uint8_t recordFlags(uint32_t packed); uint8_t recordFlagsFromQuality(uint8_t quality);`
- Wire: recorder control response version byte `3`; download chunk marker `0xD1` (records carry flags; `0xD0` remains the format-2 marker, never sent by v3 firmware); live frame version `2`, 20 bytes, byte 19 = OR of the quality of every sample since the previous notification, masked to 0x0f, bit 0 accel near rail, bit 1 gyro near rail, bit 2 timestamp gap, bit 3 timestamp wrap. (Spec refinement: the marker makes each chunk self-describing so Android never guesses the record format.)

- [ ] **Step 1: Failing tests** in `test/test_imu_monitor/test_main.cpp`:

```cpp
void test_record_flags_pack_into_the_low_timestamp_bits() {
  using namespace openski::imu;
  const uint32_t packed = packRecordTimestamp(1234567U, kRecordAccelNearRail | kRecordDroppedBefore);
  TEST_ASSERT_EQUAL_UINT8(5, recordFlags(packed));
  TEST_ASSERT_EQUAL_UINT32(1234567U & ~7U, recordTimestamp(packed));
  TEST_ASSERT_EQUAL_UINT32(0xfffffff8U, recordTimestamp(packRecordTimestamp(0xffffffffU, 7)));
  TEST_ASSERT_EQUAL_UINT8(0, recordFlags(packRecordTimestamp(7U, 0)));
  TEST_ASSERT_EQUAL_UINT8(3, recordFlagsFromQuality(kAccelNearRail | kGyroNearRail | kTimestampGap));
  TEST_ASSERT_EQUAL_UINT8(0, recordFlagsFromQuality(kTimestampGap | kTimestampWrap));
}
```
Add `#include "RecordFlags.h"` and `RUN_TEST(test_record_flags_pack_into_the_low_timestamp_bits);`.

- [ ] **Step 2: Run** `.venv/Scripts/pio.exe test -e native -f test_imu_monitor` -> fails (header missing).
- [ ] **Step 3: Implement** `RecordFlags.h` (inline functions per the interface). In `RecorderService.cpp`: add `static bool droppedBeforeNext = false;`; change `encodeRecord` to `putUint32(record.bytes, packRecordTimestamp(sample.timestampUs, recordFlagsFromQuality(sample.qualityFlags) | (droppedBeforeNext ? kRecordDroppedBefore : 0))); droppedBeforeNext = false;`; in the `xQueueSend` failure branch set `droppedBeforeNext = true;`. A flag set on a dropped record is lost with it, which is why the *next* record carries the bit; clear `droppedBeforeNext` in `start()`.
- [ ] **Step 4:** In `BluetoothService.cpp`: `kRecorderProtocolVersion = 3`, `kLiveFrameSize = 20`, live frame version byte `2` (`kLiveProtocolVersion`; leave `kProtocolVersion = 1` for the status characteristic), `frame[19]`; accumulate `liveQuality |= latest.qualityFlags` once per new sample timestamp *before* the decimation early-return, clear after sending; download marker `0xd1`.
- [ ] **Step 5: Build and test:** native tests and both `pio run` targets pass.
- [ ] **Step 6: Docs** in `docs/ble-protocol.md`: live frame table gains offset 19 `quality`, version 2 (and "versions 1 and 2 both exist; Android decodes both"); control response version 3; data marker `D1`; the record's timestamp field described as microseconds with the low 3 bits as flags (8 us resolution); the rail-flag caveat from `docs/imu-data.md` (within 8 counts of full scale, not proof of absence of clipping between reads); compatibility paragraph from the spec. Commit.

---

### Task 13: Android decodes quality and migrates the database

**Files:**
- Modify: `SensorSample.kt`, `RecorderProtocol.kt`, `LocalSessionStore.kt`, `BleSensorClient.kt`, `SensorSessionService.kt` (where chunks are persisted: `grep -n "download_samples" LocalSessionStore.kt` and follow the caller)
- Modify: `android/app/src/test/java/com/openski/android/RecorderProtocolTest.kt`, `android/app/src/androidTest/java/com/openski/android/StorageInstrumentation.kt` (stage only your hunks; owner has uncommitted edits)

**Interfaces:**
- Produces: `SensorSample.quality: Int = 0` (last constructor parameter; bit meanings as the live frame); `SensorSample.decode` accepts `size == 19 && version == 1` (quality 0) and `size == 20 && version == 2`; `RecorderStatus.version: Int` (decode accepts 2 or 3); `FlashRecord(timestampUs, sample, raw)` with `FlashRecord.decode(raw: ByteArray, withFlags: Boolean = false)` where `withFlags` masks `us and 7` into `sample.quality` (bit 0 accel rail, bit 1 gyro rail, bit 2 dropped-before mapped to quality bit 4 = 16) and clears them from `timestampUs`; `FlashChunk.withFlags: Boolean` from marker `0xd1` (`0xd0` -> false); `SensorCapture.recordFormat: Int` (2 or 3); `LocalSessionStore.setRecordFormat(captureId: String, format: Int)`; database version 9: `ALTER TABLE captures ADD COLUMN record_format INTEGER NOT NULL DEFAULT 2`, `ALTER TABLE samples ADD COLUMN quality INTEGER NOT NULL DEFAULT 0`.

- [ ] **Step 1: Failing JVM tests** in `RecorderProtocolTest`: (a) v3 record with timestamp `0x0000_0105` and `withFlags = true` decodes `timestampUs == 0x100`, `quality == 17` (bit 0 accel rail plus bit 4 dropped-before); (b) the *same bytes* with `withFlags = false` keep `timestampUs == 0x105` and `quality == 0` (legacy never masked); (c) chunk marker `0xd1` -> `withFlags`, `0xd0` -> not, `0xd2` -> null; (d) `RecorderStatus.decode` accepts version 3 and still rejects 1 and 4; (e) `SensorSample.decode` of a 20-byte version-2 frame carries `quality`, a 19-byte version-1 frame gives 0, a 20-byte version-1 frame is rejected; (f) a `TransferValidator` fed chunks of both formats for one capture is rejected (add `FlashChunk.withFlags` consistency check to `accept`, remembering the first chunk's value).
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** the protocol changes above. In the persistence path, when the first chunk of a download is stored call `setRecordFormat(captureId, if (chunk.withFlags) 3 else 2)`; in `loadData` decode flash with `FlashRecord.decode(blob, capture.recordFormat >= 3)`; in `validateDownload` keep the shape-only decode. Live samples: write/read `samples.quality`. Add `addQualitySchema(db)` for version 9 to `onCreate` and `onUpgrade (oldVersion < 9)`; bump the constructor version to 9.
- [ ] **Step 4: Instrumentation check** in `StorageInstrumentation`: create a v8 database (reuse the existing migration helper pattern for v1-6 checks), open with v9, assert both columns exist with defaults, that an existing flash BLOB with low bits `0b101` still loads with `quality == 0` and the original timestamp, and that a capture marked format 3 loads with flags. Run on the emulator: `./gradlew.bat :app:assembleDebugAndroidTest` then `adb shell am instrument -w com.openski.android.test/com.openski.android.StorageInstrumentation`.
- [ ] **Step 5: Run** the full JVM suite `./gradlew.bat :app:testDebugUnitTest` -> pass. Commit.

---

### Task 14: Quality in the analysis, exports and the recording card

**Files:**
- Modify: `SessionAnalysis.kt` (`StreamQuality`), `BootOrientation.kt`, `SessionExport.kt`, `SessionDetailActivity.kt` (Details tab quality text)
- Modify tests: `SessionAnalysisTest.kt`, `BootOrientationTest.kt`

**Interfaces:**
- Consumes: `SensorSample.quality` (Task 13).
- Produces: `StreamQuality.railSamples: Int = 0` and `StreamQuality.droppedBefore: Int = 0` (appended parameters); `BootRoll.quality: Int = 0` appended after `headingRateRadps`; `samples.csv` gains a final `quality` column; `analysis-summary.json` and `session.json` `quality` entries gain `rail_samples`, `rail_percent`, `dropped_before_samples`.

- [ ] **Step 1: Failing tests:** (a) `SessionAnalysisTest`: a stream with 3 samples at quality 1 or 2 reports `railSamples == 3`; (b) `BootOrientationTest`: with a gyro-near-rail sample (quality 2) in the middle of an otherwise still second, `restedMs` restarts, so the rest correction is delayed by at least that sample (assert the roll after the sample equals the gyro-only integration, not a rest-corrected value); (c) the export header test (if present in `DeveloperWorkflowTest`, extend it; otherwise add a small test writing a session through `SessionExport.write` into a `ByteArrayOutputStream` and reading `samples.csv`) asserts the last header is `quality`.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** In `Tracker.accept`: `if (p.sample.quality and 3 != 0) restedMs = 0.0`; pass `quality = p.sample.quality` into every `BootRoll`; feed `flags` to the heading detector from `BootRoll.quality` (bit 1 near rail -> 2) in `HeadingTurnAnalysis`. Append the CSV column; add the summary fields; in the Details tab quality line add "clipped X% of samples" only when `railSamples > 0`, labelled "within 8 counts of full scale; not proof of absence of clipping".
- [ ] **Step 4: Run** the full JVM suite and `./gradlew.bat :app:lintDebug` -> pass.
- [ ] **Step 5: Commit.**

---

### Task 15: Acceptance items, roadmap, README and final verification

**Files:**
- Modify: `docs/android-acceptance.md`, `docs/bench-results.md` (stage your hunks), `docs/feature-roadmap.md`, `README.md` (stage your hunks), `CLAUDE.md`, `docs/research-findings.md`

- [ ] **Step 1: `docs/android-acceptance.md`:** add an "Owed on hardware (research round)" list: heading turns v4 frames received on a real boot while turning in place; a flash download with a flag set by a deliberate knock and `D1` markers in a protocol trace; a v2 session recorded before the change still replays unchanged; rest-bias convergence after a cold start shown in `/api/v1/motion`; a side-by-side of roll-lobe against heading-rate candidates against video on the first snow day (`docs/first-snow-protocol.md` in the research repo). Do not tick any of them.
- [ ] **Step 2: Causal check status.** If the SkiC data has been re-downloaded, run the C++ detector through its CLI mode against it and record the F1 at +-0.5 s and the by-level split in `docs/bench-results.md`; otherwise record "causal heading-rate detector not yet scored against public data" in `docs/research-findings.md`. Do not invent numbers.
- [ ] **Step 3: `CLAUDE.md`:** add one paragraph under Architecture for `HeadingTracker`/frame version 4, one for `RestBias`, and update the wire-protocol paragraph (live v2, control v3, marker `D1`, record flags in the timestamp low bits, database version 9). Update `README.md` scope line; update `docs/feature-roadmap.md` header counts only if a row's status changed.
- [ ] **Step 4: Full verification** (report actual output, do not assert success without it):

```bash
for t in ski_tracker ski_frames heading_tracker rest_bias; do
  g++ -std=gnu++17 -Ilib/Motion/src tools/test_$t.cpp -o /tmp/$t && /tmp/$t || echo "FAILED $t"
done
.venv/Scripts/pio.exe test -e native
.venv/Scripts/pio.exe run -e esp32-devkit-v1
.venv/Scripts/pio.exe run -e esp32-s3
cd android && ./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```
Then run the instrumentation on the emulator as in Task 13 Step 4.
- [ ] **Step 5: Commit** the docs. Open the pull request only when the owner asks.
