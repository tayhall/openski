# Bring-up

1. Copy `include/wifi_config.example.h` to `include/wifi_config.h` and set the 2.4 GHz Wi-Fi credentials and a strong OTA password. The local header is ignored by Git.
2. Connect the ESP32-S3 board by USB. Build with `pio run -e esp32-s3` and flash the first firmware with `pio run -e esp32-s3 -t upload`.
3. Open the serial monitor with `pio device monitor -b 115200`. Expect boot diagnostics, a Wi-Fi IP address, and `OTA ready: openski-s3:3232`.
4. For later uploads, use the `esp32-s3-ota` environment with `PLATFORMIO_UPLOAD_PORT` set to the device IP and `PLATFORMIO_UPLOAD_FLAGS` set to `--auth=YOUR_OTA_PASSWORD`. For example, export those values in your shell, then run `pio run -e esp32-s3-ota -t upload`. Keep the OTA password out of the repository and shell history.

The device retries Wi-Fi every 10 seconds. OTA becomes available after Wi-Fi connects. If the local header is missing, firmware still builds and prints setup guidance over serial.

The default board target is `esp32-s3-devkitc-1` (8 MB flash, no PSRAM). Check your board's flash and USB connection before flashing; adjust `platformio.ini` if your hardware differs.
