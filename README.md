# OpenSki

OpenSki is an experimental boot-sensor firmware and native Android project for training, recording, analysis and phone-only whole-day tracking. The default target is the classic ESP32 DevKit V1 used for the proof of concept; an ESP32-S3 target is also available. Firmware boots, reports diagnostics over serial, joins Wi-Fi, exposes the latest IMU sample over HTTP, streams IMU samples over BLE, records short sessions to built-in flash, and accepts Arduino OTA updates. Chip-independent drivers support MPU-6050 and LSM6DSOX; XIAO left/right builds add the new boot configuration and a live LIS3MDL magnetometer on the left. Ski turn classification is not implemented.

See [current project status](docs/project-status.md) for the review through merged PR #43 (19 October 2026), architecture, evidence and validation gaps.

## Current capabilities

- Training home, nine demo/live drills, scores and progress, eight technique videos and rule-based audio coaching.
- Two-boot run flow with zeroing, half-turn events, flash recovery, reconstructed target comparison and optional phone GPS summaries.
- Local recording/replay, video alignment and ZIP exports with raw samples, quality/health, experimental turn/torso metrics, phone motion and clock-alignment evidence.
- Whole-day phone GPS/barometer tracking, descent/lift/rest splitting, cached OpenStreetMap resort naming, tags, GPX import and separate GPX/JSON exports. This new flow has synthetic coverage and needs phone and real-day validation.

Cuff roll and drill scores are experimental and do not establish snow-relative edge angle or technique quality. New XIAO hardware, timing and torso analysis need real-data validation; see [garden trials](docs/garden-trial-protocol.md) and [Android acceptance](docs/android-acceptance.md).

## Quick start

1. Install PlatformIO Core or the PlatformIO VS Code extension.
2. Copy `include/wifi_config.example.h` to `include/wifi_config.h` and enter your Wi-Fi credentials and OTA password. This local file is ignored by Git.
3. Run `pio run` for the default ESP32 DevKit V1, or `pio run -e esp32-s3` for ESP32-S3 hardware.
4. Flash over USB with `pio run -e esp32-devkit-v1 -t upload` and inspect logs with `pio device monitor -b 115200`. Moving from the old ESPHome partition layout to the session recorder layout requires this one-time USB flash before OTA updates can continue.

See [bring-up](docs/bring-up.md) for OTA upload steps and expected serial output.
The current sample is available at `http://<device-ip>/api/v1/imu` on the local Wi-Fi network; see [IMU data](docs/imu-data.md).
The Android BLE protocol is documented in [BLE protocol](docs/ble-protocol.md).

The native Android client project is in [`android/`](android/). Open that folder in Android Studio to build and run the live telemetry and local session recording app. Sessions can include attached videos and recording continues with the screen off. See [Android setup](android/README.md) for SDK and device setup.
Each boot sensor keeps one full-rate session in flash (100 Hz MPU-6050, 104 Hz LSM6DSOX; capacity depends on the partition layout, about ten minutes on legacy 4 MB builds). The Android app records both live streams for full runs, downloads retained flash recordings after stopping or reconnecting, and erases sensor data only after validating the phone's saved copy. Sessions include quality summaries, graphs, video alignment, raw CSV export and experimental turn candidates. See the Android README for recovery and timing limitations.

## Layout

The [technique research integration note](docs/technique-research-integration.md) links the imported 2026-10-08 research snapshot and its sensor logging priorities. Its five candidate metrics are experimental; the evidence does not establish ideal-angle thresholds, performance scores or technique diagnoses.

- `src/`: firmware entry point and services for diagnostics, Wi-Fi, OTA, IMU, BLE, HTTP telemetry, and flash session recording.
- `include/`: shared interfaces and local configuration template.
- `lib/`: IMU interface/monitor plus separate chip drivers. MPU-6050 and LSM6DSOX drivers share normalized samples; portable motion recognisers and a LIS3MDL reader are also included.
- `test/`: Unity tests that run on the host (`native` environment).
- `android/`: Kotlin training, recording, analysis and ski-day app.
- `tools/`: capture utilities, host checks and analysis/trial reports.
- `docs/`: setup, protocols, designs, research, validation plans and dated results.
