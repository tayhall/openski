# OpenSki status — 7 October 2026

This snapshot reflects merged PRs #1–#5 through main commit `21d82b6`. It summarises recorded progress, not a new hardware validation run.

| Merged PR | Result |
|---|---|
| #1 | Android indoor POC: calibration, orientation, synthetic demo, guided tests, labels, comparisons, replay/export and app redesign. |
| #2 | Training-first home, drill path, progress, demo/live-boot drills and Geek mode. |
| #3 | Bench tools, landmark-based mounting instructions and expanded POC plan. |
| #4 | Per-sensor accelerometer correction and first bench-results log. |
| #5 | IMU capture-rate fix, sample-counted 50 Hz BLE transmission, start-up retry/I²C recovery, build identification and overnight results. |

## Hardware and recorded evidence

The existing POC uses an MPU-6050 and classic ESP32 DevKit V1. The new ESP32-S3 Super Mini is running its dedicated 4 MB target with the MPU6050 on GPIO8/9. Wi-Fi/OTA, BLE readings and recorder storage are working. See [bring-up](bring-up.md). The mounting decision is at the boot cuff near the calf; its orientation estimate includes cuff flex and is not a measured snow-relative ski edge angle.

The current checkpoint adds Wi-Fi Bluetooth diagnostics and `bench_rules_v3` on-device movement/gesture recognition. Single handheld left and right tests each produced two complete Z-axis excursions with no on-device sampling gaps. The forward test produced one complete Y excursion; its return was rejected and needs investigation. Estimates are not independently angle-validated. Seven portable gesture regression scenarios passed. Recordings and limitations are preserved in [bench data](../data/bench/README.md).

Android idle monitoring now uses a connected-device foreground service to survive screen sleep. It was built, checked and installed on the phone. GATT operation failures/reconnections remain unresolved; the current logging and MTU callback guards support further diagnosis.

The [bench results](bench-results.md) record approximately 9.5 hours of USB-powered Wi-Fi streaming, 3,401,973 samples, an average 100.0 samples/s and zero I²C read failures. Cold-start data show temperature-dependent gyro bias and settling/hysteresis; temperature compensation remains a candidate requiring repeat validation, rather than an implemented correction.

## Next validation

- Measure the intended 50 Hz live BLE rate through Bench tools after the firmware update.
- Exercise long-running BLE, interrupted connections, flash recording/download/recovery and battery operation on physical hardware.
- Validate known cuff-roll angles, mounting repeatability and live drill behaviour against independent measurements/video.
- Repeat cold-start/thermal tests before deciding how to compensate gyro drift.
- Validate real ski turns on snow; carve/skid, pressure and true radius remain unimplemented.

The mobile launcher icon now uses `docs/logo.png`, preserving the supplied artwork and sizing it for Android adaptive masks.

The updated checkout passed 64 Android JVM unit tests, debug/instrumentation APK builds and Android lint. The new icon was visually checked in the emulator's circular launcher mask.
