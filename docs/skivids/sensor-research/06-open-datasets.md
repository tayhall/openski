# 06 - Open datasets and benchmarks for OpenSki IMU testing

Date of research: 2026-10-11. Author: research agent (Claude). Scope: public IMU datasets to test (a) orientation/roll vs ground truth, (b) event and turn segmentation, (c) noise and drift of consumer IMUs (MPU6050-class, cold/outdoor).

## How to read this report

Labels used on every claim:

- **CONFIRMED** - I opened the page or called the API and saw it.
- **REPORTED** - from a search snippet or secondary source; I did not open the primary page.
- **INFERRED** - my reasoning, not stated by a source.

Liveness check (HTTP) is given as "Live: yes/no/blocked". "Blocked" means the host refused my automated client (403/500), so I could not verify. I did not download any file over 50 MB; I only downloaded one 340 kB CSV header and listed repository trees.

Caveat on method: WebFetch passes pages through a small summariser, so where it said "not stated" the page may still contain the detail further down or on a sub-page. Those items are marked "not verified" rather than "absent".

---

## 0. Executive summary

1. **Best single find: "Distributed IMU Sensors on an Alpine Ski" (Kaggle, Beuken et al., Sensors 2024).** Open (Apache 2.0, 1.8 GB, 43 files), >700 Hz MEMS IMUs on a real ski, lab validation against an OptiTrack optical mocap (240 Hz), plus outdoor winter runs in Colorado. This is the only open ski-mounted IMU set I found with optical ground truth. It also has long static recordings (up to ~79 MB per file) usable for noise analysis. All CONFIRMED (Kaggle API and paper).
2. **Best for turn segmentation: the Robak/Turek smartphone ski data**, which is in GitHub `mszymocha/SkiC-LSTM` (MIT repo, 105 CSV runs, ~30.6 MB, 10 Hz, left/right turn labels from a ski instructor). CONFIRMED on GitHub API. Caveat: 10 Hz, phone on the calf, not a boot IMU, and the repo does not separately state a data licence.
3. **For MPU6050 noise/temperature:** Cranfield "Low-cost IMU Sensor Temperature Humidity Zero Bias Data" (three MPU-6050s in an environmental chamber, CC BY 4.0) is exactly on target, but the page returned 403 to my client, so it is REPORTED only. There is no open, long (hours) static MPU6050 recording that I could confirm; Kaggle MPU6050 sets are short.
4. **There is no open snowboard IMU dataset I could verify.** The only lead (Nokia x Burton Push Snowboarding, 2010) is a forum post I could not confirm is still downloadable.
5. **Spoerri, Martinez, Neuwirth, Azadi: none released data openly.** Statements are "on request" or "not publicly available" (CONFIRMED, quoted below).
6. **Several well known sets are not usable as-is for our purposes** (KITTI, Newer College: non-commercial licences; RIDI: download link dead; RoNIN: files currently "not available"; Blackbird: server did not respond; IEEE DataPort items: subscriber-only).

---

## 1. Ski and snowboard datasets

### 1.1 Distributed IMU Sensors on an Alpine Ski (Beuken et al.) - TOP PICK

| Field | Detail |
|---|---|
| Source | https://www.kaggle.com/datasets/leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski (Live: yes, HTTP 200, CONFIRMED) |
| Paper | Sensors 2024, https://pmc.ncbi.nlm.nih.gov/articles/PMC10974825/ (CONFIRMED; its Data Availability Statement names the Kaggle URL) |
| Contents | Ten MEMS IMUs distributed along the top of an alpine ski plus a hub in front of the binding; paper names the chip "TDK InvenSense MPU-42688-P" (probably ICM-42688-P, INFERRED typo). Rate >700 Hz (lab ~710 Hz, max ~730 Hz). CONFIRMED. |
| Ground truth | Lab validation: 8-camera OptiTrack Prime 13W, 240 Hz, stated +-0.2 mm, low-pass filtered and differentiated to compare to IMU. Outdoor: none (no mocap). CONFIRMED. |
| Field data | Outdoor testing, two skiers (intermediate and expert), >15 downhill runs at Colorado resorts; folders 1_23, 4_29, 5_17 (CONFIRMED file list). "Robust to repetitive testing in outdoor winter conditions" (CONFIRMED, Kaggle description). Temperature logs: not verified. |
| Size | 1,799,748,139 bytes total, 43 files, version 2, last updated 2024-02-02 (CONFIRMED, Kaggle API). One file is `combined_video_2.mp4` (618 MB). Largest data files 50-80 MB each. |
| Licence | Apache 2.0 (CONFIRMED, Kaggle API). |
| Format | Plain text/CSV: `Lab Validation/static_data/*.csv|txt`, `Lab Validation/dynamic_data/*`, `Outdoor Testing/.../runN.txt` (CONFIRMED names). Column layout: NOT verified (I did not download). |
| Load in Python | `kaggle datasets download -d leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski` (needs a Kaggle API token; INFERRED) then `pandas.read_csv` / `numpy.loadtxt`. Inspect headers first because the `.txt` format is unverified. |
| What it supports | (a) orientation vs OptiTrack in `Lab Validation/dynamic_data/rotation_dynamic.csv` (name suggests rotation test, INFERRED); (c) noise/drift from `static_data` (tens of MB per file, so maybe tens of minutes at 700 Hz, INFERRED); (b) event detection from outdoor runs (no turn labels; video exists but is one combined file). |
| Caveats | Ski-mounted, not boot-mounted; flex/vibration dominate; no turn labels; IMU is ICM-42688 class (better than MPU6050). |

