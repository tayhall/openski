#pragma once
#include <stdint.h>

namespace openski::mode {
// Diagnostics (default every boot): Wi-Fi, HTTP, OTA and the raw 50 Hz stream are on.
// Production: Wi-Fi and the raw stream are off; half-turn events and the state frame still flow.
enum class Mode : uint8_t { kDiagnostics = 0, kProduction = 1 };
Mode current();
inline bool production() { return current() == Mode::kProduction; }
void set(Mode mode);
}  // namespace openski::mode
