# Triage of the "OpenSki research handover" resources

Date: 2026-10-11. Source: `OpenSki_Research_Handover.docx` (ten resources, dated 11 October 2026). BROAD was excluded by the document because another developer covers it; it was already used here (`docs/imu-benchmark.md`).

## Status of each resource

| # | Resource | Status here | Result |
|---|---|---|---|
| 1 | Skiing Data Analysis (Zenodo 17186128, Prochazka, CC BY 4.0) | **Done** | Real turn labels; see below |
| 2 | Distributed IMU Sensors on an Alpine Ski (Beuken, Kaggle) | **Done earlier** | `docs/ski-imu-run.md`, `docs/ski-lab-truth.md`, `docs/ski-video-reference.md` |
| 3 | SkiPose (GitHub yingtian22, MIT) | Inspected only | Repo is 5.6 GB, mostly AlphaPose videos; keypoint positions and quaternions are IMU-derived whole-body motion, not raw six-axis data. Examples: stance, snowplow, stem christie, parallel timing. Not downloaded. |
| 4 | IMUCoCo (CMU) | Not started | Apple Watch placements; relevant to mounting variation, large |
| 5 | TUM Visual-Inertial (imu-static) | **Done (3 blocks)** | BMI160 noise and stability; see below |
| 6 | OpenAXES calibration set (14 MB, CC0) | Not started | Next candidate: accelerometer calibration repeatability across four BMI160/ADXL355 units |
| 7 | EuRoC MAV | Skipped | 28 GB, drone dynamics; BROAD and the ski lab data already cover estimator testing |
| 8 to 10 | RoNIN, OxIOD, DIP IMU | Skipped | Pedestrian or whole-body pose; low relevance now, research-use licences |

## 1. Skiing Data Analysis: first labelled boot-or-leg-IMU turns

Three XLSX recordings, 100 Hz WITmotion IMUs (accelerometer in g, gyro in deg/s, fused angles, magnetometer, temperature), 1 Hz Garmin GNSS and the video time of the **top of every turn arc** with L or R labels: 146, 160 and 156 labelled arcs. Two sensors in each run (device names change between runs), placement not documented in the record (to be read from the paper, IEEE Access 2025). The video clock is not the sensor clock.

**Findings**
- Only run 1 has gyro data. Runs 2 and 3 contain accelerometer columns only (no gyro, no fused angles), despite the description.
- Run 1, heading-rate detector (`detect_turns_heading`, defaults, nothing tuned on this data): 145 and 147 detections for 146 labelled arcs. After estimating the unknown clock offset with left or right direction included, **both sensors independently gave the same offset (-14.56 s)**, which supports the alignment. Matching within 0.6 s: recall 0.58 and 0.64, precision 0.59 and 0.64; with the correct left or right direction: recall 0.47 and 0.48. Of matched turns, about 75 to 80 percent had the correct direction (after one global sign choice per sensor).
- An unconstrained offset search (no direction) gave misleadingly good numbers (recall 0.76 to 0.79) and different offsets per sensor, about one arc apart: periodic turns make the video-to-sensor offset ambiguous. Treat any figure that did not use direction or an independent clock event as inflated.
- Accelerometer-only runs (tuned on run 2, tested on run 3): the roll-like detector over-detects (247 to 293 detections for 156 labelled), so its recall of 0.9 or more is not meaningful; with direction, recall is 0.64 to 0.68 at precision 0.61 to 0.63.

**Limits**: three runs and probably one skier; arc-apex labels are not the same event as a turn switch; offsets were estimated from the same data; no independent clock event.

## 5. TUM VI imu-static: a real long static log (BMI160, 200 Hz, 111 h)

The record is a 4.8 GB `.npy` (80 million rows of timestamp, gyro xyz in rad/s, accel xyz in m/s^2, temperature). Three 2.9 h blocks (start, middle, end) were read with HTTP range requests; the full file was not needed.

| Quantity | Value |
|---|---|
| Gyro noise density (from raw sd) | 0.0054 to 0.0064 deg/s per root Hz (published parameter 0.0046) |
| Allan deviation at 1 s | 0.0043 to 0.0052 deg/s |
| Bias instability (minimum / 0.664), per 2.9 h block | 0.0008 to 0.0035 deg/s (3 to 13 deg/h) |
| Zero-rate offset | about +1.7, +0.4, +0.9 deg/s (x, y, z); changed by less than 0.03 deg/s over 111 h |
| Accelerometer noise (z) | about 2 mg |
| Temperature in the log | 31.2 to 34.5 C (self-heating), so **no thermal sweep and nothing cold** |
| Gyro against temperature within a block | 0.005 to 0.023 deg/s per C over a 0.1 to 0.4 C range (indicative only) |

**Reading it**: BMI160-class noise and stability are good, and at near-constant temperature the offset is stable to hundredths of a degree per second for days. The difficulty for skiing is therefore temperature and start-up, not the chip's intrinsic noise. This matches the ICM-42688 noise check (`docs/mpu6050-temperature.md`). BMI323 is a successor to BMI160, not the same part; these numbers are a reference, not coefficients for it.

## What the handover got right, and where it needed correction

- It warned that TUM VI is not a cold dataset: confirmed (31 to 34.5 C only).
- It said Beuken's format and licence were unconfirmed: now confirmed (Apache 2.0, 43 files, no account needed for single files). It also said to use a "static validation recording" for noise; the file named static is not static (the longest still stretch is 13.6 s), so it cannot give Allan variance.
- It described Skiing Data Analysis as three runs with WITmotion data; two of the three have accelerometer only.

## Suggested next

1. OpenAXES (14 MB, CC0): reproduce the calibration and its repeatability across four units; adapt to our fixture.
2. Read the Prochazka paper for sensor placement and the video-sync method; ask the author for the missing gyro channels of runs 2 and 3.
3. SkiPose: fetch only the keypoint JSON and NumPy outputs of one example to see whether lower-leg orientation is usable.
