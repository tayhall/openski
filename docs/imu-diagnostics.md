# XIAO IMU diagnostics

The XIAO ESP32S3 builds use SDA D4/GPIO5 and SCL D5/GPIO6. The installed Arduino board definition agrees with Seeed's pinout.

With the IMU unavailable and flash recording stopped:

- `GET /api/v1/diagnostics/i2c` reports idle pin levels, scans addresses 0x08–0x77 at 100 kHz, and reads ST WHO_AM_I register 0x0F from responding devices. It restores the configured bus frequency afterwards.
- `GET /api/v1/diagnostics/i2c-software` stops the hardware bus, releases both lines with open-drain outputs and pull-ups, performs nine recovery clocks and STOP, checks that both GPIOs can pull low, then probes the same addresses at approximately 10 kHz without TwoWire. It restores the hardware bus afterwards. Clock stretching waits are bounded to 1 ms per rising edge.

Both endpoints return HTTP 409 when the IMU is ready or recording is active. They run synchronously in the firmware loop. The software scan does not write sensor configuration registers. Its optional `profile` query selects only documented mappings: `configured` (default), `qtpy-pads` (SDA GPIO7/SCL GPIO6) or `qtpy-jst` (SDA GPIO41/SCL GPIO40). Unknown profiles are rejected. Afterwards, the selected pins are released and the hardware bus is restored to the configured pins. These alternatives test board identity assumptions; a firmware hostname does not establish which physical board is connected.

`POST /api/v1/diagnostics/i2c-meter?line=sda` (or `line=scl`) holds only the configured selected signal low for 15 seconds using an open-drain output, then releases it and restores Wire. It pauses the main loop during the hold so IMU retries cannot reconfigure the GPIO. It rejects active IMU/recording and invalid line arguments. Use an HTTP timeout above 15 seconds. This test is operator-triggered only: first position a DC voltmeter's black probe on IMU GND and red on the selected IMU signal pad. Expect high before, near 0 V during the hold, high afterwards. A correct transition establishes that the chosen GPIO reaches that pad; it does not establish that the sensor chip acknowledges I2C. Other loop-driven functions pause during the hold.

Device addresses in JSON are decimal: LSM6DSOX 0x6A/0x6B = 106/107; LIS3MDL 0x1C/0x1E = 28/30. No address response means identification and configuration checks have not yet been reached. High idle levels alone do not prove cable continuity or sensor power.

## 21 October 2026 bench configuration

- `192.168.43.185`, `ski-xiao-l`: left/magnetometer build; battery divider midpoint confirmed on D1/GPIO2. After correcting the build flag, the reported voltage was 3.905 V. The initial 400 kHz hardware scan found no devices.
- `192.168.43.184`, `ski-xiao-r`: no battery divider fitted; battery sensing disabled in its build. The 100 kHz hardware scan found no devices, both lines high, zero non-NACK bus errors.

The committed IMU driver and GPIO mapping did not change between the two devices' original 20 October builds. The original left build was marked dirty, so its full source cannot be reconstructed from the commit ID alone.

Both units were subsequently updated and tested with the independent software scan. On both units: idle SDA/SCL high, both GPIOs able to pull low, no responding addresses, zero clock failures, and hardware bus restored successfully. Their 100 kHz hardware scans also found no devices and no non-NACK errors. The left unit, reported by the user as battery-powered, measured 3.930 V after the update. Neither IMU produced samples; the left magnetometer remained absent.

This makes an IMU register/configuration error or hardware-I2C-only failure less likely; it does not establish cable continuity, physical carrier identity, or sensor-side supply/CS levels. The next physical test is continuity from XIAO D4/D5 to the sensor SDA/SCL pads with power removed, followed by sensor-side voltage measurements. Do not infer the actual microcontroller board from its firmware hostname: an Adafruit QT Py ESP32S3 has different GPIO mappings from a Seeed XIAO ESP32S3, despite their compatible shape.

References: [Seeed XIAO ESP32S3 pinout](https://wiki.seeedstudio.com/xiao_esp32s3_getting_started/), [Adafruit Terminal Block BFF pinout](https://learn.adafruit.com/adafruit-terminal-block-bff/pinouts), [Adafruit QT Py ESP32S3 pinout](https://learn.adafruit.com/adafruit-qt-py-esp32-s3/pinouts), [Adafruit IMU breakout pinout](https://learn.adafruit.com/lsm6dsox-and-ism330dhc-6-dof-imu/pinouts).

Further mapping test on `.184`: `configured`, `qtpy-pads` and `qtpy-jst` all returned no devices, idle levels high, both pins able to pull low, zero clock failures and successful restoration of the configured hardware bus. The user also confirmed continuity from the controller's labelled SDA/SCL pads to the matching IMU pads. These checks have not identified a firmware mapping fix. Exact physical controller and sensor models remain necessary to verify interface assumptions.

Photos `63240.jpg`, `63241.jpg` and `63242.jpg` subsequently identify the controllers as Seeed XIAO ESP32-S3, the carrier as a Pi Hut ProtoMate, the USB assembly's sensor as LSM6DSOX + LIS3MDL, and the battery assembly's sensor as LSM6DSOX only. Based on the user's power/IP assignments, the magnetometer feature is currently enabled on the assembly without that sensor and disabled on the assembly with it. This does not explain either IMU's missing I2C acknowledgement: both use LSM6DSOX and the same GPIO5/6 mapping. The photos do not establish whether the sensor undersides are insulated from the exposed ProtoMate copper. Lifting one sensor clear while retaining its cable is a distinct mounting-isolation test, not another cable continuity test.

The user requested switching the firmware variants after identifying the assemblies. The intended assignment is now `.184` = `xiao-left-mag` / `ski-xiao-l`, magnetometer enabled, battery sensing disabled; `.185` = `xiao-right` / `ski-xiao-r`, magnetometer disabled, battery sense D1/GPIO2. Hostnames follow these variants, so the previously observed left/right names swap IP addresses. Earlier readings above retain their historical names.

Both variants built successfully and were uploaded separately to those addresses. Live verification confirmed `.184` identifies as `ski-xiao-l`, reports the LIS3MDL build enabled but not detected, and battery sensing unavailable. `.185` identifies as `ski-xiao-r`, reports no magnetometer build and a 3.845 V battery reading. Both IMUs still reported not ready with zero samples after the swap.

After the user removed both sensor assemblies from their mounting headers, `.184` was verified working: IMU ready, 10,728 samples with zero read failures at about 105 s uptime; magnetometer present, 4,832 samples and zero read errors. Its scan endpoint correctly refused to interrupt the active IMU (HTTP 409). `.185` remained unavailable at about 24 minutes uptime, battery 3.791 V, zero samples. Hardware and software scans still found no devices; both lines were high and controllable, with zero clock failures. The user had reconnected its JST but had not restarted the controller. This verifies the acquisition firmware on the working assembly; the remaining fault is specific to the other assembly or its powered state.

The user measured `.184`'s IMU SDA and SCL dropping during separate 15-second meter tests, verifying GPIO5/6 reach the respective labelled sensor pads. VIN and 3Vo measured 3.314 V and 3.313 V. A full power cycle of `.185` was then verified (19 s uptime, power-on reset); its scan still found no devices. The user subsequently verified that its IMU works on `.184` using either sensor JST socket. Investigation therefore moves to the `.185` assembly, including the same meter tests at its sensor end.
