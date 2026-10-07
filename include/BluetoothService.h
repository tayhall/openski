#pragma once
#include <stdint.h>

namespace openski::bluetooth {
void begin();
void tick();
bool connected();
bool recording();
struct Diagnostics {
  uint32_t connections = 0;
  uint32_t disconnections = 0;
  uint64_t lastConnectedMs = 0;
  uint64_t lastDisconnectedMs = 0;
  int lastDisconnectReason = 0;
};
Diagnostics diagnostics();
bool advertisingNow();
}  // namespace openski::bluetooth
