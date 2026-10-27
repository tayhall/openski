#pragma once
#include <stdint.h>

namespace openski::sleep {
// Sleep until the boot is picked up. A request stops BLE and Wi-Fi, takes a still baseline, then light-sleeps in
// steps (every 2 s once the IMU is in its accelerometer-only low-power mode), reading the IMU each time. A
// movement past the wake threshold (WakeDetector) restarts the board, so BLE and Wi-Fi come back through the
// normal boot. Refused while recording or downloading flash, or while the IMU is not ready, and a sleeping boot restarts if
// the IMU goes silent, so a dead sensor can never leave it asleep with the radios off.
enum class Result : uint8_t { kOk, kRecording, kAlready, kNoSensor };
Result request();
bool pending();

// What the previous boot did, kept across the restart in RTC memory.
struct LastWake {
  bool wokeFromSleep = false;
  bool gyroTrigger = false;
  float triggerDelta = 0.0f;  // m/s^2 or rad/s, whichever tripped
  uint32_t sleptMs = 0;
  bool wokeFromLowBattery = false;  // woke from the low-cell deep sleep below
  float lowBatteryVolts = 0.0f;     // the cell voltage that caused that shutdown
};
LastWake lastWake();

// Low-cell deep sleep. tick() enters it once BatteryService reports kShutdownReadings low readings in a row:
// it stops the recording (kept in flash), the radios and the IMU's gyro, then sleeps with a 10-minute timer
// wake. lowBatteryCheck() runs early in setup() after the first battery reading: on a timer wake it goes back
// to sleep unless the cell reads at least kResumeAtVolts (charging) or not like a cell at all; a reset
// button press boots normally.
void lowBatteryCheck();

void begin();  // read the wake record left by the previous boot
void tick();   // blocks while asleep; returns normally when no sleep is pending
}  // namespace openski::sleep
