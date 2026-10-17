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

// True when this boot followed a brownout reset. Low-power builds use it to start in a safe profile
// (Wi-Fi only, lower transmit power) so the board stays reachable for OTA on a cell that cannot carry
// the full radio start-up. A normal power-on or software reset returns to the usual profile.
bool brownoutBoot();
}  // namespace openski::battery
