#pragma once

#include <stdint.h>

namespace openski::battery {

// Cell voltage in volts from the millivolts read at the divider midpoint.
inline float cellVolts(float pinMillivolts, float dividerRatio) {
  return pinMillivolts * dividerRatio / 1000.0f;
}

// True when a new reading should be taken: always until the first one exists, then once per interval.
// Unsigned subtraction keeps this correct across the 32-bit millis() rollover.
inline bool sampleDue(bool haveReading, uint32_t nowMs, uint32_t lastSampleMs, uint32_t intervalMs) {
  return !haveReading || static_cast<uint32_t>(nowMs - lastSampleMs) >= intervalMs;
}

// Whether to hold Wi-Fi off because the cell is too low to carry radio bursts. Hysteresis stops it
// flapping around the threshold. Below kImplausibleVolts the reading is not a cell at all (no battery
// fitted, floating or mis-wired sense pin), so it never holds: Wi-Fi and OTA must stay reachable.
inline constexpr float kImplausibleVolts = 2.5f;
inline constexpr float kHoldBelowVolts = 3.6f;
inline constexpr float kReleaseAtVolts = 3.65f;
inline bool lowBatteryHold(float volts, bool currentlyHolding) {
  if (volts < kImplausibleVolts) return false;
  return currentlyHolding ? volts < kReleaseAtVolts : volts < kHoldBelowVolts;
}

}  // namespace openski::battery
