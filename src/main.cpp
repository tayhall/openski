#include <Arduino.h>

#include "Diagnostics.h"
#include "BluetoothService.h"
#include "ImuService.h"
#include "MotionService.h"
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
  openski::motion::tick();
  openski::recorder::tick(openski::imu::monitor());
  openski::wifi::tick();
  openski::ota::tick();
  openski::telemetry::tick();
  openski::bluetooth::tick();
  openski::diagnostics::tick();
  // Poll fast enough to catch every 100 Hz IMU sample. A 10 ms delay plus work caught only about 83 per second.
  delay(2);
}
