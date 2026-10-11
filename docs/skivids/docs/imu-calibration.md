# Boot IMU calibration and turn detection (research code)

Status: implemented and tested **against simulated signals only**. No real MPU6050 data has been processed. Code: `openski/imu.py`, `openski/turns.py`; simulator: `tools/simulate_boot_imu.py`; tests: `tests/test_imu.py`.

## Why

The sensor sits on the boot strap and its orientation changes every time it is fitted. The pipeline therefore estimates the sensor-to-boot rotation at the start of each session instead of assuming it. The same routine lets the device move between people and boots.

## Protocol (about 10 seconds)

1. **Stand still, boots flat, about 3 s.** Gravity gives the boot's up axis; the gyro mean is its bias.
2. **Bend both knees forward, then straighten, 3 times.** The gyro rotates about the boot's lateral axis. The first excursion is taken as forward flexion, so always bend forward first.

The routine refuses to calibrate (raises `CalibrationError`) when the standing segment is not 1 g, the gyro is not quiet, or the flex motion is not about a single axis.

## Frame and sign convention

Boot frame: +X forward, +Y left, +Z up. Positive roll = boot tilting to the skier's right (rotation about +X). Left turns have negative roll.

## What the simulation shows (not evidence for real snow)

| Check | Result |
|---|---|
| Mounting recovery, 12 random mounts up to 40 deg off-axis | Error about 0.05 deg |
| Roll RMSE vs simulated truth | About 2 to 3 deg |
| Turn count (carved, 8 expected) | 8 of 8 |
| Turn boundary timing error | Median 10 to 25 ms |
| Sensor-frame sweeps: accel noise up to 2.5 m/s^2, gyro bias sd up to 0.15 rad/s, 50 Hz sampling | No loss of turns |
| Very strong cornering force (cornering factor 1.0) | One extra turn detected |

## Known limits and why the simulation flatters the result

- **Drift is simulated only as a straight-line bias ramp.** Real MPU6050 drift with temperature is not necessarily linear, and cold boots warming up are a worst case. See the drift table below.
- **Accelerometer correction is gated.** It is used only when the force magnitude is within 15 % of 1 g, so it is mostly off during peak tilt. With a constant gyro bias this looks better than it will with real drift.
- **Cuff flex versus ski tilt.** Roll is of the sensor's mounting point on the boot cuff relative to gravity. It is not ski edge angle and not relative to the snow surface.
- **Forward-first rule.** Bending backward first flips the lateral axis sign. Mitigation is a protocol cue, not a check.
- **The simulator is my own model.** Passing it shows the code is self-consistent, not that it matches real boots.

## Robustness checks (simulated, 6 seeds each)

Gyro bias ramp over a 64 s run of 20 carved turns:

| Drift (rad/s per minute) | Turns found (of 20) | Roll RMSE (deg) |
|---|---|---|
| 0 | 20 | 2.9 |
| 0.05 | 20 | 3.1 |
| 0.15 | 20 | 8.2 |
| 0.30 | 20 | 16.1 |
| 0.60 | 20 | 33.3 |

Turn counting is robust to drift because the detector removes slow baselines, but **absolute roll amplitude is not**. Treat amplitude as unreliable on long runs until drift is measured on the real sensor.

Causal (streaming) detector: same turn count as the offline detector, boundary times within about 0.07 s of it. It uses only past samples.

Mounting-slip monitor (strap moves 14 s into a run; flag raised when a 4 s window deviates):

| Slip | Flagged | Delay |
|---|---|---|
| 8 deg about the leg (yaw) | 6 of 6 | about 9 s |
| 15 deg yaw | 6 of 6 | about 4 s |
| 25 to 40 deg yaw | 6 of 6 | about 2 s |
| 15 deg about the forward or lateral axis | 2 to 3 of 6 | not reliable |
| 30 deg about the forward or lateral axis | 6 of 6 | |

Yaw slip (the strap rotating around the shin) is the likely failure and is caught well. Smaller tilts of the mount are caught unreliably, so re-run the calibration whenever the strap is touched.

## Next steps

1. Record a bench test with the real sensor (protractor angles, deliberately off-axis mount, warm-up drift).
2. Replace the straight-line drift with a measured temperature response.
3. Use the slip monitor's result to prompt a re-calibration in the app.
