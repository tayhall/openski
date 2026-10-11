# Estimators on a real ski-mounted IMU run

Date: 2026-10-11. Code: `tools/analyse_ski_run.py`.

## Data

Beuken et al., *Distributed IMU Sensors on an Alpine Ski*, Sensors 2024; Kaggle `leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski`, Apache 2.0. Ten ICM-42688-class IMUs on one ski, about 714 Hz, +-16 g and +-2000 dps. File analysed: `Outdoor Testing/1_23/run10.txt` (175 s of a real downhill run, intermediate or expert skier). Individual files download without an account through the Kaggle API download endpoint. Data is not stored in this repository.

## What was measured

There is **no ground truth outdoors**, so this checks plausibility and consistency only.

| Check | Result |
|---|---|
| Accelerometer within 15 % of 1 g | **21 % of the time** (13 to 24 % across IMUs) |
| Time above 2 g | 29 % |
| Samples at the +-16 g limit | 13 % to 35 % depending on how counted (any axis) |
| p99 accelerometer magnitude | 60 to 180 m/s^2 (6 to 18 g) depending on IMU position |
| VQF roll, IMU 0 | p5 / p50 / p95 = -27 / -1 / +28 degrees: about 25 clean left-right turn cycles of 20 to 40 degrees |
| VQF versus our 3D filter, same IMU | median difference 6 to 14 degrees, 95th percentile 15 to 38 degrees, correlation 0.62 to 0.90 |
| Our accelerometer gate trusted | 13 to 24 % of time |
| Roll at the start and end (ski flat), VQF | within about 6 degrees of each other on all three IMUs over 175 s |

## What it means

1. **On a ski the accelerometer is mostly vibration and impact.** A 15 % magnitude gate leaves a complementary filter on the gyro about 80 % of the time. The boot cuff will be gentler, but this must be measured, not assumed.
2. **Saturation is real.** A +-16 g part clips a large share of samples on a ski. Flag clipped samples, bridge with the gyro, and consider a part with a higher range (ICM-45686 is listed at +-32 g) or a mechanically damped mount.
3. **The two estimators track the same turns but disagree by several degrees**, more where our gate rarely trusted the accelerometer. This run cannot say which is right; BROAD says VQF. It does suggest our hard gate is the weaker design for this use.
4. **Drift over three minutes looks small** on this class of sensor (start versus end roll agree within a few degrees), which supports spending effort on filter design and temperature calibration rather than raw gyro quality.

## Limits

One run, one ski, no truth, a different IMU from the MPU6050, ski mounted rather than boot mounted. The lab files in the same dataset have optical ground truth but need time alignment and a frame offset between the mocap rigid body and the IMU, which has not been done.
