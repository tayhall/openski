#include <Arduino.h>

#include "Diagnostics.h"
#include "BatteryService.h"
#include "BluetoothService.h"
#include "ImuService.h"
#include "MagService.h"
#include "MotionService.h"
#include "OtaService.h"
#include "RecorderService.h"
#include "SleepService.h"
#include "TelemetryService.h"
#include "WifiService.h"

void setup() {
#ifdef OPENSKI_LOW_POWER_RADIO
  setCpuFrequencyMhz(80);  // Lower supply current for small cells; still ample for the 100 Hz loop.
#endif
  openski::diagnostics::begin();
  openski::sleep::begin();
  openski::battery::begin();  // before the radio starts, so the first reading is not under Wi-Fi load
  openski::recorder::begin();
  openski::imu::begin();
  openski::magnetometer::begin();  // after the IMU, which starts the shared I2C bus
  openski::wifi::begin();
  openski::telemetry::begin();
#ifdef OPENSKI_LOW_POWER_RADIO
  if (openski::battery::brownoutBoot()) {
    Serial.println("Safe boot after brownout: BLE off, Wi-Fi only at reduced power");
  } else {
    openski::bluetooth::begin();
  }
#else
  openski::bluetooth::begin();
#endif
}

void loop() {
  openski::imu::tick();
  openski::magnetometer::tick();
  openski::motion::tick();
  openski::recorder::tick(openski::imu::monitor());
  openski::wifi::tick();
  openski::ota::tick();
  openski::telemetry::tick();
  openski::bluetooth::tick();
  openski::diagnostics::tick();
  openski::battery::tick();
  openski::sleep::tick();  // blocks while asleep
  // Poll fast enough to catch every 100 Hz IMU sample. A 10 ms delay plus work caught only about 83 per second.
  delay(2);
}
