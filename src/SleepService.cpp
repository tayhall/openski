#include "SleepService.h"

#include <Arduino.h>
#include <driver/rtc_io.h>
#include <esp_attr.h>
#include <esp_sleep.h>
#include <esp_system.h>

#include "AppConfig.h"
#include "BatteryMath.h"
#include "BatteryService.h"
#include "BluetoothService.h"
#include "ImuService.h"
#include "RecorderService.h"
#include "StillnessTimer.h"
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
constexpr unsigned long kSensorSilentMs = 10000;  // no new IMU sample for this long while asleep: restart
constexpr uint32_t kRecordMagic = 0x4f534c50;         // "OSLP"

struct Record {
  uint32_t magic;
  uint32_t sleptMs;
  float triggerDelta;
  uint8_t gyroTrigger;
};
RTC_NOINIT_ATTR Record record;  // survives esp_restart(); undefined after a power cycle, hence the magic

// Low-cell deep sleep: the board wakes on a timer, reads the cell and goes straight back to sleep unless it
// is charged enough to run. RTC memory survives deep sleep, so the cell voltage that caused the shutdown
// is still there for the report after the wake.
constexpr uint64_t kLowBatteryWakeIntervalUs = 10ULL * 60ULL * 1000000ULL;
constexpr uint32_t kLowBatteryMagic = 0x4f4c4f57;  // "OLOW"
struct LowBatteryRecord {
  uint32_t magic;
  float shutdownVolts;
};
RTC_NOINIT_ATTR LowBatteryRecord lowBatteryRecord;

// Movement sleep by deep sleep, for builds with the IMU interrupt wired (config::kImuInterruptPin). The IMU's
// own wake-up detector raises the pin, which wakes the ESP32 (ext1, any high) and restarts it. A long timer wake
// is the dead-man check: with nothing polling the IMU, a sensor that has failed would otherwise leave the board
// asleep with its radios off. On a timer wake the board checks the IMU answers, re-arms and sleeps again
// without starting the radios; if the IMU is gone it boots normally so the fault is visible.
constexpr uint64_t kMotionDeadManIntervalUs = 30ULL * 60ULL * 1000000ULL;
constexpr uint32_t kMotionMagic = 0x4f4d4f54;  // "OMOT"
constexpr int kPinSettleTries = 10;            // a boot still moving, or a stuck pin, holds the line high
constexpr unsigned long kPinSettleStepMs = 500;
RTC_NOINIT_ATTR uint32_t motionMagic;

// Arms the IMU and deep-sleeps. Returns only if that could not be done, with the IMU left in an undefined
// state: the caller must restore it with imu::begin().
bool deepSleepOnMotion() {
  if (config::kImuInterruptPin < 0) return false;
  if (!imu::armWakeOnMotion(motion::WakeDetector::kAccelThresholdMps2, config::kImuInterruptUsesInt2)) return false;
  const gpio_num_t pin = static_cast<gpio_num_t>(config::kImuInterruptPin);
  pinMode(pin, INPUT_PULLDOWN);
  bool low = false;
  for (int i = 0; i < kPinSettleTries && !low; ++i) {
    low = digitalRead(pin) == LOW;
    if (!low) {
      imu::clearWakeEvent();
      delay(kPinSettleStepMs);
    }
  }
  if (!low) return false;
  motionMagic = kMotionMagic;
  esp_sleep_enable_ext1_wakeup(1ULL << pin, ESP_EXT1_WAKEUP_ANY_HIGH);
  esp_sleep_enable_timer_wakeup(kMotionDeadManIntervalUs);
  rtc_gpio_pullup_dis(pin);
  rtc_gpio_pulldown_en(pin);  // a missing wire must not read as movement
  Serial.flush();
  esp_deep_sleep_start();
  return true;  // not reached
}

[[noreturn]] void deepSleepLowBattery();

// The low-cell shutdown, for a timer wake of the movement sleep that finds the cell flat.
[[noreturn]] void lowBatteryShutdown(float volts) {
  imu::powerDown();
  lowBatteryRecord.magic = kLowBatteryMagic;
  lowBatteryRecord.shutdownVolts = volts;
  deepSleepLowBattery();
}

[[noreturn]] void deepSleepLowBattery() {
  Serial.flush();
  battery::dividerOff();  // switched on again by battery::begin() on the next wake
  esp_sleep_enable_timer_wakeup(kLowBatteryWakeIntervalUs);
  esp_deep_sleep_start();
}

// A boot with the IMU interrupt wired puts itself to sleep once it has lain still this long, so a boot left on
// a bench or in a bag does not run its radios flat. Builds without the pin never sleep on their own.
#ifndef OPENSKI_IDLE_SLEEP_MS
#define OPENSKI_IDLE_SLEEP_MS (10UL * 60UL * 1000UL)
#endif
constexpr uint32_t kIdleSleepMs = OPENSKI_IDLE_SLEEP_MS;

bool requested = false;
unsigned long requestedAtMs = 0;
LastWake last;
motion::StillnessTimer stillness(kIdleSleepMs);
uint32_t stillnessSamples = 0;

// Feeds new IMU samples to the stillness timer and asks for sleep when the period is up. Recording or a flash
// download restarts the period, so sleep never starts straight after one ends.
void autoSleepTick() {
  const uint32_t now = millis();
  if (requested || recorder::status().recording || bluetooth::downloading()) {
    stillness.restart(now);
    return;
  }
  const imu::Stats& stats = imu::monitor().stats();
  if (stats.ready && imu::monitor().hasSample() && stats.samples != stillnessSamples) {
    stillnessSamples = stats.samples;
    const imu::Sample& s = imu::monitor().latest();
    stillness.update(now, s.accelMps2.x, s.accelMps2.y, s.accelMps2.z, s.gyroRadps.x, s.gyroRadps.y,
                     s.gyroRadps.z);
  }
  if (stillness.idle(now) && request() == Result::kOk) Serial.println("Still for the idle period: sleeping");
}
}  // namespace

