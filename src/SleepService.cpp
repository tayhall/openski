#include "SleepService.h"

#include <Arduino.h>
#include <esp_attr.h>
#include <esp_sleep.h>
#include <esp_system.h>

#include "BluetoothService.h"
#include "ImuService.h"
#include "RecorderService.h"
#include "WakeDetector.h"
#include "WifiService.h"

namespace openski::sleep {
namespace {
constexpr unsigned long kSettleBeforeSleepMs = 1000;  // lets the HTTP reply or BLE response go out first
constexpr uint64_t kPollIntervalUs = 100000;          // wake-up check period; the worst-case wake latency
// With the IMU in its 26 Hz accelerometer-only mode the baseline is taken quickly, then the board only checks
// every 2 s: a pick-up changes the tilt for as long as the boot is held, so it is still caught, at most 2 s late.
constexpr uint64_t kBaselinePollIntervalUs = 200000;
constexpr uint64_t kWatchPollIntervalUs = 2000000;
constexpr uint32_t kRecordMagic = 0x4f534c50;         // "OSLP"

struct Record {
  uint32_t magic;
  uint32_t sleptMs;
  float triggerDelta;
  uint8_t gyroTrigger;
};
RTC_NOINIT_ATTR Record record;  // survives esp_restart(); undefined after a power cycle, hence the magic

bool requested = false;
unsigned long requestedAtMs = 0;
LastWake last;
}  // namespace

Result request() {
  if (requested) return Result::kAlready;
  if (recorder::status().recording) return Result::kRecording;
  requested = true;
  requestedAtMs = millis();
  Serial.println("Sleep requested");
  return Result::kOk;
}

bool pending() { return requested; }
LastWake lastWake() { return last; }

void begin() {
  if (esp_reset_reason() == ESP_RST_SW && record.magic == kRecordMagic) {
    last.wokeFromSleep = true;
    last.gyroTrigger = record.gyroTrigger != 0;
    last.triggerDelta = record.triggerDelta;
    last.sleptMs = record.sleptMs;
    Serial.printf("Woke from sleep after %lu ms: %s moved %.3f %s\n", static_cast<unsigned long>(last.sleptMs),
                  last.gyroTrigger ? "gyro" : "accelerometer", last.triggerDelta,
                  last.gyroTrigger ? "rad/s" : "m/s^2");
  }
  record.magic = 0;  // a later reset that is not a wake must not look like one
}

void tick() {
  if (!requested || millis() - requestedAtMs < kSettleBeforeSleepMs) return;
  if (recorder::status().recording) {  // recording started in the settling second
    requested = false;
    return;
  }

  Serial.println("Going to sleep: BLE and Wi-Fi off, waiting for movement");
  Serial.flush();
  bluetooth::stop();
  wifi::setEnabled(false);

  // Nothing else may run while asleep: the loop is the sleep loop until the wake restart.
  const unsigned long startedMs = millis();
  motion::WakeDetector detector;
  uint32_t lastSamples = imu::monitor().stats().samples;
  const bool lowPower = imu::setSleepMode(true);
  Serial.println(lowPower ? "IMU in low-power accelerometer-only mode" : "IMU sleep mode unavailable: full rate");
  esp_sleep_enable_timer_wakeup(lowPower ? kBaselinePollIntervalUs : kPollIntervalUs);
  bool slowPolling = false;
  for (;;) {
    esp_light_sleep_start();
    imu::pollQuiet();
    const imu::Stats& stats = imu::monitor().stats();
    if (!imu::monitor().hasSample() || stats.samples == lastSamples) continue;
    lastSamples = stats.samples;
    if (lowPower && !slowPolling && detector.armed()) {
      slowPolling = true;
      esp_sleep_enable_timer_wakeup(kWatchPollIntervalUs);
    }
    const imu::Sample& s = imu::monitor().latest();
    if (!detector.update(s.accelMps2.x, s.accelMps2.y, s.accelMps2.z, s.gyroRadps.x, s.gyroRadps.y,
                         s.gyroRadps.z)) {
      continue;
    }
    record.magic = kRecordMagic;
    record.sleptMs = millis() - startedMs;
    record.triggerDelta = detector.triggerDelta();
    record.gyroTrigger = detector.triggeredByGyro() ? 1 : 0;
    esp_restart();
  }
}
}  // namespace openski::sleep
