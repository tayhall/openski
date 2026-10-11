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
    const bool reseeded = !seeded_;
    if (reseeded) {
      smooth_ = rate; base_ = rate; seeded_ = true;
      segmentStartUs_ = timeUs; area_ = 0; peak_ = 0; sign_ = 0;
      skipped_ = nearRail_ = false;
    }
    // The reseeding sample (first, or first after a gap) takes no filter step so the SD survives a gap.
    const float dt = reseeded ? 0.0f : (timeUs - lastUs_)/1000000.0f;
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
  // Counts only lobes that reach the amplitude threshold but last under 0.4 s.
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
