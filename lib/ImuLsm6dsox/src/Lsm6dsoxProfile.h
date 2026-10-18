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
// Accelerometer LPF2 on, HPCF_XL = 000 -> ODR/4 = 26 Hz. Gyro LPF1 on, FTYPE = 011. The gyro
// bandwidth in Hz for that FTYPE at 104 Hz is not verified against the datasheet table, so it is 0
// (unknown; the acquisition JSON reports null) until it is read from the datasheet or measured.
inline constexpr unsigned kNominalGyroBandwidthHz = 0;
}  // namespace openski::imu::lsmProfile
