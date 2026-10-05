# Bring-up

1. Copy `include/wifi_config.example.h` to `include/wifi_config.h` and set the 2.4 GHz Wi-Fi credentials and a strong OTA password. The local header is ignored by Git.
2. For the current ESP32 DevKit V1 proof of concept, build with `pio run` and flash the first firmware over USB with `pio run -t upload`. Use `-e esp32-s3` for ESP32-S3 hardware.
3. Open the serial monitor with `pio device monitor -b 115200`. Expect boot diagnostics, `IMU ready: MPU-6050`, a Wi-Fi IP address, and `OTA ready: ski:3232`. If the sensor is not found, check the SDA/SCL wiring, 3.3 V supply, ground, and I2C address. Samples are logged twice per second with acceleration in m/s², angular rate in rad/s, and sensor temperature in °C. The 30-second status report includes sample count and read failures.
4. For later uploads, use the `esp32-devkit-v1-ota` environment with `PLATFORMIO_UPLOAD_PORT` set to the device IP and `PLATFORMIO_UPLOAD_FLAGS` set to `--auth=YOUR_OTA_PASSWORD`. The ESP32-S3 OTA environment is `esp32-s3-ota`. Keep the OTA password out of the repository and shell history.

The device retries Wi-Fi every 10 seconds. OTA becomes available after Wi-Fi connects. If the local header is missing, firmware still builds and prints setup guidance over serial.

The default board target is `esp32dev` (classic ESP32, 4 MB flash). ESP32-S3 uses `esp32-s3-devkitc-1` (8 MB flash, no PSRAM). The MPU-6050 driver defaults to SDA GPIO21, SCL GPIO22, address 0x68; these values are centralized in `include/AppConfig.h` for board and sensor swaps.

The DevKit session recorder requires the SPIFFS data partition in `partitions/esp32dev-4mb.csv`. A device using its earlier ESPHome partition table needs a one-time USB flash of the application and partition table. OTA updates only replace the application, so they cannot add the filesystem partition.