Result request() {
  if (requested) return Result::kAlready;
  if (recorder::status().recording) return Result::kRecording;
  if (bluetooth::downloading()) return Result::kRecording;  // a flash download is under way: not now
  if (!imu::monitor().stats().ready) return Result::kNoSensor;  // nothing could wake it
  requested = true;
  requestedAtMs = millis();
  Serial.println("Sleep requested");
  return Result::kOk;
}

bool pending() { return requested; }
LastWake lastWake() { return last; }

void begin() {
  stillness.restart(millis());
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

void motionWakeCheck() {
  if (esp_reset_reason() != ESP_RST_DEEPSLEEP || motionMagic != kMotionMagic) return;
  const bool deadManCheck = esp_sleep_get_wakeup_cause() == ESP_SLEEP_WAKEUP_TIMER;
  motionMagic = 0;
  if (deadManCheck) {
    const battery::Reading cell = battery::reading();
    if (cell.valid && battery::lowCellAtRest(cell.volts)) {
      Serial.printf("Sleep check: cell at %.3f V: low-battery deep sleep, plug in to charge\n", cell.volts);
      lowBatteryShutdown(cell.volts);
    }
    if (imu::monitor().stats().ready) {
      Serial.println("Sleep check: IMU answers, back to sleep");
      deepSleepOnMotion();  // returns only if it could not re-arm: boot normally below
    }
    Serial.println("Sleep check: IMU not responding or not re-armed, booting normally");
    return;
  }
  last.wokeFromSleep = true;  // the IMU raised its interrupt; the size of the movement and the time slept are not kept
  last.gyroTrigger = false;
  last.triggerDelta = 0.0f;
  last.sleptMs = 0;
  Serial.println("Woke from sleep: the IMU detected movement");
}

void lowBatteryCheck() {
  if (esp_reset_reason() != ESP_RST_DEEPSLEEP || lowBatteryRecord.magic != kLowBatteryMagic) return;
  const battery::Reading cell = battery::reading();
  if (cell.valid && !battery::resumeFromShutdown(cell.volts)) {
    Serial.printf("Cell still low (%.3f V, resume at %.2f V): back to deep sleep\n", cell.volts,
                  battery::kResumeAtVolts);
    deepSleepLowBattery();
  }
  last.wokeFromLowBattery = true;
  last.lowBatteryVolts = lowBatteryRecord.shutdownVolts;
  lowBatteryRecord.magic = 0;
  Serial.printf("Woke from low-battery shutdown (shut down at %.3f V, now %.3f V)\n", last.lowBatteryVolts,
                cell.volts);
}

void tick() {
  if (battery::lowCellShutdownDue()) {
    const float volts = battery::reading().volts;
    Serial.printf("Cell at %.3f V: low-battery deep sleep, plug in to charge\n", volts);
    if (recorder::status().recording) recorder::stop();  // keeps what was recorded in flash
    bluetooth::stop();
    wifi::setEnabled(false);
    lowBatteryShutdown(volts);  // nothing can wake the board but the timer, so the IMU is powered down
  }
  if (config::kImuInterruptPin >= 0) autoSleepTick();
  if (!requested || millis() - requestedAtMs < kSettleBeforeSleepMs) return;
  if (recorder::status().recording || !imu::monitor().stats().ready) {  // changed in the settling second
    requested = false;
    return;
  }

  Serial.println("Going to sleep: BLE and Wi-Fi off, waiting for movement");
  Serial.flush();
  bluetooth::stop();
  wifi::setEnabled(false);

  if (deepSleepOnMotion()) return;  // never returns on success: the IMU wakes the board with a restart
  if (config::kImuInterruptPin >= 0) {
    Serial.println("Wake-on-motion could not be armed: light sleep instead");
    imu::begin();  // restores normal acquisition after the failed arm
    if (!imu::monitor().stats().ready) {
      Serial.println("IMU not responding: restarting");
      Serial.flush();
      esp_restart();  // nothing could wake it, and the radios are off: the normal boot brings everything back
    }
  }

  // Nothing else may run while asleep: the loop is the sleep loop until the wake restart.
  const unsigned long startedMs = millis();
  motion::WakeDetector detector;
  uint32_t lastSamples = imu::monitor().stats().samples;
  const bool lowPower = imu::setSleepMode(true);
  Serial.println(lowPower ? "IMU in low-power accelerometer-only mode" : "IMU sleep mode unavailable: full rate");
  esp_sleep_enable_timer_wakeup(lowPower ? kBaselinePollIntervalUs : kPollIntervalUs);
  bool slowPolling = false;
  unsigned long lastNewSampleMs = millis();
  for (;;) {
    esp_light_sleep_start();
    imu::pollQuiet();
    const imu::Stats& stats = imu::monitor().stats();
    if (!imu::monitor().hasSample() || stats.samples == lastSamples) {
      if (millis() - lastNewSampleMs > kSensorSilentMs) {
        Serial.println("IMU silent while asleep: restarting");
        Serial.flush();
        esp_restart();  // not recorded as a wake; the normal boot brings the radios and the sensor back
      }
      continue;
    }
    lastSamples = stats.samples;
    lastNewSampleMs = millis();
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
