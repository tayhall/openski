#include "BluetoothService.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <math.h>
#include <string.h>
#include <esp_timer.h>

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "AppConfig.h"
#include "ImuService.h"
#include "MagFrame.h"
#include "MagService.h"
#include "TimeSyncFrames.h"
#include "ModeService.h"
#include "MotionService.h"
#include "RecorderService.h"

namespace openski::bluetooth {
namespace {
constexpr char kServiceUuid[] = "b1e7a100-3c31-4d59-a2c8-1e9f2f810001";
constexpr char kLiveSampleUuid[] = "b1e7a100-3c31-4d59-a2c8-1e9f2f810002";
constexpr char kStatusUuid[] = "b1e7a100-3c31-4d59-a2c8-1e9f2f810003";
constexpr char kRecorderControlUuid[] = "b1e7a100-3c31-4d59-a2c8-1e9f2f810004";
constexpr char kRecorderDataUuid[] = "b1e7a100-3c31-4d59-a2c8-1e9f2f810005";
constexpr char kMovementEventUuid[] = "b1e7a100-3c31-4d59-a2c8-1e9f2f810006";
constexpr uint8_t kProtocolVersion = 1;  // status characteristic
constexpr uint8_t kLiveProtocolVersion = 2;  // live frame: v1 plus quality byte at offset 19
constexpr uint8_t kMovementEventVersion = 1;
constexpr uint8_t kRecorderProtocolVersion = 3;
constexpr uint8_t kDownloadChunkMarker = 0xd1;  // records carry flags in the low timestamp bits
// 20 bytes is the ATT payload of a default MTU of 23 (23 - 3), so the frame fits unnegotiated.
constexpr size_t kLiveFrameSize = 20;
constexpr size_t kStatusFrameSize = 12;
constexpr size_t kMovementEventSize = 15;
constexpr size_t kRecordSize = 16;
constexpr size_t kTransferHeaderSize = 4;
constexpr size_t kControlRequestSize = 5;
// The IMU samples at 100 Hz (104 Hz on the LSM6DSOX builds). Sending every second sample gives a steady 50 Hz
// (52 Hz on those builds) that fits the default ATT MTU; every frame carries its own timestamp.
// Counting samples, rather than timing sends against a loop tick, avoids the 20/30 ms quantisation that measured 38 Hz.
constexpr uint32_t kLiveDecimation = 2;
constexpr unsigned long kTransferRateLimitMs = 10;

enum class RecorderCommand : uint8_t {
  kStart = 0x01,
  kStop = 0x02,
  kInfo = 0x03,
  kErase = 0x04,
  kDownload = 0x05,
  kCancelDownload = 0x06,
};

enum class RecorderResult : uint8_t {
  kOk = 0,
  kUnavailable = 1,
  kBusy = 2,
  kNoSession = 3,
  kInvalidOffset = 4,
  kStorageError = 5,
  kInvalidCommand = 6,
};

struct ControlRequest {
  uint8_t length;
  uint64_t receivedUs;  // esp_timer_get_time() when the write arrived, for the time-sync opcode
  uint8_t bytes[kControlRequestSize];
};

NimBLEServer* server = nullptr;
NimBLECharacteristic* liveCharacteristic = nullptr;
NimBLECharacteristic* movementCharacteristic = nullptr;
uint32_t lastMovementSequence = 0;
uint32_t lastSkiSequence = 0;
uint32_t lastHeadingSequence = 0;
unsigned long lastHeartbeatMs = 0;
uint16_t heartbeatSequence = 0;
NimBLECharacteristic* statusCharacteristic = nullptr;
NimBLECharacteristic* recorderControlCharacteristic = nullptr;
NimBLECharacteristic* recorderDataCharacteristic = nullptr;
QueueHandle_t controlQueue = nullptr;
volatile bool clientConnected = false;
bool advertising = false;
bool downloadActive = false;
uint16_t connectionId = 0;
uint32_t downloadOffset = 0;
uint16_t sequenceNumber = 0;
uint32_t lastSentSampleTimestamp = 0;
uint32_t lastNotifiedSampleCount = 0;
// OR of the quality flags of every sample seen since the previous notification.
uint8_t liveQuality = 0;
uint32_t lastQualityTimestamp = 0;
bool haveQualityTimestamp = false;
unsigned long lastTransferMs = 0;
portMUX_TYPE diagnosticsMux = portMUX_INITIALIZER_UNLOCKED;
Diagnostics connectionDiagnostics;

int trackGapEvent(ble_gap_event* event, void*) {
  const uint64_t now = esp_timer_get_time() / 1000ULL;
  portENTER_CRITICAL(&diagnosticsMux);
  if (event->type == BLE_GAP_EVENT_CONNECT && event->connect.status == 0) {
    ++connectionDiagnostics.connections;
    connectionDiagnostics.lastConnectedMs = now;
  } else if (event->type == BLE_GAP_EVENT_DISCONNECT) {
    ++connectionDiagnostics.disconnections;
    connectionDiagnostics.lastDisconnectedMs = now;
    connectionDiagnostics.lastDisconnectReason = event->disconnect.reason;
  }
  portEXIT_CRITICAL(&diagnosticsMux);
  return 0;
}

void putUint16(uint8_t* out, uint16_t value) {
  out[0] = static_cast<uint8_t>(value & 0xff);
  out[1] = static_cast<uint8_t>((value >> 8) & 0xff);
}

void putUint32(uint8_t* out, uint32_t value) {
  out[0] = static_cast<uint8_t>(value & 0xff);
  out[1] = static_cast<uint8_t>((value >> 8) & 0xff);
  out[2] = static_cast<uint8_t>((value >> 16) & 0xff);
  out[3] = static_cast<uint8_t>((value >> 24) & 0xff);
}

int16_t quantize(float value, float scale) {
  long scaled = lroundf(value * scale);
  if (scaled > INT16_MAX) scaled = INT16_MAX;
  if (scaled < INT16_MIN) scaled = INT16_MIN;
  return static_cast<int16_t>(scaled);
}

void putSigned16(uint8_t* out, int16_t value) {
  putUint16(out, static_cast<uint16_t>(value));
}

class ServerCallbacks final : public NimBLEServerCallbacks {
 public:
  void onConnect(NimBLEServer*, ble_gap_conn_desc* desc) override {
    clientConnected = true;
    connectionId = desc->conn_handle;
  }
  void onDisconnect(NimBLEServer*) override {
    clientConnected = false;
    advertising = false;
    downloadActive = false;
  }
};

class ControlCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  void onWrite(NimBLECharacteristic* characteristic) override {
    if (controlQueue == nullptr) return;
    const std::string value = characteristic->getValue();
    if (value.empty() || value.size() > kControlRequestSize) return;
    ControlRequest request{};
    request.receivedUs = static_cast<uint64_t>(esp_timer_get_time());  // read here, not when dequeued
    request.length = static_cast<uint8_t>(value.size());
    memcpy(request.bytes, value.data(), value.size());
    xQueueSend(controlQueue, &request, 0);
  }
};

void updateStatus() {
  const imu::ImuMonitor& monitor = imu::monitor();
  const imu::Stats& stats = monitor.stats();
  uint8_t frame[kStatusFrameSize]{};
  frame[0] = kProtocolVersion;
  frame[1] = (stats.ready ? 0x01 : 0x00) | (monitor.hasSample() ? 0x02 : 0x00) |
             (clientConnected ? 0x04 : 0x00);
  putUint32(frame + 2, stats.samples);
  putUint32(frame + 6, stats.readFailures);
  if (monitor.hasSample()) {
    putSigned16(frame + 10, quantize(monitor.latest().temperatureC, 100.0f));
  }
  statusCharacteristic->setValue(frame, sizeof(frame));
}

void notifyLatestSample() {
  const imu::ImuMonitor& monitor = imu::monitor();
  if (mode::production() || !clientConnected || !monitor.hasSample() || liveCharacteristic == nullptr) {
    // No live notifications: drop pending quality so it is not attributed to a later frame.
    liveQuality = 0;
    haveQualityTimestamp = false;
    return;
  }

  // Accumulate once per new sample (by timestamp), before decimation, so the skipped
  // samples' flags reach the next notification.
  const imu::Sample& sample = monitor.latest();
  if (!haveQualityTimestamp || sample.timestampUs != lastQualityTimestamp) {
    liveQuality |= sample.qualityFlags;
    lastQualityTimestamp = sample.timestampUs;
    haveQualityTimestamp = true;
  }

  const uint32_t samples = monitor.stats().samples;
  if (samples - lastNotifiedSampleCount < kLiveDecimation) return;
  if (sample.timestampUs == lastSentSampleTimestamp) return;

  uint8_t frame[kLiveFrameSize]{};
  frame[0] = kLiveProtocolVersion;
  putUint16(frame + 1, sequenceNumber++);
  putUint32(frame + 3, sample.timestampUs / 1000U);
  putSigned16(frame + 7, quantize(sample.accelMps2.x, 100.0f));
  putSigned16(frame + 9, quantize(sample.accelMps2.y, 100.0f));
  putSigned16(frame + 11, quantize(sample.accelMps2.z, 100.0f));
  putSigned16(frame + 13, quantize(sample.gyroRadps.x, 1000.0f));
  putSigned16(frame + 15, quantize(sample.gyroRadps.y, 1000.0f));
  putSigned16(frame + 17, quantize(sample.gyroRadps.z, 1000.0f));
  frame[19] = liveQuality & imu::kSampleQualityMask;

  liveCharacteristic->setValue(frame, sizeof(frame));
  liveCharacteristic->notify();
  lastSentSampleTimestamp = sample.timestampUs;
  liveQuality = 0;
  lastNotifiedSampleCount = samples;
}

// One notification per completed tilt excursion (see docs/ble-protocol.md). Events that
// finish while no client is connected are not replayed. Returns true if a frame was sent.
bool notifyMovementEvents() {
  if (movementCharacteristic == nullptr) return false;
  const motion::TiltStatus tilt = motion::tiltStatus();
  if (!clientConnected || tilt.count < lastMovementSequence) {
    lastMovementSequence = tilt.count;
    return false;
  }
  if (tilt.count == lastMovementSequence) return false;
  motion::Excursion events[16];
  const uint8_t count = motion::recentExcursions(events, 16);
  for (uint8_t i = 0; i < count; ++i) {
    if (events[i].sequence <= lastMovementSequence) continue;
    const motion::Excursion& e = events[i];
    const uint32_t durationMs = (e.endUs - e.startUs) / 1000U;
    uint8_t frame[kMovementEventSize]{};
    frame[0] = kMovementEventVersion;
    frame[1] = e.axis;
    putUint16(frame + 2, static_cast<uint16_t>(e.sequence));
    putUint32(frame + 4, e.startUs / 1000U);
    putUint16(frame + 8, durationMs > 65535U ? 65535U : static_cast<uint16_t>(durationMs));
    putSigned16(frame + 10, quantize(e.peakDegrees, 100.0f));
    putSigned16(frame + 12, quantize(e.aboutDegrees, 100.0f));
    frame[14] = static_cast<uint8_t>(roundf(e.axisFraction * 100.0f));
    movementCharacteristic->setValue(frame, sizeof(frame));
    movementCharacteristic->notify();
    lastMovementSequence = e.sequence;
    return true;  // one per tick; the next follows on the next loop
  }
  lastMovementSequence = tilt.count;  // the missed events fell out of the history
  return false;
}

// One notification per completed ski_v0 half-turn (version 2 frame). Events that finish while
// no client is connected are not replayed. Returns true if a frame was sent.
bool notifySkiEvents() {
  if (movementCharacteristic == nullptr) return false;
  const motion::SkiStatus ski = motion::skiStatus();
  if (!clientConnected || ski.count < lastSkiSequence) {
    lastSkiSequence = ski.count;
    return false;
  }
  if (ski.count == lastSkiSequence) return false;
  motion::SkiEvent events[16];
  const uint8_t count = motion::recentSkiEvents(events, 16);
  for (uint8_t i = 0; i < count; ++i) {
    if (events[i].sequence <= lastSkiSequence) continue;
    uint8_t frame[motion::kSkiEventSize];
    motion::encodeSkiEvent(events[i], frame);
    movementCharacteristic->setValue(frame, sizeof(frame));
    movementCharacteristic->notify();
    lastSkiSequence = events[i].sequence;
    return true;
  }
  lastSkiSequence = ski.count;  // the missed events fell out of the history
  return false;
}

// One notification per completed heading-rate lobe (version 4 frame), same replay rule as half-turns.
bool notifyHeadingTurns() {
  if (movementCharacteristic == nullptr) return false;
  const uint32_t count = motion::headingCount();
  if (!clientConnected || count < lastHeadingSequence) { lastHeadingSequence = count; return false; }
  if (count == lastHeadingSequence) return false;
  motion::HeadingTurn turns[16];
  const uint8_t n = motion::recentHeadingTurns(turns, 16);
  for (uint8_t i = 0; i < n; ++i) {
    if (turns[i].sequence <= lastHeadingSequence) continue;
    uint8_t frame[motion::kHeadingTurnSize];
    motion::encodeHeadingTurn(turns[i], frame);
    movementCharacteristic->setValue(frame, sizeof(frame));
    movementCharacteristic->notify();
    lastHeadingSequence = turns[i].sequence;
    return true;
  }
  lastHeadingSequence = count;  // the missed turns fell out of the history
  return false;
}

// One state frame per second in both modes (version 3 frame). The accumulators reset every
// second even with no client, so the first frame after a connect covers only the last second.
bool notifyHeartbeat() {
  const unsigned long now = millis();
  if (now - lastHeartbeatMs < 1000UL) return false;
  lastHeartbeatMs = now;
  const motion::SkiHeartbeat beat = motion::takeSkiHeartbeat();
  const bool gap = motion::takeSkiGapSeen();
  if (!clientConnected || movementCharacteristic == nullptr) return false;
  const motion::SkiStatus ski = motion::skiStatus();
  motion::SkiStateFrame state;
  state.zeroed = ski.zeroed;
  state.production = mode::production();
  state.gap = gap;
  state.sequence = heartbeatSequence++;
  state.timeMs = static_cast<uint32_t>(esp_timer_get_time()) / 1000U;  // same clock as the live frame
  state.rollDegrees = ski.rollDegrees;
  state.pitchDegrees = ski.pitchDegrees;
  state.vibrationMps2 = beat.vibrationMps2;
  state.gyroDps = beat.gyroDps;
  uint8_t frame[motion::kSkiStateSize];
  motion::encodeSkiState(state, frame);
  movementCharacteristic->setValue(frame, sizeof(frame));
  movementCharacteristic->notify();
  return true;
}

uint8_t recorderFlags(const recorder::Status& state) {
  return (state.storageReady ? 0x01 : 0x00) |
         (state.recording ? 0x02 : 0x00) |
         (state.hasSession ? 0x04 : 0x00) |
         (state.full ? 0x08 : 0x00) |
         (state.storageError ? 0x10 : 0x00) |
         (downloadActive ? 0x20 : 0x00) |
         (mode::production() ? 0x40 : 0x00);
}

// Version 5 magnetometer frame at 10 Hz, diagnostics mode only, when a magnetometer answers and a client is
// connected. Experimental raw counts. Returns true if a frame was sent.
bool notifyMagnetometer() {
  static unsigned long lastMs = 0;
  static uint32_t lastSamples = 0;
  static uint32_t lastErrors = 0;
  static uint16_t sequence = 0;
  const magnetometer::Status mag = magnetometer::status();
  if (!mag.present || !mag.hasSample || mode::production() || !clientConnected ||
      movementCharacteristic == nullptr) {
    // Nothing is sent, so do not attribute earlier errors to a later frame.
    lastSamples = mag.samples;
    lastErrors = mag.readErrors;
    return false;
  }
  const unsigned long now = millis();
  if (now - lastMs < 100UL) return false;
  if (mag.samples == lastSamples) return false;  // no fresh sample since the last frame
  lastMs = now;
  motion::MagFrame m;
  m.nearRail = motion::magNearRail(mag.rawX, mag.rawY, mag.rawZ);
  m.gap = mag.readErrors != lastErrors;
  m.sequence = sequence++;
  m.timeMs = static_cast<uint32_t>(esp_timer_get_time() / 1000ULL);  // same clock as the live frame
  m.rawX = mag.rawX;
  m.rawY = mag.rawY;
  m.rawZ = mag.rawZ;
  lastSamples = mag.samples;
  lastErrors = mag.readErrors;
  uint8_t frame[motion::kMagFrameSize];
  motion::encodeMagFrame(m, frame);
  movementCharacteristic->setValue(frame, sizeof(frame));
  movementCharacteristic->notify();
  return true;
}

void notifyRecorderResponse(uint8_t opcode, RecorderResult result) {
  if (recorderControlCharacteristic == nullptr) return;
  const recorder::Status state = recorder::status();
  uint8_t frame[16]{};
  frame[0] = kRecorderProtocolVersion;
  frame[1] = opcode;
  frame[2] = static_cast<uint8_t>(result);
  frame[3] = recorderFlags(state);
  putUint32(frame + 4, state.samples);
  putUint32(frame + 8, state.droppedSamples);
  putUint32(frame + 12, state.maxSamples);
  recorderControlCharacteristic->setValue(frame, sizeof(frame));
  recorderControlCharacteristic->notify();
}

void processRecorderCommand(const ControlRequest& request) {
  if (request.length == 0) return;
  const uint8_t opcode = request.bytes[0];
  // Time sync needs no flash and works in both modes; the clock was read when the write arrived.
  if (opcode == motion::kTimeSyncOpcode) {
    uint16_t token = 0;
    uint8_t frame[motion::kControlResponseSize];
    if (motion::parseTimeSyncRequest(request.bytes, request.length, token)) {
      motion::encodeTimeSyncResponse(kRecorderProtocolVersion, static_cast<uint8_t>(RecorderResult::kOk),
                                     recorderFlags(recorder::status()), token, request.receivedUs, frame);
    } else {
      motion::encodeTimeSyncResponse(kRecorderProtocolVersion,
                                     static_cast<uint8_t>(RecorderResult::kInvalidCommand),
                                     recorderFlags(recorder::status()), 0, 0, frame);
    }
    if (recorderControlCharacteristic != nullptr) {
      recorderControlCharacteristic->setValue(frame, sizeof(frame));
      recorderControlCharacteristic->notify();
    }
    return;
  }
  // Zero and mode work without flash storage, so they are handled before the storage check.
  switch (motion::parseMotionCommand(request.bytes, request.length)) {
    case motion::MotionCommandKind::kZero:
      motion::zeroMotion();
      notifyRecorderResponse(opcode, RecorderResult::kOk);
      return;
    case motion::MotionCommandKind::kDiagnostics:
      mode::set(mode::Mode::kDiagnostics);
      notifyRecorderResponse(opcode, RecorderResult::kOk);
      return;
    case motion::MotionCommandKind::kProduction:
      mode::set(mode::Mode::kProduction);
      notifyRecorderResponse(opcode, RecorderResult::kOk);
      return;
    case motion::MotionCommandKind::kInvalid:
      notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      return;
    case motion::MotionCommandKind::kNone:
      break;
  }
  const recorder::Status state = recorder::status();
  if (!state.storageReady) {
    notifyRecorderResponse(opcode, RecorderResult::kUnavailable);
    return;
  }

  switch (static_cast<RecorderCommand>(opcode)) {
    case RecorderCommand::kStart:
      if (request.length != 1) {
        notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      } else if (recorder::start()) {
        downloadActive = false;
        notifyRecorderResponse(opcode, RecorderResult::kOk);
      } else {
        notifyRecorderResponse(opcode, state.hasSession ? RecorderResult::kBusy
                                                        : RecorderResult::kStorageError);
      }
      break;
    case RecorderCommand::kStop:
      if (request.length != 1) {
        notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      } else if (!state.recording) {
        notifyRecorderResponse(opcode, RecorderResult::kBusy);
      } else if (recorder::stop()) {
        notifyRecorderResponse(opcode, RecorderResult::kOk);
      } else {
        notifyRecorderResponse(opcode, RecorderResult::kStorageError);
      }
      break;
    case RecorderCommand::kInfo:
      if (request.length != 1) {
        notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      } else {
        notifyRecorderResponse(opcode, RecorderResult::kOk);
      }
      break;
    case RecorderCommand::kErase:
      if (request.length != 1) {
        notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      } else if (downloadActive || !recorder::erase()) {
        notifyRecorderResponse(opcode, RecorderResult::kBusy);
      } else {
        notifyRecorderResponse(opcode, RecorderResult::kOk);
      }
      break;
    case RecorderCommand::kDownload: {
      if (request.length != 5) {
        notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
        break;
      }
      if (state.recording) {
        notifyRecorderResponse(opcode, RecorderResult::kBusy);
        break;
      }
      if (!state.hasSession) {
        notifyRecorderResponse(opcode, RecorderResult::kNoSession);
        break;
      }
      const uint32_t offset = static_cast<uint32_t>(request.bytes[1]) |
                              (static_cast<uint32_t>(request.bytes[2]) << 8) |
                              (static_cast<uint32_t>(request.bytes[3]) << 16) |
                              (static_cast<uint32_t>(request.bytes[4]) << 24);
      if (offset > state.samples) {
        notifyRecorderResponse(opcode, RecorderResult::kInvalidOffset);
        break;
      }
      downloadOffset = offset;
      downloadActive = true;
      lastTransferMs = 0;
      notifyRecorderResponse(opcode, RecorderResult::kOk);
      break;
    }
    case RecorderCommand::kCancelDownload:
      if (request.length != 1) {
        notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      } else {
        downloadActive = false;
        notifyRecorderResponse(opcode, RecorderResult::kOk);
      }
      break;
    default:
      notifyRecorderResponse(opcode, RecorderResult::kInvalidCommand);
      break;
  }
}

void processControlRequests() {
  if (controlQueue == nullptr) return;
  ControlRequest request{};
  if (xQueueReceive(controlQueue, &request, 0) == pdTRUE) {
    processRecorderCommand(request);
  }
}

void notifyNextRecordChunk() {
  if (!downloadActive || !clientConnected || recorderDataCharacteristic == nullptr || server == nullptr) {
    return;
  }
  const unsigned long now = millis();
  if (now - lastTransferMs < kTransferRateLimitMs) return;

  const recorder::Status state = recorder::status();
  if (downloadOffset >= state.samples) {
    downloadActive = false;
    notifyRecorderResponse(0x85, RecorderResult::kOk);
    return;
  }

  uint16_t mtu = server->getPeerMTU(connectionId);
  if (mtu < 23) mtu = 23;
  size_t payloadLimit = static_cast<size_t>(mtu - 3);
  if (payloadLimit > 255) payloadLimit = 255;
  if (payloadLimit < kTransferHeaderSize + kRecordSize) {
    downloadActive = false;
    notifyRecorderResponse(0x85, RecorderResult::kStorageError);
    return;
  }

  const size_t maxRecords = (payloadLimit - kTransferHeaderSize) / kRecordSize;
  uint8_t frame[255]{};
  const size_t recordCapacity = (sizeof(frame) - kTransferHeaderSize) / kRecordSize;
  const size_t requested = maxRecords < recordCapacity ? maxRecords : recordCapacity;
  size_t recordsRead = 0;
  if (!recorder::readRecords(downloadOffset, frame + kTransferHeaderSize, requested, recordsRead) ||
      recordsRead == 0) {
    downloadActive = false;
    notifyRecorderResponse(0x85, RecorderResult::kStorageError);
    return;
  }

  frame[0] = kDownloadChunkMarker;
  frame[1] = static_cast<uint8_t>(downloadOffset & 0xff);
  frame[2] = static_cast<uint8_t>((downloadOffset >> 8) & 0xff);
  frame[3] = static_cast<uint8_t>((downloadOffset >> 16) & 0xff);
  recorderDataCharacteristic->setValue(frame, kTransferHeaderSize + recordsRead * kRecordSize);
  recorderDataCharacteristic->notify();
  downloadOffset += static_cast<uint32_t>(recordsRead);
  lastTransferMs = now;
}
}  // namespace

