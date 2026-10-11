# 07 - Kaggle datasets for orientation truth, gyro noise, static recordings and vibration

Date of check: 2026-10-11. Method: Kaggle public API without an account (search, `datasets/view`, `datasets/list`, single-file download). Every claim is labelled:

- **CONFIRMED** = read from the API JSON or from a file I downloaded and opened.
- **REPORTED** = taken from the dataset description text only.
- **INFERRED** = my deduction (including from outside knowledge of the original project); not seen in the data.
- "not verified" = I could not check it.

Scope: about 165 search query strings run by me (all requested topics plus variants and sport/hardware names; some returned 404 = no hits), several hundred distinct datasets seen (271 in the pre-ranked list plus several hundred more from the new queries, most irrelevant), about 55 metadata and file-list reads, about 35 sample files opened (counts approximate). Sample files are in `D:\Projects\skivids\research\sensor-research\samples\<slug>\` (only files under 25 MB; one slip noted at the end).

Already used by OpenSki, so excluded from the ranking: BROAD, and `leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski`.

---

## 1. Bottom line

There is **no Kaggle dataset that gives a cheap MEMS IMU (MPU6050/MPU9250/ICM/LSM) together with optical or encoder orientation truth.** The best available for each of our four tests:

| Our test | Best Kaggle option | Why / limit |
|---|---|---|
| (a) orientation / roll vs ground truth | `muhammadhamadzaheer/helmet-mounted-inertial-measurement-unit-dataset` | CONFIRMED OptiTrack quaternions at 250 Hz, 12 runs of 252-300 s, raw IMU from a VectorNav VN-100 and an InertialSense uINS (industrial MEMS, not MPU6050). Head motion only; no large roll. |
| (a) second option | `kmader/ridi-robust-imu-double-integration` | CONFIRMED 200 Hz phone gyro/accel plus a separate `pose.txt` orientation track. Truth source (Tango VIO) is INFERRED from the RIDI paper, not stated on Kaggle. Phone MEMS, walking only. Licence is CC BY-ND. |
| (b) gyro bias / temperature | `jonassouza872/motor-vibration-classification-imu-dataset` | CONFIRMED real MPU6050 with an internal temperature column (the only one found). But only about 1,200 samples per file and no timestamp, so no drift or Allan analysis. |
| (c) long static for Allan variance | **none found** | Longest genuinely static MPU6050 file is 37,459 accel-only samples with no timestamp and no gyro (`aadityamukherjee2022/...`), and it is not static throughout. |
| (d) vibration / impact | `ricardofebra/cnc-vibration-monitoring` (LSM6DSO, CC0) and `maximilianfellhuber/cnc-machining-data` (2 kHz, CC BY) | Real MEMS accelerometer vibration, but no orientation truth; sample rate of the LSM6DSO files not verified. |

Practical consequence: for (b) and (c) we will have to record our own data (a 12-24 h static log of our MPU6050 with a temperature channel, ideally also on a heated/cooled run). Kaggle cannot replace that.

---

## 2. Verified candidates (ranked by usefulness to us)

Fields: sensors, rate, units and layout, ground truth, longest continuous/static file, temperature channel, licence (exact string from API), size, last updated, how to load, which tests it supports.

### 2.1 `muhammadhamadzaheer/helmet-mounted-inertial-measurement-unit-dataset`  (rank 1, orientation truth)

- **Sensors**: REPORTED InertialSense uINS and VectorNav VN-100 on a helmet; OptiTrack IR mocap. Not MPU6050-class (industrial-grade MEMS), so absolute noise numbers will not transfer.
- **Files**: CONFIRMED 12 folders `dataset-helmetN/`, each with `camera_qtn.csv`, `vectorNav_imu.csv`, `vectorNav_qtn.csv`, `inertialSense_imu.csv`, `inertialSense_qtn.csv` (60 files, listing complete). 5-13 MB each.
- **Rate**: CONFIRMED 250 Hz (dt = 0.004 s) in the four runs I opened.
- **Layout**: CONFIRMED no header. `*_imu.csv` = 10 columns: time, then 9 channels. INFERRED from magnitudes: cols 2-4 gyro (rad/s, peak 2.3-15 rad/s), 5-7 accel (m/s^2, norm median 9.77-9.80), 8-10 magnetometer (norm about 1.0, probably Gauss). `*_qtn.csv` = time, 4 quaternion components, unit norm CONFIRMED; scalar-first INFERRED (first component about -1 at start; sign flips occur, so use |dot| when comparing).
- **Ground truth**: CONFIRMED `camera_qtn.csv` is the OptiTrack quaternion (REPORTED as "can be used as the ground truth"). Timestamps match the IMU file row for row (CONFIRMED np.allclose in run 1).
- **Duration**: CONFIRMED run 1 = 63,000 rows = 252 s; runs 5, 9, 12 = 75,000 rows = 300 s each. The time column is cumulative across runs (run 5 starts at 1152 s, run 12 at 3252 s), so the whole set is about 59 min. Which run is sitting, walking or running is not stated per file (not verified); my gyro statistics suggest run 1 is calm, run 9 and 12 are active (median |omega| 0.39 -> 0.94 rad/s; p95 1.2 -> 5.6 rad/s). INFERRED.
- **Reference-filter quality (useful as a sanity baseline)**: CONFIRMED angle between the on-board VN-100 quaternion and OptiTrack, in degrees (median / p95 / max): run 1 0.97 / 2.62 / 3.52; run 5 1.32 / 2.96 / 6.46; run 9 2.08 / 7.41 / 19.34; run 12 4.82 / 11.06 / 32.66. So frames are already aligned (no big constant offset), and a good filter should land around 1-5 deg on this data.
- **Temperature**: CONFIRMED none.
- **Licence**: "Attribution 4.0 International (CC BY 4.0)". **Size** 537,353,434 B. **Updated** 2021-06-15. 156 downloads.
- **Load**: `np.loadtxt(f, delimiter=",")`; no pandas header handling needed. Per-file download works via the single-file API (13 MB each).
- **Supports**: test (a) yes (roll/pitch/yaw vs optical truth, algorithm comparison, mag-free vs mag-using). Test (b)/(c): no (short, no temperature). (d): no.
- **Caveats**: head motion, not boot-scale roll; no ski dynamics; gyro units not stated on Kaggle (INFERRED rad/s).

### 2.2 `kmader/ridi-robust-imu-double-integration`  (rank 2, orientation truth, phone)

- **Sensors**: smartphone IMU (REPORTED via linked RIDI paper; the Kaggle text only has links). 
- **Files**: CONFIRMED per-sequence folders such as `data_publish_v2/dan_bag1/` with `acce.txt`, `gyro.txt`, `linacce.txt`, `gravity.txt`, `magnet.txt`, `orientation.txt`, `pose.txt`, `step.txt`, `processed/data.csv`, `.ply` trajectories. Listing (840 files seen before the pager was cut off) shows sequences dan_bag1-2, body1-3, handheld1-2, leg1-2 and more; also duplicated under `data_publish_v2/data_publish_v2/`. Full list not verified.
- **Rate / duration**: CONFIRMED dan_bag1 gyro 200-202 Hz, 14,540 samples = 71.8 s. Timestamps in ns, `# Created at Thu Nov 09 ...` header line. `magnet.txt` is lower-rate (about 164 KB vs 630 KB).
- **Layout**: CONFIRMED `gyro.txt`: t, gx, gy, gz (values 0.02-0.15, rad/s INFERRED). `acce.txt`: t, ax, ay, az (az/ay about 10, m/s^2). `orientation.txt`: t + 4 quaternion components (device rotation vector, REPORTED origin by RIDI). `pose.txt`: t + 3 position + 4 quaternion (14,212 rows, starts about 1.6 s after gyro). `processed/data.csv` header CONFIRMED: `time,gyro_xyz,acce_xyz,linacce_xyz,grav_xyz,magnet_xyz,pos_xyz,ori_wxyz,rv_wxyz`.
- **Ground truth**: `pose.txt` / `ori_*` / `pos_*` are the reference; their source (Google Tango visual-inertial tracking) is INFERRED from the RIDI paper, not an independent optical system. Accuracy of `ori` not verified.
- **Temperature**: not in the files I opened.
- **Licence**: "Attribution-NoDerivatives 4.0 International (CC BY-ND 4.0)" (no derivatives: fine for internal benchmarking, not for redistributing a modified set). **Size** 4,242,701,703 B. **Updated** 2019-03-08. 1,069 downloads.
- **Load**: `np.loadtxt(path, skiprows=1)` (first line is a `#` comment); `data.csv` has an unnamed index column.
- **Supports**: (a) partially (orientation tracking vs VIO reference, with walking dynamics, body/handheld/bag/leg placements). Not (b)-(d).

