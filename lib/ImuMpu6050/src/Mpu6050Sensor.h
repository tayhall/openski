#pragma once

#include <Wire.h>

#include "Imu.h"

namespace openski::imu {

// MPU-6050 I2C driver. Samples are normalized to SI units so consumers do not
// depend on this chip's register scales.
class Mpu6050Sensor final : public ImuSensor {
 public:
  Mpu6050Sensor(TwoWire& wire, int sdaPin, int sclPin, uint8_t address = 0x68,
                uint32_t busFrequencyHz = 400000);

  bool begin() override;
  ReadResult read(Sample& out) override;
  const char* name() const override { return "MPU-6050"; }

 private:
  bool writeRegister(uint8_t reg, uint8_t value);
  bool readRegisters(uint8_t firstReg, uint8_t* data, size_t length);
  bool readRegister(uint8_t reg, uint8_t& value);

  TwoWire& wire_;
  int sdaPin_;
  int sclPin_;
  uint8_t address_;
  uint32_t busFrequencyHz_;
  bool initialized_ = false;
};

}  // namespace openski::imu
