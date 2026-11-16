#include "BatteryService.h"

#include <Arduino.h>
#include <esp_system.h>

#include "AppConfig.h"
#include "BatteryMath.h"

namespace openski::battery {
namespace {
constexpr uint32_t kIntervalMs = 60000;
constexpr int kSamplesPerReading = 16;

bool haveReading = false;
uint32_t lastSampleMs = 0;
float lastVolts = 0.0f;
float lastPinMv = 0.0f;
int lowCount = 0;

constexpr uint32_t kDividerSettleMs = 50;  // 100 kohm into 100 nF settles in about 10 ms per time constant

void dividerOn() {
  if (config::kBatteryDividerEnablePin < 0) return;
  const uint8_t pin = static_cast<uint8_t>(config::kBatteryDividerEnablePin);
  pinMode(pin, OUTPUT);
  digitalWrite(pin, LOW);
  delay(kDividerSettleMs);
}

void sample() {
  const uint8_t pin = static_cast<uint8_t>(config::kBatteryAdcPin);
  analogSetPinAttenuation(pin, ADC_11db);
  analogReadMilliVolts(pin);  // Discard the first conversion after channel setup.
  uint32_t sum = 0;
  for (int i = 0; i < kSamplesPerReading; ++i) sum += analogReadMilliVolts(pin);
  lastPinMv = static_cast<float>(sum) / kSamplesPerReading;
  lastVolts = cellVolts(lastPinMv, config::kBatteryDividerRatio);
  lastSampleMs = millis();
  haveReading = true;
  lowCount = lowCellCount(lastVolts, lowCount);
  Serial.printf("Battery %.3f V (pin %.0f mV, ratio %.2f)\n", lastVolts, lastPinMv, config::kBatteryDividerRatio);
}
}  // namespace

bool brownoutBoot() { return esp_reset_reason() == ESP_RST_BROWNOUT; }

void begin() {
  if (config::kBatteryAdcPin < 0) return;
  dividerOn();  // stays on while the board runs: a minute-by-minute reading must not stall the 100 Hz loop
  sample();
}

void dividerOff() {
  if (config::kBatteryDividerEnablePin < 0) return;
  pinMode(static_cast<uint8_t>(config::kBatteryDividerEnablePin), INPUT);
}

void tick() {
  if (config::kBatteryAdcPin < 0) return;
  if (sampleDue(haveReading, millis(), lastSampleMs, kIntervalMs)) sample();
}

bool lowCellShutdownDue() { return shutdownDue(lowCount); }

Reading reading() {
  Reading r;
  r.available = config::kBatteryAdcPin >= 0;
  r.valid = haveReading;
  if (haveReading) {
    r.volts = lastVolts;
    r.pinMillivolts = lastPinMv;
    r.ageMs = millis() - lastSampleMs;
  }
  return r;
}
}  // namespace openski::battery
