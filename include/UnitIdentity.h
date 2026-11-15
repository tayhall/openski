#pragma once

namespace openski::identity {
// The unit's Wi-Fi/OTA hostname: config::kHostname, plus the last two MAC bytes when the build defines
// OPENSKI_HOSTNAME_MAC_SUFFIX (identical boot units). Built once; the pointer lives for the whole run.
const char* hostname();
}  // namespace openski::identity
