#pragma once

#if __has_include("wifi_config.h")
#include "wifi_config.h"
#else
#define OPENSKI_WIFI_SSID ""
#define OPENSKI_WIFI_PASSWORD ""
#define OPENSKI_OTA_PASSWORD ""
#endif

namespace openski::config {
inline constexpr char kWifiSsid[] = OPENSKI_WIFI_SSID;
inline constexpr char kWifiPassword[] = OPENSKI_WIFI_PASSWORD;
inline constexpr char kOtaPassword[] = OPENSKI_OTA_PASSWORD;
inline constexpr char kHostname[] = "openski-s3";
}  // namespace openski::config
