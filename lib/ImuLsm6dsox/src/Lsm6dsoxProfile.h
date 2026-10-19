#pragma once

// Acquisition profile for the LSM6DSOX driver. Register values were taken from ST's reference driver
// (lsm6dsox_reg.h/.c); see docs/poc-plan.md for what is still unverified on hardware.
namespace openski::imu::lsmProfile {
inline constexpr const char* kId = "openski_lsm6dsox_v1";
// The LSM6DSOX has no 100 Hz setting (12.5/26/52/104/208 Hz). 104 Hz is the closest; the nominal rate
// below is the register setting, not 100.
inline constexpr unsigned kSampleRateHz = 104;
inline constexpr unsigned kAccelRangeG = 16;
inline constexpr unsigned kGyroRangeDps = 2000;
// Accelerometer LPF2 on, HPCF_XL = 000 -> ODR/4 = 26 Hz. Gyro LPF1 on, FTYPE = 011. ST datasheet
// DS12814 Rev 4, Table 67 (gyroscope LPF1 bandwidth selection) gives 33.0 Hz for FTYPE 011 at a 104 Hz
// ODR; the fixed LPF2 stage (Table 18) is also 33.0 Hz at 104 Hz. A nominal figure from the table, not
// a measured response.
inline constexpr unsigned kNominalGyroBandwidthHz = 33;
}  // namespace openski::imu::lsmProfile
