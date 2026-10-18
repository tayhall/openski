#include "ImuService.h"

#include <Arduino.h>
#include <Wire.h>

#include "AppConfig.h"
#ifdef OPENSKI_IMU_LSM6DSOX
#include "Lsm6dsoxSensor.h"
#include "WireRegisterBus.h"
#else
#include "Mpu6050Sensor.h"
#endif

namespace openski::imu {
namespace {
#ifdef OPENSKI_IMU_LSM6DSOX
WireRegisterBus imuBus(Wire, config::kImuSdaPin, config::kImuSclPin, config::kImuI2cAddress,
                       config::kImuI2cFrequencyHz);
uint32_t nowUs() { return micros(); }
void delayMs(uint32_t ms) { delay(ms); }
Lsm6dsoxSensor lsm6dsox(imuBus, nowUs, delayMs);
ImuSensor* sensor = &lsm6dsox;
#else
Mpu6050Sensor mpu6050(Wire, config::kImuSdaPin, config::kImuSclPin,
                      config::kImuI2cAddress, config::kImuI2cFrequencyHz);
ImuSensor* sensor = &mpu6050;
#endif
ImuMonitor imuMonitor(sensor);
uint32_t lastLoggedSampleCount = 0;
unsigned long lastSampleLogMs = 0;
constexpr uint32_t kRetryIntervalMs = 2000;
}  // namespace

void begin() {
  if (imuMonitor.begin()) {
    Serial.printf("IMU ready: %s\n", imuMonitor.sensorName());
  } else {
    Serial.printf("IMU not detected: %s\n", imuMonitor.sensorName());
  }
}

void tick() {
  // The sensor can be missed at power-up. Keep trying quietly so a reconnected or late sensor comes online.
  if (imuMonitor.retryBegin(millis(), kRetryIntervalMs)) {
    Serial.printf("IMU ready after retry: %s\n", imuMonitor.sensorName());
  }
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
