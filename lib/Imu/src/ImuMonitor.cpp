#include "ImuMonitor.h"

namespace openski::imu {
bool ImuMonitor::begin() {
  stats_.ready = sensor_ != nullptr && sensor_->begin();
  return stats_.ready;
}

bool ImuMonitor::retryBegin(uint32_t nowMs, uint32_t intervalMs) {
  if (stats_.ready) return false;
  if (attempted_ && nowMs - lastAttemptMs_ < intervalMs) return false;
  attempted_ = true;
  lastAttemptMs_ = nowMs;
  return begin();
}

bool ImuMonitor::checkStall(uint32_t nowMs, uint32_t stallMs) {
  const uint32_t sinceLastCheck = nowMs - lastCheckMs_;  // unsigned: safe across a millis() wrap
  lastCheckMs_ = nowMs;
  if (!stats_.ready) {
    progressInit_ = false;
    return false;
  }
  if (!progressInit_ || stats_.samples != progressSamples_ || sinceLastCheck >= stallMs / 2) {
    progressInit_ = true;
    progressSamples_ = stats_.samples;
    progressMs_ = nowMs;
    return false;
  }
  if (nowMs - progressMs_ < stallMs) return false;  // unsigned: safe across a millis() wrap
  stats_.ready = false;
  ++stats_.stalls;
  progressInit_ = false;
  return true;
}

void ImuMonitor::poll() {
  if (!stats_.ready) return;
  Sample sample{};
  switch (sensor_->read(sample)) {
    case ReadResult::kSample:
      if (hasSample()) {
        // Unsigned subtraction preserves intervals across one micros() wrap.
        const uint32_t interval = sample.timestampUs - latest_.timestampUs;
        stats_.lastIntervalUs = interval;
        if (interval > stats_.maxIntervalUs) stats_.maxIntervalUs = interval;
        if (sample.timestampUs < latest_.timestampUs) {
          ++stats_.timestampWraps;
          sample.qualityFlags |= kTimestampWrap;
        }
        if (interval > 20000U) {
          ++stats_.timestampGaps;
          sample.qualityFlags |= kTimestampGap;
        }
      }
      if (sample.qualityFlags & kAccelNearRail) ++stats_.accelNearRailSamples;
      if (sample.qualityFlags & kGyroNearRail) ++stats_.gyroNearRailSamples;
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
