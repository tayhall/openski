# 08. Kaggle datasets for ski-technique sensing: winter sports and analogue sports

Compiled 2026-10-11 by a research agent using the unauthenticated Kaggle web API (search, `datasets/view`, `datasets/list`, single-file download, plus HTTP Range requests for large files). No Kaggle account was used.

Claim labels: **CONFIRMED** = read from the API JSON or from a downloaded file in this session. **REPORTED** = taken from the uploader's description text only. **INFERRED** = my reasoning. "Not verified" = could not check.

Samples are saved under `D:\Projects\skivids\research\sensor-research\samples\<slug>\`. Nothing from them was executed. Large files were only read with HTTP Range requests (first bytes), not saved.

---

## 1. Bottom line

1. **There is no new Kaggle dataset of ski or snowboard boot/ski IMU data.** Across 228 search queries (about 190 distinct terms plus the pre-ranked list of 271), every "ski" or "snowboard" hit was resort statistics, competition results, images or video, except the one we already use (`leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski`, found and not re-reported). The SkiC-LSTM phone data is on GitHub, not Kaggle.
2. **Best analogue for our boot/lower-leg placement: `abhinavneelam/duo-gait`** (IMUs on both feet, both lower legs, sacrum, sternum, head, wrists, 128 Hz). Licence is "Unknown" on Kaggle, so we must check the source paper before reuse.
3. **Best labelled multi-sensor lower-limb data with a known sensor map: HuGaDB, PAMAP2 (ankle), mHealth (ankle), Daphnet (ankle/shank).** All are old academic sets with per-sample activity labels. None has turn events.
4. **Outdoor/winter signal exists only as low-rate, non-IMU data:** `jarnomatarmaa/sportdata-mts-5` (heart rate, speed, altitude, with "Skiing" and "R-Skiing" labels) and `frankvanrest/ice-skating-compass-data` (phone heading while ice skating, about 10 Hz).
5. **A large share of "sports wearable" Kaggle sets are synthetic.** I measured lag-1 autocorrelation on six; the four I flagged have values near 0 and unit variance, which real IMU signals at 50 to 100 Hz never show (real ones here: 0.84 to 0.96). See section 4.
6. **WEAR on Kaggle contains annotations only, not the sensor data.** The raw accelerometer data must come from the authors' website.

---

## 2. Search coverage

| Batch | Queries | Notes |
|---|---|---|
| Your list plus extras (ski, snowboard, skating, skateboard, surf, cycling, motorcycle, shoe, insole, gait events, helmet, winter, snow, ice, GNSS...) | 50 | Multi-word AND queries often returned 0 hits, so I re-ran them as single words |
| Single-word sweep (skiing, skating, cycling, bike, gait, stride, wearable, IMU, inertial, accelerometer, stroke, paddle, rowing, kayak, surf, helmet, GNSS, foot, pressure, mocap, sled, curling, dance, cutting...) | 73 and 51 (second pass after rate-limit failures) | |
| Named public HAR sets and sport words (PAMAP2, mHealth, Daphnet, freezing of gait, USC-HAD, rope jumping, nordic walking, e-scooter, wheelchair, equestrian, windsurf, motorcycle sensor...) | 54 | |

Total 228 queries. Hits were merged with `ranked.json` (271 earlier candidates, whose scores are noisy as you warned: top-ranked "alpine-skiing-at-the-winter-paralympics" is a 227-byte results table). About 45 datasets got a full metadata and file-list read; 30 had at least one file sampled.

Not found on Kaggle at all (CONFIRMED by empty or irrelevant results): ski boot sensors, snowboard IMU, nordic or cross-country ski IMU, speed skating sensors, roller or inline skating IMU, wakeboard, longboard, bicycle lean-angle IMU, mountain-bike IMU, shank-IMU or lower-leg-IMU-labelled-turns, GNSS+IMU sports sets with event labels.

---

## 3. Verified shortlist (ranked by usefulness to OpenSki)

Test codes used in the last column: **SEG** = event segmentation, **LR** = left/right alternation, **G** = high-g or impact robustness, **PL** = lower-leg/foot/helmet placement, **OUT** = outdoor or cold.

### Tier A: use or pull next

| # | Dataset (ref) | Sensors and placement | Rate | Subjects / sessions | Labels | Licence (exact) | Size / updated | Supports |
|---|---|---|---|---|---|---|---|---|
| 1 | **DUO-GAIT** `abhinavneelam/duo-gait` | CONFIRMED from file names: 1200 CSVs with per-sensor files HE, LF, RF, LL, RL, LW, RW, SA, ST per subject and condition. INFERRED mapping: head, left/right foot, left/right lower leg, left/right wrist, sacrum, sternum. Columns GyrX/Y/Z, AccX/Y/Z (CONFIRMED in `LL.csv`). | 128 Hz (CONFIRMED: timestamp step 0.0078125 s) | At least 16 subjects (CONFIRMED sub_01..sub_18 folders, some missing). Conditions: `dt`/`st` x `control`/`fatigue`/`sit_to_stand` (CONFIRMED folder names) | Condition labels by folder. `interim/stance_magnitude_thresholds_manual.csv` holds per-subject manual stance thresholds and counts (CONFIRMED). `interruptions.csv` lists excluded intervals. No per-step ground truth read. | `Unknown` | 6.76 GB, updated 2025-08-19 | PL (strong), LR, SEG (stance detection from foot IMUs, thresholds as weak reference). Indoor walking only, no OUT, no high-g. Description is empty, so provenance and collection method are REPORTED nowhere; INFERRED to be the published DUO-GAIT study but not verified. |
| 2 | **HuGaDB** `romanchereshnev/hugadb-human-gait-database` | REPORTED and CONFIRMED in header: accelerometer+gyro on right/left foot, shin and thigh (6 IMUs) plus 2 EMG on quadriceps | Not stated in the description; not verified. (File of 2435 rows covers about 2400 samples.) | REPORTED 18 participants, 10 h, 364 files (CONFIRMED 364) | Per-sample `activity` column (CONFIRMED in sample: sitting, standing, sitting_down, standing_up). REPORTED set includes walking, running, stairs. | `Data files © Original Authors` | 243 MB, updated 2020-11-24, 2300 downloads | PL (shin and foot, both sides: very good match), LR (separate L/R channels), SEG (activity transitions are segmented and annotated). Raw integer ADC units, not SI. Indoor. |
| 3 | **PAMAP2** `phamson/pamap2` (original .dat format). Other copies: `sufiinamulhassan/pamap2-clean-data-set`, `wangboluo/pamap2` | CONFIRMED from file: 3 IMUs (hand, chest, ankle), each with two accelerometers (+/-16 g and +/-6 g), gyro, magnetometer, plus heart rate. Ankle is one side only. | 100 Hz (CONFIRMED: 0.01 s timestamp step in `subject109.dat`) | 9 `.dat` files, subject101 to 109 (CONFIRMED); about 9 subjects REPORTED by the original paper, not verified here | Numeric `activity_id` per row. In `subject109.dat` only IDs 0 and 24 occur (CONFIRMED: 6391 rows of ID 24, 2086 of ID 0). My knowledge of the original: 24 = rope jumping, 5 = running, 6 = cycling, 7 = Nordic walking. **Not verified in this session**. | `Unknown` on `phamson/pamap2` (CONFIRMED). The `clean` copy and `wangboluo` copy say `Apache 2.0` (CONFIRMED), which the uploaders probably have no right to apply (INFERRED). Check UCI terms. | 1.30 GB (phamson), updated 2022-06-06 | PL (ankle), G (+/-16 g channel), cycling, running and Nordic walking as cyclic analogues. No turn or step event labels. |
| 4 | **mHealth** `nirmalsankalana/mhealth-dataset-data-set` | CONFIRMED from README: Shimmer2 on chest, right wrist, **left ankle** (accel, gyro, magnetometer), chest 2-lead ECG | 50 Hz (CONFIRMED in README) | 10 subjects (CONFIRMED: `mHealth_subject1..10.log`) | Column 24 label 0 to 12 (CONFIRMED in subject 10: label 0 = 64,614 rows; labels 1 to 11 about 3,072 rows each = 61 s at 50 Hz; label 12 1,024). README activity list: cycling (9), jogging (10), running (11), jump front and back (12). | `Unknown` | 227 MB, updated 2023-01-01 | PL (left ankle), cyclic cycling/jogging/running. Old, simple, one ankle only. |
| 5 | **Daphnet FoG** `siiner/uci-daphnet-freezing-of-gait` (also `nurulabsarshadik/daphnet-fog`, MIT) | CONFIRMED header: ankle, upper-leg, trunk accelerometers (3 axes each) | 64 Hz (CONFIRMED: 15.6 ms step, 64.00 Hz measured on `s01r01`) | 20 files `sXXrYY` listed; REPORTED by UCI source: 10 Parkinson's patients | `annotation` column 0/1/2 (CONFIRMED counts in s01r01: 0 = 59,185, 1 = 87,655, 2 = 5,147). Meaning of 1 = walking, 2 = freeze is INFERRED from the UCI documentation. | `Unknown` (siiner); `MIT` (nurulabsarshadik, uploaded 2026-07-10, almost certainly relicensed by the uploader: INFERRED) | 268 MB (siiner), updated 2023-04-12 | SEG (freeze episodes as events), PL (ankle). Pathological gait, accelerometer only, no gyro. |
| 6 | **STEP-Insole** `antonygarciag/step-insole-dataset` | CONFIRMED from header via Range read: insole with 3-axis accel (`accel_x_g`), 3-axis gyro (`gyro_*_dps`), and **18 pressure channels** per sample, each row is a window of 160 samples with `elapsed_s_*` per sample | Not stated; not verified (derive from `elapsed_s`) | `subject_id` column present (CONFIRMED, value 14 on first rows); count not verified | `class` column. Classes seen by sampling 40 offsets in the file: Walking, Tandem, Sitting, Standing (CONFIRMED; more may exist). `feet` Left/Right. | `MIT` | 905 MB (two near-identical CSVs, 456 MB + 449 MB), updated 2026-02-21, 20 downloads | PL (insole at foot), LR (Left/Right column). Windowed rows (4,005 columns) so continuous event timing is lost. No description or collection method (INFERRED unreviewed upload). |
| 7 | **Helmet Mounted IMU** `muhammadhamadzaheer/helmet-mounted-inertial-measurement-unit-dataset` | CONFIRMED file names: two IMUs (InertialSense uINS, VectorNav VN-100) at two helmet positions, plus OptiTrack camera quaternion ground truth | 250 Hz (CONFIRMED: 0.004 s step in `vectorNav_imu.csv`) | 12 recordings (CONFIRMED 60 files = 12 x 5). REPORTED: head movement while sitting, walking, running. | No event labels. Orientation ground truth only. Files have no header row (column meaning not verified; INFERRED time + gyro + accel + mag, accel z about -9.8 in first row). | `Attribution 4.0 International (CC BY 4.0)` | 537 MB, updated 2021-06-15 | PL (helmet), orientation-estimation validation, head vibration while running. No turn events. |

### Tier B: partial fit or specific use

| # | Dataset (ref) | What it is | Labels | Licence (exact) | Size / updated | Supports and caveats |
|---|---|---|---|---|---|---|
| 8 | **WEAR** `mariusbo/wear-an-outdoor-sports-dataset` | **Kaggle copy contains only annotation JSON** (CONFIRMED 65 files: 18 splits at each of 12, 30 and 60 fps, about 14.5 MB). Raw acceleration is on the authors' site (REPORTED link). REPORTED: 22 participants, 18 workout activities, 4-limb accelerometers plus egocentric video. | Segment-level start/end times with 18 classes (CONFIRMED in `wear_split_1.json`: jogging, jogging variants, push-ups, burpees, lunges, stretching...) | `CC BY-NC-SA 4.0` **non-commercial** | 14.6 MB, updated 2025-03-17 | SEG (segment-boundary labels), outdoor. No ski. NC licence. Must download sensor data externally. |
| 9 | **Sport Activity MTS-5** `jarnomatarmaa/sportdata-mts-5` | Heart rate, speed, altitude series (69 points each) from a single non-competitive athlete (REPORTED). No IMU. Five classes. | CONFIRMED label counts in META file: Running 370, Biking 245, **Skiing 245**, **R-Skiing 220**, Walking 60 (1,140 activities). Two device IDs in META: 1765 (990 rows) and 2623 (150 rows, labelled vivosport). Skiing is INFERRED to be cross-country. | `EU ODP Legal Notice` | 4.7 MB, updated 2023-07-13 | OUT. Only coarse activity classification from speed/altitude. Sibling copy `mexwell/outdoor-sport-activities-mts` is `CC0: Public Domain` with 3 classes (REPORTED). Not technique data. |
| 10 | **Swimming styles IMU** `ruslanbredun/swimming-styles-imu-data` | 286 short CSV slices of a worn IMU (gyro mdps, acc mg, mag mG; CONFIRMED header). Folder per style. Placement not stated. | Folder names (CONFIRMED): Bras, Batterfly, "Kroli na spine L/R", "Kroli na grudi L/R", Kuvirok... i.e. breaststroke, butterfly, backstroke and crawl with **left/right variants** | `Unknown` | 11.7 MB, updated 2022-11-19 | LR (explicit L/R classes), SEG (stroke cycles, alternating limbs). About 220 Hz (CONFIRMED approx: 4.5 ms step). Short slices, few seconds each (REPORTED). Appears real (integer sensor units). |
| 11 | **Horse Riding Movement Patterns** `simongbe/horse-riding-movement-patterns` | Vertical acceleration only from a phone in the rider's pocket (Equilab app), 15 rides | Per-sample `activity` (CONFIRMED in `20m_circles.csv`: trot 41,024, unknown 31,388, walk 18,193, canter 8,541, stand 5,959). Total annotated 573 min (REPORTED). | `Other (specified in description)`; the description names no licence terms (CONFIRMED), so effectively **undefined** | 99.6 MB, updated 2021-02-16 | SEG, cyclic gait. Rising trot is a left/right-alternating rhythm (INFERRED). One axis, 100 Hz (CONFIRMED 10 ms step), `;`-separated. |
| 12 | **Ice skating compass** `frankvanrest/ice-skating-compass-data` | iPhone X in back pocket, CoreMotion heading 0 to 360 only | None per sample. Description gives 123 laps in 1 h, 3 breaks, 220 m track (REPORTED). Uploader asks how to count laps. | `CC0: Public Domain` | 1.1 MB, updated 2018-10-30 | SEG (lap/turn counting from heading), OUT. One signal, about 9.95 Hz (CONFIRMED over 37,571 rows, 3,777 s). Heading wraps visibly in the first rows (271 to 23 degrees). Weakly relevant. |
| 13 | **Cyclist Accident Prevention** `shashwatwork/cyclist-accident-prevention-dataset` | Android phone on the handlebar: accelerometer, gyro, magnetometer, GPS (REPORTED), 3 routes x 3 laps | No labels read. Raw logs (CONFIRMED columns: timestamp ns, accuracy, x, y, z...). | `CC BY-SA 4.0` | 41 MB, updated 2022-11-05 | OUT, cornering on a bicycle (INFERRED, no lean labels). 50 Hz (CONFIRMED 20 ms step). No event labels. |
| 14 | **Bike Underground Detection** `nilshmeier/bike-underground-detection` | Phone accelerometer (Phyphox) during cycling, several bikes | `Underground` (surface) per file; sampling frequency and bike number per file (CONFIRMED `test.csv`: 100 Hz, Asphalt) | `CC BY-NC-SA 4.0` **non-commercial** | 72.9 MB, updated 2022-01-07 | G / vibration (surface classes). Phone position not stated (not verified). No turn events. NC. |
| 15 | **Reckless Motorcycle Riders** `vegatamafirdiady23/reckless-motorcycle-riders-smartphone-sensors` | Smartphone linear accel and gyro plus speed, two scooters (Yamaha Aerox 155, Honda Scoopy FI) | Per file: AA/AN accel, DA/DN decel, **PLA/PLN lane change with `_L`/`_R` direction** (CONFIRMED file names), 3 repeats each; 72 files | `CC BY-NC-SA 4.0` **non-commercial** | 1.4 MB, updated 2024-07-30 | LR (left/right lane-change labels), cornering analogue. About 29 Hz (CONFIRMED approx: 35 ms step), short files. NC. |
| 16 | **Turkish Folk Dance IMU** `berkaykarakis/turkish-folk-dance-imu-dataset` | CONFIRMED: 5 nodes (ESP32-C6 + MPU6050): right foot, right hand, left foot, left hand, waist; ax..gz per node | Dance class per file (Artvin, Susl, Teke) and subject ID (CONFIRMED columns). | `Attribution 4.0 International (CC BY 4.0)` | 348 MB, updated 2026-06-19 | PL (foot nodes, L/R), cyclic alternating steps. Irregular timestamps (CONFIRMED: 0, 12, 41 ms first rows, so about 25 to 80 Hz not verified), amateur graduation project (REPORTED), clock sync issue noted in description. No step events. |
| 17 | **Human Gait Phase** `dasmehdixtr/human-gait-phase-dataset` | Mocap markers and force plates, **not IMU** (REPORTED: Qualisys 200 Hz, Bertec 1,000 Hz, split-belt treadmill, 21 subjects, 25,306 steps) | `*_oversteps.csv` lists event times (CONFIRMED: 7 values e.g. 7.905, 34.11 s); force CSV gives FP1/FP2 x,y,z (CONFIRMED) | `Database: Open Database, Contents: Database Contents` (ODbL family) | 1.46 GB, updated 2019-12-02, 5,660 downloads | SEG: force-plate heel strike and toe-off ground truth to test an event detector (INFERRED). No IMU. Treadmill only. |
| 18 | **IMU walking/running (feet)** `maamli/imu-data-on-walking-and-running-unlabeled` | CONFIRMED header: ActiGraph Link 9-DOF IMU, accel (g), gyro, mag, temperature at 100 Hz, one session. REPORTED: sensor on the feet; subject strikes the ground twice between activities. | **Unlabeled** (title and description say so); markers are two foot strikes (REPORTED) | `Unknown` | 15 MB, updated 2018-11-13 | PL (foot), SEG demo (walk to run transitions delimited by double strikes). One subject. |

### Tier C: low value but verified (video or context only)

| # | Dataset | What it is | Licence (exact) | Why it might still matter |
|---|---|---|---|---|
| 19 | **Surf Maneuvers** `twonzii/surfing-maneuver-classification` | CONFIRMED: 1,062 video clips in train/val/test folders by class (360, cutback-frontside, roller, take-off), 1.64 GB. No sensors. | `Apache 2.0` | Video-labelled turns (cutback) as a labelling-protocol reference; could test video-to-turn-label pipelines. |
| 20 | **Skiing Stowe VT** `goldfinch/skiing-stowe-vt` | CONFIRMED: 1,005 GoPro images in folders `Green` (85), `blue` (502), `chairlift` (332), `crash` (85), `skier` (1). No sensors, no description. | `MIT` | First-person ski imagery with trail difficulty and "crash" folders. Possible image-side context only. |
| 21 | **Skateboarding Trick 120fps** `sensui0731/skateboarding-trick-dataset-120fps` | About 410 video clips, tricks by folder (ollie, shuvit, backside/frontside 180, kickflip, heelflip, pressure flip; REPORTED). No sensors. | `Attribution 4.0 International (CC BY 4.0)` | Event-labelled video for a vision comparison only. 4.0 GB. |
| 22 | **FHAR field hockey** `azmanshahar/field-hockey-activity-recognition-fhar-dataset` | CONFIRMED sensors.csv: 4 Physilog 5 IMUs at 128 Hz (chest, right wrist, waist, left wrist), 33 players (REPORTED), 6 activities. Not lower leg. | `Attribution 4.0 International (CC BY 4.0)` | Wearable sports labelling reference. One 110 MB CSV (not read). Updated 2026-07-22. |
| 23 | **K2MUSE** `k2muse/fortest` | Kinematics, kinetics, ultrasound, sEMG; 30 young + 12 older adults walking on slopes and speeds (REPORTED). Ships MATLAB/Python gait-cycle extraction code (CONFIRMED file names `funcExtGaitCyc.m`, `funcEventMerge.m`). | `Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)` **non-commercial** | 150.7 GB, updated 2026-02-15. Mocap and force plates, not IMU. Too large to be practical. Not sampled. |
| 24 | **Rowing lower-extremity kinematics** `aliabbasi1981/lower-extremity-kinematics-during-rowing` | 10 subjects x 4 stroke rates (CONFIRMED 40 files). CONFIRMED sheet columns: ankle, hip, knee, pelvis angles, left and right, x/y/z, 6,944 data rows in Speed1. Mocap joint angles, not IMU. | `Unknown` | Cyclic bilateral leg motion at known stroke rates (17 to 36 rpm REPORTED). No stroke event labels. 51.6 MB, updated 2023-04-20. |

---

## 4. Rejected or downgraded (with reason)

| Dataset | Reason | Evidence |
|---|---|---|
| `kitesurf365` (maevebennett) | Podcast transcript, not sensor data | CONFIRMED: columns `name,line`, "Welcome to the Kitesurf 365 podcast." |
| `inputkayak` (yeszetoh) | Android UI resource names, not kayak data | CONFIRMED: rows like `abc_decor_view_status_guard,abc,L#000000` |
| `loureiro85/surfing` | Surfer questionnaire (surfboard, wave, manoeuvre frequency) | CONFIRMED: 164 rows of form answers; licence "Data files (c) Original Authors" |
| `bakunga/snowboarding-competition` | One 4096x2160 24 fps MP4, no description, unknown licence; file name pattern suggests stock footage (INFERRED) | CONFIRMED: single `.mp4` 48 MB |
| `toedtlifhsg/skier-datensatz-seraina-fischer` | 8 screenshots in "Gut"/"Verkratzt" (good/scratched) folders | CONFIRMED file list |
| `oznbgcvn/snowboardpowdervalue`, `thomasnibb/european-ski-resorts`, `ulrikthygepedersen/ski-resorts`, `wrotki8778/ski-jumping-results-database-2009now`, `mathurinache/*-at-the-winter-*` | Product, resort, or results tables | CONFIRMED titles, sizes |
| `zoya77/wearable-sensor-data-for-tibial-load-prediction` | **Synthetic.** Description says "simulating data from wearable IMUs"; accel lag-1 autocorrelation -0.026, sd 0.99 | REPORTED + CONFIRMED stats |
| `datresearch/swimming-stroke-recognition` | **Synthetic-looking.** 2,010 rows x 60 IMU columns, lag-1 autocorrelation -0.002, sd 1.02; includes a `stroke_prob` column; no collection method | CONFIRMED stats |
| `zara2099/ai-smart-wearable-biomechanics-dataset` | **Synthetic-looking.** 1,000 rows, autocorrelation -0.007; "Feedback_Latency_ms", "Injury_Risk_Level" columns with no method; prolific uploader | CONFIRMED stats |
| `zoya77/multimodal-track-athlete-sprint-start-dataset` | **Fabricated-looking.** Claims IMU of Usain Bolt, David Gain, Trayvon Bromell; each IMU file is 300 rows of near-white noise (autocorrelation 0.027) | CONFIRMED stats |
| `marcoocorso/jumps-recording-with-imu` | Self-declared simulated ("50 jumps simulated data") | REPORTED |
| `ziya07/ai-powered-imu-motion-dataset`, `ziya07/college-sports-real-time-skill-feedback-dataset`, `ziya07/*` sports/athlete family | Descriptions say "simulates" / "simulated wearable sensor readings"; prolific uploader pattern. Not sampled. | REPORTED, not verified further |
| `zara2099/*`, `colabsss/*` sports-injury and biomechanics family | Same pattern: generic tabular "AI" datasets with no collection method. Not sampled. | INFERRED |
| `wangboluo/human-activity-recognition-trondheim-harth` | Real HARTH data but Kaggle copy is anonymised: columns `Feature_1..6, Activity`, timestamps removed (CONFIRMED). Sensors on thigh and lower back, not the shank. | CONFIRMED |
| `sufiinamulhassan/pamap2-clean-data-set` | Header looks misaligned: `extra_col_43..54` appended and values shifted vs. names (e.g. `chest_temp` = 1.0 on row 1) | CONFIRMED in first 400 kB |
| `sujaykapadnis/comprehensive-sports-database` (Sport DB 2.0) | Cardiorespiratory only (ECG, HR, RR), no IMU. `CC BY 4.0` | CONFIRMED description |
| `vlbthambawita/pmdata-a-sports-logging-dataset` | Fitbit logs and questionnaires, no raw IMU | REPORTED |
| `lucabasa/my-cycling-activities`, `carlossicre/hiking-running-and-cycling-fit-files` | Garmin FIT summaries: GPS, speed, power, cadence at 1 s; lap table CONFIRMED (no IMU columns). `CC BY 4.0`, `Apache 2.0` | CONFIRMED |
| `niloy333/eegmobility-dataset` | Single participant, headband/wrist/ActivPAL thigh patch ACC, MIT | REPORTED "pilot... single participant" |
| `vmalyi/run-or-walk` | Wrist phone, 5.4 Hz, `CC BY-NC-SA 4.0` | CONFIRMED (REPORTED description) |
| `jindongwang92/crossposition-activity-recognition` | Extracted features of PAMAP2/DSADS/OPPORTUNITY, not raw | REPORTED |
| `hadeerharfoush/usc-had-combined`, `dwaarakeshr/freezing-of-gait` (33 GB), `viji1609/*`, `janbrederecke/*` | Re-uploads or competition derivatives; no gain over the originals above | INFERRED |
| `djaarf/motionid-*` | Phone IMU for identification; 0.9 to 21.7 GB; `CC BY-NC-SA 4.0` | CONFIRMED sizes and licence |
| `orac04/nivel-de-conciencia-corporal-en-skateboarding` | 14 KB spreadsheet of a body-awareness questionnaire | CONFIRMED |
| `skin*`, `skill*`, `Cyclistic*`, `bike-sharing*`, `Olympic results`, weather, drone and UAV IMU sets | Keyword noise | CONFIRMED titles |