### 2.3 `huseyincavus/uzh-fpv-drone-racing-benchmark`  (rank 3, aggressive-motion truth, large)

- **Sensors**: REPORTED Snapdragon Flight and DAVIS cameras with IMUs, aggressive drone racing up to 80 km/h with "visual and inertial ground truth" (REPORTED).
- **Files**: CONFIRMED folder tree `UZH_FPV/calib/...` (Kalibr yaml, imu.yaml, results txt), `UZH_FPV/competition_test/<seq>/imu.txt`. Only 4,720 of an estimated much larger file set could be listed (pager token limit); I could not see the groundtruth.txt files. Full list not verified.
- **CONFIRMED sample**: `competition_test/indoor_45_11_snapdragon/imu.txt`: header `# timestamp ang_vel_x ang_vel_y ang_vel_z lin_acc_x lin_acc_y lin_acc_z`, 500.0 Hz, 27,250 rows = 54.5 s, units rad/s and m/s^2 (REPORTED by the UZH convention; INFERRED from the 9.8 values). Gyro values are visibly quantized (steps about 0.00107 rad/s), consistent with an MPU-class 16-bit gyro; chip not verified.
- **Ground truth**: for competition_test sequences ground truth is withheld in the original release (INFERRED); training sequences would have groundtruth.txt (not verified in this listing).
- **Licence**: "CC BY-NC-SA 4.0" (non-commercial; share-alike). **Size** 29,239,244,224 B. **Updated** 2026-08-25.
- **Supports**: (a) maybe, only if the labelled sequences are present; also useful as a high-rate high-dynamics IMU stream. Low priority because the data is drone/camera-centred and huge.

