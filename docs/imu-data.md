# IMU samples

The application-facing interface is `ImuSensor`. Each driver returns a timestamp in microseconds, acceleration in m/s², angular rate in rad/s, and temperature in °C. Sensor-axis orientation is preserved; mounting transforms belong in a later calibration/orientation layer.

The current MPU-6050 driver uses I2C address `0x68`, SDA GPIO21, SCL GPIO22, a 100 Hz output rate, ±8 g acceleration range, and ±1000°/s gyro range. It checks `WHO_AM_I` before enabling sampling. The serial monitor prints the latest sample every 500 ms; the sensor is polled from the main loop and the monitor counts successful reads and bus failures.

## LAN endpoint

When Wi-Fi is connected, `GET http://<device-ip>/api/v1/imu` returns the latest sample and sensor health as JSON. It reports `ready`, `has_sample`, `samples`, and `read_failures`; when available, it also reports `timestamp_us`, `accel_mps2`, `gyro_radps`, and `temperature_c`. It also reports `build`, the git commit and date the firmware was built from (with `-dirty` if there were uncommitted changes). Recorder health includes `recorder_ready`, `recording`, `recorded_samples`, `recording_capacity_samples`, and `dropped_samples`. `recorder_partition_found` and `recorder_partition_bytes` help diagnose whether the installed partition table has the SPIFFS area required for flash sessions. The endpoint is read-only and currently has no HTTP authentication, so use it only on a trusted local network.

The common sample contract lets another driver, such as an LSM6DSOX, replace the MPU-6050 driver without changing application consumers. The driver remains responsible for converting its register scales into SI units. Keep chip-specific address, bus, and register setup inside that driver.

No turn classification or orientation fusion is included yet. Initial readings are uncalibrated: mounting orientation, gyro bias, and sensor-to-ski alignment still need characterization before app-level interpretation.
