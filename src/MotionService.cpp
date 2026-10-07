#include "MotionService.h"
#include "ImuService.h"
#include "SensorCalibration.h"
#include <math.h>
#include <string.h>
#include <esp_timer.h>

namespace openski::motion {
namespace {
Status result;
uint32_t lastTimestamp = 0;
uint32_t candidateSince = 0;
const char* candidate = "no_sample";
imu::Vec3 restReference{};
bool referenceReady = false;
Event history[16]{};
GestureTracker gestures;
TiltTracker tilt;
}

void tick() {
  const auto& monitor = imu::monitor();
  if (!monitor.hasSample()) return;
  const auto& sample = monitor.latest();
  if (sample.timestampUs == lastTimestamp) return;
  lastTimestamp = sample.timestampUs;
  result.sampleTimestampUs = sample.timestampUs;
  // Recogniser inputs use the fixed per-unit correction; raw samples stay untouched.
  namespace cal = openski::calibration;
  const imu::Vec3 a{(sample.accelMps2.x-cal::kAccelOffsetMps2[0])/cal::kAccelScale[0],
                    (sample.accelMps2.y-cal::kAccelOffsetMps2[1])/cal::kAccelScale[1],
                    (sample.accelMps2.z-cal::kAccelOffsetMps2[2])/cal::kAccelScale[2]};
  const imu::Vec3 g{sample.gyroRadps.x-cal::kGyroBiasRadps[0],
                    sample.gyroRadps.y-cal::kGyroBiasRadps[1],
                    sample.gyroRadps.z-cal::kGyroBiasRadps[2]};
  gestures.update(sample.timestampUs, g.x, g.y, g.z);
  tilt.update(sample.timestampUs, a.x, a.y, a.z, g.x, g.y, g.z);
  const float speed = sqrtf(g.x*g.x + g.y*g.y + g.z*g.z);
  const float magnitude = sqrtf(a.x*a.x + a.y*a.y + a.z*a.z);
  if (!referenceReady) { restReference = a; referenceReady = true; }
  const float dx = a.x-restReference.x, dy = a.y-restReference.y, dz = a.z-restReference.z;
  const float accelerationVariation = sqrtf(dx*dx+dy*dy+dz*dz);
  // Follow the local baseline slowly, allowing sensor offset without calling
  // a changing acceleration vector a stationary candidate.
  restReference.x += 0.05f*dx;
  restReference.y += 0.05f*dy;
  restReference.z += 0.05f*dz;
  result.angularSpeedRadps = speed;
  result.accelerationMagnitudeMps2 = magnitude;
  const float axes[] = {g.x, g.y, g.z};
  int dominant = 0;
  for (int i = 1; i < 3; ++i) if (fabsf(axes[i]) > fabsf(axes[dominant])) dominant = i;
  float otherSquared = 0;
  for (int i = 0; i < 3; ++i) if (i != dominant) otherSquared += axes[i]*axes[i];
  const char* next = "mixed_motion";
  if (speed < 0.12f && magnitude > 7.0f && magnitude < 13.0f && accelerationVariation < 0.25f)
    next = "stationary_candidate";
  else if (fabsf(axes[dominant]) > 0.35f && fabsf(axes[dominant]) > 1.4f*sqrtf(otherSquared)) {
    const char* positive[] = {"rotate_x_positive", "rotate_y_positive", "rotate_z_positive"};
    const char* negative[] = {"rotate_x_negative", "rotate_y_negative", "rotate_z_negative"};
    next = axes[dominant] > 0 ? positive[dominant] : negative[dominant];
  } else if (speed < 0.2f && fabsf(magnitude - 9.80665f) > 1.5f) next = "acceleration_change";
  if (strcmp(next, candidate) != 0) {
    candidate = next;
    candidateSince = sample.timestampUs;
  }
  // Require sustained evidence rather than labelling isolated noisy samples.
  const uint32_t dwell = strcmp(next, "stationary_candidate") == 0 ? 300000U : 80000U;
  if (sample.timestampUs - candidateSince >= dwell && strcmp(result.label, candidate) != 0) {
    result.label = candidate;
    ++result.events;
    history[(result.events-1)%16] = {candidate, result.events, sample.timestampUs};
  }
}

uint8_t recentEvents(Event* output, uint8_t capacity) {
  const uint8_t count = static_cast<uint8_t>(result.events < 16 ? result.events : 16);
  const uint8_t copied = count < capacity ? count : capacity;
  for (uint8_t i = 0; i < copied; ++i) {
    const uint32_t sequence = result.events-copied+i;
    output[i] = history[sequence%16];
  }
  return copied;
}

uint8_t recentGestures(Gesture* output, uint8_t capacity) { return gestures.recent(output, capacity); }
uint32_t gestureCount() { return gestures.count(); }
uint32_t rejectedGestures() { return gestures.rejected(); }
uint32_t gestureSampleGaps() { return gestures.gaps(); }
bool gestureActive() { return gestures.active(); }

TiltStatus tiltStatus() {
  TiltStatus snapshot;
  snapshot.neutralReady = tilt.neutralReady();
  snapshot.active = tilt.active();
  snapshot.tiltDegrees = tilt.tiltDegrees();
  for (int i = 0; i < 3; ++i) snapshot.aboutDegrees[i] = tilt.aboutDegrees(i);
  snapshot.verticalAxis = tilt.verticalAxis();
  snapshot.verticalSign = tilt.verticalSign();
  snapshot.count = tilt.count();
  snapshot.rejected = tilt.rejected();
  snapshot.gaps = tilt.gaps();
  return snapshot;
}
uint8_t recentExcursions(Excursion* output, uint8_t capacity) { return tilt.recent(output, capacity); }
void zeroTilt() { tilt.zero(); }

Status status() {
  Status snapshot = result;
  // A stopped sensor must never continue reporting a current movement.
  if (!imu::monitor().stats().ready) snapshot.label = "sensor_unavailable";
  else if (result.sampleTimestampUs != 0 &&
           static_cast<uint32_t>(esp_timer_get_time()) - result.sampleTimestampUs > 250000U)
    snapshot.label = "stale_sample";
  return snapshot;
}
}
