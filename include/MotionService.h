#pragma once
#include <stdint.h>
#include "GestureTracker.h"

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
void tick();
Status status();
}