### 2.4 `banaankiamanesh/imu-for-ai`  (BNO055 raw + fused orientation labels)

- **Sensors**: REPORTED/CONFIRMED BNO055. 
- **Files**: CONFIRMED 8 CSVs (`IMU_Data_1..5.csv`, `IMU_Data_1..3(No Mag).csv`), 3.0-3.6 MB each.
- **Layout**: CONFIRMED header `Gyro_x,Gyro_y,Gyro_z,Acc_x,Acc_y,Acc_z,Mag_x,Mag_y,Mag_z,Euler_x,Euler_y,Euler_z,Quat_0..3`. No time column. REPORTED rate "EXACTLY 100 Hz"; 30,000 rows = 300 s CONFIRMED (IMU_Data_1). Euler_x -180..180 (roll), Euler_y -89..89 (pitch), Euler_z 0..360 (yaw), CONFIRMED ranges, so roll and pitch cover wide ranges (good for large-tilt tests).
- **Ground truth**: the "labels" are the BNO055's own on-chip fusion output (REPORTED: "labels (containing pitch, roll, yaw and quaternions)"). INFERRED: this is a reference, not an independent truth, and BNO055 fusion itself has several degrees of error. Gyro units not stated (values +-30; deg/s INFERRED).
- **Temperature**: none. **Licence**: "Data files (c) Original Authors" (not a standard licence; unclear reuse rights). **Size** 27,102,778 B. **Updated** 2022-02-28. 838 downloads.
- **Supports**: (a) as a cheap proxy: compare our Madgwick/Mahony roll to the BNO055 quaternion over large tilts. Never claim this as absolute accuracy.

### 2.5 `aadityamukherjee2022/accelerometer-sensor-data-mpu6050`  (static MPU6050, accel only)

- **Sensors**: REPORTED MPU6050 on an RP2040 via I2C; raw LSB at 16384 LSB/g (+-2 g).
- **File**: CONFIRMED single `sensor_data.csv`, 956,246 B, no header, 4 columns: X, Y, Z (raw LSB) and "Error" (% magnitude error of the gravity axis; CONFIRMED the formula |abs(Y)-16384|/16384*100). 37,459 rows. **No timestamp** so the rate is unknown; **no gyro; no temperature**.
- **Claim vs file (flag)**: REPORTED "the sensor was in a static, stationary resting state during the entire capture period". CONFIRMED the data is mostly at rest but not throughout: Y ranges -25,244 to +9,184 and X up to 20,828; the Z-axis mean steps from about -560 (first half) to about +200-250 (second half) at roughly row 18,700, and rows 18,725-26,215 show std 335-533 LSB, i.e. the board was bumped or re-oriented once. Per-segment rest noise: Y std about 70 LSB (about 4.3 mg), Z std about 100 LSB. Use only rows 0-18,000 or segments where std is low.
- **Licence**: "Unknown". **Size** 956,246 B. **Updated** 2026-06-24. 13 downloads.
- **Supports**: (b) accel offset and noise density estimate only; (c) not Allan-grade (no time base, one disturbance).

### 2.6 `jonassouza872/motor-vibration-classification-imu-dataset`  (MPU6050 with temperature)

