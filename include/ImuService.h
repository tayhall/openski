#pragma once

#include "ImuMonitor.h"

namespace openski::imu {
void begin();
void tick();
const ImuMonitor& monitor();
}  // namespace openski::imu
