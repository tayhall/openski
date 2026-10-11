# IMU orientation benchmark on real data (BROAD)

Date: 2026-10-11. Code: `tools/benchmark_broad.py`, `openski/imu.py` (`estimate_roll`, `estimate_attitude`).

## Why

Everything in `docs/imu-calibration.md` was tested only against signals simulated by our own model. BROAD is a public dataset of real inertial recordings with synchronised optical (motion-capture) ground-truth orientation, so it is the first independent check of the roll filter.

- Dataset: Laidig, Caruso, Cereatti, Seel, *BROAD: A Benchmark for Robust Inertial Orientation Estimation*, Data 2021, 6(7), 72, DOI 10.3390/data6070072. Data at DOI 10.14279/depositonce-12033 and https://github.com/dlaidig/broad. Licence CC BY 4.0.
- 39 trials, 286 Hz, one IMU (myon aktos-t) plus Optitrack ground truth at 120 Hz resampled to the IMU: 23 undisturbed rotation / translation / combined trials, 16 with deliberate disturbances (tapping, phone vibration, magnets, office). We use the accelerometer and gyroscope only.
- Data is not stored in this repository. `tools/benchmark_broad.py <dir>` expects the `data_hdf5/*.hdf5` files.

## Method

- Score roll about the sensor X axis (ZYX Euler). Roll is independent of heading, so magnetometer-free filters are scored fairly.
- Score only samples flagged as moving and with ground-truth pitch below 35 degrees, which is the range a ski boot sees.
- Gyro-only integration has its start offset removed; it drifts by construction and is only a reference.
- Compare: `estimate_roll` (one-axis, as originally built), `estimate_attitude` (3D quaternion complementary filter, same accelerometer gating), the same with an integral bias term, accelerometer-only, and x-io `imufusion` (default settings, gain 0.5, with its bias algorithm).

## Results (roll RMSE in degrees, 39 trials)

| Method | Median | Worst trial |
|---|---|---|
| Our one-axis filter (original) | 56.6 | 115.1 |
| **Our 3D filter** | **1.6** | **14.1** |
| Our 3D filter with integral bias term | 1.8 | 40.2 |
| Accelerometer only | 12.9 | 73.3 |
| x-io `imufusion` | 1.3 | 33.7 |
| **VQF** (Laidig and Seel, `vqf` package, defaults) | **0.4** | **1.3** |
| Gyro only | drifts by construction (80 to 100+ on the trials where it can be scored) | |

## What it means

1. **The one-axis roll filter was wrong for real motion and must not be used.** It integrates only the roll-axis gyro and ignores how pitch and yaw rate feed into roll rate. On a synthetic test that isolates that coupling (pitch up to 32 degrees, heading swing of 45 degrees) it has 20 degrees RMSE against 0.2 degrees for the 3D filter. On BROAD it fails outright. Our own simulator had hidden this because its coupling was mild.
2. **The 3D filter is competitive with a mature library.** Median 1.6 against 1.3 degrees, and it has the better worst case (14 against 34 degrees on the fast translation and combined trials, where sustained acceleration corrupts the accelerometer).
3. **Do not enable the integral bias term by default.** It helps on several trials and badly hurts one (40 degrees on trial 15, sustained acceleration). If online bias estimation is wanted it must be gated on stillness, as flight controllers do.
4. **Accelerometer gating is carrying the result.** Trusting the accelerometer only when its magnitude is within 15 % of 1 g is what makes the 3D filter robust to translation and tapping.

## VQF (added after the flight-controller research)

VQF was run with its default parameters on all 39 trials. It beat everything else, including the worst case (1.3 degrees against 14 degrees for our filter and 34 degrees for `imufusion`). Its design addresses our exact problem: it low-passes the accelerometer in an almost inertial frame (time constant about 3 s), so accelerations that alternate left and right cancel out instead of being rejected by a hard magnitude gate, and it estimates gyro bias whenever the sensor is at rest.

Read this with care:
- **VQF's authors also built BROAD** (same group). The defaults were not tuned on this exact data as far as we know, but the benchmark is its home ground.
- **Nobody has tested it on sustained 1 to 2 g cornering with a boot sensor.** BROAD's disturbances are taps, vibration, magnets and translation, not 5 s carves. That test is the next one; the ski IMU dataset on Kaggle (Apache 2.0, 700+ Hz) is the candidate.
- Even so, it is now the leading candidate estimator, and our complementary filter should be treated as the fallback and as the readable reference implementation.

## Limits, so this is not over-read

- **Different sensor.** The IMU is a research-grade unit sampled at 286 Hz, not an MPU6050 on a boot. Expect worse noise, bias drift and clipping on the real device.
- **Different motion.** Trials are handheld or rigidly moved rotations and translations. They are not sustained 1 to 2 g cornering with a boot-mounted sensor. The 15 % gate may reject most of a hard carve, in which case the filter falls back to gyro integration there.
- **No temperature drift.** The recordings are indoor at room temperature. A cold boot warming up is untested.
- **Pitch limited.** Samples with ground-truth pitch above 35 degrees were excluded because the boot does not reach it; the filter was not tuned for it.
- **Only roll.** Pitch and heading were not scored.

## Where this leaves us

- Use `estimate_attitude` (ki = 0) as the roll source in the pipeline and for turn detection. Keep `estimate_roll` only as a documented counter-example.
- Next real-data tests, in order: bench test of the actual MPU6050 (protractor angles, deliberate off-axis mount, warm-up drift); a phone IMU recording of skiing (Robak and Turek's dataset, if it can be obtained) for turn counting; the on-snow protocol in `docs/first-snow-protocol.md`.
- The `ahrs` package (Mahony, Madgwick in pure Python) is supported by the benchmark script but was not run on the full set because it is slow; run `--trials` on a few trials if it is wanted.
