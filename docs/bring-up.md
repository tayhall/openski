# Bring-up

1. Copy `include/wifi_config.example.h` to `include/wifi_config.h` and set the 2.4 GHz Wi-Fi credentials and a strong OTA password. The local header is ignored by Git.
2. For the current ESP32 DevKit V1 proof of concept, build with `pio run` and flash the first firmware over USB with `pio run -t upload`. Use `-e esp32-s3` for ESP32-S3 hardware.
3. Open the serial monitor with `pio device monitor -b 115200`. Expect boot diagnostics, `IMU ready: MPU-6050`, a Wi-Fi IP address, and `OTA ready: ski:3232`. If the sensor is not found, check the SDA/SCL wiring, 3.3 V supply, ground, and I2C address. Samples are logged twice per second with acceleration in m/s², angular rate in rad/s, and sensor temperature in °C. The 30-second status report includes sample count and read failures.
4. For later uploads, use the `esp32-devkit-v1-ota` environment with `PLATFORMIO_UPLOAD_PORT` set to the device IP and `PLATFORMIO_UPLOAD_FLAGS` set to `--auth=YOUR_OTA_PASSWORD`. The ESP32-S3 OTA environment is `esp32-s3-ota`. Keep the OTA password out of the repository and shell history.

The device retries Wi-Fi every 10 seconds. OTA becomes available after Wi-Fi connects. If the local header is missing, firmware still builds and prints setup guidance over serial.

The default board target is `esp32dev` (classic ESP32, 4 MB flash). ESP32-S3 uses `esp32-s3-devkitc-1` (8 MB flash, no PSRAM). The MPU-6050 driver defaults to SDA GPIO21, SCL GPIO22, address 0x68; these values are centralized in `include/AppConfig.h` for board and sensor swaps.

The DevKit session recorder requires the SPIFFS data partition in `partitions/esp32dev-4mb.csv`. A device using its earlier ESPHome partition table needs a one-time USB flash of the application and partition table. OTA updates only replace the application, so they cannot add the filesystem partition.

## ESP32-S3 Super Mini — 4 MB board

The board identified over COM13 on 7 October 2026 is an ESP32-S3 revision 0.2 with 4 MB embedded flash and 2 MB embedded PSRAM. Use `pio run -e esp32-s3-supermini -t upload --upload-port COM13`, not the generic 8 MB S3 environment. The dedicated target uses the existing 4 MB OTA/SPIFFS partition layout, DIO flash and native USB serial. PSRAM is not enabled or required by this firmware.

MPU-6050 wiring for this target: 3.3 V → VCC, GND → GND, GPIO8 → SDA and GPIO9 → SCL, address 0x68. GPIO22 is unavailable on ESP32-S3. The classic DevKit target retains GPIO21/22. Board-specific build flags select the pins without changing the classic board's defaults.

The S3 hostname is `ski-s3` to avoid conflicting with the existing `ski` board. OTA uses `esp32-s3-supermini-ota`. BLE still advertises `OpenSki-ski`; distinguish the units by the app's address label. This unit's MAC is `d4:05:92:40:20:f0`.

First USB flash passed esptool hash verification. Wi-Fi/OTA/API came up at 192.168.43.28 (DHCP may change). The API confirmed recorder storage ready, a 1,441,792-byte partition and 57,662-sample capacity. No MPU-6050 was detected on GPIO8/9 at the time of verification, so IMU sampling and BLE sensor delivery remain unverified until wiring is confirmed.
