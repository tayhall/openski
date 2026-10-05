#pragma once

#include <stdint.h>

namespace openski::imu {
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
};

enum class ReadResult {
  kSample,  // `out` holds a new sample.
  kNoData,  // Sensor is healthy but has nothing new yet.
  kError,   // Bus or device error; `out` is unchanged.
};

// Implemented by each chip driver (e.g. LSM6DSO, ICM-42688, BMI270).
class ImuSensor {
 public:
  virtual ~ImuSensor() = default;
  virtual bool begin() = 0;
  virtual ReadResult read(Sample& out) = 0;
  virtual const char* name() const = 0;
};
}  // namespace openski::imu
