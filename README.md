# OpenSki

OpenSki is modular firmware for a ski boot motion sensor. The default target is the classic ESP32 DevKit V1 used for the proof of concept; an ESP32-S3 target is also available. Firmware boots, reports diagnostics over serial, joins Wi-Fi, exposes the latest IMU sample over HTTP, streams IMU samples over BLE, records short sessions to built-in flash, and accepts Arduino OTA updates. The current prototype reads MPU-6050 samples through a chip-independent IMU interface. Ski turn classification is not implemented.

## Quick start

1. Install PlatformIO Core or the PlatformIO VS Code extension.
2. Copy `include/wifi_config.example.h` to `include/wifi_config.h` and enter your Wi-Fi credentials and OTA password. This local file is ignored by Git.
3. Run `pio run` for the default ESP32 DevKit V1, or `pio run -e esp32-s3` for ESP32-S3 hardware.
4. Flash over USB with `pio run -e esp32-devkit-v1 -t upload` and inspect logs with `pio device monitor -b 115200`. Moving from the old ESPHome partition layout to the session recorder layout requires this one-time USB flash before OTA updates can continue.

See [bring-up](docs/bring-up.md) for OTA upload steps and expected serial output.
The current sample is available at `http://<device-ip>/api/v1/imu` on the local Wi-Fi network; see [IMU data](docs/imu-data.md).
The Android BLE protocol is documented in [BLE protocol](docs/ble-protocol.md).

The native Android client project is in [`android/`](android/). Open that folder in Android Studio to build and run the live telemetry and local session recording app. Sessions can include attached videos and recording continues with the screen off. See [Android setup](android/README.md) for SDK and device setup.
Each boot sensor keeps one 100 Hz session of about 10 minutes in its flash filesystem. The Android app is intended to record both live streams for full runs, download any missing data from each sensor, and erase a sensor session only after validating the copy.

## Layout

- `src/`: firmware entry point and services for diagnostics, Wi-Fi, OTA, IMU, BLE, HTTP telemetry, and flash session recording.
- `include/`: shared interfaces and local configuration template.
- `lib/`: IMU interface/monitor plus separate chip drivers. Replace the MPU-6050 driver with an LSM6DSOX driver without changing consumers of normalized samples.
- `test/`: Unity tests that run on the host (`native` environment).
- `docs/`: setup and bring-up notes.