### 1.2 Robak and Turek smartphone ski turns (as curated in SkiC-LSTM)

| Field | Detail |
|---|---|
| Papers | Robak and Turek, "Turn Detection in Alpine Skiing Using Smartphone Sensors", ICCS 2025, LNCS 15910 pp. 86-94 (REPORTED via search: https://www.iccs-meeting.org/archive/iccs2025/papers/159100084.pdf; the PDF was >10 MB so I could not open it). Szymocha, Kacprzak, Robak, Turek, arXiv:2608.07513, https://arxiv.org/abs/2608.07513 (CONFIRMED). |
| Data location | https://github.com/mszymocha/SkiC-LSTM, folder `data/turns_labeled/`. Stated in the arXiv HTML footnote: "Source code, pretrained weights, and curated dataset are available at https://github.com/mszymocha/SkiC-LSTM" (CONFIRMED, https://arxiv.org/html/2608.07513). Live: yes, CONFIRMED (GitHub API listing). |
| Contents | 105 CSV runs (CONFIRMED by tree listing) from two phones (`HONOR_8X`, `huawei_mate`) on dates Feb-Mar 2024 (CONFIRMED). arXiv text: 105 runs, 11 skiers, 1,781 turns; evaluation subset 99 runs, 9 skiers, 1,656 turns; 10 Hz; phone on calf; label per timestamp = left/right turn direction, no explicit "straight" class (CONFIRMED from arXiv HTML summary). Turn labels came from a certified instructor using synchronised video (REPORTED, search summary of the follow-up paper). |
| Columns (CONFIRMED from first rows of one CSV) | `time, TotalAcceleration, Orientation (4-7 value vector), Magnetometer, GyroscopeUncalibrated, MagnetometerUncalibrated, Accelerometer, Gyroscope, Gravity, Behavior (left/right), Status (START...), STYLE, SKIER_LEVEL, SLOPE`, plus GPS fields (bearing, speed, altitude, lon, lat) in one concatenated header. Vector columns are stored as strings like `"[x, y, z]"`, so parse with `ast.literal_eval`. |
| Metadata | `data/all_session_metadata.csv`: columns `id, STYLE, SKIER_LEVEL, SLOPE, SKIER_ID, GENDER` (CONFIRMED). Styles seen: quick, carving_long, skidded_short, skidded_long. |
| Size | ~30.6 MB for the whole `turns_labeled` tree (115 entries, CONFIRMED via git tree API). |
| Licence | Repo has an MIT `LICENSE` (CONFIRMED). The data licence is not separately stated; the original-source licence is not verified. Treat as "MIT as published, confirm with authors before redistribution". |
| Load | `git clone https://github.com/mszymocha/SkiC-LSTM` then `pd.read_csv(path)`; parse list-strings with `ast.literal_eval`. |
| What it supports | (b) turn segmentation: reproduce the published numbers (gradient-based algorithm F1 0.943 at turn level, 89.7% direction accuracy, REPORTED; causal LSTM 89.8% average directional accuracy with leave-one-subject-out, CONFIRMED abstract). Also (a) weakly: the phone's fused `Orientation` can be compared to your own filter run on raw accel/gyro (INFERRED; it is not an independent ground truth). |
| Caveats | 10 Hz is far below a boot IMU at 100-1000 Hz; phone on calf; no independent orientation truth; MIT licence covers the repo, not necessarily the participants' consent. |

### 1.3 Other ski datasets checked

| Dataset | Status | Evidence |
|---|---|---|
| Azadi et al. 2022 "Alpine Skiing Activity Recognition Using Smartphone's IMUs" (Sensors 22:5922) | Data not public. Data Availability Statement is "Not applicable." Smartphone 500 Hz, pelvis/thigh. | CONFIRMED https://pmc.ncbi.nlm.nih.gov/articles/PMC9371385 |
| Azadi, Haslgrubler, Ferscha 2025 "Motion Analysis in Alpine Skiing: Sensor Placement and Orientation-Invariant Sensing" | Not public: "not publicly available, as informed consent ... limited solely to publication of study results ... may be made available ... upon reasonable request." 12 Xsens MTw + 2 phones, 6 skiers, GoPro labels. | CONFIRMED https://pmc.ncbi.nlm.nih.gov/articles/PMC12031278 |
| Neuwirth et al. 2020 "Classification of Alpine Skiing Styles Using GNSS and IMUs" (Sensors 20:4232) | No data availability statement found. 2 Movesense IMUs on boot cuffs, 833 Hz resampled to 54 Hz, 20 skiers, ~2,000 turns, video-confirmed styles. | CONFIRMED https://pmc.ncbi.nlm.nih.gov/articles/PMC7435691 |
| Snyder, Martinez, Jahnel, Roe, Stoggl 2021 "Connected Skiing" (Sensors 21:3779) | "Available on request from the corresponding author." Boot-cuff IMUs 833 Hz, 54 Hz transmitted. (This is the "Martinez" paper.) | CONFIRMED https://pmc.ncbi.nlm.nih.gov/articles/PMC8199039/ |
| Kranzinger et al. 2024 (Frontiers, Connected Boot, 62 analysed skiers) | "Will be made available by the authors, without undue reservation." No repository. | CONFIRMED https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1495176/full |
| Spoerri (Sporri) et al. | I found no open IMU dataset. The related EPFL "Ski 2DPose" dataset is camera-only (1,982 annotated images, 24 joints, no IMU) with direct downloads; licence not stated. | REPORTED for the IMU claim (search); CONFIRMED for Ski 2DPose: https://www.epfl.ch/labs/cvlab/data/ski-2dpose-dataset |
| IEEE DataPort "Skiing Data Analysis" (Prochazka, Charvatova; DOI 10.21227/qebb-nf33) | Interesting: WITmotion IMU 100 Hz, Garmin GNSS 1 Hz, GoPro video, turn points from video, 3 runs (XLSX, 9.3/2.9/3.1 MB). But **subscriber-only** and licence not stated. | CONFIRMED https://ieee-dataport.org/documents/skiing-data-analysis |
| SINTEF cross-country (Sensors 18:75, DOI 10.3390/s18010075) | No dataset link on publication page; 10 participants, arm+chest IMUs. | CONFIRMED https://www.sintef.no/en/publications/publication/1534305 |
| Snowboard | No open snowboard IMU dataset found. Lead: "Nokia x Burton Push Snowboarding Dataset" 2010 forum post; could not confirm download. Gorges et al. ISBS 2024 halfpipe boot IMU: no data release mentioned. | REPORTED https://www.qtcentre.org/threads/38487-Nokia-x-Burton-Push-Snowboarding-Dataset-Release! ; https://commons.nmu.edu/isbs/vol42/iss1/160 |

---

## 2. Orientation ground-truth datasets (complements to BROAD)

BROAD itself: https://github.com/dlaidig/broad (Live: yes, HTTP 200).

| Dataset | Sensors / rate | Ground truth | Size / licence / location | Status | Test it supports |
|---|---|---|---|---|---|
| **FIU MARG DB** (Sonchan et al., PMC10144482) | Yost Labs 3-Space MARG, 120 Hz | OptiTrack V120:Trio optical, 3-marker rigid body; 30 recordings + extra folders (long run, reverse itineraries, alternative disrupters) | GitHub https://github.com/LABDSP/FIUMARGDB_marg_signals_and_reference_orientations (Live: yes, API 200). Repo licence GPL-3.0; repo description says 90,452 CSV records. Paper CC BY 4.0. | CONFIRMED (paper + GitHub API) | (a) orientation under magnetic disturbance; direct BROAD-style comparison. CSV: `pd.read_csv`. |
| **RepoIMU** (Szczesna et al. 2016) | IMU: acc+mag+gyro; wand (T-stick) and 3-segment pendulum | Vicon Nexus quaternions, synchronised | https://github.com/agnieszkaszczesna/RepoIMU (Live: yes). Licence/size not verified. README CONFIRMED. A 2023 survey reports duplicated samples, so effective rate is reduced and it advises against precision evaluation (REPORTED, https://arxiv.org/pdf/2302.06037). | CONFIRMED (README) | (a) quick sanity test only. |
| **MIMU_OPTICAL_SASSARI** (Caruso et al.) | 6 MIMUs (Xsens, APDM, Shimmer), 100 Hz | Vicon, 8 markers | IEEE DataPort DOI 10.21227/b23b-rx94, https://ieee-dataport.org/documents/mimuopticalsassaridataset. **Subscriber-only**. Homogeneous magnetic field only (REPORTED). | REPORTED (search only; page returned 206 to curl, not read) | (a), if you have an IEEE DataPort subscription. |
| **Kinematic data of a pendulum** (figshare 28271405) | Xsens + self-built IMUs | Optical mocap; angle, angular rate, angular acceleration | https://figshare.com/articles/dataset/Kinematic_data_of_a_pendulum_serving_as_an_angular_acceleration_reference/28271405 (HTTP 202 to curl; page not read) | REPORTED | (a) controlled single-axis check. |
| **EuRoC MAV** | MEMS IMU 200 Hz (CONFIRMED); model not stated on the page | Vicon 6D pose (Vicon rooms) and Leica MS50 laser tracker (machine hall), aligned | https://doi.org/10.3929/ethz-b-000690084 -> ETH Research Collection (https://www.research-collection.ethz.ch/handle/20.500.11850/690084). Files: Machine Hall 12,096 MB; Vicon Room 1 5,762 MB; Vicon Room 2 5,735 MB; Calibration 4,211 MB. Rights: "In Copyright - Non-Commercial Use Permitted"; Open Access. Page returned HTTP 500 to curl once but the fetch tool read it. Format: ASL zip (CSV) and per sequence. | CONFIRMED (https://projects.asl.ethz.ch/datasets/euroc-mav/ and Research Collection page) | (a) pure 6DoF orientation of an aggressive-motion IMU; heavy download (27.8 GB). |
| **TUM VI** | IMU 200 Hz, hardware-synchronised with cameras | Mocap 120 Hz, only at start and end of most sequences (full for "room") | https://cvg.cit.tum.de/data/datasets/visual-inertial-dataset (Live: yes). Licence CC BY 4.0 data, BSD-2 code. Formats: rosbag, EuRoC/DSO, raw. Links: https://vision.in.tum.de/tumvi/calibrated/512_16/ (Live: yes), `.../exported/euroc/512_16/`, raw at https://cdn3.vision.in.tum.de/tumvi/raw. | CONFIRMED | (a) orientation drift over long outdoor sequences (start/end loop closure); only "room" has full truth. |
| **UZH-FPV** | Snapdragon Flight board IMU (model, rate not stated on page) | Leica Nova MS60 laser tracker | https://fpv.ifi.uzh.ch/ and https://fpv.ifi.uzh.ch/datasets/ (Live: yes). CC BY-NC-SA 3.0. 16 sequences with public GT; DAVIS bags 0.5-1.3 GB, Snapdragon bags 1.1-2.6 GB, GT zips 46-142 kB. rosbag or zip. | CONFIRMED | (a) aggressive dynamics; large downloads; non-commercial. |
| **Blackbird** | 4.9 TB total (CONFIRMED on repo README) | Not described on README page | https://github.com/mit-aera/Blackbird-Dataset (Live: yes). Data host http://blackbird-dataset.mit.edu/BlackbirdDatasetData/ **did not respond** to my curl (HTTP 000). Repo MIT; dataset licence not verified. | CONFIRMED repo; data host NOT verified live | Too large; skip. |
| **Zurich Urban MAV** | Raw accel/gyro CSV (`RawAccel.csv`, `RawGyro.csv`, microsecond timestamps); rate not stated | Photogrammetric position (Pix4D) at 1 fps, no orientation truth for the IMU | https://rpg.ifi.uzh.ch/zurichmavdataset.html (Live: yes). Full 28 GB: https://download.ifi.uzh.ch/rpg/AGZ_data/AGZ.zip; sample <200 MB: https://download.ifi.uzh.ch/rpg/AGZ_data/AGZ_subset.zip (both answered 206). Licence: "no restriction", research and commercial. | CONFIRMED | Little value for roll; only raw outdoor MAV IMU noise. |
| **Newer College** | Intel RealSense D435i, Ouster OS-1 IMU (model/rate not stated) | LiDAR-map-derived 6DoF pose per scan | https://ori-drs.github.io/newer-college-dataset/ (Live: yes). Download by form. CC BY-NC-SA 4.0, non-commercial. | CONFIRMED | Low relevance; handheld walking, ground truth at LiDAR rate. |
| **KITTI raw** | OXTS GPS/IMU, text files; 10 Hz sync | GPS/INS | https://www.cvlibs.net/datasets/kitti/raw_data.php (Live: yes). Downloads need login. CC BY-NC-SA 3.0, academic use only (CONFIRMED on https://www.cvlibs.net/datasets/kitti/). | CONFIRMED | Poor fit; vehicle, 10 Hz, non-commercial. |

Other robotics sets seen only in search results (REPORTED, not opened): Brno Urban Dataset Winter Extension (RTK GNSS + Xsens IMU, snow; https://arxiv.org/pdf/2106.02952), seasonal navigation set with navigation-grade IMU and PPK truth (https://arxiv.org/html/2404.13166v1), Michigan Tech winter driving set (https://digitalcommons.mtu.edu/michigantech-p/2744), Multiple/Gyro-free IMU dataset: 54 sensors, 35 h, ground-truth trajectories, GitHub link not on the abstract page (https://arxiv.org/abs/2403.15494, CONFIRMED abstract only).

### Inertial odometry datasets (pedestrian phones)

| Dataset | What | Location and status | Usefulness |
|---|---|---|---|
| **OxIOD** | 158 sequences, >42 km, 4 consumer phones, handheld/pocket/bag/trolley, optical mocap truth for 132 of 158 (REPORTED, search snippet of the paper) | Project page http://deepio.cs.ox.ac.uk/ (Live: yes, CONFIRMED; the HTTPS cert is wrong, use http). Link on page: "Dataset (1.01G)" via a Google Form (https://forms.gle/wjE7u5AonoyyrgXJ7). Licence: not stated on the page or on the Oxford abstract page. | (a) phone orientation + heading drift; low for boots. 1 GB, form-gated. |
| **RoNIN** | >42.7 h, 100 subjects, two phones; position ground truth from body-worn tracking phone (CONFIRMED, https://ronin.cs.sfu.ca/) | DOI https://doi.org/10.20383/102.0543 -> FRDR page, which at fetch time said "No files uploaded" and "files are being backed up and can't be approved yet". Custom non-commercial research licence. Sampling rate not verified. | Currently not downloadable. |
| **RIDI** | Google Tango Lenovo Phab2 Pro, 200 Hz, >100 min, 6 subjects, 4 placements (REPORTED, arXiv abstract page via search) | Project page https://yanhangpublic.github.io/ridi/index.html (206 OK) links to https://wustl.box.com/s/6lzfkaw00w76f8dmu0axax7441xcrzd9, which returned **404**. The earlier link https://wustl.app.box.com/v/ridi-project-website was also 404. | NOT live. Ask the authors. |

---

## 3. MPU6050 / MPU9250 / low-cost IMU datasets

All Kaggle facts below are CONFIRMED from the public Kaggle API (`https://www.kaggle.com/api/v1/datasets/view/<owner>/<slug>` and `/list/`), which returned size, licence and file names without login. Download itself needs Kaggle credentials (INFERRED). Python: `pd.read_csv`.

### 3.1 Noise / temperature-relevant

| Dataset | Contents | Size / licence | Location | Status |
|---|---|---|---|---|
| **Cranfield: Low-cost IMU Sensor Temperature Humidity Zero Bias Data** (Maton, Economou, Galvao Wall, Khan, Cooper, May 2024) | Responses of three MPU-6050 IMUs in a Sanyo Gallenkamp environmental chamber under varying temperature and humidity | CC BY 4.0 (REPORTED); size not verified | DOI https://doi.org/10.17862/cranfield.rd.24291112 -> https://dspace.lib.cranfield.ac.uk/handle/1826/22168 | **Blocked**: HTTP 403 to both WebFetch and curl. Metadata from search snippet only (REPORTED). Check temperature range (cold coverage) before relying on it. |
| **Met4FoF MPU9250 sine excitation** (PTB) | MPU9250 raw sensor dump and ADC dump, Z-axis sine 10-250 Hz, reference accelerations from PTB reference system | 1.1 GB total (Sensor.dump 759.9 MB, ADC.dump 386.7 MB, xlsx 91 kB); CC BY 4.0 | https://zenodo.org/records/3885131 | CONFIRMED (WebFetch). Zenodo API/HEAD returned 403/odd content to curl but the page was readable. Useful for frequency response/SNR, not for drift. |
| **Kaggle MPU6050 body movement (idle/walking/running)** | `idle.csv`, `walking.csv`, `running.csv`; columns timestamp, ax, ay, az, gx, gy, gz, temp; ~12-14k rows per class | 1.8 MB; licence unknown | https://www.kaggle.com/datasets/letmecook123/mpu6050-body-movement-dataset-walking-running-idle | CONFIRMED. Idle is too short for meaningful long-tau Allan variance. Sample rate not stated. |
| **Kaggle Data from first readings with MPU6050 & Rasp Pi** | Four CSVs (13-62 kB each) | 199 kB; licence unknown | https://www.kaggle.com/datasets/iainmcintosh/data-from-first-readings-with-mpu6050-rasp-pi | CONFIRMED but description empty; not documented. Low value. |
| **BiWheel-IMU-Fault** (IEEE DataPort, DOI 10.21227/x2cr-td87) | MPU6050 on opposing wheels, ESP32 logger, ~100 Hz, fault labels; Dataset-2wheel.zip 5.08 MB | Licence not stated; **subscriber-only** | https://ieee-dataport.org/documents/biwheel-imu-fault | CONFIRMED |
| **Beuken ski static_data** | Long static ski-mounted ICM-42688 recordings, 37-79 MB files | Apache 2.0 | see 1.1 | CONFIRMED names/sizes; durations not verified. Not MPU6050. |
| **Kalibr-allan example data** | Static recordings for XSENS MTI-G-700, Tango Yellowstone tablet, ASL-ETH VI-Sensor; one Google Drive folder https://drive.google.com/drive/folders/1a3Es85JDKl7tSpVWEUZryOwtsXB8793o?usp=sharing | Durations, rates, licence not stated on README | README: https://github.com/rpng/kalibr_allan | README CONFIRMED; Drive folder not opened (NOT verified live). Another paper reports the Xsens set as 3 h at 400 Hz (REPORTED, https://www.frontiersin.org/journals/neurorobotics/articles/10.3389/fnbot.2022.993936/xml). |
| **FoMo** (boreal forest, Universite Laval) | 64 km over a year, 12 deployments, two IMUs (models not stated), snow >1 m, weather station every minute, GNSS-post-processed truth | CC BY 4.0 | https://fomo.norlab.ulaval.ca (page only listed topics; dataset links not verified); paper https://arxiv.org/abs/2603.08433 | Paper CONFIRMED. A search summary claimed a 24 h static calibration sequence; the abstract page does not mention it, so treat that as unverified. |

### 3.2 Activity / gesture sets recorded with MPU6050-class parts (event segmentation, label noise caveats)

| Dataset | Notes | Size / licence | Location |
|---|---|---|---|
| MPU6050 3 axis acceleration (drinking/driving/throwing) | 1 kHz, 300 samples x 100 sets per gesture; accel only | 1.6 MB, Apache 2.0 | https://www.kaggle.com/datasets/ashikshahriar/mpu6050-3-axis-acceleration-dataset |
| 5-Meter Walking Dataset | MPU6050 on a vest, two subjects, >50 trials each, pitch/roll/yaw (sensor-computed) + gyro; 14.6 MB | MIT | https://www.kaggle.com/datasets/santiagoburgos94/5-meter-walking-dataset |
| MPU fall detect classification | falls vs ADL | 524 kB, CC0 | https://www.kaggle.com/datasets/hnpawspham/mpu-fall-detect-classification |
| Walk and fall (MPU6050 on back) | 7 experiments, label `secayo` | 53 kB, CC0 | https://www.kaggle.com/datasets/juanbautistagitba/caminata-y-caida-datos-imu-mpu6050-en-espalda |
| Hand Gesture Recognition using MPU6050 | gestures | 354 kB, MIT | https://www.kaggle.com/datasets/prabhatlodhi/mpu6050-hand-gesture-recognition-dataset |
| Hand tremor (3x MPU9250) | acc+gyro+mag, shaking vs stable | 1.2 MB, licence unknown | https://www.kaggle.com/datasets/aaryapandya/hand-tremor-dataset-collected-using-mpu9250-sensor |
| Driving Behavior Dataset | phone, ~2 rows/s; not MPU | 20.8 MB, CC BY 4.0 | https://www.kaggle.com/datasets/shashwatwork/driving-behavior-dataset |
| MPU6050 Gyroscope Data for Posture Tracking (Mendeley) | Angles derived by gyro integration via Phyphox; rate/file list not on page | CC BY 4.0, DOI 10.17632/ftn4rjnd6x.1 | https://data.mendeley.com/datasets/ftn4rjnd6x/1 (Live: yes, CONFIRMED) |
| Digital Goniometer Measurements (Mendeley) | MPU6050 readings | CC BY-NC 3.0 (REPORTED) | https://data.mendeley.com/datasets/bjff5v29p8 |
| Biomechanical Basketball Shooting | MPU-6050 wrist band, derived features | 36 kB, CC0 | https://www.kaggle.com/datasets/ziya07/biomechanical-basketball-shooting-dataset |

Honest assessment: none of these has orientation ground truth or hours of static data. Many are tiny or synthetic-looking feature tables (INFERRED from size and descriptions; I did not open the files). They are only useful as a check that your pipeline handles real MPU6050 quirks (timestamps, saturation, 1 kHz jitter).

---

## 4. Sports and human-motion sets with IMU plus optical/video truth

| Dataset | Contents | Size / licence | Location | Status |
|---|---|---|---|---|
| **GAITEX** | 9 IMUs, 35 optical markers + 4 markers on each IMU (IMU-orientation reference), 19 participants, gait and rehab exercises, time-stamped segmentations, OpenSim models | data.zip 17.7 GB, CC BY 4.0, v1.0.0 June 2025 | https://zenodo.org/records/15729055 | CONFIRMED. Strong for (a) IMU orientation vs mocap and (b) temporal segmentation. Sampling rate not verified. |
| **Kuopio gait dataset** | 51 participants; Xsens MTw Awinda on pelvis/thighs/shanks/feet at 100 Hz; Vicon-compatible markers + force plates; sagittal video with OpenPose keypoints | 23.3 GB (three ~7-9 GB zips), CC BY 4.0, Jan 2024 | https://zenodo.org/records/10559504 | CONFIRMED. Force plates give gait-event truth. |
| **Football IMU + Vicon** (movement intensity) | Processed IMU and VICON measurements plus Matlab scripts | imuValidation.zip 987 MB, CC BY 4.0 | https://zenodo.org/records/3732988 | CONFIRMED. Sensor model/rate not stated. |
| **MoVi** | 90 actors, 20 actions incl. sports motions; 9 h optical mocap, 17 h video (4 views), 6.6 h IMU | Non-commercial research licence (AMASS licence applies to part); size not stated | https://www.biomotionlab.ca/movi/ (Live: yes); data at https://dataverse.scholarsportal.info/dataset.xhtml?persistentId=doi:10.5683/SP2/JRHDRN (not opened) | CONFIRMED page; Dataverse not opened |
| **StrengthSense** (Movesense) | 10 Movesense HR+ 9-axis IMUs, 29 participants, 13 exercises; joint angles validated vs video | 592 MB zip, CC BY 4.0 but "non-commercial research only" and limited to users located in EU member states (CONFIRMED) | https://zenodo.org/records/17424677 | CONFIRMED. Movesense is the same family as the Connected Skiing boot hardware (REPORTED in section 1.3, INFERRED link). Location restriction is a blocker if you are outside the EU. |
| **UFABC gait (figshare)** | Inertial plus foot-switch contact data for toe-off; 9,661 strides healthy + 496 foot-drop | Licence not verified | figshare (found via search, URL not captured; REPORTED) | Not opened |
| **GaitMotion (UBC)** | Gait events at sample index, GaitRite truth, CC BY-NC-SA 4.0 | | https://open.library.ubc.ca/collections/researchdata/items/1.0435087 | Page blocked me ("unusual activity"); REPORTED only |
| **IEEE DataPort "IMU based walking data"** | 4 IMUs incl. BNO055, Vicon, toe-off/heel-strike labels, Data_abs.zip 31.2 MB | Licence not stated; **subscriber-only** | https://ieee-dataport.org/documents/imu-based-walking-data (DOI 10.21227/sfy3-jn97) | CONFIRMED |

Not found: any open dataset of boot/foot-mounted IMU in alpine skiing with motion-capture or video-labelled turn events (apart from the Beuken lab set and the DataPort paywalled one).

---

## 5. Ranking by usefulness to OpenSki

Scoring: relevance to our three tests x openness x effort.

| Rank | Dataset | Why | Blocker |
|---|---|---|---|
| 1 | Beuken Kaggle alpine ski IMU | Ski domain, outdoor winter, optical truth, high rate, static files, Apache 2.0 | Format unverified; ski-mounted; needs Kaggle token |
| 2 | Robak/Turek in SkiC-LSTM | Only open labelled ski-turn set; tiny; reproducible published baselines | 10 Hz, phone not boot, licence ambiguity |
| 3 | FIU MARG DB (GitHub) | Clean BROAD-style orientation vs optical truth, with magnetic disturbance, CSV, small | Not skiing; 120 Hz; GPL-3.0 repo licence |
| 4 | Cranfield MPU-6050 chamber data | The only MPU6050 temperature/bias set found | Page blocked; temp range unknown |
| 5 | GAITEX | IMU orientation vs mocap with 4 markers per IMU + segmentation labels, CC BY 4.0 | 17.7 GB; walking, not skiing |
| 6 | Kuopio gait | Force plate gait events + IMU 100 Hz | 23 GB |
| 7 | EuRoC MAV | Gold-standard IMU+mocap benchmark, well supported tooling | 28 GB; non-commercial rights wording; MAV dynamics |
| 8 | TUM VI | CC BY 4.0, 200 Hz IMU, mocap | Truth only at start/end for outdoor |
| 9 | Kalibr-allan Drive set | Real long static recordings | Link unverified, no licence |
| 10 | StrengthSense | Movesense IMUs, EU-only | Access restriction |
| 11 | OxIOD / UZH-FPV / MoVi | Marginal | Size, licence, relevance |
| - | KITTI, Newer College, Blackbird, Zurich MAV, RIDI, RoNIN | Low relevance, non-commercial, dead or unreachable | See sections 2 |

---

## 6. Plan: three best new benchmarks to run next

### Benchmark 1 - Ski-mounted orientation vs optical truth (Beuken lab set)
- **Goal:** extend the BROAD result (1.6 deg median roll for the 3D complementary filter) to a ski-like, vibration-heavy, outdoors-adjacent IMU.
- **Steps:** (1) `kaggle datasets download` only the `Lab Validation/` subtree (about 300 MB; the Kaggle CLI can fetch single files with `-f`); (2) inspect columns, find accel/gyro units and the OptiTrack columns (in `rotation_dynamic.csv` and `dynamic*`, INFERRED); (3) time-align via cross-correlation of angular rate magnitude; (4) run your complementary filter, plus Madgwick/VQF as baselines; (5) report roll/pitch error median and 95th percentile per file.
- **Risks:** mocap file may hold only position or a rotation of one axis; ski flexing; IMU is a better chip than MPU6050 so add MPU6050-like noise (from benchmark 3) to the data for a fair test.
- **Effort:** 1.5-2 days (half a day of format discovery).

### Benchmark 2 - Turn segmentation on Robak/Turek phone data
- **Goal:** evaluate our turn/event detector on labelled real skiing, compare with published numbers.
- **Steps:** (1) `git clone` the repo (30 MB); (2) parse CSVs, resample uniformly at 10 Hz, use `Gyroscope` and `Accelerometer` (shank-equivalent axis) plus `Behavior` labels; (3) run our zero-crossing/peak turn detector on gyro-derived roll rate or edge-angle proxy; (4) metrics: turn-level F1 with +-0.5 s tolerance, direction accuracy, leave-one-skier-out (the metadata has `SKIER_ID`); (5) compare with 0.943 F1 (gradient method, REPORTED) and 89.8% directional accuracy (causal LSTM, CONFIRMED).
- **Risks:** 10 Hz hides boot-level impacts; phone-axis mapping unknown; licence for redistribution.
- **Effort:** 1 day.

### Benchmark 3 - MPU6050 noise, bias and temperature drift (cold-biased)
- **Goal:** quantify what a low-cost MPU6050 does in cold/outdoor use, and feed a realistic noise model into benchmark 1.
- **Steps:** (1) obtain Cranfield dataset (browser download from https://doi.org/10.17862/cranfield.rd.24291112, since scripted access gets 403); confirm the temperature range includes <= 0 C, otherwise record your own cold run; (2) fit bias vs temperature per axis; (3) Allan deviation (e.g. `allantools`) on the Beuken static files and the Kalibr-allan Xsens/VI-sensor folders as reference-grade comparators; (4) inject fitted noise into the BROAD and Beuken data and re-run the filter.
- **Risks:** Cranfield file layout and temperature range unknown; no public hours-long MPU6050 static log was found, so the MPU6050 Allan curve may need your own 2-6 h recording (cheap to make).
- **Effort:** 2-3 days, of which 0.5 day is your own static capture.

Runner-ups if you want more orientation coverage: FIU MARG DB (half a day; CSV; magnetic disturbance), GAITEX (needs 17.7 GB, 2-3 days).

---

## 7. Items I could not verify (be careful)

- Cranfield dataset page (403), UBC GaitMotion (blocked), IEEE DataPort pages (subscription), Blackbird data host (no response), RIDI Box links (404), RoNIN files (not available on FRDR at fetch time), Kalibr-allan Google Drive (not opened), MoVi Dataverse (not opened), Zenodo REST API (non-JSON responses via curl; I used page fetches instead).
- Sampling rates for Cranfield, OxIOD, RoNIN, Zurich MAV, UZH-FPV IMU were not stated on the pages I read.
- Whether the Robak/Turek ICCS 2025 paper published its own data separately from the SkiC-LSTM repo: not verified (PDF too large for the fetch tool).
- The exact IMU part number in the Beuken paper ("MPU-42688-P") looks like a typo for ICM-42688-P; INFERRED.

## 8. Python loading cheat sheet

```python
import pandas as pd, ast, numpy as np

# Robak/Turek (SkiC-LSTM): vector cells are strings like "[x, y, z]"
df = pd.read_csv("data/turns_labeled/HONOR_8X/2024-02-17/1.csv", parse_dates=["time"])
acc = np.vstack(df["Accelerometer"].map(ast.literal_eval))
gyr = np.vstack(df["Gyroscope"].map(ast.literal_eval))
lab = df["Behavior"]            # left/right at turn events, NaN elsewhere (per first rows)

# Kaggle datasets (needs ~/.kaggle/kaggle.json)
#   kaggle datasets download -d leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski \
#          -f "Lab Validation/static_data/static_data_1.csv"

# EuRoC (ASL format): mav0/imu0/data.csv  (timestamp [ns], w_RS_S_x/y/z [rad/s], a_RS_S_x/y/z [m/s^2])
# TUM VI EuRoC export: same layout
# Rosbag formats (UZH-FPV, Blackbird): use `rosbags` (pip install rosbags) to read without ROS
```

(EuRoC column names are from my background knowledge, INFERRED, not opened in this session.)
