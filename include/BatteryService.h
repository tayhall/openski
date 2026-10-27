#pragma once

#include <stdint.h>

namespace openski::battery {
struct Reading {
  bool available = false;  // false when no battery sense pin is configured for this build
  bool valid = false;      // a reading has been taken since boot
  float volts = 0.0f;      // cell voltage after the divider ratio
  float pinMillivolts = 0.0f;
  uint32_t ageMs = 0;      // time since the reading was taken
};

// Takes one reading in begin(), then one per minute (mean of 16 conversions). The read takes a few tens
// of microseconds, so it does not disturb the loop. Reports unavailable when the build has no sense pin.
void begin();
void tick();
Reading reading();

// True after kShutdownReadings consecutive readings below the shutdown threshold (BatteryMath.h). The sleep
// service acts on it by entering deep sleep. Always false when the build has no sense pin.
bool lowCellShutdownDue();

// Breaks the divider so it draws nothing (builds with OPENSKI_BATTERY_DIVIDER_ENABLE_PIN; otherwise a no-op).
// begin() closes it again, so every deep-sleep wake check switches the divider on, settles, reads and, if the
// board goes back to sleep, switches it off.
void dividerOff();

// True when this boot followed a brownout reset. Low-power builds use it to start in a safe profile
// (Wi-Fi only, lower transmit power) so the board stays reachable for OTA on a cell that cannot carry
// the full radio start-up. A normal power-on or software reset returns to the usual profile.
bool brownoutBoot();
}  // namespace openski::battery
