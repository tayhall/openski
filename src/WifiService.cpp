#include "WifiService.h"

#include <Arduino.h>
#include <WiFi.h>

#include "AppConfig.h"

namespace openski::wifi {
namespace {
constexpr unsigned long kRetryIntervalMs = 10000;
unsigned long lastAttemptMs = 0;
bool wasConnected = false;
bool radioEnabled = true;
}  // namespace

void begin() {
  if (config::kWifiSsid[0] == '\0') return;
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(config::kHostname);
  Serial.printf("Connecting to Wi-Fi SSID: %s\n", config::kWifiSsid);
  WiFi.begin(config::kWifiSsid, config::kWifiPassword);
  lastAttemptMs = millis();
}

bool connected() { return radioEnabled && WiFi.status() == WL_CONNECTED; }

void setEnabled(bool enabled) {
  if (enabled == radioEnabled) return;
  radioEnabled = enabled;
  wasConnected = false;
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
