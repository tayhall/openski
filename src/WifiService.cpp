#include "WifiService.h"

#include <Arduino.h>
#include <WiFi.h>

#include "AppConfig.h"
#include "BatteryMath.h"
#include "BatteryService.h"

namespace openski::wifi {
namespace {
constexpr unsigned long kRetryIntervalMs = 10000;
unsigned long lastAttemptMs = 0;
bool wasConnected = false;
bool radioEnabled = true;
bool started = false;

#ifdef OPENSKI_LOW_POWER_RADIO
// Battery builds bring the radio up after BLE has settled, at reduced transmit power, and not at all
// while the cell is too low to carry Wi-Fi bursts. Transmit power goes back up if the link never forms.
constexpr unsigned long kStartDelayMs = 5000;
constexpr unsigned long kRaisePowerAfterMs = 20000;
unsigned long startNotBeforeMs = 0;
unsigned long startedAtMs = 0;
bool holding = false;
bool powerRaised = false;
#endif

void startNow() {
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(config::kHostname);
  Serial.printf("Connecting to Wi-Fi SSID: %s\n", config::kWifiSsid);
  WiFi.begin(config::kWifiSsid, config::kWifiPassword);
#ifdef OPENSKI_LOW_POWER_RADIO
  WiFi.setSleep(true);
  WiFi.setTxPower(battery::brownoutBoot() ? WIFI_POWER_5dBm : WIFI_POWER_8_5dBm);
  startedAtMs = millis();
  powerRaised = false;
#endif
  lastAttemptMs = millis();
  started = true;
}

#ifdef OPENSKI_LOW_POWER_RADIO
void maybeStart() {
  if (static_cast<long>(millis() - startNotBeforeMs) < 0) return;
  const battery::Reading cell = battery::reading();
  if (cell.valid) {
    // After a brownout the board must stay reachable for OTA, so the low-cell hold is bypassed.
    const bool hold = !battery::brownoutBoot() && battery::lowBatteryHold(cell.volts, holding);
    if (hold != holding) {
      holding = hold;
      Serial.printf(holding ? "Wi-Fi held off: cell %.2f V is low\n" : "Cell %.2f V: starting Wi-Fi\n", cell.volts);
    }
  }
  if (!holding) startNow();
}
#endif
}  // namespace

void begin() {
  if (config::kWifiSsid[0] == '\0') return;
  started = false;
#ifdef OPENSKI_LOW_POWER_RADIO
  startNotBeforeMs = millis() + kStartDelayMs;
#else
  startNow();
#endif
}

bool connected() { return radioEnabled && started && WiFi.status() == WL_CONNECTED; }

void setEnabled(bool enabled) {
  if (enabled == radioEnabled) return;
  radioEnabled = enabled;
  wasConnected = false;
  started = false;
  if (!enabled) {
    WiFi.disconnect(true, false);
    WiFi.mode(WIFI_OFF);
    Serial.println("Wi-Fi off (production mode)");
  } else {
    begin();
  }
}

void tick() {
  if (!radioEnabled || config::kWifiSsid[0] == '\0') return;
#ifdef OPENSKI_LOW_POWER_RADIO
  if (!started) {
    maybeStart();
    return;
  }
  if (!powerRaised && WiFi.status() != WL_CONNECTED && millis() - startedAtMs >= kRaisePowerAfterMs) {
    powerRaised = true;
    WiFi.setTxPower(battery::brownoutBoot() ? WIFI_POWER_11dBm : WIFI_POWER_15dBm);
    Serial.println("Wi-Fi not connected at low power: raising transmit power");
  }
#endif

  const bool isConnected = connected();
  if (isConnected != wasConnected) {
    wasConnected = isConnected;
    if (isConnected) {
      Serial.printf("Wi-Fi connected. IP: %s, RSSI: %d dBm\n",
                    WiFi.localIP().toString().c_str(), WiFi.RSSI());
    } else {
      Serial.println("Wi-Fi disconnected");
    }
  }

  const unsigned long now = millis();
  if (!isConnected && now - lastAttemptMs >= kRetryIntervalMs) {
    Serial.println("Retrying Wi-Fi connection");
    WiFi.reconnect();
    lastAttemptMs = now;
  }
}
}  // namespace openski::wifi
