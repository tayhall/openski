#include "Diagnostics.h"

#include <Arduino.h>

#include "AppConfig.h"
#include "ImuService.h"
#include "WifiService.h"

namespace openski::diagnostics {
namespace {
constexpr unsigned long kReportIntervalMs = 30000;
unsigned long lastReportMs = 0;
}  // namespace

void begin() {
  Serial.begin(115200);
  delay(300);  // Give the USB CDC monitor a moment to attach.
  Serial.println();
  Serial.println("OpenSki boot");
  Serial.printf("Build: %s\n", config::kBuildId);
  Serial.printf("Chip: %s, revision %u\n", ESP.getChipModel(), ESP.getChipRevision());
  Serial.printf("Flash: %u bytes, heap: %u bytes\n", ESP.getFlashChipSize(), ESP.getFreeHeap());
  if (config::kWifiSsid[0] == '\0') {
    Serial.println("Wi-Fi unconfigured: copy include/wifi_config.example.h to include/wifi_config.h");
  }
  if (config::kOtaPassword[0] == '\0') {
    Serial.println("OTA unconfigured: set OPENSKI_OTA_PASSWORD in include/wifi_config.h");
  }
}

void tick() {
  const unsigned long now = millis();
  if (now - lastReportMs < kReportIntervalMs) return;
  lastReportMs = now;
  Serial.printf("Uptime: %lu s, heap: %u bytes, Wi-Fi: %s\n",
                now / 1000, ESP.getFreeHeap(), wifi::connected() ? "connected" : "disconnected");
  const imu::Stats& imuStats = imu::monitor().stats();
  Serial.printf("IMU: %s, samples: %lu, read failures: %lu\n",
                imuStats.ready ? "ready" : "offline",
                static_cast<unsigned long>(imuStats.samples),
                static_cast<unsigned long>(imuStats.readFailures));
}
}  // namespace openski::diagnostics
