#include "TelemetryService.h"

#include <Arduino.h>
#include <WebServer.h>
#include <WiFi.h>
#include <esp_timer.h>
#include <esp_system.h>

#include "AppConfig.h"
#include "BluetoothService.h"
#include "ImuService.h"
#include "MotionService.h"
#include "RecorderService.h"
#include "WifiService.h"

namespace openski::telemetry {
namespace {
WebServer server(80);
bool serverStarted = false;

void handleMotion() {
  const auto state = motion::status();
  char body[512];
  snprintf(body, sizeof(body),
           "{\"device\":\"%s\",\"algorithm\":\"bench_rules_v3\",\"frame\":\"sensor\","
           "\"label\":\"%s\",\"label_changes\":%lu,\"sample_timestamp_us\":%lu,"
           "\"angular_speed_radps\":%.4f,\"acceleration_magnitude_mps2\":%.4f}",
           config::kHostname, state.label, static_cast<unsigned long>(state.events),
           static_cast<unsigned long>(state.sampleTimestampUs), state.angularSpeedRadps,
           state.accelerationMagnitudeMps2);
  String response(body);
  response.remove(response.length()-1);
  response += ",\"recent_events\":[";
  motion::Event events[16];
  const uint8_t count = motion::recentEvents(events, 16);
  for (uint8_t i = 0; i < count; ++i) {
    char eventBody[160];
    snprintf(eventBody, sizeof(eventBody), "%s{\"sequence\":%lu,\"timestamp_us\":%lu,\"label\":\"%s\"}",
             i ? "," : "", static_cast<unsigned long>(events[i].sequence),
             static_cast<unsigned long>(events[i].timestampUs), events[i].label);
    response += eventBody;
  }
  char summary[256];
  snprintf(summary, sizeof(summary),
           "],\"gesture_count\":%lu,\"rejected_gestures\":%lu,\"sample_gaps\":%lu,\"gesture_active\":%s,\"recent_gestures\":[",
           static_cast<unsigned long>(motion::gestureCount()),
           static_cast<unsigned long>(motion::rejectedGestures()),
           static_cast<unsigned long>(motion::gestureSampleGaps()), motion::gestureActive() ? "true" : "false");
  response += summary;
  motion::Gesture gestures[16];
  const uint8_t gestureCount = motion::recentGestures(gestures,16);
  for(uint8_t i=0;i<gestureCount;++i) {
    const auto& gesture=gestures[i];
    char gestureBody[320];
    snprintf(gestureBody,sizeof(gestureBody),
             "%s{\"sequence\":%lu,\"start_us\":%lu,\"end_us\":%lu,\"axis\":\"%c\","
             "\"angle_degrees\":%.2f,\"duration_ms\":%lu,\"peak_radps\":%.3f,\"axis_fraction\":%.3f}",
             i ? "," : "", static_cast<unsigned long>(gesture.sequence),
             static_cast<unsigned long>(gesture.startUs),static_cast<unsigned long>(gesture.endUs),
             'x'+gesture.axis,gesture.angleDegrees,
             static_cast<unsigned long>((gesture.endUs-gesture.startUs)/1000),gesture.peakRadps,gesture.axisFraction);
    response += gestureBody;
  }
  response += "]}";
  server.send(200, "application/json", response);
}

void handleRoot() {
  server.send(200, "text/plain", "OpenSki sensor API: GET /api/v1/imu, GET /api/v1/status (Bluetooth diagnostics)");
}

void handleStatus() {
  const bluetooth::Diagnostics ble = bluetooth::diagnostics();
  char body[1024];
  snprintf(body, sizeof(body),
           "{\"device\":\"%s\",\"build\":\"%s\",\"uptime_ms\":%llu,"
           "\"reset_reason\":%d,\"free_heap_bytes\":%lu,\"wifi_rssi_dbm\":%d,"
           "\"ble\":{\"connected\":%s,\"advertising\":%s,\"connections\":%lu,"
           "\"disconnections\":%lu,\"last_connected_ms\":%llu,"
           "\"last_disconnected_ms\":%llu,\"last_disconnect_reason\":%d},"
           "\"imu_samples\":%lu,\"imu_read_failures\":%lu}",
           config::kHostname, config::kBuildId,
           static_cast<unsigned long long>(esp_timer_get_time() / 1000ULL),
           static_cast<int>(esp_reset_reason()), static_cast<unsigned long>(ESP.getFreeHeap()), WiFi.RSSI(),
           bluetooth::connected() ? "true" : "false", bluetooth::advertisingNow() ? "true" : "false",
           static_cast<unsigned long>(ble.connections), static_cast<unsigned long>(ble.disconnections),
           static_cast<unsigned long long>(ble.lastConnectedMs),
           static_cast<unsigned long long>(ble.lastDisconnectedMs), ble.lastDisconnectReason,
           static_cast<unsigned long>(imu::monitor().stats().samples),
           static_cast<unsigned long>(imu::monitor().stats().readFailures));
  server.send(200, "application/json", body);
}

void handleImu() {
  const imu::ImuMonitor& monitor = imu::monitor();
  const imu::Stats& stats = monitor.stats();
  const recorder::Status recorderStatus = recorder::status();
  char body[1024];

  if (!monitor.hasSample()) {
    snprintf(body, sizeof(body),
             "{\"device\":\"%s\",\"build\":\"%s\",\"sensor\":\"%s\",\"ready\":%s,"
             "\"has_sample\":false,\"samples\":%lu,\"read_failures\":%lu,"
             "\"recorder_ready\":%s,\"recording\":%s,\"recorded_samples\":%lu,"
             "\"recording_capacity_samples\":%lu,\"dropped_samples\":%lu,"
             "\"recorder_partition_found\":%s,\"recorder_partition_bytes\":%lu}",
             config::kHostname, config::kBuildId, monitor.sensorName(), stats.ready ? "true" : "false",
             static_cast<unsigned long>(stats.samples),
             static_cast<unsigned long>(stats.readFailures),
             recorderStatus.storageReady ? "true" : "false",
             recorderStatus.recording ? "true" : "false",
             static_cast<unsigned long>(recorderStatus.samples),
             static_cast<unsigned long>(recorderStatus.maxSamples),
             static_cast<unsigned long>(recorderStatus.droppedSamples),
             recorderStatus.partitionFound ? "true" : "false",
             static_cast<unsigned long>(recorderStatus.partitionBytes));
    server.send(200, "application/json", body);
    return;
  }

  const imu::Sample& sample = monitor.latest();
  snprintf(body, sizeof(body),
           "{\"device\":\"%s\",\"build\":\"%s\",\"sensor\":\"%s\",\"ready\":%s,"
           "\"has_sample\":true,\"timestamp_us\":%lu,"
           "\"accel_mps2\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f},"
           "\"gyro_radps\":{\"x\":%.5f,\"y\":%.5f,\"z\":%.5f},"
           "\"temperature_c\":%.2f,\"samples\":%lu,\"read_failures\":%lu,"
           "\"recorder_ready\":%s,\"recording\":%s,\"recorded_samples\":%lu,"
           "\"recording_capacity_samples\":%lu,\"dropped_samples\":%lu,"
           "\"recorder_partition_found\":%s,\"recorder_partition_bytes\":%lu}",
           config::kHostname, config::kBuildId, monitor.sensorName(), stats.ready ? "true" : "false",
           static_cast<unsigned long>(sample.timestampUs),
           sample.accelMps2.x, sample.accelMps2.y, sample.accelMps2.z,
           sample.gyroRadps.x, sample.gyroRadps.y, sample.gyroRadps.z,
           sample.temperatureC, static_cast<unsigned long>(stats.samples),
           static_cast<unsigned long>(stats.readFailures),
           recorderStatus.storageReady ? "true" : "false",
           recorderStatus.recording ? "true" : "false",
           static_cast<unsigned long>(recorderStatus.samples),
           static_cast<unsigned long>(recorderStatus.maxSamples),
           static_cast<unsigned long>(recorderStatus.droppedSamples),
           recorderStatus.partitionFound ? "true" : "false",
           static_cast<unsigned long>(recorderStatus.partitionBytes));
  server.send(200, "application/json", body);
}

void handleNotFound() {
  server.send(404, "application/json", "{\"error\":\"not_found\"}");
}
}  // namespace

void begin() {
  server.on("/", HTTP_GET, handleRoot);
  server.on("/api/v1/imu", HTTP_GET, handleImu);
  server.on("/api/v1/motion", HTTP_GET, handleMotion);
  server.on("/api/v1/status", HTTP_GET, handleStatus);
  server.on("/api/v1/ble", HTTP_GET, handleStatus);
  server.onNotFound(handleNotFound);
}

void tick() {
  if (!wifi::connected()) return;
  if (!serverStarted) {
    server.begin();
    serverStarted = true;
    Serial.printf("Sensor API ready: http://%s/api/v1/imu\n", WiFi.localIP().toString().c_str());
  }
  server.handleClient();
}
}  // namespace openski::telemetry
