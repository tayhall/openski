#pragma once

namespace openski::identity {
// The unit's Wi-Fi/OTA hostname: config::kHostname, plus the last two MAC bytes when the build defines
// OPENSKI_HOSTNAME_MAC_SUFFIX (identical boot units). Built once; the pointer lives for the whole run.
const char* hostname();
// The BLE advertising name: config::kBleName, plus the same MAC suffix, so identical boots can be told apart in a scan.
const char* bleName();
}  // namespace openski::identity
