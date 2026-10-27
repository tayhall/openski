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

// Low-cell deep sleep. The board shuts down after kShutdownReadings consecutive readings below
// kShutdownBelowVolts (a sag under a radio burst is one reading, not three). Implausible readings never
// count. While shut down it wakes on a timer and resumes only once the cell reads kResumeAtVolts or more,
// which in practice means it is charging, or the sense pin no longer reads a cell at all (so a board with
// no cell fitted is never stuck asleep). The thresholds are placeholders until a discharge curve is logged.
inline constexpr float kShutdownBelowVolts = 3.3f;
inline constexpr float kResumeAtVolts = 3.8f;
inline constexpr int kShutdownReadings = 3;

// Consecutive low readings including this one.
inline int lowCellCount(float volts, int previousCount) {
  return (volts >= kImplausibleVolts && volts < kShutdownBelowVolts) ? previousCount + 1 : 0;
}
inline bool shutdownDue(int count) { return count >= kShutdownReadings; }
inline bool resumeFromShutdown(float volts) { return volts >= kResumeAtVolts || volts < kImplausibleVolts; }

}  // namespace openski::battery
