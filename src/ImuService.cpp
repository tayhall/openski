#include "ImuService.h"

#include <Arduino.h>

namespace openski::imu {
namespace {
// No chip driver yet; set this to a driver instance once the IMU is chosen.
ImuSensor* sensor = nullptr;
ImuMonitor imuMonitor(sensor);
}  // namespace

void begin() {
  if (imuMonitor.begin()) {
    Serial.printf("IMU ready: %s\n", imuMonitor.sensorName());
  } else if (sensor == nullptr) {
    Serial.println("IMU unconfigured: no driver selected");
  } else {
    Serial.printf("IMU not detected: %s\n", imuMonitor.sensorName());
  }
}

void tick() { imuMonitor.poll(); }

const ImuMonitor& monitor() { return imuMonitor; }
}  // namespace openski::imu