- **Sensors**: REPORTED/CONFIRMED MPU6050, electric fan motor, 4 speed levels.
- **Files**: CONFIRMED `nivel0.csv` (motor off, 1,196 samples), `nivel1`, `nivel2`, `nivel3` (about 1,200 each), 57-61 KB.
- **Layout**: CONFIRMED header `Amostra,Acel_X,Acel_Y,Acel_Z,Giro_X,Giro_Y,Giro_Z,Temperatura`. Units REPORTED m/s^2 and deg/s. No timestamp.
- **Claim vs file (positive surprise)**: the description lists only 6 channels; CONFIRMED the files carry a 7th `Temperatura` column (internal die temperature, 17.19-17.99 C in nivel0, 0.1 C quantization, 14 unique values).
- **Content check**: nivel0 (motor off): gyro mean (0.70, 1.39, 0.27) deg/s with std about 0.12-0.14 deg/s; accel mean (-0.96, 0.74, 9.95) m/s^2 with std 0.02-0.03. CONFIRMED. This is a usable single-point gyro bias and noise estimate for a real MPU6050 at room temperature.
- **Limits**: about 1,200 samples; the time base is unknown, so no PSD or Allan analysis. Temperature barely moves in these files.
- **Licence**: "MIT". **Size** 238,425 B. **Updated** 2025-12-08. 294 downloads.
- **Supports**: (b) bias/noise snapshot; (d) vibration level contrast (off vs speeds 1-3).

### 2.7 `dinupadevinda/byd-atto-bmi160-imu-driving-events`  (BMI160 raw, CC BY)

- **Sensors**: CONFIRMED BMI160 (REPORTED, Raspberry Pi 4B over I2C), mounted flat in a car.
- **Files**: CONFIRMED `data/raw/harsh_braking/*.csv`, `data/raw/lane_change/*.csv` (about 0.3-1.2 MB each), `data/processed/imu_samples_clean.csv` (14.5 MB), README, CITATION.cff, LICENSE. Listing paged; remaining files not verified.
- **Layout**: CONFIRMED header `sr_no,timestamp,road_type,harsh_event,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,event_class`; timestamp `dd-mm-yyyy HH:MM:SS:mmm`; 50 ms steps = 20 Hz (CONFIRMED); `brake_20260207_090906.csv` has 5,791 rows (about 290 s). acc about 9.97 on z (m/s^2); gyro values about 0.004-0.008 (rad/s INFERRED).
- **Ground truth**: event labels only; no orientation truth. **Temperature**: none.
- **Licence**: "Attribution 4.0 International (CC BY 4.0)". **Size** 21,579,571 B. **Updated** 2026-06-23. 11 downloads.
- **Supports**: (b) gyro offset at rest in the first seconds (first 200 rows: gyro mean 0.007, 0.004, 0.002, std 0.012-0.024); (d) low-rate impact events. 20 Hz is too slow for vibration work.

### 2.8 `ricardofebra/cnc-vibration-monitoring`  (LSM6DSO vibration, CC0)

- **Sensors**: REPORTED Teensy 4.1 plus LSM6DSO accelerometer on a CNC.
- **Files**: CONFIRMED 13 CSVs `Dataset/Dataset/label_NN.csv`, 20-252 MB each; `label_24.csv` (19.9 MB) is the only one under 25 MB. CONFIRMED header `acc_x,acc_y,acc_z`, 800,500 rows, values about 1,050 on z (milli-g INFERRED), **no timestamp and no sampling rate in the file** (the description text I read does not state it; not verified).
- **Truth**: machining-parameter labels only (REPORTED). **Temperature / gyro**: none in the file.
- **Licence**: "CC0: Public Domain". **Size** 1,493,248,662 B. **Updated** 2023-10-27 (version 5). 682 downloads.
- **Supports**: (d) real-world MEMS vibration PSDs with known spindle RPM, good for checking our anti-alias / ODR / filter choices if the rate can be confirmed from the linked GitHub repo (not done).

### 2.9 `maximilianfellhuber/cnc-machining-data`  (Bosch CISS, 2 kHz)

- **Sensors**: REPORTED Bosch CISS tri-axial accelerometer at 2 kHz, 3 machines, 15 processes, "good" vs "bad" labels, 6 timeframes of 6 months. Not opened (files are about 5 MB each, so this was feasible; I did not download one, so columns are not verified).
- **Files**: CONFIRMED listing of 1,702 CSV files (e.g. `M01/OP00/good/M01_Aug_2019_OP00_000.csv`, about 5.1 MB each).
- **Licence**: "Attribution 4.0 International (CC BY 4.0)". **Size** 2,954,078,279 B. **Updated** 2024-01-17.
- **Supports**: (d) only, accelerometer vibration; no gyro, no orientation, no temperature (REPORTED).