void begin() {
  controlQueue = xQueueCreate(4, sizeof(ControlRequest));
  NimBLEDevice::init(config::kBleName);
  NimBLEDevice::setCustomGapHandler(trackGapEvent);
  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* service = server->createService(kServiceUuid);
  liveCharacteristic = service->createCharacteristic(
      kLiveSampleUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  statusCharacteristic = service->createCharacteristic(
      kStatusUuid, NIMBLE_PROPERTY::READ);
  recorderControlCharacteristic = service->createCharacteristic(
      kRecorderControlUuid,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
  recorderControlCharacteristic->setCallbacks(new ControlCallbacks());
  recorderDataCharacteristic = service->createCharacteristic(
      kRecorderDataUuid, NIMBLE_PROPERTY::NOTIFY);
  movementCharacteristic = service->createCharacteristic(
      kMovementEventUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

  service->start();
  NimBLEAdvertising* bleAdvertising = NimBLEDevice::getAdvertising();
  bleAdvertising->addServiceUUID(kServiceUuid);
  bleAdvertising->setScanResponse(true);
  NimBLEDevice::startAdvertising();
  advertising = true;
  updateStatus();
  notifyRecorderResponse(static_cast<uint8_t>(RecorderCommand::kInfo), RecorderResult::kOk);
  Serial.printf("BLE ready: %s, service %s, recorder control %s\n",
                config::kBleName, kServiceUuid, kRecorderControlUuid);
}

bool connected() { return clientConnected; }
bool advertisingNow() { return server != nullptr && NimBLEDevice::getAdvertising()->isAdvertising(); }
Diagnostics diagnostics() {
  portENTER_CRITICAL(&diagnosticsMux);
  const Diagnostics snapshot = connectionDiagnostics;
  portEXIT_CRITICAL(&diagnosticsMux);
  return snapshot;
}
bool recording() { return recorder::status().recording; }

void tick() {
  if (server == nullptr) return;  // BLE was not started (safe boot after a brownout)
  if (!clientConnected && !advertising) {
    NimBLEDevice::startAdvertising();
    advertising = true;
  }
  processControlRequests();
  if (statusCharacteristic != nullptr) updateStatus();
  notifyLatestSample();
  // One frame on the movement characteristic per loop: half-turns first, then heading turns, then tilt events,
  // then the once-a-second state frame, then the 10 Hz magnetometer frame (left boot builds only).
  if (!notifySkiEvents() && !notifyHeadingTurns() && !notifyMovementEvents() && !notifyHeartbeat()) {
    notifyMagnetometer();
  }
  notifyNextRecordChunk();
}
}  // namespace openski::bluetooth
