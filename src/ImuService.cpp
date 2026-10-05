#include "ImuService.h"

#include <Arduino.h>
#include <Wire.h>

#include "AppConfig.h"
#include "Mpu6050Sensor.h"

namespace openski::imu {
namespace {
Mpu6050Sensor mpu6050(Wire, config::kImuSdaPin, config::kImuSclPin,
                      config::kImuI2cAddress, config::kImuI2cFrequencyHz);
ImuSensor* sensor = &mpu6050;
ImuMonitor imuMonitor(sensor);
uint32_t lastLoggedSampleCount = 0;
unsigned long lastSampleLogMs = 0;
}  // namespace

void begin() {
  if (imuMonitor.begin()) {
    Serial.printf("IMU ready: %s\n", imuMonitor.sensorName());
  } else {
    Serial.printf("IMU not detected: %s\n", imuMonitor.sensorName());
  }
}

void tick() {
  imuMonitor.poll();
  const Stats& stats = imuMonitor.stats();
  const unsigned long now = millis();
  if (!imuMonitor.hasSample() || stats.samples == lastLoggedSampleCount ||
      now - lastSampleLogMs < 500) {
    return;
  }

  lastLoggedSampleCount = stats.samples;
  lastSampleLogMs = now;
  const Sample& sample = imuMonitor.latest();
  Serial.printf("IMU,%lu,accel_mps2=%.3f,%.3f,%.3f,gyro_radps=%.4f,%.4f,%.4f,temp_c=%.2f\n",
                static_cast<unsigned long>(sample.timestampUs),
                sample.accelMps2.x, sample.accelMps2.y, sample.accelMps2.z,
                sample.gyroRadps.x, sample.gyroRadps.y, sample.gyroRadps.z,
                sample.temperatureC);
}

const ImuMonitor& monitor() { return imuMonitor; }
}  // namespace openski::imu
