# IMU samples

The application-facing interface is `ImuSensor`. Each driver returns a timestamp in microseconds, acceleration in m/s², angular rate in rad/s, and temperature in °C. Sensor-axis orientation is preserved; mounting transforms belong in a later calibration/orientation layer.

The current MPU-6050 driver uses I2C address `0x68`, SDA GPIO21, SCL GPIO22, a 100 Hz output rate, ±8 g acceleration range, and ±1000°/s gyro range. It checks `WHO_AM_I` before enabling sampling. The serial monitor prints the latest sample every 500 ms; the sensor is polled from the main loop and the monitor counts successful reads and bus failures.

## LAN endpoint

When Wi-Fi is connected, `GET http://<device-ip>/api/v1/imu` returns the latest sample and sensor health as JSON. It reports `ready`, `has_sample`, `samples`, and `read_failures`; when available, it also reports `timestamp_us`, `accel_mps2`, `gyro_radps`, and `temperature_c`. It also reports `build`, the git commit and date the firmware was built from (with `-dirty` if there were uncommitted changes). Recorder health includes `recorder_ready`, `recording`, `recorded_samples`, `recording_capacity_samples`, and `dropped_samples`. `recorder_partition_found` and `recorder_partition_bytes` help diagnose whether the installed partition table has the SPIFFS area required for flash sessions. The endpoint is read-only and currently has no HTTP authentication, so use it only on a trusted local network.

The common sample contract lets another driver, such as an LSM6DSOX, replace the MPU-6050 driver without changing application consumers. The driver remains responsible for converting its register scales into SI units. Keep chip-specific address, bus, and register setup inside that driver.

No turn classification or orientation fusion is included yet. Initial readings are uncalibrated: mounting orientation, gyro bias, and sensor-to-ski alignment still need characterization before app-level interpretation.

## Research acquisition builds

Build `esp32-devkit-v1-research` or `esp32-s3-supermini-research` to select `openski_mpu6050_research_v1`: requested 100 Hz, ±16 g acceleration, ±1000°/s gyro and nominal 20 Hz gyro bandwidth (DLPF register setting 4). The normal builds retain ±8 g and nominal 42 Hz bandwidth. Register configuration and acceleration conversion share a profile definition; initialisation reads back the rate, filter and range registers and refuses ready status on mismatch. Read-back verifies configuration, not achieved sampling performance or filter response.

These are opt-in acquisition experiments based on the [revision-2 research baseline](technique-research/docs/provisional-interpretation.md). They do not change the existing motion detector, thresholds or calibration constants. Changed bandwidth can change recogniser timing and noise, so validate the recogniser separately before relying on its events. The S3 research build was flashed by OTA on 2026-10-08; see [bench results](bench-results.md).

### Acquisition diagnostics

In diagnostics mode, `GET /api/v1/acquisition` reports the actual compiled acquisition profile, requested ranges/rate/bandwidth, firmware build, raw units, sensor frame, gravity inclusion and timing/near-rail counters. `ready` indicates successful driver initialisation/read-back. `has_sample` determines whether the latest flags and interval statistics have sample data. Boot side, mounting transform and calibration reference remain null because the firmware has no supported metadata-setting mechanism. The hostname is a device label, not a globally unique hardware ID. Capture this response alongside bench recordings; Android does not yet persist it automatically.

Timestamps are 32-bit microseconds taken at device read completion, not sensor data-ready interrupts, and wrap after about 71.6 minutes. Successful reads increment the existing sample count; this is not a hardware acquisition sequence and cannot count sensor samples overwritten before polling. Adjacent read timestamps use unsigned subtraction across a single wrap. Gaps strictly greater than 20 ms are review flags at nominal 100 Hz, not measured loss counts or metric accuracy limits. Multiple wraps without a sample cannot be reconstructed by this monitor.

The endpoint reports cumulative counters since boot for timing gaps, timestamp wraps, accelerometer/gyro near-rail samples, read failures, and the latest/maximum interval. The latest sample quality bits are `1` accelerometer near rail, `2` gyro near rail, `4` timestamp gap and `8` timestamp wrap. Near rail means any raw axis lies within eight counts of the signed 16-bit rail; absence of this flag cannot establish that clipping never happened between reads. Raw values are preserved.

BLE and flash record layouts remain unchanged. These quality flags are currently available in memory and HTTP diagnostics only; they are **not stored per sample or transmitted over BLE**. Per-record sequence/quality metadata, durable session/profile metadata and measured dual-boot clock offset/drift remain coordinated firmware/Android follow-up work. No synchronisation accuracy or skiing performance is implied by these diagnostics.

Before field use, compare actual interval distributions and loss under BLE/flash/Wi-Fi load, bench-check axis signs and scale with known rotations and static gravity, measure noise/clipping at both ranges, and check bandwidth/delay against reference excitation. Then validate events against independent same-definition annotations in synchronised permitted skiing recordings.
