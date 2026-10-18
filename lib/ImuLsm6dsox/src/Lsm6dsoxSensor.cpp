#include "Lsm6dsoxSensor.h"

namespace openski::imu {
namespace {
constexpr float kStandardGravity = 9.80665f;
constexpr float kDegreesToRadians = 0.01745329252f;
// ST lsm6dsox_from_fs16_to_mg / from_fs2000_to_mdps / from_lsb_to_celsius.
constexpr float kAccelMgPerLsb = 0.488f;
constexpr float kGyroMdpsPerLsb = 70.0f;
constexpr float kTemperatureLsbPerC = 256.0f;
constexpr float kTemperatureOffsetC = 25.0f;

int16_t toSignedWord(uint8_t low, uint8_t high) {  // little-endian output registers
  return static_cast<int16_t>((static_cast<uint16_t>(high) << 8) | low);
}
}  // namespace

bool Lsm6dsoxSensor::begin() {
  initialized_ = false;
  if (!bus_.restart()) return false;

  uint8_t value = 0;
  if (!bus_.readRegisters(kRegWhoAmI, &value, 1) || value != kWhoAmIValue) return false;

  // Software reset, then wait for the self-clearing SW_RESET bit.
  if (!bus_.writeRegister(kRegCtrl3C, 0x01)) return false;
  bool resetDone = false;
  for (int i = 0; i < 10 && !resetDone; ++i) {
    delayMs_(2);
    resetDone = bus_.readRegisters(kRegCtrl3C, &value, 1) && (value & 0x01) == 0;
  }
  if (!resetDone) return false;

  if (!bus_.writeRegister(kRegCtrl3C, kCtrl3Value) ||
      !bus_.writeRegister(kRegCtrl4C, kCtrl4Value) ||
      !bus_.writeRegister(kRegCtrl6C, kCtrl6Value) ||
      !bus_.writeRegister(kRegCtrl8Xl, kCtrl8Value) ||
      !bus_.writeRegister(kRegCtrl1Xl, kCtrl1Value) ||  // ODR last: outputs start once configured
      !bus_.writeRegister(kRegCtrl2G, kCtrl2Value)) {
    return false;
  }

  // Refuse to report ready if the acquisition registers did not stick.
  const struct {
    uint8_t reg;
    uint8_t expected;
  } checks[] = {
      {kRegCtrl3C, kCtrl3Value}, {kRegCtrl4C, kCtrl4Value}, {kRegCtrl6C, kCtrl6Value},
      {kRegCtrl8Xl, kCtrl8Value}, {kRegCtrl1Xl, kCtrl1Value}, {kRegCtrl2G, kCtrl2Value},
  };
  for (const auto& check : checks) {
    if (!bus_.readRegisters(check.reg, &value, 1) || value != check.expected) return false;
  }
  initialized_ = true;
  return true;
}

ReadResult Lsm6dsoxSensor::read(Sample& out) {
  if (!initialized_) return ReadResult::kError;

  uint8_t status = 0;
  if (!bus_.readRegisters(kRegStatus, &status, 1)) return ReadResult::kError;
  const uint8_t ready = kStatusAccelReady | kStatusGyroReady;
  if ((status & ready) != ready) return ReadResult::kNoData;

  uint8_t data[14]{};
  if (!bus_.readRegisters(kRegOutTempL, data, sizeof(data))) return ReadResult::kError;

  const int16_t temperature = toSignedWord(data[0], data[1]);
  const int16_t gyroX = toSignedWord(data[2], data[3]);
  const int16_t gyroY = toSignedWord(data[4], data[5]);
  const int16_t gyroZ = toSignedWord(data[6], data[7]);
  const int16_t accelX = toSignedWord(data[8], data[9]);
  const int16_t accelY = toSignedWord(data[10], data[11]);
  const int16_t accelZ = toSignedWord(data[12], data[13]);

  Sample sample{};
  // Same near-rail diagnostic as the MPU-6050 driver (within 8 counts of full scale).
  const auto nearRail = [](int16_t v) { return v >= 32760 || v <= -32760; };
  if (nearRail(accelX) || nearRail(accelY) || nearRail(accelZ)) sample.qualityFlags |= kAccelNearRail;
  if (nearRail(gyroX) || nearRail(gyroY) || nearRail(gyroZ)) sample.qualityFlags |= kGyroNearRail;
  sample.timestampUs = nowUs_();
  const float accelScale = kAccelMgPerLsb / 1000.0f * kStandardGravity;
  sample.accelMps2 = {accelX * accelScale, accelY * accelScale, accelZ * accelScale};
  const float gyroScale = kGyroMdpsPerLsb / 1000.0f * kDegreesToRadians;
  sample.gyroRadps = {gyroX * gyroScale, gyroY * gyroScale, gyroZ * gyroScale};
  sample.temperatureC = temperature / kTemperatureLsbPerC + kTemperatureOffsetC;
  out = sample;
  return ReadResult::kSample;
}

}  // namespace openski::imu