---

## 5. Licence flags

| Licence | Datasets | Commercial use |
|---|---|---|
| `CC BY-NC-SA 4.0` | WEAR, bike-underground-detection, reckless-motorcycle-riders, run-or-walk, motionid | **No** (non-commercial, share-alike) |
| `CC BY-NC 4.0` | K2MUSE | **No** |
| `Unknown` (Kaggle shows no licence) | DUO-GAIT, PAMAP2 (phamson), mHealth, Daphnet (siiner), IMU walking/running feet, swimming-styles-imu, rowing kinematics | Unclear; **treat as all-rights-reserved until the original source licence is read**. Original academic sources (UCI, authors) typically carry their own terms. |
| `Other (specified in description)` | Horse riding | Description specifies nothing; undefined |
| `Data files (c) Original Authors` | HuGaDB, loureiro85/surfing | Undefined; go to the source |
| `EU ODP Legal Notice` | sportdata-mts-5 | Permissive reuse in principle (INFERRED); not verified |
| `Database: Open Database, Contents: Database Contents` | Human Gait Phase | ODbL: attribution and share-alike on the database |
| `Attribution 4.0 International (CC BY 4.0)` | Helmet IMU, Turkish folk dance, FHAR, skateboarding video | Yes with attribution |
| `CC BY-SA 4.0` | Cyclist accident prevention | Yes, share-alike |
| `MIT`, `Apache 2.0`, `CC0: Public Domain` | STEP-Insole, daphnet-fog (nurulabsarshadik), Skiing Stowe, Surf Maneuvers, Ice skating compass | Yes. Note uploader-applied MIT/Apache on re-uploads of third-party data (daphnet-fog, pamap2 copies) may not be valid (INFERRED). |

