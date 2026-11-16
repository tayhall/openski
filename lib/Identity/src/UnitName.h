#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace openski::identity {

// Builds the unit's network name: "<prefix>" or "<prefix>-<hex mac[4]><hex mac[5]>" (lowercase, zero padded).
// Always NUL-terminates when outSize > 0. When the buffer is short the prefix is truncated so the suffix still
// fits; a buffer too small even for the suffix gets the truncated prefix alone. Returns the length written,
// not counting the NUL. Header-only and Arduino-free so the host tests can build it.
inline size_t formatHostname(const char* prefix, const uint8_t mac[6], bool withMacSuffix, char* out, size_t outSize) {
  if (outSize == 0) return 0;
  constexpr size_t kSuffixLen = 5;  // '-' plus four hex digits
  const size_t room = outSize - 1;
  const bool suffix = withMacSuffix && room >= kSuffixLen;
  const size_t prefixRoom = suffix ? room - kSuffixLen : room;
  size_t n = strlen(prefix);
  if (n > prefixRoom) n = prefixRoom;
  memcpy(out, prefix, n);
  if (suffix) {
    static const char kHex[] = "0123456789abcdef";
    out[n++] = '-';
    out[n++] = kHex[mac[4] >> 4];
    out[n++] = kHex[mac[4] & 0x0f];
    out[n++] = kHex[mac[5] >> 4];
    out[n++] = kHex[mac[5] & 0x0f];
  }
  out[n] = '\0';
  return n;
}

}  // namespace openski::identity
