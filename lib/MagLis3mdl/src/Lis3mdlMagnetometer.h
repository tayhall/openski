#pragma once

#include <stdint.h>

#include "RegisterBus.h"

namespace openski::mag {

struct MagSample {
  int16_t rawX;
  int16_t rawY;
  int16_t rawZ;
  float gaussX;
  float gaussY;
  float gaussZ;
};

enum class MagResult { kSample, kNoData, kError };

// LIS3MDL reader (7-bit address 0x1C, 0x1E with SA0 high). Raw field only: no calibration, no heading,
// not yet wired into BLE or the recorder. Config: ultra-high performance XY and Z, 80 Hz, +-4 gauss, continuous.
class Lis3mdlMagnetometer {
 public:
  explicit Lis3mdlMagnetometer(openski::imu::RegisterBus& bus) : bus_(bus) {}

  bool begin();
  MagResult read(MagSample& out);

  static constexpr uint8_t kRegWhoAmI = 0x0F;
  static constexpr uint8_t kRegCtrl1 = 0x20;
  static constexpr uint8_t kRegCtrl2 = 0x21;
  static constexpr uint8_t kRegCtrl3 = 0x22;
  static constexpr uint8_t kRegCtrl4 = 0x23;
  static constexpr uint8_t kRegStatus = 0x27;
  static constexpr uint8_t kRegOutXL = 0x28;
  static constexpr uint8_t kWhoAmIValue = 0x3D;
  static constexpr uint8_t kCtrl1Value = 0x7C;     // OM = UHP (11), DO = 80 Hz (111), FAST_ODR off
  static constexpr uint8_t kCtrl2Value = 0x00;     // +-4 gauss
  static constexpr uint8_t kCtrl3Value = 0x00;     // continuous conversion
  static constexpr uint8_t kCtrl4Value = 0x0C;     // OMZ = UHP
  static constexpr uint8_t kAutoIncrement = 0x80;  // sub-address MSB for multi-byte I2C reads
  static constexpr uint8_t kStatusZyxReady = 0x08;
  static constexpr float kCountsPerGauss = 6842.0f;

 private:
  openski::imu::RegisterBus& bus_;
  bool initialized_ = false;
};

}  // namespace openski::mag
