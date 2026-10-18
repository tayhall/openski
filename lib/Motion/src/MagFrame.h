#pragma once
#include <cstddef>
#include <cstdint>

#include "SkiFrames.h"

// Experimental magnetometer live frame (version 5) on the movement characteristic. Raw LIS3MDL
// counts, no calibration. Host-testable; see docs/ble-protocol.md.
namespace openski::motion {
constexpr uint8_t kMagFrameVersion = 5;
constexpr size_t kMagFrameSize = 16;

struct MagFrame {
  bool nearRail = false;  // any raw axis within a few counts of the int16 range limit
  bool gap = false;       // samples were missed since the previous frame
  uint16_t sequence = 0;
  uint32_t timeMs = 0;
  int16_t rawX = 0, rawY = 0, rawZ = 0;
};

// +-4 gauss at 6842 counts/gauss is +-27368 counts; flag any axis within 100 counts of that limit.
constexpr int kMagRangeCounts = 27368;
constexpr int kMagRailMargin = 100;
inline bool magNearRail(int16_t x, int16_t y, int16_t z) {
  auto bad = [](int v) { return v >= kMagRangeCounts - kMagRailMargin || v <= -(kMagRangeCounts - kMagRailMargin); };
  return bad(x) || bad(y) || bad(z);
}

inline void encodeMagFrame(const MagFrame& m, uint8_t* out) {
  out[0] = kMagFrameVersion;
  out[1] = (m.nearRail ? 0x01 : 0) | (m.gap ? 0x02 : 0);
  frame::put16(out + 2, m.sequence);
  frame::put32(out + 4, m.timeMs);
  frame::put16(out + 8, static_cast<uint16_t>(m.rawX));
  frame::put16(out + 10, static_cast<uint16_t>(m.rawY));
  frame::put16(out + 12, static_cast<uint16_t>(m.rawZ));
  out[14] = 0;
  out[15] = 0;
}
}  // namespace openski::motion
