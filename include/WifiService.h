#pragma once

namespace openski::wifi {
void begin();
void tick();
bool connected();
// Production mode turns the radio off to save battery; diagnostics turns it back on.
void setEnabled(bool enabled);
}  // namespace openski::wifi
