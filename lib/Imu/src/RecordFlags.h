#pragma once

#include <stdint.h>

#include "Imu.h"

// Flash-record quality flags (recorder protocol v3). The 32-bit record timestamp is
// microseconds; its low 3 bits carry these flags, so timestamps have 8 us resolution.
namespace openski::imu {
constexpr uint8_t kRecordFlagMask = 0x07;

enum RecordFlag : uint8_t {
  kRecordAccelNearRail = 1,
  kRecordGyroNearRail = 2,
  kRecordDroppedBefore = 4,  // one or more earlier samples were dropped immediately before this one
};

inline uint32_t packRecordTimestamp(uint32_t us, uint8_t flags) {
  return (us & ~static_cast<uint32_t>(kRecordFlagMask)) | (flags & kRecordFlagMask);
}

inline uint32_t recordTimestamp(uint32_t packed) {
  return packed & ~static_cast<uint32_t>(kRecordFlagMask);
}

inline uint8_t recordFlags(uint32_t packed) {
  return static_cast<uint8_t>(packed & kRecordFlagMask);
}

// Only the rail bits are stored per record; timing quality is recoverable from timestamps.
inline uint8_t recordFlagsFromQuality(uint8_t quality) {
  return quality & (kAccelNearRail | kGyroNearRail);
}
}  // namespace openski::imu