### 2.10 `hkayan/industrial-robotic-arm-imu-data-casper-1-and-2`  (robot arm IMU + injected impacts)

- **Sensors**: REPORTED UR3e arm with IMUs; sensor model not stated on Kaggle (not verified); CSV column `name` shows IMU20/IMU22.
- **Files**: CONFIRMED 14 CSVs, e.g. `IMU_earthquake.csv` 1.8 MB, `IMU_hitting_arm.csv` 1.9 MB, `IMU_hitting_platform.csv` 2.3 MB, `IMU_extra_weigth.csv`, `IMU_magnet.csv`; big ones `IMU_10Hz.csv` 136 MB, `quaternions_20Hz.csv` 184 MB, `nicla.csv` 139 MB.
- **Layout**: CONFIRMED header `name,time,accX,accY,accZ,gyroX,gyroY,gyroZ,magX,magY,magZ`; time in ns epoch; rate about 10.2 Hz in `IMU_earthquake.csv` (median dt 0.098 s), 11,409 rows = 1,143 s. First row has zeros for gyro/mag. Gyro values are on a coarse grid (0.0625 steps). Units not stated (accX about 9.84 so m/s^2 INFERRED; gyro probably deg/s).
- **Truth**: none in these files (robot joint data not included). **Temperature**: none.
- **Licence**: "MIT". **Size** 3,065,646,348 B (version 2). **Updated** 2024-09-01. 922 downloads.
- **Supports**: (d) only qualitatively: at about 10 Hz the "earthquake", "hitting" events are heavily aliased. Not useful for roll accuracy.

### 2.11 `mdjakariakhalasi/fan-vibration-data-for-mpu6050`  (MPU6050 accel; rate claim wrong)

- **Files**: CONFIRMED `normal_fan1_data.csv`, `fault_1.csv`, `fault_2.csv`, `test_1.csv` (about 0.4-0.5 MB each). Header `timestamp,AcX,AcY,AcZ`, 22,915 rows, accel in g (z about 1.00).
- **Claim vs file (flag)**: REPORTED "Sampling Rate: 200 Hz". CONFIRMED timestamp steps are 21 ms (21 ms median and mean) = **47.6 Hz**, and the file spans 481 s. So the real rate is under a quarter of what is claimed; a 5 Hz-wide vibration band at 200 Hz would not be preserved.
- **Gyro / temperature**: none. **Licence**: "Unknown". **Size** 2,045,899 B. **Updated** 2026-05-12.
- **Supports**: (d) coarse only. Honest use: example of an uneven-quality ESP32 logger.

### 2.12 `shauryasingh21/3-axis-vibration-dataset-for-motor-fault-detection`  (MPU6050, STM32, DMA)

- **Files**: CONFIRMED folders `Healthy/`, `Bearing_Fault/`, `Imbalance`, `Transient_Shock` (REPORTED) with per-condition `raw_data_*.csv` of 5.4 MB (120,000 rows) and a summary `final_*_dataset.csv`. Names include `cold`, `warm`, `load`, `aired`, `weared`, `heavy`.
- **Layout**: CONFIRMED header `Accel_X,Accel_Y,Accel_Z` (z about -0.99, so units g). **No timestamp, no sampling rate in the file, no temperature column** even though file names suggest "cold" and "warm" conditions. Rate not verified.
- **Licence**: "Database: Open Database, Contents: Database Contents" (ODbL-style, unspecific). **Size** 110,974,630 B. **Updated** 2026-09-09. 54 downloads.
- **Supports**: (d) only; (b)/(c) no (accel only).

### 2.13 `iizawr/project-bodydata1`  (3x BNO055 on a garment)

- **Files**: CONFIRMED single `AllBodyData.csv`, 2.2 MB, 6,756 rows.
- **Layout**: CONFIRMED header per module: `sAccAnalog{0,1,2}.x/y/z, sMagAnalog.., sGyrAnalog.., sLiaAnalog.. (linear accel), sGrvAnalog.. (gravity), sEulAnalog.head/roll/pitch, sQuaAnalog.w/x/y/z`, final column the activity label (`LAYING`, ...). Three modules. No time column, no temperature; rate not verified. Units mixed (raw acc in mg-like integers, gyro deg/s, INFERRED).
- **Truth**: on-chip BNO055 fusion only (not independent). **Licence**: "Apache 2.0". **Size** 2,208,430 B. **Updated** 2024-08-13.
- **Supports**: (a) as a BNO055 reference only; small.

### 2.14 `aaryapandya/hand-tremor-dataset-collected-using-mpu9250-sensor`  (MPU9250; claims do not match file)

