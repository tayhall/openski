#pragma once

#include <stdint.h>

namespace openski::imu {
enum SampleQuality : uint8_t {
  kAccelNearRail = 1,
  kGyroNearRail = 2,
  kTimestampGap = 4,
  kTimestampWrap = 8,
};
constexpr uint8_t kRailQualityMask = kAccelNearRail | kGyroNearRail;
constexpr uint8_t kSampleQualityMask = 0x0f;  // bits carried by the live frame's quality byte
struct Vec3 {
  float x;
  float y;
  float z;
};

// One motion sample in the sensor frame, in SI units.
struct Sample {
  uint32_t timestampUs;
  Vec3 accelMps2;
  Vec3 gyroRadps;
  float temperatureC;
  uint8_t qualityFlags = 0;
};

enum class ReadResult {
  kSample,  // `out` holds a new sample.
  kNoData,  // Sensor is healthy but has nothing new yet.
  kError,   // Bus or device error; `out` is unchanged.
};

// Implemented by each chip driver (e.g. MPU-6050, LSM6DSOX).
class ImuSensor {
 public:
  virtual ~ImuSensor() = default;
  virtual bool begin() = 0;
  virtual ReadResult read(Sample& out) = 0;
  virtual const char* name() const = 0;
  // Accelerometer-only, low-rate, low-power acquisition for sleeping boots that only watch for movement
  // (gyro samples read as zero). begin() restores normal acquisition. False if the chip cannot do it.
  virtual bool setSleepMode(bool) { return false; }
};
}  // namespace openski::imu
