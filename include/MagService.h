#pragma once

#include <stdint.h>

namespace openski::magnetometer {
// Latest raw LIS3MDL reading. Present only on builds with OPENSKI_MAG_LIS3MDL (the left boot);
// elsewhere every call is a no-op and present() is false.
struct Status {
  bool present;          // the sensor answered and is being read
  bool hasSample;
  int16_t rawX, rawY, rawZ;
  uint32_t samples;      // samples read since boot
  uint32_t readErrors;   // failed reads since boot
};

void begin();
void tick();
Status status();
}  // namespace openski::magnetometer
