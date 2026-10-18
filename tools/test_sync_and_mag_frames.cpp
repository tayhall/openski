#include "MagFrame.h"
#include "TimeSyncFrames.h"
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
  {  // Request parsing: length must be exactly 3.
    uint16_t token = 0;
    const uint8_t good[3] = {0x09, 0x34, 0x12};
    check(parseTimeSyncRequest(good, 3, token) && token == 0x1234, "token is little-endian");
    check(!parseTimeSyncRequest(good, 1, token), "length 1 rejected");
    check(!parseTimeSyncRequest(good, 2, token), "length 2 rejected");
    const uint8_t longer[4] = {0x09, 1, 2, 3};
    check(!parseTimeSyncRequest(longer, 4, token), "length 4 rejected");
    const uint8_t other[3] = {0x08, 1, 2};
    check(!parseTimeSyncRequest(other, 3, token), "other opcode rejected");
  }
  {  // Response layout with a 64-bit time above 2^32.
    uint8_t out[kControlResponseSize];
    const uint64_t t = 0x0102030405060708ULL;
    encodeTimeSyncResponse(3, 0, 0x41, 0xbeef, t, out);
    const uint8_t golden[16] = {0x03, 0x09, 0x00, 0x41, 0xef, 0xbe, 0x08, 0x07,
                                0x06, 0x05, 0x04, 0x03, 0x02, 0x01, 0x00, 0x00};
    check(std::memcmp(out, golden, 16) == 0, "time sync response matches the golden bytes");
    uint64_t back = 0;
    for (int i = 7; i >= 0; --i) back = (back << 8) | out[6 + i];
    check(back == t && back > 0xffffffffULL, "time round-trips above 2^32");
  }
  {  // An error response keeps the envelope.
    uint8_t out[kControlResponseSize];
    encodeTimeSyncResponse(3, 6, 0, 0, 0, out);
    check(out[2] == 6 && out[1] == 0x09, "result 6 envelope");
  }
  {  // Magnetometer frame golden bytes (decoded by the Kotlin side).
    MagFrame m;
    m.nearRail = true; m.gap = true; m.sequence = 258; m.timeMs = 3000000;
    m.rawX = -2; m.rawY = 1000; m.rawZ = -32768;
    uint8_t out[kMagFrameSize];
    encodeMagFrame(m, out);
    const uint8_t golden[16] = {0x05, 0x03, 0x02, 0x01, 0xc0, 0xc6, 0x2d, 0x00,
                                0xfe, 0xff, 0xe8, 0x03, 0x00, 0x80, 0x00, 0x00};
    check(std::memcmp(out, golden, 16) == 0, "mag frame matches the golden bytes");
  }
  {
    check(!magNearRail(0, 0, 0), "mid-range not near rail");
    check(magNearRail(32767, 0, 0) && magNearRail(0, -32768, 0) && magNearRail(0, 0, 27300) && magNearRail(-27300, 0, 0), "rails flagged");
    check(!magNearRail(27200, -27200, 1000), "large but legal values not flagged");
  }
  std::printf("sync and mag frame tests passed\n");
  return 0;
}
