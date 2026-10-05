#include <Arduino.h>

#include "Diagnostics.h"
#include "OtaService.h"
#include "WifiService.h"

void setup() {
  openski::diagnostics::begin();
  openski::wifi::begin();
}

void loop() {
  openski::wifi::tick();
  openski::ota::tick();
  openski::diagnostics::tick();
  delay(10);
}
