#pragma once

// TwoWire-backed RegisterBus for the real boards. Arduino builds only; host tests use a fake bus.
#if defined(ARDUINO)
#include <Wire.h>

#include "RegisterBus.h"

namespace openski::imu {
class WireRegisterBus final : public RegisterBus {
 public:
  // Set ownsBus false for a second device on a bus another RegisterBus already started.
  WireRegisterBus(TwoWire& wire, int sdaPin, int sclPin, uint8_t address, uint32_t frequencyHz,
                  bool ownsBus = true)
      : wire_(wire), sdaPin_(sdaPin), sclPin_(sclPin), address_(address), frequencyHz_(frequencyHz),
        ownsBus_(ownsBus) {}
  bool restart() override;
  bool writeRegister(uint8_t reg, uint8_t value) override;
  bool readRegisters(uint8_t firstReg, uint8_t* data, size_t length) override;

 private:
  TwoWire& wire_;
  int sdaPin_;
  int sclPin_;
  uint8_t address_;
  uint32_t frequencyHz_;
  bool ownsBus_;
};
}  // namespace openski::imu
#endif
