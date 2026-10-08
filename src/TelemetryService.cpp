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

String tiltJson() {
  const auto tilt = motion::tiltStatus();
  const char* const names[] = {"+x", "-x", "+y", "-y", "+z", "-z"};
  const char* vertical = "none";
  if (tilt.verticalAxis >= 0) vertical = names[tilt.verticalAxis*2 + (tilt.verticalSign > 0 ? 0 : 1)];
  char head[640];
  snprintf(head, sizeof(head),
           "{\"algorithm\":\"tilt_v1\",\"frame\":\"sensor\",\"neutral_ready\":%s,"
           "\"tilt_degrees\":%.2f,\"about_degrees\":{\"x\":%.2f,\"y\":%.2f,\"z\":%.2f},"
           "\"vertical_axis\":\"%s\",\"excursion_active\":%s,\"excursion_count\":%lu,"
           "\"rejected_excursions\":%lu,\"sample_gaps\":%lu,"
           "\"axes\":{\"x\":\"toward the mounting-hole edge\","
           "\"y\":\"right-hand rule from z and x (derived, not yet measured)\","
           "\"z\":\"the way the right-angle header pins exit the board (measured)\"},"
           "\"recent_excursions\":[",
           tilt.neutralReady ? "true" : "false", tilt.tiltDegrees, tilt.aboutDegrees[0],
           tilt.aboutDegrees[1], tilt.aboutDegrees[2], vertical, tilt.active ? "true" : "false",
           static_cast<unsigned long>(tilt.count), static_cast<unsigned long>(tilt.rejected),
           static_cast<unsigned long>(tilt.gaps));
  String json(head);
  motion::Excursion excursions[16];
  const uint8_t count = motion::recentExcursions(excursions, 16);
  for (uint8_t i = 0; i < count; ++i) {
    const auto& e = excursions[i];
    char body[256];
    snprintf(body, sizeof(body),
             "%s{\"sequence\":%lu,\"start_us\":%lu,\"peak_us\":%lu,\"end_us\":%lu,\"axis\":\"%c\","
             "\"peak_degrees\":%.2f,\"about_degrees\":%.2f,\"duration_ms\":%lu,\"axis_fraction\":%.3f}",
             i ? "," : "", static_cast<unsigned long>(e.sequence), static_cast<unsigned long>(e.startUs),
             static_cast<unsigned long>(e.peakUs), static_cast<unsigned long>(e.endUs), 'x'+e.axis,
             e.peakDegrees, e.aboutDegrees, static_cast<unsigned long>((e.endUs-e.startUs)/1000),
             e.axisFraction);
    json += body;
  }
  json += "]}";
  return json;
}

String skiJson() {
  const auto ski = motion::skiStatus();
  char head[320];
  snprintf(head, sizeof(head),
           "{\"algorithm\":\"ski_v0\",\"frame\":\"leg\",\"zeroed\":%s,\"zeroing\":%s,"
           "\"roll_degrees\":%.2f,\"pitch_degrees\":%.2f,\"half_turn_count\":%lu,"
           "\"rejected\":%lu,\"sample_gaps\":%lu,\"recent\":[",
           ski.zeroed ? "true" : "false", ski.zeroing ? "true" : "false", ski.rollDegrees,
           ski.pitchDegrees, static_cast<unsigned long>(ski.count),
           static_cast<unsigned long>(ski.rejected), static_cast<unsigned long>(ski.gaps));
  String json(head);
  motion::SkiEvent events[16];
  const uint8_t count = motion::recentSkiEvents(events, 16);
  for (uint8_t i = 0; i < count; ++i) {
    const auto& e = events[i];
    char body[288];
    snprintf(body, sizeof(body),
             "%s{\"sequence\":%lu,\"start_us\":%lu,\"end_us\":%lu,\"duration_ms\":%lu,"
             "\"peak_roll_degrees\":%.2f,\"peak_rate_dps\":%.1f,\"pitch_degrees\":%.2f,"
             "\"outside_envelope\":%s}",
             i ? "," : "", static_cast<unsigned long>(e.sequence), static_cast<unsigned long>(e.startUs),
             static_cast<unsigned long>(e.endUs), static_cast<unsigned long>((e.endUs - e.startUs)/1000),
             e.peakRollDegrees, e.peakRateDps, e.pitchDegrees, e.outsideEnvelope ? "true" : "false");
    json += body;
  }
  json += "]}";
  return json;
}

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
  response += "],\"tilt\":";
  response += tiltJson();
  response += ",\"ski\":";
  response += skiJson();
  response += "}";
  server.send(200, "application/json", response);
}

void handleZero() {
  motion::zeroMotion();
  server.send(200, "application/json", "{\"zeroing\":true}");
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
  server.on("/api/v1/motion/zero", HTTP_POST, handleZero);
  server.on("/api/v1/status", HTTP_GET, handleStatus);
  server.on("/api/v1/ble", HTTP_GET, handleStatus);
  server.onNotFound(handleNotFound);
}

void tick() {
  if (!wifi::connected()) {
    // Close the listener so it is rebuilt cleanly when Wi-Fi returns.
    if (serverStarted) {
      server.stop();
      serverStarted = false;
    }
    return;
  }
  if (!serverStarted) {
    server.begin();
    serverStarted = true;
    Serial.printf("Sensor API ready: http://%s/api/v1/imu\n", WiFi.localIP().toString().c_str());
  }
  server.handleClient();
}
}  // namespace openski::telemetry
