# XIAO IMU diagnostics

The XIAO ESP32S3 builds use SDA D4/GPIO5 and SCL D5/GPIO6. The installed Arduino board definition agrees with Seeed's pinout.

With the IMU unavailable and flash recording stopped, `GET /api/v1/diagnostics/i2c` reports the idle pin levels, scans addresses 0x08 to 0x77 at 100 kHz, and reads the ST WHO_AM_I register 0x0F from responding devices. It restores the configured bus frequency afterwards and returns HTTP 409 when the IMU is ready or recording is active. It runs synchronously in the firmware loop and writes no sensor registers.

Device addresses in JSON are decimal: LSM6DSOX 0x6A/0x6B = 106/107; LIS3MDL 0x1C/0x1E = 28/30. No address response means the identification and configuration checks have not been reached. High idle levels alone do not prove cable continuity or sensor power.

## 21 to 27 October 2026 bring-up

On 21 October neither XIAO's IMU answered on the bus, and the scan found no devices on either board. A bit-banged software scan (including the QT Py pin mappings) and a voltmeter test that held SDA or SCL low showed the GPIOs reached the sensor pads, so a firmware mapping fault was ruled out. The fault was in the sensor assemblies and their mounting: after the sensor assemblies were removed from their mounting headers and reseated, the IMU worked. Both boots now stream (left `ski-xiao-l`, 192.168.43.185, on battery; right `ski-xiao-r`, 192.168.43.184, which carries the LIS3MDL). The software scan and the 15 s meter test were removed from the firmware once they were no longer needed; they are in the git history (commit 62a1e8d).

Do not infer the actual microcontroller board from its firmware hostname: an Adafruit QT Py ESP32S3 has different GPIO mappings from a Seeed XIAO ESP32S3, despite their compatible shape.

References: [Seeed XIAO ESP32S3 pinout](https://wiki.seeedstudio.com/xiao_esp32s3_getting_started/), [Adafruit QT Py ESP32S3 pinout](https://learn.adafruit.com/adafruit-qt-py-esp32-s3/pinouts), [Adafruit IMU breakout pinout](https://learn.adafruit.com/lsm6dsox-and-ism330dhc-6-dof-imu/pinouts).
