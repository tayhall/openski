#pragma once

#include <stddef.h>
#include <stdint.h>

#include "Imu.h"
#include "ImuMonitor.h"

namespace openski::recorder {
struct Status {
  bool partitionFound;
  bool storageReady;
  bool recording;
  bool hasSession;
  bool full;
  bool storageError;
  uint32_t partitionBytes;
  uint32_t samples;
  uint32_t droppedSamples;
  uint32_t maxSamples;
};

void begin();
void tick(const imu::ImuMonitor& monitor);
Status status();
bool start();
bool stop();
bool erase();
bool readRecords(uint32_t firstRecord, uint8_t* destination, size_t maxRecords,
                 size_t& recordsRead);
}  // namespace openski::recorder