---

## 6. Which test each dataset supports

| Test | Best candidates | Gaps |
|---|---|---|
| Event segmentation (step, turn, stroke) | Human Gait Phase (force-plate events), Daphnet (freeze episodes), HuGaDB (activity transitions), WEAR (segment boundaries, data external), DUO-GAIT (stance thresholds), PAMAP2 and mHealth (activity blocks) | No dataset has turn start/stop events. Nothing like ski turns. |
| Left/right alternation | HuGaDB (separate L/R shin and foot), STEP-Insole (L/R foot), Turkish folk dance (L/R foot nodes), swimming-styles-imu (L/R classes), reckless motorcycle (L/R lane change), DUO-GAIT | No sustained-carve left-right turn labels. |
| High-g robustness | PAMAP2 (+/-16 g accelerometer), bike-underground (vibration), helmet IMU running | No landing or impact labelled set. Distributed ski IMU (already held) remains the only high-rate set. |
| Placement lower leg / foot / helmet | DUO-GAIT (lower leg and foot), HuGaDB (shin, foot), STEP-Insole, maamli foot IMU, Daphnet (ankle), mHealth and PAMAP2 (ankle), helmet IMU | No boot-mounted data. No ski-mounted data apart from the one we have. |
| Outdoor / cold / winter | MTS-5 (skiing and roller-skiing coarse), Ice skating compass, Cyclist accident prevention, WEAR (outdoor, external data) | No cold-weather IMU data besides the alpine ski set already held. |

