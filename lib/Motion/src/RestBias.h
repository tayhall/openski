#pragma once
#include <cmath>
#include <cstdint>

namespace openski::motion {

// Slow gyro-bias estimator that learns only while the boot is demonstrably at rest. It adds to the
// static per-unit calibration: inputs are already corrected (accel m/s^2, gyro rad/s) and bias(axis)
// is the additional offset the caller subtracts from the gyro. Rate limited so it cannot chase real
// motion. Experimental: thresholds are guesses (docs/technique-research-integration.md).
class RestBias {
 public:
  static constexpr float kGravity = 9.80665f;
  static constexpr float kStillGyroRadps = 0.12f;       // per-sample |g| for a still sample
  static constexpr float kStillAccelFraction = 0.03f;   // | |a| - g | / g
  static constexpr float kGyroPeakToPeak = 0.05f;       // rad/s per axis across the window
  static constexpr float kAccelPeakToPeak = 0.5f;       // m/s^2 per axis across the window
  static constexpr uint32_t kMaxStepUs = 100000U;       // larger timestamp step ends the window
  static constexpr uint32_t kWindowUs = 2000000U;       // still time needed before an update
  static constexpr float kMaxRateRadpsPerS = 0.002f;    // estimate speed limit
  static constexpr float kRefuseRadps = 0.15f;          // window mean above this is refused
  static constexpr float kClampRadps = 0.15f;           // estimate limit

  void update(uint32_t timeUs, float ax, float ay, float az, float gx, float gy, float gz) {
    if (open_ && timeUs - lastUs_ > kMaxStepUs) closeWindow();
    const float a[3] = {ax, ay, az}, g[3] = {gx, gy, gz};
    const float amag = std::sqrt(ax*ax + ay*ay + az*az);
    const bool accelStill = std::fabs(amag - kGravity)/kGravity < kStillAccelFraction;
    if (!accelStill) {
      closeWindow();
      return;
    }
    if (!open_) beginWindow(timeUs, a, g);
    else if (!extendWindow(timeUs, a, g)) {
      // Motion inside the window: drop it and restart from this (still) sample.
      closeWindow();
      beginWindow(timeUs, a, g);
    }
    if (open_ && lastUs_ - startUs_ >= kWindowUs) finishWindow();
  }

  float bias(int axis) const { return axis >= 0 && axis < 3 ? estimate_[axis] : 0.0f; }
  uint32_t updates() const { return updates_; }
  uint32_t refused() const { return refused_; }
  // True while a still window is open and no sample in it has been too fast to count as rest.
  bool resting() const { return open_ && !overLimit_; }

  void reset() { *this = RestBias(); }

 private:
  void beginWindow(uint32_t timeUs, const float a[3], const float g[3]) {
    open_ = true;
    startUs_ = lastUs_ = timeUs;
    n_ = 1;
    overLimit_ = false;
    for (int i = 0; i < 3; ++i) {
      sum_[i] = g[i];
      gMin_[i] = gMax_[i] = g[i];
      aMin_[i] = aMax_[i] = a[i];
    }
    noteMagnitude(g);
  }
  // Returns false when the window's peak-to-peak limits are exceeded (window not extended).
  bool extendWindow(uint32_t timeUs, const float a[3], const float g[3]) {
    for (int i = 0; i < 3; ++i) {
      const float gLo = g[i] < gMin_[i] ? g[i] : gMin_[i], gHi = g[i] > gMax_[i] ? g[i] : gMax_[i];
      const float aLo = a[i] < aMin_[i] ? a[i] : aMin_[i], aHi = a[i] > aMax_[i] ? a[i] : aMax_[i];
      if (gHi - gLo >= kGyroPeakToPeak || aHi - aLo >= kAccelPeakToPeak) return false;
    }
    for (int i = 0; i < 3; ++i) {
      sum_[i] += g[i];
      if (g[i] < gMin_[i]) gMin_[i] = g[i];
      if (g[i] > gMax_[i]) gMax_[i] = g[i];
      if (a[i] < aMin_[i]) aMin_[i] = a[i];
      if (a[i] > aMax_[i]) aMax_[i] = a[i];
    }
    ++n_;
    lastUs_ = timeUs;
    noteMagnitude(g);
    return true;
  }
  void noteMagnitude(const float g[3]) {
    const float mag = std::sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]);
    if (mag >= kStillGyroRadps) overLimit_ = true;
  }
  void closeWindow() { open_ = false; n_ = 0; overLimit_ = false; }

  void finishWindow() {
    const float seconds = (lastUs_ - startUs_)/1000000.0f;
    float target[3];
    bool refuse = overLimit_;  // too fast to be rest, e.g. a steady turn or a large offset
    for (int i = 0; i < 3; ++i) {
      target[i] = sum_[i]/static_cast<float>(n_);
      if (std::fabs(target[i]) > kRefuseRadps) refuse = true;
    }
    if (refuse) {
      ++refused_;
    } else {
      const float maxStep = kMaxRateRadpsPerS*seconds;
      for (int i = 0; i < 3; ++i) {
        float d = target[i] - estimate_[i];
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        float e = estimate_[i] + d;
        if (e > kClampRadps) e = kClampRadps;
        if (e < -kClampRadps) e = -kClampRadps;
        estimate_[i] = e;
      }
      ++updates_;
    }
    closeWindow();
  }

  float estimate_[3] = {0, 0, 0};
  float sum_[3] = {0, 0, 0};
  float gMin_[3] = {0, 0, 0}, gMax_[3] = {0, 0, 0};
  float aMin_[3] = {0, 0, 0}, aMax_[3] = {0, 0, 0};
  uint32_t startUs_ = 0, lastUs_ = 0, n_ = 0;
  uint32_t updates_ = 0, refused_ = 0;
  bool open_ = false, overLimit_ = false;
};

}  // namespace openski::motion
