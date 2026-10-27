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
bool downloading();  // a flash download to the phone is in progress
// Shut BLE down for good (until the next boot): disconnects any client and stops advertising. Used by sleep.
void stop();
}  // namespace openski::bluetooth
