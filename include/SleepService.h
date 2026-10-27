#pragma once
#include <stdint.h>

namespace openski::sleep {
// Sleep until the boot is picked up. A request stops BLE and Wi-Fi, takes a still baseline, then light-sleeps in
// steps (every 2 s once the IMU is in its accelerometer-only low-power mode), reading the IMU each time. A
// movement past the wake threshold (WakeDetector) restarts the board, so BLE and Wi-Fi come back through the
// normal boot. Refused while recording to flash.
enum class Result : uint8_t { kOk, kRecording, kAlready };
Result request();
bool pending();

// What the previous boot did, kept across the restart in RTC memory.
struct LastWake {
  bool wokeFromSleep = false;
  bool gyroTrigger = false;
  float triggerDelta = 0.0f;  // m/s^2 or rad/s, whichever tripped
  uint32_t sleptMs = 0;
};
LastWake lastWake();

void begin();  // read the wake record left by the previous boot
void tick();   // blocks while asleep; returns normally when no sleep is pending
}  // namespace openski::sleep
