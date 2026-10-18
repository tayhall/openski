#include "Lis3mdlMagnetometer.h"

namespace openski::mag {
bool Lis3mdlMagnetometer::begin() {
  initialized_ = false;
  if (!bus_.restart()) return false;
  uint8_t value = 0;
  if (!bus_.readRegisters(kRegWhoAmI, &value, 1) || value != kWhoAmIValue) return false;
  if (!bus_.writeRegister(kRegCtrl1, kCtrl1Value) || !bus_.writeRegister(kRegCtrl2, kCtrl2Value) ||
      !bus_.writeRegister(kRegCtrl4, kCtrl4Value) || !bus_.writeRegister(kRegCtrl3, kCtrl3Value)) {
    return false;
  }
  const struct {
    uint8_t reg;
    uint8_t expected;
  } checks[] = {{kRegCtrl1, kCtrl1Value}, {kRegCtrl2, kCtrl2Value},
                {kRegCtrl3, kCtrl3Value}, {kRegCtrl4, kCtrl4Value}};
  for (const auto& check : checks) {
    if (!bus_.readRegisters(check.reg, &value, 1) || value != check.expected) return false;
  }
  initialized_ = true;
  return true;
}

MagResult Lis3mdlMagnetometer::read(MagSample& out) {
  if (!initialized_) return MagResult::kError;
  uint8_t status = 0;
  if (!bus_.readRegisters(kRegStatus, &status, 1)) return MagResult::kError;
  if ((status & kStatusZyxReady) == 0) return MagResult::kNoData;
  uint8_t data[6]{};
  if (!bus_.readRegisters(kRegOutXL | kAutoIncrement, data, sizeof(data))) return MagResult::kError;
  MagSample s{};
  s.rawX = static_cast<int16_t>((static_cast<uint16_t>(data[1]) << 8) | data[0]);
  s.rawY = static_cast<int16_t>((static_cast<uint16_t>(data[3]) << 8) | data[2]);
  s.rawZ = static_cast<int16_t>((static_cast<uint16_t>(data[5]) << 8) | data[4]);
  s.gaussX = s.rawX / kCountsPerGauss;
  s.gaussY = s.rawY / kCountsPerGauss;
  s.gaussZ = s.rawZ / kCountsPerGauss;
  out = s;
  return MagResult::kSample;
}
}  // namespace openski::mag
