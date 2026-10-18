#include "MagService.h"

#include <Arduino.h>

#include "AppConfig.h"

#ifdef OPENSKI_MAG_LIS3MDL
#ifndef OPENSKI_IMU_LSM6DSOX
#error "OPENSKI_MAG_LIS3MDL needs the shared I2C bus of the OPENSKI_IMU_LSM6DSOX builds"
#endif
#include <Wire.h>

#include "Lis3mdlMagnetometer.h"
#include "WireRegisterBus.h"

namespace openski::magnetometer {
namespace {
constexpr uint8_t kMagI2cAddress = 0x1C;
constexpr uint32_t kRetryIntervalMs = 2000;
constexpr uint32_t kPollIntervalMs = 20;  // only 10 frames a second go out, so poll lightly: the bus is shared with the IMU
constexpr uint32_t kMaxConsecutiveErrors = 50;

// ownsBus false: the IMU's bus object starts and recovers the shared Wire bus.
imu::WireRegisterBus magBus(Wire, config::kImuSdaPin, config::kImuSclPin, kMagI2cAddress,
                            config::kImuI2cFrequencyHz, false);
mag::Lis3mdlMagnetometer sensor(magBus);
Status current{};
unsigned long lastAttemptMs = 0;
unsigned long lastPollMs = 0;
uint32_t consecutiveErrors = 0;
bool everAttempted = false;
}  // namespace

void begin() {
  everAttempted = true;
  lastAttemptMs = millis();
  if (sensor.begin()) {
    current.present = true;
    Serial.println("Magnetometer ready: LIS3MDL");
  } else {
    Serial.println("Magnetometer not detected: LIS3MDL (will retry quietly)");
  }
}

void tick() {
  const unsigned long now = millis();
  if (!current.present) {
    // Quiet retry: a missing magnetometer must never disturb the IMU.
    if (everAttempted && now - lastAttemptMs >= kRetryIntervalMs) {
      lastAttemptMs = now;
      if (sensor.begin()) {
        current.present = true;
        consecutiveErrors = 0;
        Serial.println("Magnetometer ready after retry: LIS3MDL");
      }
    }
    return;
  }
  if (now - lastPollMs < kPollIntervalMs) return;
  lastPollMs = now;
  mag::MagSample sample{};
  switch (sensor.read(sample)) {
    case mag::MagResult::kSample:
      current.rawX = sample.rawX;
      current.rawY = sample.rawY;
      current.rawZ = sample.rawZ;
      current.hasSample = true;
      ++current.samples;
      consecutiveErrors = 0;
      break;
    case mag::MagResult::kNoData:
      break;
    case mag::MagResult::kError:
      ++current.readErrors;
      if (++consecutiveErrors >= kMaxConsecutiveErrors) {
        current.present = false;  // fall back to the quiet retry loop
        lastAttemptMs = now;
      }
      break;
  }
}

Status status() { return current; }
}  // namespace openski::magnetometer
#else
namespace openski::magnetometer {
void begin() {}
void tick() {}
Status status() { return Status{}; }
}  // namespace openski::magnetometer
#endif
