#pragma once
#include <stdint.h>

#include "WakeDetector.h"

namespace openski::motion {
// Tells when a boot has lain still for a whole idle period, so it can put itself to sleep. A sample that moves
// any axis past the thresholds from the baseline (the WakeDetector test) counts as movement, restarts the period
// and takes a new baseline. The thresholds can be looser than the wake's: table vibration should not keep a boot
// awake. Times are millis() values, wrap-safe.
class StillnessTimer {
 public:
  explicit StillnessTimer(uint32_t idleMs, float accelThreshold = WakeDetector::kAccelThresholdMps2,
                          float gyroThreshold = WakeDetector::kGyroThresholdRadps)
      : idleMs_(idleMs), detector_(accelThreshold, gyroThreshold) {}

  // Restarts the period now, e.g. while a recording or download makes sleeping unwelcome.
  void restart(uint32_t nowMs) {
    detector_.reset();
    sinceMs_ = nowMs;
  }

  void update(uint32_t nowMs, float ax, float ay, float az, float gx, float gy, float gz) {
    if (detector_.update(ax, ay, az, gx, gy, gz)) restart(nowMs);
  }

  bool idle(uint32_t nowMs) const { return nowMs - sinceMs_ >= idleMs_; }
  uint32_t stillMs(uint32_t nowMs) const { return nowMs - sinceMs_; }

 private:
  uint32_t idleMs_;
  uint32_t sinceMs_ = 0;
  WakeDetector detector_;
};
}  // namespace openski::motion
