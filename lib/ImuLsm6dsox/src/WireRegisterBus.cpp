#include "WireRegisterBus.h"

#if defined(ARDUINO)
#include <Arduino.h>

namespace openski::imu {
namespace {
// Same stuck-bus recovery as the MPU-6050 driver: clock SCL until the device releases SDA.
void recoverBus(int sdaPin, int sclPin) {
  pinMode(sdaPin, INPUT_PULLUP);
  pinMode(sclPin, OUTPUT);
  digitalWrite(sclPin, HIGH);
  delayMicroseconds(10);
  for (int pulse = 0; pulse < 9 && digitalRead(sdaPin) == LOW; ++pulse) {
    digitalWrite(sclPin, LOW);
    delayMicroseconds(10);
    digitalWrite(sclPin, HIGH);
    delayMicroseconds(10);
  }
}
}  // namespace

bool WireRegisterBus::restart() {
  if (!ownsBus_) return true;
  wire_.end();
  recoverBus(sdaPin_, sclPin_);
  return wire_.begin(sdaPin_, sclPin_, frequencyHz_);
}

bool WireRegisterBus::writeRegister(uint8_t reg, uint8_t value) {
  wire_.beginTransmission(address_);
  wire_.write(reg);
  wire_.write(value);
  return wire_.endTransmission() == 0;
}

bool WireRegisterBus::readRegisters(uint8_t firstReg, uint8_t* data, size_t length) {
  wire_.beginTransmission(address_);
  wire_.write(firstReg);
  if (wire_.endTransmission(false) != 0) return false;
  if (wire_.requestFrom(address_, length, true) != length) return false;
  for (size_t i = 0; i < length; ++i) {
    if (!wire_.available()) return false;
    data[i] = static_cast<uint8_t>(wire_.read());
  }
  return true;
}
}  // namespace openski::imu
#endif
