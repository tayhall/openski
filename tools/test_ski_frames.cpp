#include "SkiFrames.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>

using namespace openski::motion;

namespace {
void check(bool ok, const char* what) {
  if (!ok) { std::printf("FAILED: %s\n", what); std::exit(1); }
}
}  // namespace

int main() {
  {  // The same bytes are decoded by SkiEventProtocolTest.kt.
    SkiEvent e;
    e.sequence = 513; e.startUs = 4294966000U; e.endUs = 4294966000U + 1830000U;  // wraps the 32-bit clock
    e.positive = false; e.skippedSamples = true;
    e.peakRollDegrees = -32.5f; e.peakRateDps = 123.4f; e.pitchDegrees = 18.25f;
    uint8_t out[kSkiEventSize];
    encodeSkiEvent(e, out);
    const uint8_t golden[kSkiEventSize] = {0x02, 0x08, 0x01, 0x02, 0x36, 0x89, 0x41, 0x00, 0x26,
                                           0x07, 0x4e, 0xf3, 0xd2, 0x04, 0x21, 0x07, 0x00, 0x00};
    check(std::memcmp(out, golden, kSkiEventSize) == 0, "event frame matches the golden bytes");
  }
  {  // Saturation: long duration, huge rate and out-of-range angles clamp instead of wrapping.
    SkiEvent e;
    e.sequence = 70000; e.startUs = 0; e.endUs = 90000000U;
    e.positive = true; e.outsideEnvelope = true; e.pitchOutside = true;
    e.peakRollDegrees = 500; e.peakRateDps = 9000; e.pitchDegrees = -500;
    uint8_t out[kSkiEventSize];
    encodeSkiEvent(e, out);
    check(out[1] == 0x07, "flags");
    check(out[2] == (70000 & 0xff) && out[3] == ((70000 >> 8) & 0xff), "sequence wraps modulo 65536");
    check(out[8] == 0xff && out[9] == 0xff, "duration saturates");
    check(out[10] == 0xff && out[11] == 0x7f, "roll saturates at +327.67");
    check(out[12] == 0xff && out[13] == 0xff, "rate saturates");
    check(out[14] == 0x00 && out[15] == 0x80, "pitch saturates at -327.68");
  }
  {  // The same bytes are decoded by SkiEventProtocolTest.kt.
    SkiStateFrame s;
    s.zeroed = true; s.production = true; s.sequence = 258; s.timeMs = 3000000;
    s.rollDegrees = 12.34f; s.pitchDegrees = -3.21f; s.vibrationMps2 = 1.5f; s.gyroDps = 45.6f;
    uint8_t out[kSkiStateSize];
    encodeSkiState(s, out);
    const uint8_t golden[kSkiStateSize] = {0x03, 0x03, 0x02, 0x01, 0xc0, 0xc6, 0x2d, 0x00,
                                           0xd2, 0x04, 0xbf, 0xfe, 0x96, 0x00, 0xc8, 0x01};
    check(std::memcmp(out, golden, kSkiStateSize) == 0, "state frame matches the golden bytes");
  }
  {  // Negative vibration or gyro (never expected) clamps to zero rather than wrapping.
    SkiStateFrame s;
    s.vibrationMps2 = -1; s.gyroDps = -1;
    uint8_t out[kSkiStateSize];
    encodeSkiState(s, out);
    check(out[1] == 0 && out[12] == 0 && out[13] == 0 && out[14] == 0 && out[15] == 0, "no wrap on negatives");
  }
  {  // Zero and mode commands: exact lengths and arguments only; other opcodes belong to the recorder.
    const uint8_t zero[] = {0x07}, zeroLong[] = {0x07, 0x00};
    const uint8_t diagnostics[] = {0x08, 0x00}, production[] = {0x08, 0x01};
    const uint8_t badMode[] = {0x08, 0x02}, shortMode[] = {0x08}, longMode[] = {0x08, 0x01, 0x00};
    const uint8_t start[] = {0x01}, download[] = {0x05, 0, 0, 0, 0}, unknown[] = {0x09};
    check(parseMotionCommand(zero, 1) == MotionCommandKind::kZero, "zero");
    check(parseMotionCommand(zeroLong, 2) == MotionCommandKind::kInvalid, "zero with a payload");
    check(parseMotionCommand(diagnostics, 2) == MotionCommandKind::kDiagnostics, "diagnostics");
    check(parseMotionCommand(production, 2) == MotionCommandKind::kProduction, "production");
    check(parseMotionCommand(badMode, 2) == MotionCommandKind::kInvalid, "mode 2");
    check(parseMotionCommand(shortMode, 1) == MotionCommandKind::kInvalid, "mode without argument");
    check(parseMotionCommand(longMode, 3) == MotionCommandKind::kInvalid, "mode with extra bytes");
    check(parseMotionCommand(start, 1) == MotionCommandKind::kNone, "recorder start is not ours");
    check(parseMotionCommand(download, 5) == MotionCommandKind::kNone, "recorder download is not ours");
    check(parseMotionCommand(unknown, 1) == MotionCommandKind::kNone, "unknown opcode is left to the recorder");
    check(parseMotionCommand(zero, 0) == MotionCommandKind::kNone, "empty write");
  }
  std::puts("ski frame tests passed");
}
