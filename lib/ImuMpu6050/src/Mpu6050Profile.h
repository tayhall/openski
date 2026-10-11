#pragma once

namespace openski::imu::mpuProfile {
inline constexpr unsigned kSampleRateHz = 100;
inline constexpr unsigned kGyroRangeDps = 1000;
inline constexpr float kGyroCountsPerDegree = 32.8f;
#if defined(OPENSKI_RESEARCH_ACQUISITION)
inline constexpr const char* kId = "openski_mpu6050_research_v1";
inline constexpr unsigned kAccelRangeG = 16;
inline constexpr float kAccelCountsPerG = 2048.0f;
inline constexpr unsigned char kAccelConfig = 0x18;
inline constexpr unsigned char kDlpfConfig = 0x04;
inline constexpr unsigned kNominalGyroBandwidthHz = 20;
#else
inline constexpr const char* kId = "openski_mpu6050_legacy_v1";
inline constexpr unsigned kAccelRangeG = 8;
inline constexpr float kAccelCountsPerG = 4096.0f;
inline constexpr unsigned char kAccelConfig = 0x10;
inline constexpr unsigned char kDlpfConfig = 0x03;
inline constexpr unsigned kNominalGyroBandwidthHz = 42;
#endif
}  // namespace openski::imu::mpuProfile
