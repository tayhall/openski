#pragma once

// Fixed per-unit correction for the motion recogniser. It is applied only to
// the values the recogniser sees; raw samples (BLE, recorder, Wi-Fi /imu) stay
// uncorrected. Accelerometer: corrected = (raw - offset) / scale, the same form
// as the Android AccelCorrection. Gyro: corrected = raw - bias.
namespace openski::calibration {
#if defined(OPENSKI_CAL_S3_SUPERMINI)
// Bench Tools six-face run and 60 s still capture, 7 October 2026 (docs/bench-results.md).
inline constexpr float kAccelOffsetMps2[3] = {0.5074297f, -0.1688016f, -0.0054902f};
inline constexpr float kAccelScale[3] = {0.9987761f, 1.0056490f, 1.0305399f};
inline constexpr float kGyroBiasRadps[3] = {-0.033161f, 0.004014f, 0.006981f};  // -1.90, +0.23, +0.40 deg/s
#else
inline constexpr float kAccelOffsetMps2[3] = {0, 0, 0};
inline constexpr float kAccelScale[3] = {1, 1, 1};
inline constexpr float kGyroBiasRadps[3] = {0, 0, 0};
#endif
}  // namespace openski::calibration
