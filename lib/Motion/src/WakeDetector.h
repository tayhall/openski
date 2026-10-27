#pragma once
#include <math.h>
#include <stdint.h>

namespace openski::motion {
// Decides when a sleeping boot has been picked up. The first `settleSamples` samples only build a baseline
// (mean accelerometer and gyro); after that any single axis that moves more than its threshold from the
// baseline reports a wake. Bench noise on the LSM6DSOX is about 0.007 m/s^2 SD (0.04 peak to peak) and
// 0.0005 rad/s SD, so 0.1 on either leaves a wide margin.
class WakeDetector {
 public:
  static constexpr float kAccelThresholdMps2 = 0.1f;
  static constexpr float kGyroThresholdRadps = 0.1f;
  static constexpr uint8_t kSettleSamples = 5;

  WakeDetector(float accelThreshold = kAccelThresholdMps2, float gyroThreshold = kGyroThresholdRadps)
      : accelThreshold_(accelThreshold), gyroThreshold_(gyroThreshold) {}

  void reset() {
    settled_ = 0;
    for (int i = 0; i < 6; ++i) sum_[i] = baseline_[i] = 0.0f;
    delta_ = 0.0f;
    gyroTrigger_ = false;
  }

  bool armed() const { return settled_ >= kSettleSamples; }

  // Returns true when this sample is a wake. Samples used for the baseline never wake.
  bool update(float ax, float ay, float az, float gx, float gy, float gz) {
    const float v[6] = {ax, ay, az, gx, gy, gz};
    if (!armed()) {
      for (int i = 0; i < 6; ++i) sum_[i] += v[i];
      if (++settled_ == kSettleSamples) {
        for (int i = 0; i < 6; ++i) baseline_[i] = sum_[i] / kSettleSamples;
      }
      return false;
    }
    for (int i = 0; i < 6; ++i) {
      const float d = fabsf(v[i] - baseline_[i]);
      const bool gyro = i >= 3;
      if (d > (gyro ? gyroThreshold_ : accelThreshold_)) {
        delta_ = d;
        gyroTrigger_ = gyro;
        return true;
      }
    }
    return false;
  }

  float triggerDelta() const { return delta_; }
  bool triggeredByGyro() const { return gyroTrigger_; }

 private:
  float accelThreshold_;
  float gyroThreshold_;
  uint8_t settled_ = 0;
  float sum_[6] = {};
  float baseline_[6] = {};
  float delta_ = 0.0f;
  bool gyroTrigger_ = false;
};
}  // namespace openski::motion
