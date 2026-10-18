#pragma once

#include "Imu.h"

namespace openski::imu {
struct Stats {
  bool ready;
  uint32_t samples;
  uint32_t readFailures;
  uint32_t timestampGaps;
  uint32_t timestampWraps;
  uint32_t accelNearRailSamples;
  uint32_t gyroNearRailSamples;
  uint32_t lastIntervalUs;
  uint32_t maxIntervalUs;
  uint32_t stalls;  // times checkStall() found the sensor silent and marked it not ready
};

// Polls an ImuSensor and tracks its health. A null sensor means no driver is
// configured; the monitor then stays not-ready and polling does nothing.
class ImuMonitor {
 public:
  explicit ImuMonitor(ImuSensor* sensor) : sensor_(sensor) {}

  bool begin();
  void poll();

  // While the sensor is not ready, tries begin() again at most once per intervalMs. Returns true only when a
  // retry brings the sensor up, so a missed detection at boot (or a bus left stuck by a reset) recovers.
  bool retryBegin(uint32_t nowMs, uint32_t intervalMs);

  // A sensor that is ready but produces no new sample for stallMs (data-ready never rises, or every read fails)
  // is marked not ready so retryBegin() re-initialises it. Call from the service loop with a millisecond clock.
  // Returns true on the call that detects the stall.
  bool checkStall(uint32_t nowMs, uint32_t stallMs);

  const Stats& stats() const { return stats_; }
  bool hasSample() const { return stats_.samples > 0; }
  const Sample& latest() const { return latest_; }
  const char* sensorName() const { return sensor_ ? sensor_->name() : "none"; }

 private:
  ImuSensor* sensor_;
  Stats stats_{};
  Sample latest_{};
  bool attempted_ = false;
  uint32_t lastAttemptMs_ = 0;
  bool progressInit_ = false;
  uint32_t progressSamples_ = 0;
  uint32_t progressMs_ = 0;
};
}  // namespace openski::imu