- **File**: CONFIRMED `Dataset.csv`, 27,995 rows, header `aX,aY,aZ,gX,gY,gZ,mX,mY,mZ,Result`, raw integers (e.g. aY 14,340), no time column.
- **Claim vs file (flag)**: REPORTED "3 MPU9250 sensors connected simultaneously on different positions of the hand". CONFIRMED the file has only one sensor's channels. CONFIRMED mY and mZ are constant -1 in every row (invalid magnetometer; only mX varies). `Result` is a shaking/stable label, mean 0.54.
- **Licence**: "Unknown". **Size** 1,227,257 B. **Updated** 2023-08-12. 655 downloads.
- **Supports**: only as "raw MPU9250 integers" for a gyro scale sanity check; the magnetometer is unusable.

### 2.15 `santiagoburgos94/5-meter-walking-dataset`  (MPU6050 DMA angles + gyro)

- **Files**: CONFIRMED 108 CSV files (`5_Meter_Walking_Dataset/Subject_N_Measurements/received_data/sensor_data_*.csv`, 0.1-0.17 MB each).
- **Layout**: CONFIRMED header `pitch,roll,yaw,gyrox,gyroy,gyroz` (angles in degrees, REPORTED; the sample trial `sensor_data_240822153228.csv` has 2,650 rows). **No raw accelerometer, no time column.** Angles come from an on-board fusion (REPORTED MPU6050; DMP or filter not stated).
- **Truth**: none. **Licence**: "MIT". **Size** 14,606,138 B. **Updated** 2025-02-06.
- **Supports**: sample of what an MPU6050 DMP roll/pitch looks like on a body; (a) no.

### 2.16 `chukajuzo/high-resolution-imu-car-trajectory-dataset`  (orientation log from phone)

