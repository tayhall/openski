#pragma once

#ifndef OPENSKI_BUILD_ID
#define OPENSKI_BUILD_ID "unknown"
#endif

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
#ifndef OPENSKI_HOSTNAME
#define OPENSKI_HOSTNAME "ski"
#endif
inline constexpr char kHostname[] = OPENSKI_HOSTNAME;
inline constexpr char kBuildId[] = OPENSKI_BUILD_ID;
inline constexpr char kBleName[] = "OpenSki-ski";
#ifndef OPENSKI_IMU_SDA_PIN
#define OPENSKI_IMU_SDA_PIN 21
#endif
#ifndef OPENSKI_IMU_SCL_PIN
#define OPENSKI_IMU_SCL_PIN 22
#endif
inline constexpr int kImuSdaPin = OPENSKI_IMU_SDA_PIN;
inline constexpr int kImuSclPin = OPENSKI_IMU_SCL_PIN;
inline constexpr uint8_t kImuI2cAddress = 0x68;
inline constexpr uint32_t kImuI2cFrequencyHz = 400000;
}  // namespace openski::config
