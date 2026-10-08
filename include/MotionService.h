#pragma once
#include <stdint.h>
#include "GestureTracker.h"
#include "TiltTracker.h"
#include "SkiFrames.h"

namespace openski::motion {
struct Status {
  const char* label = "no_sample";
  uint32_t events = 0;
  uint32_t sampleTimestampUs = 0;
  float angularSpeedRadps = 0;
  float accelerationMagnitudeMps2 = 0;
};
struct Event {
  const char* label;
  uint32_t sequence;
  uint32_t timestampUs;
};
uint8_t recentEvents(Event* output, uint8_t capacity);
uint8_t recentGestures(Gesture* output, uint8_t capacity);
uint32_t gestureCount();
uint32_t rejectedGestures();
uint32_t gestureSampleGaps();
bool gestureActive();
struct TiltStatus {
  bool neutralReady = false, active = false;
  float tiltDegrees = 0, aboutDegrees[3]{};
  int verticalAxis = -1, verticalSign = 0;
  uint32_t count = 0, rejected = 0, gaps = 0;
};
TiltStatus tiltStatus();
uint8_t recentExcursions(Excursion* output, uint8_t capacity);
void zeroTilt();
struct SkiStatus {
  bool zeroed = false, zeroing = false;
  float rollDegrees = 0, pitchDegrees = 0;
  uint32_t count = 0, rejected = 0, gaps = 0;
};
SkiStatus skiStatus();
uint8_t recentSkiEvents(SkiEvent* output, uint8_t capacity);
SkiHeartbeat takeSkiHeartbeat();
bool takeSkiGapSeen();
// Recapture the neutral pose of both the tilt and ski recognisers from the next still second.
void zeroMotion();
void tick();
Status status();
}
