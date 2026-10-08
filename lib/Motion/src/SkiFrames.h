#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>
#include "SkiTracker.h"

// Wire encoders for the ski_v0 frames on the movement characteristic (docs/ble-protocol.md).
// Little-endian. Kept free of Arduino and NimBLE so the layout is host-testable.
namespace openski::motion {
constexpr uint8_t kSkiEventVersion = 2;
constexpr uint8_t kSkiStateVersion = 3;
constexpr size_t kSkiEventSize = 18;
constexpr size_t kSkiStateSize = 16;

namespace frame {
inline void put16(uint8_t* out, uint16_t value) {
  out[0] = static_cast<uint8_t>(value & 0xff);
  out[1] = static_cast<uint8_t>(value >> 8);
}
inline void put32(uint8_t* out, uint32_t value) {
  put16(out, static_cast<uint16_t>(value & 0xffff));
  put16(out + 2, static_cast<uint16_t>(value >> 16));
}
inline uint16_t unsigned16(float value) {
  const long scaled = std::lround(value);
  return scaled < 0 ? 0 : (scaled > 65535 ? 65535 : static_cast<uint16_t>(scaled));
}
inline int16_t signed16(float value) {
  const long scaled = std::lround(value);
  return static_cast<int16_t>(scaled > 32767 ? 32767 : (scaled < -32768 ? -32768 : scaled));
}
}  // namespace frame

// 18 bytes, one per completed half-turn.
inline void encodeSkiEvent(const SkiEvent& e, uint8_t* out) {
  for (size_t i = 0; i < kSkiEventSize; ++i) out[i] = 0;
  const uint32_t durationMs = (e.endUs - e.startUs)/1000U;
  out[0] = kSkiEventVersion;
  out[1] = (e.positive ? 0x01 : 0) | (e.outsideEnvelope ? 0x02 : 0) | (e.pitchOutside ? 0x04 : 0) |
           (e.skippedSamples ? 0x08 : 0);
  frame::put16(out + 2, static_cast<uint16_t>(e.sequence & 0xffff));
  frame::put32(out + 4, e.startUs/1000U);
  frame::put16(out + 8, durationMs > 65535U ? 65535 : static_cast<uint16_t>(durationMs));
  frame::put16(out + 10, static_cast<uint16_t>(frame::signed16(e.peakRollDegrees*100.0f)));
  frame::put16(out + 12, frame::unsigned16(e.peakRateDps*10.0f));
  frame::put16(out + 14, static_cast<uint16_t>(frame::signed16(e.pitchDegrees*100.0f)));
}

struct SkiStateFrame {
  bool zeroed = false, production = false, gap = false;
  uint16_t sequence = 0;
  uint32_t timeMs = 0;
  float rollDegrees = 0, pitchDegrees = 0, vibrationMps2 = 0, gyroDps = 0;
};

// 16 bytes, one per second.
inline void encodeSkiState(const SkiStateFrame& s, uint8_t* out) {
  out[0] = kSkiStateVersion;
  out[1] = (s.zeroed ? 0x01 : 0) | (s.production ? 0x02 : 0) | (s.gap ? 0x04 : 0);
  frame::put16(out + 2, s.sequence);
  frame::put32(out + 4, s.timeMs);
  frame::put16(out + 8, static_cast<uint16_t>(frame::signed16(s.rollDegrees*100.0f)));
  frame::put16(out + 10, static_cast<uint16_t>(frame::signed16(s.pitchDegrees*100.0f)));
  frame::put16(out + 12, frame::unsigned16(s.vibrationMps2*100.0f));
  frame::put16(out + 14, frame::unsigned16(s.gyroDps*10.0f));
}

// Recorder-control opcodes handled outside the recorder (they work without flash storage).
enum class MotionCommandKind : uint8_t { kNone, kZero, kDiagnostics, kProduction, kInvalid };
inline MotionCommandKind parseMotionCommand(const uint8_t* bytes, size_t length) {
  if (length == 0) return MotionCommandKind::kNone;
  if (bytes[0] == 0x07) return length == 1 ? MotionCommandKind::kZero : MotionCommandKind::kInvalid;
  if (bytes[0] == 0x08) {
    if (length != 2) return MotionCommandKind::kInvalid;
    if (bytes[1] == 0) return MotionCommandKind::kDiagnostics;
    if (bytes[1] == 1) return MotionCommandKind::kProduction;
    return MotionCommandKind::kInvalid;
  }
  return MotionCommandKind::kNone;
}
}  // namespace openski::motion
