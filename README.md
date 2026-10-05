# OpenSki

OpenSki is an ESP32-S3 ski motion sensor firmware project. The first milestone boots, reports diagnostics over serial, joins Wi-Fi, and accepts Arduino OTA firmware updates. Ski turn classification is not implemented.

## Quick start

1. Install PlatformIO Core or the PlatformIO VS Code extension.
2. Copy `include/wifi_config.example.h` to `include/wifi_config.h` and enter your Wi-Fi credentials and OTA password. This local file is ignored by Git.
3. Run `pio run -e esp32-s3` to build.
4. Flash once over USB with `pio run -e esp32-s3 -t upload` and inspect logs with `pio device monitor -b 115200`.

See [bring-up](docs/bring-up.md) for OTA upload steps and expected serial output.

## Layout

- `src/`: firmware entry point and services for diagnostics, Wi-Fi, and OTA.
- `include/`: shared interfaces and local configuration template.
- `lib/`: future hardware drivers.
- `docs/`: setup and bring-up notes.
