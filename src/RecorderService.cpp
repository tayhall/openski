#include "RecorderService.h"

#include <Arduino.h>
#include <FS.h>
#include <SPIFFS.h>
#include <esp_partition.h>
#include <math.h>
#include <string.h>

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>

namespace openski::recorder {
namespace {
constexpr char kSessionPath[] = "/openski-session.bin";
constexpr size_t kRecordSize = 16;
constexpr size_t kBatchRecords = 16;
constexpr size_t kQueueRecords = 256;
constexpr uint8_t kStorageLimitPercent = 70;
constexpr char kWriterTaskName[] = "ski-log";

struct QueuedRecord {
  uint8_t bytes[kRecordSize];
};

QueueHandle_t recordQueue = nullptr;
portMUX_TYPE stateMux = portMUX_INITIALIZER_UNLOCKED;
volatile bool storageReady = false;
volatile bool partitionFound = false;
volatile uint32_t partitionBytes = 0;
volatile bool recording = false;
volatile bool sessionExists = false;
volatile bool full = false;
volatile bool storageError = false;
volatile uint32_t sampleCount = 0;
volatile uint32_t droppedSampleCount = 0;
volatile uint32_t maxSampleCount = 0;
volatile uint32_t pendingSampleCount = 0;
uint32_t lastSeenMonitorCount = 0;

void putUint16(uint8_t* out, uint16_t value) {
  out[0] = static_cast<uint8_t>(value & 0xff);
  out[1] = static_cast<uint8_t>(value >> 8);
}

void putUint32(uint8_t* out, uint32_t value) {
  out[0] = static_cast<uint8_t>(value & 0xff);
  out[1] = static_cast<uint8_t>(value >> 8);
  out[2] = static_cast<uint8_t>(value >> 16);
  out[3] = static_cast<uint8_t>(value >> 24);
}

int16_t quantize(float value, float scale) {
  long scaled = lroundf(value * scale);
  if (scaled > INT16_MAX) scaled = INT16_MAX;
  if (scaled < INT16_MIN) scaled = INT16_MIN;
  return static_cast<int16_t>(scaled);
}

void encodeRecord(const imu::Sample& sample, QueuedRecord& record) {
  putUint32(record.bytes, sample.timestampUs);
  putUint16(record.bytes + 4, static_cast<uint16_t>(quantize(sample.accelMps2.x, 100.0f)));
  putUint16(record.bytes + 6, static_cast<uint16_t>(quantize(sample.accelMps2.y, 100.0f)));
  putUint16(record.bytes + 8, static_cast<uint16_t>(quantize(sample.accelMps2.z, 100.0f)));
  putUint16(record.bytes + 10, static_cast<uint16_t>(quantize(sample.gyroRadps.x, 1000.0f)));
  putUint16(record.bytes + 12, static_cast<uint16_t>(quantize(sample.gyroRadps.y, 1000.0f)));
  putUint16(record.bytes + 14, static_cast<uint16_t>(quantize(sample.gyroRadps.z, 1000.0f)));
}

void setStorageFailure() {
  uint32_t savedSamples = 0;
  File file = SPIFFS.open(kSessionPath, FILE_READ);
  if (file) {
    savedSamples = static_cast<uint32_t>(file.size() / kRecordSize);
    file.close();
  }
  portENTER_CRITICAL(&stateMux);
  storageError = true;
  recording = false;
  if (savedSamples < sampleCount) droppedSampleCount += sampleCount - savedSamples;
  sampleCount = savedSamples;
  sessionExists = savedSamples > 0;
  pendingSampleCount = 0;
  portEXIT_CRITICAL(&stateMux);
  if (recordQueue != nullptr) xQueueReset(recordQueue);
  Serial.println("Recorder error: flash write failed; recording stopped");
}

bool flushBatch(const uint8_t* bytes, size_t count) {
  if (count == 0) return true;
  File file = SPIFFS.open(kSessionPath, FILE_APPEND);
  if (!file) return false;
  const size_t byteCount = count * kRecordSize;
  const size_t written = file.write(bytes, byteCount);
  file.flush();
  file.close();
  if (written != byteCount) return false;

  portENTER_CRITICAL(&stateMux);
  pendingSampleCount -= static_cast<uint32_t>(count);
  portEXIT_CRITICAL(&stateMux);
  return true;
}

void writerTask(void*) {
  uint8_t batch[kBatchRecords * kRecordSize];
  size_t batchCount = 0;
  QueuedRecord next{};

  for (;;) {
    const TickType_t wait = batchCount == 0 ? portMAX_DELAY : pdMS_TO_TICKS(100);
    if (xQueueReceive(recordQueue, &next, wait) == pdTRUE) {
      memcpy(batch + batchCount * kRecordSize, next.bytes, kRecordSize);
      ++batchCount;
      if (batchCount < kBatchRecords) continue;
    }

    if (batchCount > 0) {
      const size_t writtenCount = batchCount;
      if (!flushBatch(batch, writtenCount)) {
        setStorageFailure();
      }
      batchCount = 0;
    }
  }
}

}  // namespace

void begin() {
  const esp_partition_t* dataPartition = esp_partition_find_first(
      ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_DATA_SPIFFS, nullptr);
  partitionFound = dataPartition != nullptr;
  if (dataPartition != nullptr) partitionBytes = dataPartition->size;
  if (!partitionFound) {
    Serial.println("Recorder unavailable: no SPIFFS data partition in the installed partition table");
    return;
  }

  storageReady = SPIFFS.begin(true);
  if (!storageReady) {
    Serial.println("Recorder unavailable: could not mount flash filesystem");
    return;
  }

  const uint64_t practicalBytes =
      (static_cast<uint64_t>(SPIFFS.totalBytes()) * kStorageLimitPercent) / 100;
  maxSampleCount = static_cast<uint32_t>(practicalBytes / kRecordSize);
  if (SPIFFS.exists(kSessionPath)) {
    File existing = SPIFFS.open(kSessionPath, FILE_READ);
    if (existing) {
      sampleCount = static_cast<uint32_t>(existing.size() / kRecordSize);
      sessionExists = sampleCount > 0;
      full = sampleCount >= maxSampleCount;
      existing.close();
    }
  }

  recordQueue = xQueueCreate(kQueueRecords, sizeof(QueuedRecord));
  if (recordQueue == nullptr ||
      xTaskCreatePinnedToCore(writerTask, kWriterTaskName, 4096, nullptr, 1, nullptr, 0) != pdPASS) {
    storageReady = false;
    Serial.println("Recorder unavailable: could not start flash writer");
    return;
  }

  Serial.printf("Recorder ready: %lu sample slots (~%lu seconds at 100 Hz)%s\n",
                static_cast<unsigned long>(maxSampleCount),
                static_cast<unsigned long>(maxSampleCount / 100),
                sessionExists ? ", previous session awaits sync or erase" : "");
}

void tick(const imu::ImuMonitor& monitor) {
  const imu::Stats& stats = monitor.stats();
  if (!monitor.hasSample() || stats.samples == lastSeenMonitorCount) return;
  lastSeenMonitorCount = stats.samples;

  if (!recording || recordQueue == nullptr) return;
  if (sampleCount >= maxSampleCount) {
    portENTER_CRITICAL(&stateMux);
    recording = false;
    full = true;
    portEXIT_CRITICAL(&stateMux);
    Serial.println("Recorder full: stopped at safe flash limit");
    return;
  }

  QueuedRecord record{};
  encodeRecord(monitor.latest(), record);
  portENTER_CRITICAL(&stateMux);
  ++sampleCount;
  ++pendingSampleCount;
  sessionExists = true;
  portEXIT_CRITICAL(&stateMux);

  if (xQueueSend(recordQueue, &record, 0) != pdTRUE) {
    portENTER_CRITICAL(&stateMux);
    --sampleCount;
    --pendingSampleCount;
    sessionExists = sampleCount > 0;
    ++droppedSampleCount;
    portEXIT_CRITICAL(&stateMux);
    return;
  }
}

Status status() {
  Status result{};
  portENTER_CRITICAL(&stateMux);
  result.partitionFound = partitionFound;
  result.storageReady = storageReady;
  result.recording = recording;
  result.hasSession = sessionExists;
  result.full = full;
  result.storageError = storageError;
  result.partitionBytes = partitionBytes;
  result.samples = sampleCount;
  result.droppedSamples = droppedSampleCount;
  result.maxSamples = maxSampleCount;
  portEXIT_CRITICAL(&stateMux);
  return result;
}

bool start() {
  if (!storageReady || recording || sessionExists || pendingSampleCount != 0) return false;
  if (SPIFFS.exists(kSessionPath) && !SPIFFS.remove(kSessionPath)) return false;
  File file = SPIFFS.open(kSessionPath, "w");
  if (!file) return false;
  file.close();

  portENTER_CRITICAL(&stateMux);
  sampleCount = 0;
  droppedSampleCount = 0;
  full = false;
  storageError = false;
  sessionExists = false;
  recording = true;
  portEXIT_CRITICAL(&stateMux);
  Serial.println("Recorder started");
  return true;
}

bool stop() {
  if (!storageReady || !recording) return false;
  portENTER_CRITICAL(&stateMux);
  recording = false;
  portEXIT_CRITICAL(&stateMux);

  const unsigned long startedAt = millis();
  while (pendingSampleCount > 0 && millis() - startedAt < 5000) delay(1);
  const bool saved = pendingSampleCount == 0 && !storageError;
  Serial.printf("Recorder stopped: %lu samples%s\n",
                static_cast<unsigned long>(sampleCount), saved ? "" : " (flush incomplete)");
  return saved;
}

bool erase() {
  if (!storageReady || recording || pendingSampleCount != 0) return false;
  if (SPIFFS.exists(kSessionPath) && !SPIFFS.remove(kSessionPath)) return false;
  portENTER_CRITICAL(&stateMux);
  sessionExists = false;
  sampleCount = 0;
  droppedSampleCount = 0;
  full = false;
  storageError = false;
  portEXIT_CRITICAL(&stateMux);
  Serial.println("Recorder session erased");
  return true;
}

bool readRecords(uint32_t firstRecord, uint8_t* destination, size_t maxRecords,
                 size_t& recordsRead) {
  recordsRead = 0;
  if (!storageReady || recording || pendingSampleCount != 0 || destination == nullptr) return false;
  if (firstRecord >= sampleCount || maxRecords == 0) return firstRecord == sampleCount;

  File file = SPIFFS.open(kSessionPath, FILE_READ);
  if (!file || !file.seek(static_cast<uint32_t>(firstRecord * kRecordSize), SeekSet)) {
    if (file) file.close();
    return false;
  }

  for (size_t i = 0; i < maxRecords && firstRecord + i < sampleCount; ++i) {
    if (file.read(destination + i * kRecordSize, kRecordSize) != kRecordSize) break;
    ++recordsRead;
  }
  file.close();
  return recordsRead > 0;
}

}  // namespace openski::recorder
