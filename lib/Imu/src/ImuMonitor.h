#pragma once

#include "Imu.h"

namespace openski::imu {
struct Stats {
  bool ready;
  uint32_t samples;
  uint32_t readFailures;
};

// Polls an ImuSensor and tracks its health. A null sensor means no driver is
// configured; the monitor then stays not-ready and polling does nothing.
class ImuMonitor {
 public:
  explicit ImuMonitor(ImuSensor* sensor) : sensor_(sensor) {}

  bool begin();
  void poll();

  const Stats& stats() const { return stats_; }
  bool hasSample() const { return stats_.samples > 0; }
  const Sample& latest() const { return latest_; }
  const char* sensorName() const { return sensor_ ? sensor_->name() : "none"; }

 private:
  ImuSensor* sensor_;
  Stats stats_{};
  Sample latest_{};
};
}  // namespace openski::imu
