#include <Arduino.h>

#include "Diagnostics.h"
#include "ImuService.h"
#include "OtaService.h"
#include "WifiService.h"

void setup() {
  openski::diagnostics::begin();
  openski::imu::begin();
  openski::wifi::begin();
}

void loop() {
  openski::imu::tick();
  openski::wifi::tick();
  openski::ota::tick();
  openski::diagnostics::tick();
  delay(10);
}
