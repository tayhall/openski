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
// Which sensor axis points up the leg, forward and sideways when the board sits in its cuff clip,
// and the sign that makes it point that way. Assumed for the first mounting: board length (y) up
// the leg, holes edge (x) forward, header side (z) as the lateral axis. Confirm against the real
// mounting and override per board with build flags; the right boot normally needs
// -DOPENSKI_MOUNT_LATERAL_SIGN=-1 so roll has the same meaning on both legs.
#ifndef OPENSKI_MOUNT_UP_AXIS
#define OPENSKI_MOUNT_UP_AXIS 1
#endif
#ifndef OPENSKI_MOUNT_UP_SIGN
#define OPENSKI_MOUNT_UP_SIGN 1
#endif
#ifndef OPENSKI_MOUNT_FORWARD_AXIS
#define OPENSKI_MOUNT_FORWARD_AXIS 0
#endif
#ifndef OPENSKI_MOUNT_FORWARD_SIGN
#define OPENSKI_MOUNT_FORWARD_SIGN 1
#endif
#ifndef OPENSKI_MOUNT_LATERAL_AXIS
#define OPENSKI_MOUNT_LATERAL_AXIS 2
#endif
#ifndef OPENSKI_MOUNT_LATERAL_SIGN
#define OPENSKI_MOUNT_LATERAL_SIGN 1
#endif
inline constexpr uint8_t kMountUpAxis = OPENSKI_MOUNT_UP_AXIS;
inline constexpr int8_t kMountUpSign = OPENSKI_MOUNT_UP_SIGN;
inline constexpr uint8_t kMountForwardAxis = OPENSKI_MOUNT_FORWARD_AXIS;
inline constexpr int8_t kMountForwardSign = OPENSKI_MOUNT_FORWARD_SIGN;
inline constexpr uint8_t kMountLateralAxis = OPENSKI_MOUNT_LATERAL_AXIS;
inline constexpr int8_t kMountLateralSign = OPENSKI_MOUNT_LATERAL_SIGN;
}  // namespace openski::config
