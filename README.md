# OpenSki

OpenSki is an ESP32-S3 ski motion sensor firmware project. The first milestone boots, reports diagnostics over serial, joins Wi-Fi, and accepts Arduino OTA firmware updates. It also defines a chip-independent IMU interface; no IMU chip driver is selected yet, and ski turn classification is not implemented.

## Quick start

1. Install PlatformIO Core or the PlatformIO VS Code extension.
2. Copy `include/wifi_config.example.h` to `include/wifi_config.h` and enter your Wi-Fi credentials and OTA password. This local file is ignored by Git.
3. Run `pio run -e esp32-s3` to build, and `pio test -e native` to run host unit tests.
4. Flash once over USB with `pio run -e esp32-s3 -t upload` and inspect logs with `pio device monitor -b 115200`.

See [bring-up](docs/bring-up.md) for OTA upload steps and expected serial output.

## Layout

- `src/`: firmware entry point and services for diagnostics, Wi-Fi, OTA, and the IMU.
- `include/`: shared interfaces and local configuration template.
- `lib/`: hardware-independent libraries. `lib/Imu` holds the `ImuSensor` driver interface and `ImuMonitor`; chip drivers implement `ImuSensor`.
- `test/`: Unity tests that run on the host (`native` environment).
- `docs/`: setup and bring-up notes.
