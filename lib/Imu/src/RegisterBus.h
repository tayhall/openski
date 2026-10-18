#pragma once

#include <stddef.h>
#include <stdint.h>

namespace openski::imu {
// Minimal register-oriented I2C access, so chip drivers can be tested on the host with a fake device.
// The bus object owns the 7-bit device address. Implemented over TwoWire by WireRegisterBus.
class RegisterBus {
 public:
  virtual ~RegisterBus() = default;
  // (Re)initialise the physical bus; called at the start of every driver begin().
  virtual bool restart() { return true; }
  virtual bool writeRegister(uint8_t reg, uint8_t value) = 0;
  // Reads `length` consecutive bytes starting at `firstReg`. Returns false on any bus error or short read.
  virtual bool readRegisters(uint8_t firstReg, uint8_t* data, size_t length) = 0;
};
}  // namespace openski::imu
