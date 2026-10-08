#include "ModeService.h"

#include <Arduino.h>

#include "WifiService.h"

namespace openski::mode {
namespace {
Mode active = Mode::kDiagnostics;
}  // namespace

Mode current() { return active; }

void set(Mode mode) {
  if (mode == active) return;
  active = mode;
  wifi::setEnabled(mode == Mode::kDiagnostics);
  Serial.printf("Mode: %s\n", mode == Mode::kProduction ? "production" : "diagnostics");
}
}  // namespace openski::mode
