#include "ImuMonitor.h"

namespace openski::imu {
bool ImuMonitor::begin() {
  stats_.ready = sensor_ != nullptr && sensor_->begin();
  return stats_.ready;
}

void ImuMonitor::poll() {
  if (!stats_.ready) return;
  Sample sample{};
  switch (sensor_->read(sample)) {
    case ReadResult::kSample:
      latest_ = sample;
      ++stats_.samples;
      break;
    case ReadResult::kError:
      ++stats_.readFailures;
      break;
    case ReadResult::kNoData:
      break;
  }
}
}  // namespace openski::imu
