#include "OtaService.h"

#include <Arduino.h>
#include <ArduinoOTA.h>

#include "AppConfig.h"
#include "WifiService.h"

namespace openski::ota {
namespace {
bool started = false;
}  // namespace

void tick() {
  if (!wifi::connected() || config::kOtaPassword[0] == '\0') return;

  if (!started) {
    ArduinoOTA.setHostname(config::kHostname);
    ArduinoOTA.setPassword(config::kOtaPassword);
    ArduinoOTA.onStart([]() { Serial.println("OTA update started"); });
    ArduinoOTA.onEnd([]() { Serial.println("OTA update finished"); });
    ArduinoOTA.onError([](ota_error_t error) {
      Serial.printf("OTA error: %u\n", static_cast<unsigned>(error));
    });
    ArduinoOTA.begin();
    started = true;
    Serial.printf("OTA ready: %s:3232\n", config::kHostname);
  }

  ArduinoOTA.handle();
}
}  // namespace openski::ota
