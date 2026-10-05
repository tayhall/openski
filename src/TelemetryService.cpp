#include "TelemetryService.h"

#include <Arduino.h>
#include <WebServer.h>
#include <WiFi.h>

#include "AppConfig.h"
#include "ImuService.h"
#include "RecorderService.h"
#include "WifiService.h"

namespace openski::telemetry {
namespace {
WebServer server(80);
bool serverStarted = false;

void handleRoot() {
  server.send(200, "text/plain", "OpenSki sensor API: GET /api/v1/imu");
}

void handleImu() {
  const imu::ImuMonitor& monitor = imu::monitor();
  const imu::Stats& stats = monitor.stats();
  const recorder::Status recorderStatus = recorder::status();
  char body[1024];

  if (!monitor.hasSample()) {
    snprintf(body, sizeof(body),
             "{\"device\":\"%s\",\"sensor\":\"%s\",\"ready\":%s,"
             "\"has_sample\":false,\"samples\":%lu,\"read_failures\":%lu,"
             "\"recorder_ready\":%s,\"recording\":%s,\"recorded_samples\":%lu,"
             "\"recording_capacity_samples\":%lu,\"dropped_samples\":%lu,"
             "\"recorder_partition_found\":%s,\"recorder_partition_bytes\":%lu}",
             config::kHostname, monitor.sensorName(), stats.ready ? "true" : "false",
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
           "{\"device\":\"%s\",\"sensor\":\"%s\",\"ready\":%s,"
           "\"has_sample\":true,\"timestamp_us\":%lu,"
           "\"accel_mps2\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f},"
           "\"gyro_radps\":{\"x\":%.5f,\"y\":%.5f,\"z\":%.5f},"
           "\"temperature_c\":%.2f,\"samples\":%lu,\"read_failures\":%lu,"
           "\"recorder_ready\":%s,\"recording\":%s,\"recorded_samples\":%lu,"
           "\"recording_capacity_samples\":%lu,\"dropped_samples\":%lu,"
           "\"recorder_partition_found\":%s,\"recorder_partition_bytes\":%lu}",
           config::kHostname, monitor.sensorName(), stats.ready ? "true" : "false",
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
