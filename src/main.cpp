#include <Arduino.h>

#include "Diagnostics.h"
#include "BluetoothService.h"
#include "ImuService.h"
#include "OtaService.h"
#include "RecorderService.h"
#include "TelemetryService.h"
#include "WifiService.h"

void setup() {
  openski::diagnostics::begin();
  openski::recorder::begin();
  openski::imu::begin();
  openski::wifi::begin();
  openski::telemetry::begin();
  openski::bluetooth::begin();
}

void loop() {
  openski::imu::tick();
  openski::recorder::tick(openski::imu::monitor());
  openski::wifi::tick();
  openski::ota::tick();
  openski::telemetry::tick();
  openski::bluetooth::tick();
  openski::diagnostics::tick();
  delay(10);
}
