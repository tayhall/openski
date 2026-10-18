#pragma once
#include <cstddef>
#include <cstdint>

// Recorder-control opcode 09 "time sync" (docs/ble-protocol.md, docs/clock-sync-design.md).
// Free of Arduino and NimBLE so the layout is host-testable. Little-endian.
namespace openski::motion {
constexpr uint8_t kTimeSyncOpcode = 0x09;
constexpr size_t kTimeSyncRequestSize = 3;
constexpr size_t kControlResponseSize = 16;

// Request: 09 token_lo token_hi. Any other length is invalid (the caller answers result 6).
inline bool parseTimeSyncRequest(const uint8_t* bytes, size_t length, uint16_t& token) {
  if (length != kTimeSyncRequestSize || bytes[0] != kTimeSyncOpcode) return false;
  token = static_cast<uint16_t>(bytes[1] | (static_cast<uint16_t>(bytes[2]) << 8));
  return true;
}

// Response: version, opcode, result, flags, token (2), sensor time in microseconds (8), reserved (2).
inline void encodeTimeSyncResponse(uint8_t version, uint8_t result, uint8_t flags, uint16_t token,
                                   uint64_t sensorTimeUs, uint8_t* out) {
  out[0] = version;
  out[1] = kTimeSyncOpcode;
  out[2] = result;
  out[3] = flags;
  out[4] = static_cast<uint8_t>(token & 0xff);
  out[5] = static_cast<uint8_t>(token >> 8);
  for (int i = 0; i < 8; ++i) out[6 + i] = static_cast<uint8_t>((sensorTimeUs >> (8 * i)) & 0xff);
  out[14] = 0;
  out[15] = 0;
}
}  // namespace openski::motion