---

## 7. Recommended next steps

1. Pull **DUO-GAIT** `LL.csv`, `RL.csv`, `LF.csv`, `RF.csv` for three subjects (4 to 5 MB each) and check axes and orientation conventions for a boot-frame comparison. Resolve licence from the published paper first.
2. Pull **HuGaDB** walking and running files and **PAMAP2** subjects with IDs 5, 6, 7, 24 to test the lower-limb stride detector.
3. Obtain **WEAR raw data** from the authors' site if we need segment-labelled outdoor workouts (non-commercial licence).
4. Do not spend time on the synthetic "AI" sports sets (section 4). Treat their numbers as noise.
5. Re-check Kaggle monthly for new winter-sport uploads: the 2026 uploads seen (STEP-Insole, FHAR, Turkish folk dance) show fast churn, though the winter-sports slot has remained empty.

## 8. Method caveats

- The unauthenticated `datasets/list` search is relevance-ranked, returns at most 20 rows per query, and treats multi-word queries as AND. Some low-ranked relevant sets may be missed.
- File sampling covered one file per dataset. Statements about "all subjects" or "all files" come from descriptions (REPORTED) unless marked CONFIRMED.
- Licence strings are quoted from the `licenseName` field. The Kaggle licence can differ from the original source's terms.
- Original-paper facts that I know from background (e.g. PAMAP2 activity ID meanings, Daphnet annotation semantics, UCI licences) were not verified in this session and are labelled accordingly.