- **File**: CONFIRMED `Orientation.csv`, 169 MB, header `time,seconds_elapsed,qz,qy,qx,qw,roll,pitch,yaw`, 949,631 rows over 4,743.8 s, about 200 Hz (INFERRED from rows/elapsed; the column name `seconds_elapsed` matches a phone logging app's export, app not named).
- **Truth / raw**: orientation output only (an OS-fused estimate); no gyro, accel or temperature, so it cannot be used to test our own estimator. **Licence**: "GPL 2". **Size** 169,027,393 B. **Updated** 2023-11-10.
- **Supports**: nothing of ours. Note the file is over the 25 MB limit; see the slip noted below.

### 2.17 `debanik123/sensor-fusion-v1-with-ros2`  (ROS 2 bags, RTK heading)

- **Files**: CONFIRMED 15 ROS 2 bags as `.zip`-described `.db3` + `metadata.yaml`; sizes up to 751 MB (sf_01_0.db3); gnss_bag_01 is 1.5 MB.
- **Content (CONFIRMED by reading `gnss_bag_01_0.db3` with sqlite)**: topics `/imu/data` (sensor_msgs/Imu, 3,664 msgs in 14.6 s = about 250 Hz), `/rtk/heading` (Imu type, 15 msgs, about 1 Hz), `/rtk/left|right|mid/fix`, `/rtk/odom`. IMU model not stated (not verified).
- **Truth**: RTK dual-antenna heading only (yaw), at about 1 Hz; no roll/pitch truth. Longest bag 175.5 s (REPORTED table).
- **Licence**: "Unknown". **Size** 4,096,726,089 B. **Updated** 2026-09-15. 10 downloads.
- **Supports**: yaw drift of a robot-grade IMU against RTK heading (not our roll metric). Low priority.

### 2.18 `danylomarchuk/euroc-cleaned`  (EuRoC derivative: orientation truth removed)

- **Content (CONFIRMED)**: `cleaned/vicon_room1/V1_01_easy/gt_array.npy` has shape (2870, 3): **position only**. `imu_input_array.npy` has shape (2870, 10, 6) = 10 IMU samples (6 channels) per image frame (values like acc 8.8, 0.7, -3.7 m/s^2 and gyro 0.003, 0.038, 0.074 rad/s), no raw time stamps. The only other non-image file per sequence is `cam_fnames_sequence.csv`. Orientation truth from the original Vicon data is therefore not in this derivative (checked only for V1_01_easy; other sequences listed with the same file pattern).
- **Licence**: "Other (specified in description)", description: "In Copyright - Non-Commercial Use Permitted". **Size** 153,825,689 B. **Updated** 2026-07-12.
- **Supports**: nothing for roll accuracy. The original EuRoC does contain Vicon orientation (INFERRED from the EuRoC project), but the Kaggle derivative does not. The original MH bags (`danielkarasov/eurocmav-bag`, `chunai/euroc-mh-01-easy-ros-bag-dataset`, 2-10 GB, CC BY-NC-SA 4.0) are Machine Hall, which in EuRoC uses a Leica position tracker, not Vicon (INFERRED; not verified here). Not downloaded because of size.

### 2.19 `adityajeevan/synchronized-semg-imu-dataset`  (IMU-derived elbow angle)

- CONFIRMED description: `Angle` label is derived from two IMUs with a Madgwick filter; EMG at about 500 Hz; 258 CSVs, `Time, EMG1-7, Angle`. The raw IMU channels are not in the table I saw (columns REPORTED). So the "ground truth" is itself an IMU estimate. **Licence** "Attribution 4.0 International (CC BY 4.0)". **Size** 492,640,343 B. **Updated** 2026-06-29. Not useful for our tests.

### 2.20 `iainmcintosh/data-from-first-readings-with-mpu6050-rasp-pi`  (tiny, partly synthetic)

- **Files**: CONFIRMED 4 CSVs, 14-62 KB. Header `,TimeStamp,Ax,Ay,Az,Gx,Gy,Gz`.
- **Claim vs file (flag)**: `MPU6050.csv` (100 rows, 1.55 s) has all six channels as uniform values in [0, 1), e.g. gyro 0.97, 0.58, 0.48, i.e. it looks like random test data, not sensor output (INFERRED). The other three files look real (az about 0.97-1.0 g, gyro about 0.0-0.2, 500 rows over 33 s = about 15 Hz, ramp and pumping motions). **Licence** "Unknown". **Updated** 2022-10-28.
- **Supports**: nothing quantitative.

---

## 3. Rejected (not a fit)

| Dataset | Why rejected | Basis |
|---|---|---|
| `mathurinache/alpine-skiing-at-the-winter-paralympics`, `oznbgcvn/snowboardpowdervalue`, `wrotki8778/ski-jumping-results-database-2009now`, `bakunga/snowboarding-competition`, `farheenshaukat/ski-resort`, `thomasnibb/european-ski-resorts`, `piterfm/beijing-2022-olympics` | Results tables or resort info, no sensor data | CONFIRMED titles/subtitles/file names |
| `jarnomatarmaa/sportdata-mts-5` | Contains skiing, but only HR, speed, altitude (files `HR-DATA`, `SPD-DATA`, `ALT-DATA`, `META-DATA`); no IMU | CONFIRMED file list |
| `mexwell/outdoor-sport-activities-mts` | Same source family (MTS sport activity, GPS-derived signals), no IMU | REPORTED; same family as above |
| `goldfinch/skiing-stowe-vt` | GoPro imagery of skiing | REPORTED |
| `zacky131/generated-quadrotor-6-dof-dataset-card` | Synthetic 6-DOF trajectories, no IMU noise | CONFIRMED description |
| `akkmit/drone-flight-imu-sensors-log`, `teodorababei/imu-drone-data` | Same 167,631,947 B file; REPORTED simulated environment, "6 hours"; also over 25 MB so not opened | REPORTED, file not opened |
| `sagerkudrick/rotation-estimation` | Synthetic rendered engine images with camera quaternions, no IMU | CONFIRMED description |
| `leaonwang/tartanimu-v25b-042934-checkpoint` | Model checkpoint and inference code for a competition, no raw data | CONFIRMED file list |
| `vishwanathtiwari/dead-reckoning-and-inertial-navigation` | Phone-sensor sessions for a hackathon (acc, gamerot, baro, GNSS), 3.4 GB, no truth orientation; not opened | REPORTED/CONFIRMED file names |
| `tomasbarata/ugv-sensor-data-2d-and-3d-trajectories` | IMU + odometry + GNSS + IPS on a ground robot, ROS CSVs 50-160 MB, trajectory truth only, not opened | REPORTED |
| `ahmedibra20/soltrack-dual-axis-solar-tracking-system-dataset` | Description says a Witmotion IMU measures platform orientation, but CONFIRMED columns are `Time (s), Temperature, Irradiance, Humidity, LDR1-4, Tracked Azimuth, Tracked Zenith` at 15 s steps; no raw IMU | CONFIRMED (xlsx header) |
| `rabieelkharoua/physical-therapy-exercises-dataset` | Xsens MTx at 25 Hz, no truth | REPORTED |
| `shahmirmustafa/frozen-shoulder-rehabilatation-exercises-imu` | Xsens exports, videos and xlsx (3 GB), output of Xsens fusion only | CONFIRMED file list |
| `serglez/gait-dataset-using-a-3d-motion-capture-system`, `dasmehdixtr/human-gait-phase-dataset`, `sanjeev196/vicon-physical-action-data-set`, `paultimothymooney/drivelineresearch-openbiomechanicsproject`, `kmader/cmu-mocap` | Optical mocap only (markers, force plates or EMG); no IMU file seen in the listing | CONFIRMED listing prefixes / REPORTED |
| `smartilizer/commercial-vehicles-sensor-data-set` | Dumper speed and 1-axis gyro/accel, labels of activity, no truth | CONFIRMED description |
| `djaarf/motionid-*` (4 sets, 0.9-22 GB) | Phone IMU for user identification, no orientation truth | REPORTED |
| `jimitjaishwal/gyro-acc-data` | No description, 123 MB, not opened | not verified |
| `jvh0903/datos-diatone-gt-m530-conacie25` | Betaflight blackbox CSV of a racing quad (gyro, PID, motors); no truth; 72 MB not opened | REPORTED |
| Many HAR sets (UCI-HAR, MotionSense, WISDM, KU-HAR, MHEALTH, etc.) | Phone/wearable HAR labels, no orientation truth | titles only |

---

## 4. Descriptions that claim more than the files show (summary)

| Dataset | Claim | What the files show |
|---|---|---|
| `aadityamukherjee2022/accelerometer-sensor-data-mpu6050` | Static, stationary for the entire capture | Row 18,700-26,200 shows large steps and std up to 533 LSB; Y min -25,244, max 9,184 (CONFIRMED) |
| `mdjakariakhalasi/fan-vibration-data-for-mpu6050` | 200 Hz sampling | 21 ms step = 47.6 Hz (CONFIRMED) |
| `aaryapandya/hand-tremor-dataset-collected-using-mpu9250-sensor` | 3 MPU9250 sensors, with magnetometer | One sensor's 9 channels; mY and mZ constant -1 (CONFIRMED) |
| `ahmedibra20/soltrack-...` | IMU measures actual platform orientation | Only a "tracked azimuth/zenith" angle at 15 s (CONFIRMED) |
| `iainmcintosh/...` `MPU6050.csv` | MPU6050 readings | Uniform [0,1) numbers in all channels (INFERRED synthetic) |
| `danylomarchuk/euroc-cleaned` | "Cleaned data from Vicon Room datasets from EuRoC" | Ground truth reduced to 3-column position array (CONFIRMED, one sequence) |
| `jonassouza872/motor-vibration-...` | 6-axis only | Also has a `Temperatura` column (CONFIRMED, in our favour) |
| `banaankiamanesh/imu-for-ai`, `iizawr/project-bodydata1` | "labels" of pitch, roll, yaw | Labels are the BNO055's own fusion output, not independent truth (REPORTED + INFERRED) |

---

## 5. Which dataset serves which of our tests

| Our test | Use | Gaps |
|---|---|---|
| (a) orientation/roll vs truth | Helmet (OptiTrack), RIDI (Tango pose), `imu-for-ai` (BNO055 reference, large tilts) | No mocap-grade roll at ski-like angles; no MPU6050-class hardware with truth. Our own recording against a tilt jig or phone camera + ArUco is still needed. |
| (b) gyro bias, noise, temperature drift | `motor-vibration-...` `nivel0.csv` (bias/noise snapshot with temperature), BMI160 first seconds | No long temperature sweep anywhere. |
| (c) Allan variance | none | No file with a known sample rate, gyro+accel, over 1 hour static. |
| (d) vibration / impact | `cnc-vibration-monitoring`, `cnc-machining-data`, fan sets (coarse) | No impact dataset with a high-rate gyro; CASPER impact files are about 10 Hz. |

## 6. Process notes

- The search endpoint returns HTTP 404 for queries with no hits and, occasionally, for rapid repeated requests; I added delays and retries. Paging a listing past about 200-240 pages also returns 404, so very large image-heavy datasets (euroc-cleaned, UZH-FPV, RIDI) could not be listed completely.
- Slip: while sampling `chukajuzo/...` I downloaded `Orientation.csv` (169 MB) by mistake before checking size; I read the header and first rows, then deleted it. No other file over 25 MB was fetched.
- My helmet sample folders are `samples/helmet-mounted-inertial-measurement-unit-dataset/helmet{1,5,9,12}/` (camera_qtn, vectorNav_qtn, vectorNav_imu); other samples are flat in their slug folders. Other agents' folders in `samples/` were not touched.
- Not verified: IMU chip models in CASPER and debanik; per-run activity labels in the helmet set; UZH-FPV groundtruth file presence; LSM6DSO sampling rate in the CNC set; Bosch CNC columns.
