#pragma once

#include "ImuMonitor.h"

namespace openski::imu {
void begin();
void tick();
const ImuMonitor& monitor();
// Sleeping boots: accelerometer-only low-power acquisition, polled without the serial log. See ImuSensor.
bool setSleepMode(bool on);
bool powerDown();  // both sensors off; only before a deep sleep that restarts the board on wake
void pollQuiet();
}  // namespace openski::imu
