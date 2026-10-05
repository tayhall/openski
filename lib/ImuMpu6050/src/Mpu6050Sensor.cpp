#include "Mpu6050Sensor.h"

#include <Arduino.h>
#include <math.h>

namespace openski::imu {
namespace {
constexpr uint8_t kRegSampleRateDivider = 0x19;
constexpr uint8_t kRegConfig = 0x1A;
constexpr uint8_t kRegGyroConfig = 0x1B;
constexpr uint8_t kRegAccelConfig = 0x1C;
constexpr uint8_t kRegDataReadyInterrupt = 0x38;
constexpr uint8_t kRegInterruptStatus = 0x3A;
constexpr uint8_t kRegAccelOutput = 0x3B;
constexpr uint8_t kRegPowerManagement = 0x6B;
constexpr uint8_t kRegWhoAmI = 0x75;
constexpr uint8_t kWhoAmIValue = 0x68;
constexpr uint8_t kDataReadyMask = 0x01;

constexpr float kStandardGravity = 9.80665f;
constexpr float kAccelCountsPerG = 4096.0f;  // +/-8 g
constexpr float kGyroCountsPerDegree = 32.8f; // +/-1000 degrees/second
constexpr float kDegreesToRadians = 0.01745329252f;
constexpr float kTemperatureCountsPerC = 340.0f;
constexpr float kTemperatureOffsetC = 36.53f;

int16_t toSignedWord(uint8_t high, uint8_t low) {
  return static_cast<int16_t>((static_cast<uint16_t>(high) << 8) | low);
}
}  // namespace

Mpu6050Sensor::Mpu6050Sensor(TwoWire& wire, int sdaPin, int sclPin,
                             uint8_t address, uint32_t busFrequencyHz)
    : wire_(wire),
      sdaPin_(sdaPin),
      sclPin_(sclPin),
      address_(address),
      busFrequencyHz_(busFrequencyHz) {}

bool Mpu6050Sensor::begin() {
  initialized_ = false;
  if (!wire_.begin(sdaPin_, sclPin_, busFrequencyHz_)) return false;

  // Wake the device and use the X gyro PLL as its clock source.
  if (!writeRegister(kRegPowerManagement, 0x01)) return false;
  delay(100);

  uint8_t identity = 0;
  if (!readRegister(kRegWhoAmI, identity) || identity != kWhoAmIValue) return false;

  // DLPF setting 3 gives a 1 kHz internal sample base and about 42 Hz gyro
  // bandwidth. Divider 9 configures a 100 Hz output data rate.
  if (!writeRegister(kRegConfig, 0x03) ||
      !writeRegister(kRegSampleRateDivider, 9) ||
      !writeRegister(kRegGyroConfig, 0x10) ||  // +/-1000 degrees/second
      !writeRegister(kRegAccelConfig, 0x10) || // +/-8 g
      !writeRegister(kRegDataReadyInterrupt, 0x01)) {
    return false;
  }

  initialized_ = true;
  return true;
}

ReadResult Mpu6050Sensor::read(Sample& out) {
  if (!initialized_) return ReadResult::kError;

  uint8_t status = 0;
  if (!readRegister(kRegInterruptStatus, status)) return ReadResult::kError;
  if ((status & kDataReadyMask) == 0) return ReadResult::kNoData;

  uint8_t data[14]{};
  if (!readRegisters(kRegAccelOutput, data, sizeof(data))) return ReadResult::kError;

  const int16_t accelX = toSignedWord(data[0], data[1]);
  const int16_t accelY = toSignedWord(data[2], data[3]);
  const int16_t accelZ = toSignedWord(data[4], data[5]);
  const int16_t temperature = toSignedWord(data[6], data[7]);
  const int16_t gyroX = toSignedWord(data[8], data[9]);
  const int16_t gyroY = toSignedWord(data[10], data[11]);
  const int16_t gyroZ = toSignedWord(data[12], data[13]);

  Sample sample{};
  sample.timestampUs = micros();
  sample.accelMps2 = {
      accelX / kAccelCountsPerG * kStandardGravity,
      accelY / kAccelCountsPerG * kStandardGravity,
      accelZ / kAccelCountsPerG * kStandardGravity,
  };
  sample.gyroRadps = {
      gyroX / kGyroCountsPerDegree * kDegreesToRadians,
      gyroY / kGyroCountsPerDegree * kDegreesToRadians,
      gyroZ / kGyroCountsPerDegree * kDegreesToRadians,
  };
  sample.temperatureC = temperature / kTemperatureCountsPerC + kTemperatureOffsetC;
  out = sample;
  return ReadResult::kSample;
}

bool Mpu6050Sensor::writeRegister(uint8_t reg, uint8_t value) {
  wire_.beginTransmission(address_);
  wire_.write(reg);
  wire_.write(value);
  return wire_.endTransmission() == 0;
}

bool Mpu6050Sensor::readRegister(uint8_t reg, uint8_t& value) {
  return readRegisters(reg, &value, 1);
}

bool Mpu6050Sensor::readRegisters(uint8_t firstReg, uint8_t* data, size_t length) {
  wire_.beginTransmission(address_);
  wire_.write(firstReg);
  if (wire_.endTransmission(false) != 0) return false;

  const size_t received = wire_.requestFrom(address_, length, true);
  if (received != length) return false;
  for (size_t i = 0; i < length; ++i) {
    if (!wire_.available()) return false;
    data[i] = static_cast<uint8_t>(wire_.read());
  }
  return true;
}

}  // namespace openski::imu
