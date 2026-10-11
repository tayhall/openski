# Attitude estimators against optical truth on a ski (Beuken lab data)

Date: 2026-10-11. Code: `tools/lab_alignment.py`, `tools/lab_attitude_eval.py`.

## Data

Beuken et al., *Distributed IMU Sensors on an Alpine Ski*, Sensors 2024; Kaggle `leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski` (Apache 2.0). Lab Validation folder: OptiTrack rigid-body export (240 Hz, Euler Z, Y, X in degrees, Y-up world, rigid body attached to the second IMU from the ski tip) and a ten-IMU log at about 714 Hz (ICM-42688-class, +-16 g, +-2000 dps). Two dynamic takes were used: `rotation_dynamic.csv` with `data_6.txt` (hand rotations of the ski, 103 s) and `dynamic2.csv` with `data_8.txt` (213 s). The file pairing is not documented; it was found from the data.

## How the alignment was solved (nothing was documented)

| Unknown | How it was found | Evidence |
|---|---|---|
| Which log goes with which take | Duration match, then speed correlation | Correlation 0.90 (take 1) and 0.97 (take 2) between mocap angular speed and gyro norm |
| Time offset | Cross-correlation of speed magnitudes | -40.15 s (take 1), -35.88 s (take 2); the angular-velocity fit has a sharp minimum, so good to about 20 ms |
| Euler convention and axis mapping | Brute force over 576 column, composition and sign choices and 6 up axes, scored by how well a single constant rotation maps mocap gravity direction onto the IMU's | Best mapping median 2.2 degrees; the median over all candidates was 8.6 degrees. Equivalent sign sets give identical results. Used: Z, Y, X columns, Y angle negated, R = Rx Ry Rz, transposed, up = world Y |
| Constant rotation between rigid body and IMU | Kabsch fit on gravity directions (3 parameters) | fitted per take and IMU |

The angular-velocity fit alone could not resolve the frame mapping (residual about half the signal), probably because of ski flex and vibration; the gravity-direction fit is much less sensitive to that.

## Results: inclination error (angle between estimated and optical gravity direction)

| Take / IMU | Estimator | Median | p90 | p95 | p99 |
|---|---|---|---|---|---|
| 1 / IMU 1 | VQF | 2.2 | 12.4 | 24.5 | 54 |
| 1 / IMU 1 | ours 3D | 3.5 | 21.2 | 33.6 | 59 |
| 1 / IMU 9 | VQF | 1.6 | 9.2 | 22.5 | 46 |
| 1 / IMU 9 | ours 3D | 3.3 | 22.1 | 36.9 | 61 |
| 1 / IMU 4 | VQF | 3.7 | 14.4 | 27.7 | 62 |
| 1 / IMU 4 | ours 3D | 8.4 | 34.4 | 50.2 | 75 |
| 2 / IMU 1 | VQF | 0.7 | 2.3 | 3.7 | 8.9 |
| 2 / IMU 1 | ours 3D | 1.4 | 4.0 | 5.4 | 8.2 |
| 2 / IMU 9 | VQF | 0.8 | 2.2 | 3.6 | 9.3 |
| 2 / IMU 9 | ours 3D | 1.2 | 4.5 | 6.3 | 9.3 |

All values in degrees, all aligned samples (moving-only figures are similar; see the script output).

## What it means

1. **VQF is better than our filter on real ski-mounted data too**, by roughly a factor of 1.5 to 2.5 at the median, and by more in the tails. This agrees with BROAD (0.4 against 1.6 degrees median).
2. **The tails are large in take 1** (p95 22 to 37 degrees for both). That take has rotations up to about 70 degrees and hand-induced shocks; take 2 is much gentler (p95 under 7 degrees). The heavy tail is not visible in a median and matters for turn-by-turn feedback. Some of it may be mocap error (marker occlusion), which was not checked.
3. **Sensor position matters**: IMU 4 is worse than IMU 1 and 9 in take 1 for both filters, consistent with ski flex or mounting differences along the ski.
4. **Ski-mounted, lab, hand-driven, ICM-42688-class**: the closest we have to our use, and still not a boot on snow.

## Limits

- The constant frame offset was fitted on the VQF output, which gives VQF a small advantage over our filter. It is only three parameters and applied to both.
- The mapping was chosen from 3,456 candidates on the data itself; the fact that a second take agrees with the same convention (new lag, new fit) is the check on that.
- The up-vector error measures roll and pitch together; heading is not tested.
- No sustained high-g cornering, no cold, and no MPU6050.
