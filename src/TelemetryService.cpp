#include "TelemetryService.h"

#include <Arduino.h>
#include <WebServer.h>
#include <WiFi.h>
#include <Wire.h>
#include <esp_timer.h>
#include <esp_system.h>
#include <string.h>

#include "AppConfig.h"
#include "BatteryService.h"
#include "BluetoothService.h"
#include "ImuService.h"
#ifdef OPENSKI_IMU_LSM6DSOX
#include "Lsm6dsoxProfile.h"
#else
#include "Mpu6050Profile.h"
#endif
#include "SensorCalibration.h"
#include "MagService.h"
#ifdef OPENSKI_MAG_LIS3MDL
#include "Lis3mdlMagnetometer.h"
#endif
#include "MotionService.h"
#include "RecorderService.h"
#include "SleepService.h"
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
  char headingHead[128];
  snprintf(headingHead, sizeof(headingHead),
           "],\"heading_count\":%lu,\"heading_rejected\":%lu,\"heading_turns\":[",
           static_cast<unsigned long>(ski.headingCount), static_cast<unsigned long>(ski.headingRejected));
  json += headingHead;
  motion::HeadingTurn turns[16];
  const uint8_t turnCount = motion::recentHeadingTurns(turns, 16);
  for (uint8_t i = 0; i < turnCount; ++i) {
    const auto& t = turns[i];
    char body[288];
    snprintf(body, sizeof(body),
             "%s{\"sequence\":%lu,\"start_ms\":%lu,\"end_ms\":%lu,\"positive\":%s,"
             "\"peak_rate_dps\":%.1f,\"change_deg\":%.2f,\"skipped\":%s,\"near_rail\":%s}",
             i ? "," : "", static_cast<unsigned long>(t.sequence), static_cast<unsigned long>(t.startUs/1000),
             static_cast<unsigned long>(t.endUs/1000), t.positive ? "true" : "false", t.peakRateDps,
             t.changeDegrees, t.skippedSamples ? "true" : "false", t.nearRail ? "true" : "false");
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
  float bias[3];
  motion::gyroBias(bias);
  char biasBody[128];
  snprintf(biasBody, sizeof(biasBody),
           ",\"gyro_bias_radps\":[%.5f,%.5f,%.5f],\"bias_updates\":%lu,\"bias_refused\":%lu",
           bias[0], bias[1], bias[2], static_cast<unsigned long>(motion::biasUpdates()),
           static_cast<unsigned long>(motion::biasRefused()));
  response += biasBody;
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

void handleSleep() {
  const sleep::Result result = sleep::request();
  if (result == sleep::Result::kRecording) {
    server.send(409, "application/json", "{\"error\":\"recording\"}");
    return;
  }
  server.send(200, "application/json", "{\"sleeping\":true,\"wake_accel_mps2\":0.1,\"wake_gyro_radps\":0.1}");
}

void handleSleepStatus() {
  const sleep::LastWake wake = sleep::lastWake();
  char body[200];
  snprintf(body, sizeof(body),
           "{\"sleep_pending\":%s,\"woke_from_sleep\":%s,\"slept_ms\":%lu,\"wake_trigger\":\"%s\",\"wake_delta\":%.4f}",
           sleep::pending() ? "true" : "false", wake.wokeFromSleep ? "true" : "false",
           static_cast<unsigned long>(wake.sleptMs), wake.gyroTrigger ? "gyro_radps" : "accel_mps2",
           wake.triggerDelta);
  server.send(200, "application/json", body);
}

void handleRoot() {
  server.send(200, "text/plain", "OpenSki sensor API: GET /api/v1/imu, GET /api/v1/status (Bluetooth diagnostics)");
}

void formatBatteryVolts(char* out, size_t size, const battery::Reading& reading) {
  if (reading.valid) {
    snprintf(out, size, "%.3f", reading.volts);
  } else {
    snprintf(out, size, "null");
  }
}

void handleBattery() {
  const battery::Reading reading = battery::reading();
  char volts[16];
  formatBatteryVolts(volts, sizeof(volts), reading);
  char body[256];
  snprintf(body, sizeof(body),
           "{\"available\":%s,\"battery_voltage_v\":%s,\"pin_mv\":%.1f,\"divider_ratio\":%.2f,"
           "\"samples_per_reading\":16,\"interval_ms\":60000,\"age_ms\":%lu,"
           "\"charge_percent\":null}",
           reading.available ? "true" : "false", volts, reading.pinMillivolts, config::kBatteryDividerRatio,
           static_cast<unsigned long>(reading.ageMs));
  server.send(200, "application/json", body);
}

void handleStatus() {
  const bluetooth::Diagnostics ble = bluetooth::diagnostics();
  char batteryVolts[16];
  formatBatteryVolts(batteryVolts, sizeof(batteryVolts), battery::reading());
  char body[1100];
  snprintf(body, sizeof(body),
           "{\"device\":\"%s\",\"build\":\"%s\",\"uptime_ms\":%llu,"
           "\"reset_reason\":%d,\"free_heap_bytes\":%lu,\"wifi_rssi_dbm\":%d,"
           "\"ble\":{\"connected\":%s,\"advertising\":%s,\"connections\":%lu,"
           "\"disconnections\":%lu,\"last_connected_ms\":%llu,"
           "\"last_disconnected_ms\":%llu,\"last_disconnect_reason\":%d},"
           "\"imu_samples\":%lu,\"imu_read_failures\":%lu,\"battery_voltage_v\":%s,"
           "\"power_profile\":\"%s\"}",
           config::kHostname, config::kBuildId,
           static_cast<unsigned long long>(esp_timer_get_time() / 1000ULL),
           static_cast<int>(esp_reset_reason()), static_cast<unsigned long>(ESP.getFreeHeap()), WiFi.RSSI(),
           bluetooth::connected() ? "true" : "false", bluetooth::advertisingNow() ? "true" : "false",
           static_cast<unsigned long>(ble.connections), static_cast<unsigned long>(ble.disconnections),
           static_cast<unsigned long long>(ble.lastConnectedMs),
           static_cast<unsigned long long>(ble.lastDisconnectedMs), ble.lastDisconnectReason,
           static_cast<unsigned long>(imu::monitor().stats().samples),
           static_cast<unsigned long>(imu::monitor().stats().readFailures), batteryVolts,
           battery::brownoutBoot() ? "safe_after_brownout" : "normal");
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

#ifdef OPENSKI_IMU_LSM6DSOX
namespace imuProfile = imu::lsmProfile;
#else
namespace imuProfile = imu::mpuProfile;
#endif
static_assert(imuProfile::kSampleRateHz == config::kImuNominalRateHz,
              "the profile sample rate and config::kImuNominalRateHz must agree");

void handleAcquisition() {
  const auto& monitor = imu::monitor();
  const auto& stats = monitor.stats();
  // The recogniser calibration is a fixed per-unit constant set; flag when it was measured under a
  // different acquisition profile than the one compiled in (e.g. legacy 8 g / 42 Hz vs research 16 g / 20 Hz).
  const char* measuredProfile = calibration::kMeasuredAcquisitionProfile;
  const bool calibrationNone = strcmp(measuredProfile, "none") == 0;
  const bool calibrationMatches = calibrationNone || strcmp(measuredProfile, imuProfile::kId) == 0;
  // 0 means the bandwidth has not been verified for this profile: report null, not a placeholder.
  char bandwidth[12] = "null";
  if (imuProfile::kNominalGyroBandwidthHz != 0) {
    snprintf(bandwidth, sizeof(bandwidth), "%u", static_cast<unsigned>(imuProfile::kNominalGyroBandwidthHz));
  }
  char body[2100];
  snprintf(body, sizeof(body),
      "{\"schema_version\":1,\"profile_id\":\"%s\","
      "\"research_baseline\":\"openski_pre_field_experiments_v0.1\","
      "\"sensor\":\"%s\",\"ready\":%s,\"device\":\"%s\",\"build\":\"%s\","
      "\"frame\":\"sensor\",\"acceleration_includes_gravity\":true,"
      "\"raw_calibration_applied\":false,"
      "\"recogniser_calibration_profile\":\"%s\",\"recogniser_calibration_matches_profile\":%s,"
      "\"boot_side\":null,"
      "\"sensor_to_boot_rotation\":null,\"calibration_reference\":null,"
      "\"nominal_sample_rate_hz\":%u,\"accel_full_scale_g\":%u,"
      "\"gyro_full_scale_deg_s\":%u,\"nominal_gyro_bandwidth_hz\":%s,"
      "\"timestamp_unit\":\"us\",\"timestamp_bits\":32,"
      "\"timestamp_reference\":\"device_read_completion\","
      "\"accel_unit\":\"m/s2\",\"gyro_unit\":\"rad/s\","
      "\"sample_count\":%lu,\"read_failures\":%lu,\"stalls\":%lu,"
      "\"gap_review_threshold_us\":20000,\"timestamp_gaps\":%lu,"
      "\"timestamp_wraps\":%lu,\"last_interval_us\":%lu,\"max_interval_us\":%lu,"
      "\"accel_near_rail_samples\":%lu,\"gyro_near_rail_samples\":%lu,"
      "\"latest_quality_flags\":%u,\"has_sample\":%s,"
      "\"quality_scope\":\"since_device_boot\",\"dual_boot_synchronised\":false,"
      "\"validation_status\":\"bench_and_field_validation_required\"}",
      imuProfile::kId, monitor.sensorName(), stats.ready ? "true" : "false",
      config::kHostname, config::kBuildId, measuredProfile, calibrationMatches ? "true" : "false",
      imuProfile::kSampleRateHz, imuProfile::kAccelRangeG,
      imuProfile::kGyroRangeDps, bandwidth,
      static_cast<unsigned long>(stats.samples), static_cast<unsigned long>(stats.readFailures),
      static_cast<unsigned long>(stats.stalls),
      static_cast<unsigned long>(stats.timestampGaps), static_cast<unsigned long>(stats.timestampWraps),
      static_cast<unsigned long>(stats.lastIntervalUs), static_cast<unsigned long>(stats.maxIntervalUs),
      static_cast<unsigned long>(stats.accelNearRailSamples),
      static_cast<unsigned long>(stats.gyroNearRailSamples),
      static_cast<unsigned>(monitor.latest().qualityFlags), monitor.hasSample() ? "true" : "false");
  server.send(200, "application/json", body);
}

// Latest raw LIS3MDL reading (left boot build only), so the magnetometer can be checked without BLE or serial.
// Uncalibrated: no hard-iron or soft-iron correction.
void handleMag() {
#ifdef OPENSKI_MAG_LIS3MDL
  const magnetometer::Status mag = magnetometer::status();
  char body[384];
  if (!mag.hasSample) {
    snprintf(body, sizeof(body),
             "{\"device\":\"%s\",\"built\":true,\"sensor\":\"LIS3MDL\",\"present\":%s,\"has_sample\":false,"
             "\"samples\":%lu,\"read_errors\":%lu}",
             config::kHostname, mag.present ? "true" : "false", static_cast<unsigned long>(mag.samples),
             static_cast<unsigned long>(mag.readErrors));
  } else {
    constexpr float kPerGauss = mag::Lis3mdlMagnetometer::kCountsPerGauss;
    snprintf(body, sizeof(body),
             "{\"device\":\"%s\",\"built\":true,\"sensor\":\"LIS3MDL\",\"present\":%s,\"has_sample\":true,"
             "\"raw\":{\"x\":%d,\"y\":%d,\"z\":%d},\"gauss\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f},"
             "\"calibration\":\"uncalibrated\",\"samples\":%lu,\"read_errors\":%lu}",
             config::kHostname, mag.present ? "true" : "false", mag.rawX, mag.rawY, mag.rawZ,
             mag.rawX / kPerGauss, mag.rawY / kPerGauss, mag.rawZ / kPerGauss,
             static_cast<unsigned long>(mag.samples), static_cast<unsigned long>(mag.readErrors));
  }
  server.send(200, "application/json", body);
#else
  char body[96];
  snprintf(body, sizeof(body), "{\"device\":\"%s\",\"built\":false}", config::kHostname);
  server.send(200, "application/json", body);
#endif
}

void handleNotFound() {
  server.send(404, "application/json", "{\"error\":\"not_found\"}");
}

// Only probe an idle, unavailable IMU so diagnostics cannot interrupt acquisition.
void handleI2cDiagnostic() {
  if (imu::monitor().stats().ready || recorder::status().recording) {
    server.send(409, "application/json", "{\"error\":\"sensor_or_recorder_active\"}");
    return;
  }
  String body = "{\"sda_gpio\":" + String(config::kImuSdaPin) +
                ",\"scl_gpio\":" + String(config::kImuSclPin) +
                ",\"sda_high\":" + String(digitalRead(config::kImuSdaPin)) +
                ",\"scl_high\":" + String(digitalRead(config::kImuSclPin)) +
                ",\"scan_hz\":100000,\"devices\":[";
  Wire.setClock(100000);
  bool first = true;
  unsigned busErrors = 0;
  for (uint8_t address = 8; address < 120; ++address) {
    Wire.beginTransmission(address);
    const uint8_t result = Wire.endTransmission();
    if (result != 0) {
      if (result != 2) ++busErrors;  // Address NACK is expected for absent devices.
      continue;
    }
    if (!first) body += ",";
    first = false;
    body += "{\"address\":" + String(address);
    Wire.beginTransmission(address);
    Wire.write(0x0f);  // WHO_AM_I on the ST IMU and magnetometer.
    if (Wire.endTransmission(false) == 0 && Wire.requestFrom(address, uint8_t(1)) == 1) {
      body += ",\"who_am_i\":" + String(Wire.read());
    }
    body += "}";
  }
  Wire.setClock(config::kImuI2cFrequencyHz);
  body += "],\"bus_errors\":" + String(busErrors) + "}";
  server.send(200, "application/json", body);
}

// Independent open-drain address probe, bypassing TwoWire and the ESP32 I2C peripheral.
void handleSoftwareI2cDiagnostic() {
  if (imu::monitor().stats().ready || recorder::status().recording) {
    server.send(409, "application/json", "{\"error\":\"sensor_or_recorder_active\"}");
    return;
  }
  int sda = config::kImuSdaPin;
  int scl = config::kImuSclPin;
  const String profile = server.hasArg("profile") ? server.arg("profile") : "configured";
  // Only documented board mappings; never scan arbitrary GPIO combinations.
  if (profile == "qtpy-pads") { sda = 7; scl = 6; }
  else if (profile == "qtpy-jst") { sda = 41; scl = 40; }
  else if (profile != "configured") {
    server.send(400, "application/json", "{\"error\":\"unknown_pin_profile\"}");
    return;
  }
  Wire.end();
  digitalWrite(sda, HIGH);
  digitalWrite(scl, HIGH);
  pinMode(sda, OUTPUT_OPEN_DRAIN | PULLUP);
  pinMode(scl, OUTPUT_OPEN_DRAIN | PULLUP);
  const auto clockHigh = [&]() {
    digitalWrite(scl, HIGH);
    const uint32_t started = micros();
    while (digitalRead(scl) == LOW) {
      if (micros() - started >= 1000) return false;
    }
    delayMicroseconds(50);
    return true;
  };
  const auto stop = [&]() {
    digitalWrite(scl, LOW);
    digitalWrite(sda, LOW);
    delayMicroseconds(50);
    clockHigh();
    digitalWrite(sda, HIGH);
    delayMicroseconds(50);
  };
  // Complete a bus-clear sequence and STOP before probing.
  digitalWrite(sda, HIGH);
  for (int i = 0; i < 9; ++i) {
    digitalWrite(scl, LOW);
    delayMicroseconds(50);
    if (!clockHigh()) break;
  }
  stop();
  const bool idleSda = digitalRead(sda);
  const bool idleScl = digitalRead(scl);
  digitalWrite(sda, LOW);
  const bool sdaCanPullLow = digitalRead(sda) == LOW;
  digitalWrite(sda, HIGH);
  digitalWrite(scl, LOW);
  const bool sclCanPullLow = digitalRead(scl) == LOW;
  digitalWrite(scl, HIGH);
  delayMicroseconds(50);
  String body = "{\"method\":\"software_open_drain_10khz\",\"profile\":\"" + profile +
                "\",\"sda_gpio\":" + String(sda) +
                ",\"scl_gpio\":" + String(scl) + ",\"idle_sda_high\":" + String(idleSda) +
                ",\"idle_scl_high\":" + String(idleScl) +
                ",\"sda_can_pull_low\":" + String(sdaCanPullLow) +
                ",\"scl_can_pull_low\":" + String(sclCanPullLow) + ",\"devices\":[";
  bool first = true;
  unsigned failures = 0;
  if (idleSda && idleScl) {
    for (uint8_t address = 8; address < 120; ++address) {
      digitalWrite(sda, LOW);  // START while SCL is high.
      delayMicroseconds(50);
      bool clockOk = true;
      for (int bit = 7; bit >= 0; --bit) {
        digitalWrite(scl, LOW);
        digitalWrite(sda, ((address << 1) >> bit) & 1);
        delayMicroseconds(50);
        if (!clockHigh()) { clockOk = false; break; }
      }
      digitalWrite(scl, LOW);
      digitalWrite(sda, HIGH);  // Release SDA for the slave's ACK.
      delayMicroseconds(50);
      clockOk = clockHigh() && clockOk;
      const bool acknowledged = clockOk && digitalRead(sda) == LOW;
      stop();
      if (!clockOk) ++failures;
      if (acknowledged) {
        if (!first) body += ",";
        first = false;
        body += String(address);
      }
      delay(1);
    }
  }
  pinMode(sda, INPUT_PULLUP);
  pinMode(scl, INPUT_PULLUP);
  const bool restored = Wire.begin(config::kImuSdaPin, config::kImuSclPin,
                                   config::kImuI2cFrequencyHz);
  body += "],\"clock_failures\":" + String(failures) +
          ",\"hardware_bus_restored\":" + String(restored ? "true" : "false") + "}";
  server.send(200, "application/json", body);
}

// Deliberately slow, bounded signal test for a DC voltmeter. Diagnostic use only:
// the main loop pauses while the selected line is held low, preventing IMU retries
// from reconfiguring the pins during the measurement.
void handleI2cMeterTest() {
  if (imu::monitor().stats().ready || recorder::status().recording) {
    server.send(409, "application/json", "{\"error\":\"sensor_or_recorder_active\"}");
    return;
  }
  const String line = server.arg("line");
  if (line != "sda" && line != "scl") {
    server.send(400, "application/json", "{\"error\":\"line_must_be_sda_or_scl\"}");
    return;
  }
  const int sda = config::kImuSdaPin;
  const int scl = config::kImuSclPin;
  const int pin = line == "sda" ? sda : scl;
  Wire.end();
  digitalWrite(sda, HIGH);
  digitalWrite(scl, HIGH);
  pinMode(sda, OUTPUT_OPEN_DRAIN | PULLUP);
  pinMode(scl, OUTPUT_OPEN_DRAIN | PULLUP);
  delay(10);
  const bool beforeHigh = digitalRead(pin);
  digitalWrite(pin, LOW);
  delay(15000);  // Open-drain low only; never drive a signal strongly high.
  const bool heldLow = digitalRead(pin) == LOW;
  digitalWrite(pin, HIGH);
  delay(10);
  const bool releasedHigh = digitalRead(pin);
  pinMode(sda, INPUT_PULLUP);
  pinMode(scl, INPUT_PULLUP);
  const bool restored = Wire.begin(sda, scl, config::kImuI2cFrequencyHz);
  String body = "{\"line\":\"" + line + "\",\"gpio\":" + String(pin) +
                ",\"hold_ms\":15000,\"before_high\":" + String(beforeHigh) +
                ",\"held_low\":" + String(heldLow) +
                ",\"released_high\":" + String(releasedHigh) +
                ",\"hardware_bus_restored\":" + String(restored ? "true" : "false") + "}";
  server.send(200, "application/json", body);
}

#if defined(OPENSKI_ADC_DIAGNOSTIC) && defined(CONFIG_IDF_TARGET_ESP32S3)
void handleAdcDiagnostic() {
  if (recorder::status().recording || bluetooth::connected()) {
    server.send(409, "application/json", "{\"error\":\"stop_recording_and_disconnect_ble_before_adc_scan\"}");
    return;
  }
  // ADC1 GPIO1..10 only. Never touch IMU I2C pins, USB, boot straps,
  // flash/PSRAM pins or ADC2 while Wi-Fi is active. No outputs or pulls.
  const uint8_t pins[] = {1, 2, 4, 5, 6, 7, 10};
  analogReadResolution(12);
  String json = "{\"method\":\"unused_adc1_scan_v1\",\"battery_voltage_v\":null,"
                "\"charge_percent\":null,\"interpretation\":\"unverified_pin_voltages\","
                "\"attenuation\":\"11db\",\"samples_per_pin\":16,\"pins\":[";
  bool first = true;
  for (uint8_t pin : pins) {
    if (pin == config::kImuSdaPin || pin == config::kImuSclPin || pin == config::kBatteryAdcPin) continue;
    analogSetPinAttenuation(pin, ADC_11db);
    analogRead(pin);  // Discard initial conversion after channel setup.
    uint32_t rawSum = 0, mvSum = 0, minMv = UINT32_MAX, maxMv = 0;
    for (int i = 0; i < 16; ++i) {
      rawSum += analogRead(pin);
      const uint32_t mv = analogReadMilliVolts(pin);
      mvSum += mv;
      if (mv < minMv) minMv = mv;
      if (mv > maxMv) maxMv = mv;
    }
    char item[180];
    snprintf(item, sizeof(item), "%s{\"gpio\":%u,\"mean_raw\":%.1f,\"mean_mv\":%.1f,\"min_mv\":%lu,\"max_mv\":%lu}",
             first ? "" : ",", static_cast<unsigned>(pin), rawSum / 16.0f, mvSum / 16.0f,
             static_cast<unsigned long>(minMv), static_cast<unsigned long>(maxMv));
    json += item;
    first = false;
  }
  json += "]}";
  server.send(200, "application/json", json);
}
#endif
}  // namespace

void begin() {
#if defined(OPENSKI_ADC_DIAGNOSTIC) && defined(CONFIG_IDF_TARGET_ESP32S3)
  server.on("/api/v1/diagnostics/adc", HTTP_GET, handleAdcDiagnostic);
#endif
  server.on("/", HTTP_GET, handleRoot);
  server.on("/api/v1/imu", HTTP_GET, handleImu);
  server.on("/api/v1/diagnostics/i2c", HTTP_GET, handleI2cDiagnostic);
  server.on("/api/v1/diagnostics/i2c-software", HTTP_GET, handleSoftwareI2cDiagnostic);
  server.on("/api/v1/diagnostics/i2c-meter", HTTP_POST, handleI2cMeterTest);
  server.on("/api/v1/acquisition", HTTP_GET, handleAcquisition);
  server.on("/api/v1/motion", HTTP_GET, handleMotion);
  server.on("/api/v1/motion/zero", HTTP_POST, handleZero);
  server.on("/api/v1/sleep", HTTP_POST, handleSleep);
  server.on("/api/v1/sleep", HTTP_GET, handleSleepStatus);
  server.on("/api/v1/battery", HTTP_GET, handleBattery);
  server.on("/api/v1/mag", HTTP_GET, handleMag);
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
