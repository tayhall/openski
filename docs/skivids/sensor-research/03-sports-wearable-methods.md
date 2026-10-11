# 03 - IMU methods and open code from other sports and domains

Scope: reusable ideas for OpenSki (boot-cuff IMU, mounting varies per fitting; outputs: turn detection/timing, boot roll per turn, L/R consistency, later load).
Excluded as already known: Martinez 2019, Neuwirth 2020, Snyder 2021, Fasel 2017, Robak/Turek.
Research date: 2026-10-11. Tools: web search plus page/PDF fetches.

Evidence labels:
- CONFIRMED = I read the claim in the primary source (page or PDF text) during this session.
- REPORTED = taken from a search-result summary or secondary page; I did not read the primary text.
- INFERRED = my reasoning, not stated by a source.

Items I searched for and could NOT verify are listed in section 9. Nothing below is invented; where a number is missing, it says so.

---

## 0. Executive summary

1. The strongest directly reusable asset is the **gaitmap** ecosystem (MIT core): pre-built gravity alignment, heading alignment, DTW/HMM stride segmentation, benchmark design, and a "tpcp" validation harness. Foot-worn, not boot-cuff, but the structure (segmentation then event detection then orientation then parameters) maps onto turn segmentation then switch time then edge angle.
2. For **unknown mounting orientation**, the most transferable recipes are: (a) gravity from quiet periods gives pitch/roll offsets; (b) yaw offset from a data-selected "straight, steady" or "braking/accelerating" segment (Stanglmayr, motorcycle, CONFIRMED); (c) PCA of horizontal acceleration for the direction of travel with a 180-degree disambiguation (Humaine, CONFIRMED); (d) functional hinge-axis identification from gyroscope and accelerometer constraints (Seel 2014, Olsson/Seel 2019, CONFIRMED). The boot cuff roll/pitch problem is a near-hinge problem (ankle/cuff flexion), so (d) is a candidate for a "do a few knee bends" calibration.
3. For **drift**: complementary filters with accelerometer correction plus kinematic constraints (OpenSense: -0.14 to 0.17 deg/min drift, CONFIRMED), zero-velocity/stance-phase updates (gaitmap), and the bicycle/motorcycle frequency-separation pattern (gyro for dynamic roll, speed/yaw-rate-corrected accelerometer for the quasi-static roll, CONFIRMED). Warning: accelerometer saturation on ice/bumps breaks Madgwick edge angles in skiing (Hummel 2023, CONFIRMED from abstract).
4. For **validation design**: video-labelled events at frame precision with multiple raters (Martinez 2019/Gomes 2019 surf, Groh skate), optical mocap with markers on anatomical landmarks rather than on the IMU (Seel 2014), a physics-defined reference event (Supej turn-switch concept, in Martinez 2021) and a high-grade reference IMU (ADMA in the motorcycle work).
5. No open **snowboard** turn-detection code, no open **surf** manoeuvre code and no open **golf/cricket/baseball** event data were found (section 9).

---

## 1. Skiing and snowboarding beyond the known set

### 1.1 Martinez, Snyder, Moore, Stoggl 2021 - comparison of 27 turn-switch methods (treadmill)
- URL: https://pmc.ncbi.nlm.nih.gov/articles/PMC8038779 (Sensors 2021, 21(7):2573) - CONFIRMED.
- Measured: turn-switch time of alpine skiing, compared across published methods. 14 skiers on an indoor ski treadmill (17 deg slope, 4.6 m/s), 30 s trials of long and short turns.
- Sensors: 7 body IMUs (512 Hz), an instrumented boot IMU on the back of the upper cuff (833 Hz internal, 54 Hz over Bluetooth), pressure insoles (100 Hz), force plates (2 skiers), EMG, 13-camera mocap (100 Hz), 2D video.
- Algorithm: 27 method-sensor entries (CoM/ski trajectory, video edge-change, minimum load, force crossing, EMG/knee angle, IMU/boot angular velocity).
- Validation: reference = Supej concept (CoM projection crossing the mean of ski positions) from mocap trajectories. All methods within 0.25 s, ten within 0.05 s, precision 0.10 to 0.60 s; best: Klous Video, Spoerri PI, Martinez CTD, Yamagiwa IMU.
- Open: data on request only.
- Reuse: (i) the physics-defined reference event (Supej) is a better ground-truth definition than "frame where the skis look flat"; (ii) a ready list of alternative turn-switch definitions to benchmark our detector; (iii) a boot-cuff mount at 54 Hz Bluetooth is a realistic rate floor, so check our detector at 50 Hz. Caveat: treadmill, mostly junior racers.

### 1.2 Hummel et al. 2023 - ski orientation from boot-back IMUs with Madgwick
- URL: https://lida.sport-iat.de/svd/Record/4081463 (9th Int. Congress on Science and Skiing, Saalbach 2023) - CONFIRMED (abstract page).
- Measured: ski/boot edge angle. 2 IMUs on the back of the boot, 3 instructors x 3 trials (skidding/stop, snowplow, carving). Low-pass then Madgwick, quaternions to Euler.
- Finding: plausible overall, but edge angles drift when the accelerometer saturates on icy or bumpy slopes; adjusting filter gain helps. No reference system used yet. Suggested: dynamic gain adjustment, mechanical damping.
- Open: none stated.
- Reuse: direct warning for our roll-per-turn output. Implement accelerometer saturation/overrange detection and gain scheduling (the Fusion library does acceleration rejection and overrange restart, section 4.4).

### 1.3 Edge-engagement metric (2025)
- URL: https://www.scitepress.org/publishedPapers/2025/136651/pdf/index.html - REPORTED only (search snippet; not fetched). Boot IMUs, edge-angle progression in turn initiation, segmentation by the Martinez 2019 algorithm.

### 1.4 Snowboarding
- Groh, Fleckenstein, Eskofier, "Wearable Trick Classification in Freestyle Snowboarding," BSN 2016. URL: https://cris.fau.de/publications/117773304/ - REPORTED. IMMU (accelerometer, gyro, magnetometer) mounted on the board, 11 athletes, 275 trick events; category recall 96.6% and 97.4%; classification accuracy 90.3% and 93.3%. Pipeline = detect, categorise, classify.
- Holleczek et al. "turn recognition for snowboarding" using specified gyroscope signal conditions to separate stance direction, turns and riding technique: REPORTED via the related-work paragraph of Groh 2015 (https://www5.informatik.uni-erlangen.de/Forschung/Publikationen/2015/Groh15-ITC.pdf, read as PDF text, CONFIRMED that the paragraph says this). I did not locate or read the Holleczek paper itself.
- Harding et al. (half-pipe rotation counting) and Sadi/Klukas (jump detection, airtime) are cited in the same paragraph (CONFIRMED that they are cited; originals not read).
- Reuse: heuristic gyro-condition turn logic is a precedent for rule-based detection; stance direction (regular/goofy) detection from sensor axes is analogous to our left/right boot identification (INFERRED).
- No open snowboard turn-detection code or dataset found.

---

## 2. Wheeled lean: motorcycle and bicycle roll angle

This is the closest physics analogue to "boot roll per turn": a body rolls about a quasi-horizontal axis, with centripetal acceleration contaminating the accelerometer gravity vector.

### 2.1 Stanglmayr, Baeumler, Buettner, Prokop 2020 - smartphone motorcycle roll angle (TU Dresden)
- URL: https://www.ifz.de/IMC/Session8/IMC2020-Session8-IFZ_2020_TUD_Paper_Smartphones_MotorcycleDynamics.pdf - CONFIRMED (full text read).
- Measured: motorcycle roll (lean) angle, curve radius, course angle; uncontrolled phone mount (orientation differs each ride).
- Sensors/rate: Android phone, gyro and accelerometer 100 Hz, GPS 1 Hz; custom "MotoLogger" app writes CSV.
- Algorithm (plain terms):
  1. Pick "straight, steady" data: speed above 30 km/h, acceleration below 0.3 m/s2, rotation-rate sum below a speed-dependent threshold (thresholds correspond to a maximum 3 deg roll). From these, gravity gives phone pitch/roll relative to the vehicle; take the **median** over selected points (CDF-based).
  2. Pre-rotate data to a "horizontal" frame.
  3. Yaw offset from braking-while-straight segments (decel at least 2.5 m/s2, sign from GPS speed derivative).
  4. Roll angle via frequency separation: integrated roll rate (dynamic part) fused by Kalman filter with a stationary roll estimate from lateral acceleration and speed/yaw rate; low-pass filters 1 Hz for accelerations, 5 Hz for rotation rates, 0.3 Hz for speed (Butterworth, forward-backward).
- Validation: against an ADMA G high-precision inertial reference (CONFIRMED mentioned), several routes, plus a multi-rider feasibility study. Deviations: at most about 3 deg stationary cornering or straight, below about 6 deg while rolling dynamically (up to 9 deg in one phone comparison); RMSE typically about 2 deg (example 1.8 deg). Largest error source is time lag between systems.
- Open: paper only; no code or data stated in what I read.
- Reuse (high): this is nearly our problem statement. A three-step self-alignment that needs no operator action: gravity from steady periods, then yaw from a known-direction manoeuvre. For skiing, equivalents are straight traverse/schuss or the "stand still at lift/start" window for gravity, and the symmetric left/right turn assumption for the yaw/roll zero (INFERRED). The data-point-selection thresholds, median aggregation and error budget (time lag dominates) are directly portable. Beware: the paper itself reports that automatic roll/pitch determination fails when standing still on a side stand (CONFIRMED), the analogue of a skier standing on a slope with a cuff unbuckled.

### 2.2 Chuang, Zhang, Chen 2022 - bicycle roll via two-step Kalman filter
- URL: https://pmc.ncbi.nlm.nih.gov/articles/PMC9694013/ (Sensors 22(22):8991) - CONFIRMED.
- Measured: roll angle of a bicycle. IMU (3-axis accel and gyro), ABS-unit longitudinal velocity, wheel speed, MCU; reference = two laser ToF distance sensors either side of the rear wheel (roll = atan(height difference / spacing)).
- Algorithm: time update integrates roll rate; update 1 estimates yaw rate from acceleration magnitude (sigmoid restores turn direction); update 2 estimates roll from lateral and vertical acceleration using the steady-turn relation with velocity and yaw rate. Noise covariances tuned by trial and error.
- Accuracy (RMSE, units not stated, presumably deg): steady turning 0.57 vs 2.65 for the simple filter; double lane change 1.38 vs 5.43; sine turning 1.16 vs 1.51.
- Open: none (data and code "Not applicable").
- Reuse: the centripetal correction is the key idea (accelerometer roll is wrong in a turn unless corrected by v times yaw rate). For skis, speed from GPS/phone would be needed (INFERRED). Single rider, single bike: weak evidence.

### 2.3 Sanjurjo, Naya, Cuadrado, Schwab 2019 - bicycle roll from wheel speed and 3 gyros
- URL: https://research.tudelft.nl/en/publications/roll-angle-estimator-based-on-angular-rate-measurements-for-bicyc (Vehicle System Dynamics 57(11)) - CONFIRMED (abstract page).
- Kalman filter using wheel speed and three low-cost angular rate sensors only (no accelerometer); microcontroller; compared with optical sensor measurements, good correlation. Numbers not obtained.
- Reuse: roll can be reconstructed from gyro rates plus speed via kinematics (roll rate, yaw rate, steer relation) without relying on the corrupted accelerometer (INFERRED applicability).

### 2.4 Mountain bike drift assessment (Hanegraaf, Ferlinghetti, Lancini, Brescia)
- URL: https://iris.unibs.it/handle/11379/645929 (PDF: https://iris.unibs.it/retrieve/cf7aa812-df38-45e9-b47e-f479bbccc49c/Final_Mountain_Bike_IMU_Assessment.pdf) - REPORTED (both URLs returned HTTP 403 to my fetches; content below is from a search summary).
- Rider pelvis and sternum IMUs plus bike frame IMU, lean angle through OpenSim; drift quantified by calibration movements at start and end of a run, RMS around the residual (non-leaning) axes: rider 4.2 deg, bike 3.0 deg; Monte Carlo mean SD 0.30 deg (0.60 deg at 95%); run-to-run difference within athlete averaged 8.9 deg.
- Reuse: "bracket every run with a calibration movement and measure the difference" is a cheap drift metric. The Monte Carlo of gyro noise is a way to publish uncertainty per turn.

### 2.5 Other motorcycle items
- Balzer82 "Kalman Filter Bike Lean Angle" notebook: https://nbviewer.org/github/balzer82/Kalman/blob/master/Kalman-Filter-Bike-Lean-Angle.ipynb - REPORTED (appeared in search; not opened; licence unchecked).
- Romualdi et al., FME Transactions 2020 (EKF roll with IMU) and Bologna/Padova theses (https://amslaurea.unibo.it/id/eprint/24147/1/Master_Thesis.pdf ; https://thesis.unipd.it/handle/20.500.12608/28847) - REPORTED, not read; code release unknown.
- Review of smartphone-based roll with GPS model (2025): https://pmc.ncbi.nlm.nih.gov/articles/PMC12656265 - REPORTED.

---

## 3. Surfing and skateboarding

### 3.1 Gomes et al. 2019 - surf session profiling with a smartphone (Fraunhofer AICOS)
- URL: https://pmc.ncbi.nlm.nih.gov/articles/PMC6679232 (Sensors 19(14):3138) - CONFIRMED.
- Measured: surf session events (paddling, waves, etc.; 7 event types). Phone in waterproof case between shoulder blades under the wetsuit (placement chosen from interviews with 15 surfers). IMU 100 Hz (some sessions 50 Hz), GPS 1 Hz.
- Algorithm: rule/threshold-based. Madgwick orientation filter gives linear acceleration; 1 s windows with 50% overlap; take-off requires lying stance then fast speed increase then sustained speed; post-processing drops waves under 3 s.
- Validation: beach video annotated at frame precision in Kinovea, cross-checked against real-time button presses by a third person in a companion app. 327 annotated waves: precision 97.47%, recall 94.19%; wave detection at 1 s precision 90.3%; all seven events 88.1%.
- Open: not stated.
- Reuse: the **dual ground truth** (video plus live button presses) is cheap and good for slope sessions (a spotter taps on each turn); Kinovea is a free frame-accurate annotation tool (INFERRED suitability).

### 3.2 Other surf work
- Activity recognition in surfing, HMM vs SVM (91.4% vs 83.4%, 5-fold CV): https://lida.sport-iat.de/svd/Record/4039830 - REPORTED (search summary).
- Smartfin (UCSD fin-mounted IMU): thesis https://kastner.ucsd.edu/wp-content/uploads/2020/06/admin/CSE-199H_-Final-Undergraduate-Thesis.pdf - REPORTED, student thesis, weak evidence.
- A Springer chapter says manoeuvre detection has few validated studies (https://link.springer.com/chapter/10.1007/978-3-030-49186-4_22) - REPORTED.
- Instrumented surfboard (de Bona et al.): https://repositorio.inesctec.pt/handle/123456789/6527 - REPORTED; 3.8 to 5% of session time is actual wave riding.

### 3.3 Groh et al. - skateboard tricks
- URL: https://www5.informatik.uni-erlangen.de/Forschung/Publikationen/2015/Groh15-ITC.pdf (2015, KDD workshop "IMU-based trick classification in skateboarding") - CONFIRMED (PDF text read).
- Measured: trick events (6 tricks, 7 skateboarders). One IMU (miPod, +/-16 g, +/-2000 deg/s, 200 Hz) glued behind the front axle; sensor-board axes defined by the board's longitudinal/lateral/vertical axes. A 25 fps camera recorded the sessions.
- Algorithm: landing-impact based event detection windows, 54 features per sensor type, classifiers (Naive Bayes, SVM, kNN, partial decision tree) via the Embedded Classification Software Toolbox.
- Validation: detected intervals compared against manually labelled video events: 323 of 343 detected, sensitivity 94.2%, specificity 99.9%; best classification accuracy 97.8% (Naive Bayes and SVM).
- Journal extension (Pervasive and Mobile Computing 2017, vol. 40, 42-55): recall 96.4%, accuracy 89.1%, board-orientation visualisation error 2.2 +/- 1.9 deg - REPORTED (https://cris.fau.de/publications/123693944).
- Open: no dataset release found.
- Reuse: "detect candidate events first, classify second" avoids running a classifier on every sample; evaluation by event matching to video (hit/miss/false alarm) is a template.
- Also: PeerJ CS 2021 transfer-learning skateboard classifier (https://peerj.com/articles/cs-680/) REPORTED only (fetch 403): five tricks, six amateurs, CWT image inputs, claimed 100% test accuracy; treat the 100% as a small-sample result.

---

## 4. Running and gait toolboxes

### 4.1 gaitmap (MaD Lab, FAU) - **primary reuse candidate**
- Paper: Kuederle et al., IEEE OJEMB 2024;5:163-172, https://pmc.ncbi.nlm.nih.gov/articles/PMC10939318 - CONFIRMED. Code https://github.com/mad-lab-fau/gaitmap - CONFIRMED (README read).
- Licence: gaitmap MIT; gaitmap_mad AGPL-3.0 (CONFIRMED from README). Check which algorithms live in `_mad` before depending on them. Article is CC BY 4.0.
- Measured: foot-worn IMU gait. Datasets: two lateral shoe sensors (3 datasets), two instep sensors (SensorPositionComparison2019). Example data at 204.8 Hz. Reported: 20+ algorithms from 17+ publications, 7 datasets, 8 benchmark challenges.
- Algorithms (CONFIRMED, Table I in the paper): `align_dataset_to_gravity`, `align_heading_of_sensors` (PCA on gyroscope plus acceleration integration, described as unpublished); gait detection (Ullrich harmonics); stride segmentation `BarthDtw`, `ConstrainedBarthDtw`, `HmmStrideSegmentation`; event detection (Rampp, Herzer); ZUPT detectors (ARED, norm, SHOE); orientation/trajectory (RtsKalman, MadwickRtsKalman, forward-backward integration, piecewise-linear de-drifted integration); temporal and spatial parameters.
- Validation: stride start/end hand-labelled; a stride counts as detected if both labels are within 30 ms; stride-segmentation median F1 above 90% after optimisation; HMM degrades from 4x10 m walks to free walking; spatial parameters against mocap, geriatric patients on a GAITRite carpet (CONFIRMED).
- Reuse (high): (a) DTW stride segmentation with a template translates to "turn template" segmentation on the roll-axis gyro, and the constrained variant helps against false turns; (b) gravity+heading alignment functions as a reference implementation; (c) piecewise-linear de-drifting between known zero-velocity points; (d) the `tpcp` validation approach (cross-validated parameter optimisation, leave-participant-out) and the 30 ms tolerance style of event scoring; (e) benchmark-challenge packaging so alternative detectors can be compared on the same data. Caveat: designed for foot sensors; a cuff sensor lacks foot flat stance ZUPTs (INFERRED: ski boots have flat-ski phases only when stationary).
- Docs: https://gaitmap.readthedocs.io (alignment page returned only short summaries; details not CONFIRMED).

### 4.2 OpenSense / OpenSim IMU tools
- Paper: Al Borno et al., J NeuroEng Rehabil 2022, https://pmc.ncbi.nlm.nih.gov/articles/PMC8859896 - CONFIRMED. Docs (IMU Placer): https://opensimconfluence.atlassian.net/wiki/spaces/OpenSim/pages/53086387 - CONFIRMED via search summary only, REPORTED.
- Measured: lower-extremity joint kinematics over 10 min walking plus movement circuits, 11 subjects. 7 Xsens MTw Awinda IMUs (T2, L5, thighs, shanks, feet), 100 Hz (40 Hz for two subjects).
- Algorithm: sensor fusion (Mahony or Madgwick complementary filters, Madgwick beta 0.1) then OpenSim IK minimising weighted squared orientation errors on a 22-segment, 43-DOF model; distal IMUs down-weighted; IMUs with orientation differences above 45 deg or excessive variability in the first 10 s excluded. Sensor-to-segment calibration is by a 5 s static pose (facing forward, standing) with the model in its default pose, giving fixed offsets (IMU Placer, with optional heading correction of a base IMU).
- Validation: 8-camera optical mocap at 100 Hz. Median RMS differences 3 to 6 deg for most joints, hip rotation about 12 deg, r = 0.60 to 0.87, drift slopes -0.14 to 0.17 deg/min.
- Open: Apache 2.0; validation dataset at simtk.org/projects/opensense_val (doi 10.18735/2f9k-ca43) - all CONFIRMED from the paper.
- Reuse: (a) the pre-screening rule (reject sensor if orientation implausible or too variable in a calibration window) as a mount-quality gate; (b) a published error budget for static-pose calibration; (c) drift slope in deg/min as a standard way to report drift; (d) honest limitation: the authors note their placement procedure used mocap to pose the model, "unrealistic" for natural environments (CONFIRMED).

### 4.3 GaitPy and scikit-digital-health (lumbar accelerometer)
- GaitPy: https://joss.theoj.org/papers/10.21105/joss.01778 and https://gaitpy.readthedocs.io - REPORTED (search summaries). Single lower-back accelerometer, vertical acceleration, continuous-wavelet event detection, ML bout classification, minimum 50 Hz; project no longer maintained, points to scikit-digital-health.
- scikit-digital-health (skdh): https://scikit-digital-health.readthedocs.io/en/latest/ref/gait.html ; paper https://mhealth.jmir.org/2022/4/e36762 - REPORTED. MIT per the JMIR paper summary (LICENSE file not opened). `GaitLumbar`: bout detection by gradient-boosted trees, inverted-pendulum model, CWT heel-strike/toe-off, turn handling with gyro, default 20 Hz filter and 8 s minimum bout.
- Reuse: bout detection (is the person actually skiing vs. standing/lifting) is a prerequisite for any free-living feedback (INFERRED); the CWT event detector is a template for locating turn switches by wavelet scale rather than fixed low-pass.

### 4.4 Madgwick-style filters (open code)
- Fusion (xioTechnologies): https://github.com/xioTechnologies/Fusion - CONFIRMED (README read). MIT. Complementary-filter AHRS with startup gain ramp (3 s), acceleration rejection with recovery mode, magnetic rejection, overrange restart at 98% of gyro range, and run-time gyro bias estimation by stationary detection. Python package `imufusion` is mentioned in the README. 
- Reuse: acceleration rejection, overrange recovery and bias estimation address exactly the failure Hummel 2023 reported on ice/bumps (CONFIRMED that both exist; applicability INFERRED).
- Tutorial: Kok, Hol, Schoen, "Using Inertial Sensors for Position and Orientation Estimation," https://arxiv.org/pdf/1704.06053 - REPORTED (search only). Useful for the calibration, bias and filter design background.

### 4.5 Sensor-to-segment calibration literature (see also section 6)
- Seel, Raisch, Schauer 2014, "IMU-based joint angle measurement for gait analysis," Sensors 14(4):6891, https://pmc.ncbi.nlm.nih.gov/articles/PMC4029684 - CONFIRMED.
- Olsson, Seel, Lehmann, Halvorsen, "Joint axis estimation for fast and slow movements using weighted gyroscope and acceleration constraints," https://arxiv.org/abs/1903.07353 - CONFIRMED (abstract). Tested on nine motions of an upper-limb exoskeleton, accurate axis after about 5 s; no code link on the page.
- Comparison of functional vs static calibration in sports tasks (Sensors 2023, https://www.mdpi.com/1424-8220/23/18/7987) - REPORTED via search snippet (fetch 403): ROM mean differences from optical mocap from under 0.1 to 24.1 deg depending on joint and task. Lower-limb functional-calibration comparison (squat gave worst accuracy; others under 3.6 deg RMSE): https://pmc.ncbi.nlm.nih.gov/articles/PMC7039222 - REPORTED.

---

## 5. Golf, cricket, baseball, rowing/kayak

### 5.1 Golf - Kim and Park 2024, Scientific Reports (KAIST)
- URL: https://pmc.ncbi.nlm.nih.gov/articles/PMC11035581 - CONFIRMED (first ~100k chars read).
- Measured: swing events (address, backswing top, impact, finish), wrist trajectory. One wrist IMU (Bosch BMA456 + BMG250, +/-16 g, +/-2000 deg/s, 200 Hz, 20 Hz 10th-order Butterworth).
- Algorithm: BLSTM segmentation; orientation and velocity drift correction using segmented events (reduced orientation error to 7.6 +/- 3.1 deg at address; velocity error 0.76 to 0.42 m/s; trajectory error 0.39 to 0.21 m; full pipeline wrist trajectory 0.168 +/- 0.054 m, 82% below IMU-only baseline).
- Validation: optical mocap at 200 Hz; events defined from club-head motion (address = local velocity minimum before backswing; impact = club head crossing ball y position). Mean event error 38 +/- 19 ms (address and finish 61 to 69 ms, top and impact 9 to 15 ms). 20 golfers, 389 of 400 swings used.
- Open: data on reasonable request only; no code statement seen.
- Reuse: **event-anchored drift correction** (reset orientation/velocity at detected quasi-static events) - for us, reset roll at detected turn switches or at standing phases (INFERRED). Event definitions anchored to a physical quantity in the reference system. Event-timing error should be reported per event type, as here.
- Other golf sources: heuristic threshold methods and club-mounted impact detection are described in search summaries (https://www.iaeng.org/publication/IMECS2017/IMECS2017_pp765-769.pdf, REPORTED). No open golf IMU dataset found.

### 5.2 Cricket and baseball
- Wixted, Portus, Spratford, James 2011, Sports Technology 4(3-4):134-140, "Detection of throwing in cricket using wearable sensors": https://research-repository.griffith.edu.au/items/3283c927-38ac-5f1c-88ed-2ab318d996b4 - REPORTED. Upper/lower-arm inertial sensors distinguishing legal bowling from throwing; expert assessment with high-speed video and mocap.
- Baseball pitching: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6348088/ (wearable IMU vs marker-based mocap, 10 pitchers) - REPORTED; numbers not read.
- No swing-detection code or open data found. Reuse limited to the validation pattern (IMU vs high-speed video/mocap with an expert-labelled gold standard).

### 5.3 Rowing and kayak
- Nagy et al. 2025 (icSPORTS), kayak stroke segmentation with IMU+EMG: https://www.scitepress.org/PublishedPapers/2025/137812 - CONFIRMED (abstract page): GBDT 96.18% test accuracy, mean absolute onset error 24.6 +/- 51.6 ms (about 4.5% of stroke duration), per-subject accuracy 91.89% to 98.9%. Sampling rate, placement detail and reference method not stated in the fetched content. No code/data statement.
- Groh et al. 2014 (subsequence DTW on boat acceleration for stroke segmentation): https://www5.informatik.uni-erlangen.de/Forschung/Publikationen/2014/Groh14-MPI.pdf - REPORTED (search only).
- Para-rowing ISBS 2021 (forearm/upper-arm IMU stroke events about 29 ms error vs trunk 57 ms, single athlete): https://commons.nmu.edu/isbs/vol39/iss1/23 - REPORTED.
- Reuse: subsequence-DTW on a cyclic signal (same as gaitmap BarthDtw); "report onset error as % of cycle duration" gives a scale-free metric that compares short and long turns (INFERRED).

---

## 6. Handling unknown or variable sensor mounting orientation

| Technique | What it needs | Source and status | Fit for boot cuff |
|---|---|---|---|
| Gravity alignment in quiet window | any still period | gaitmap `align_dataset_to_gravity` (REPORTED docs); Stanglmayr step 1 (CONFIRMED) | Good: stand before first run; yields pitch and roll offsets only |
| Median over data-selected "steady straight" segments | speed or acceleration gate, rotation-rate gate | Stanglmayr 2020, thresholds v>30 km/h, a<0.3 m/s2 (CONFIRMED) | Good if GNSS speed is available; skiing traverse/straight-run gate (INFERRED) |
| Yaw from known-direction acceleration (braking) | GPS speed derivative | Stanglmayr step 3 (CONFIRMED) | Hard on skis: braking is not along the boot axis; use symmetric-turn assumption instead (INFERRED) |
| PCA of horizontal linear acceleration, plus 180-deg disambiguation | gravity, walking/forward motion, 3 s window | Humaine, arXiv 1411.2156 (CONFIRMED text: max-variance direction, PCA window 3 s, ambiguity resolved with a phone-orientation hint, median error 15 deg across placements; 50 Hz) | Partly: cuff accelerations in a turn are lateral, so max-variance is not heading; use as a sanity check (INFERRED) |
| Heading alignment of rigid sensors by PCA of gyro + integrated accel | multiple sensors on one rigid body | gaitmap `align_heading_of_sensors` (CONFIRMED in Table I; algorithm "unpublished") | Weak: needs two sensors on one body |
| Hinge-joint axis from gyro norm constraint | 10 s of non-degenerate joint motion; two sensors on either side of the joint | Seel 2014 (CONFIRMED): equal projected rates, Gauss-Newton, spherical parametrisation, 4 sign minima; knee RMSE about 0.7 deg prosthesis, about 3.3 deg human; 60 Hz | Plausible: shank/cuff on one side, boot shell or foot on the other; ankle flexion is near-hinge (INFERRED) |
| Weighted gyro+accel joint axis (fast and slow) | 5 s of motion | Olsson/Seel 2019 (CONFIRMED abstract; exoskeleton) | Same as above, better at slow movement |
| Static pose (N-pose/T-pose) | subject stands in known pose; model default pose | OpenSense IMU Placer (CONFIRMED paper; 5 s static pose) | Easy to do at the lift or start; requires a pose that actually matches the model (REPORTED: forum users confused by heading/axis settings, https://simtk.org/plugins/phpBB/viewtopic.php?p=42578) |
| Orientation-invariant features | none | patents/papers such as https://patents.justia.com/patent/20180031605 (REPORTED, gravity-aligned and rotation-invariant about vertical) | Cheap fallback: use gyro-norm, gravity-axis acceleration, rotation-about-gravity features |
| Mount-quality gate | calibration window | OpenSense pre-screen: orientation difference above 45 deg or range above 30 deg in 60 ms bins (CONFIRMED) | Useful to flag a loose or misplaced strap |

Honest caveats: (i) no source above validated any of these on ski boots; (ii) static-pose calibration is quickly invalidated if the sensor shifts on soft tissue (REPORTED in search summary of a calibration review, no URL read); (iii) functional methods are accuracy-dependent on the calibration motion (squat worst in https://pmc.ncbi.nlm.nih.gov/articles/PMC7039222, REPORTED).

Suggested OpenSki recipe (INFERRED, not from a single source): 1) gravity from first stand-still; 2) short ankle/knee-bend functional calibration using Seel/Olsson constraints to find the cuff flexion axis; 3) roll/pitch refinement using the median of "straight steady" glide segments; 4) check for left/right symmetry across turns as a residual yaw/roll offset diagnostic; 5) mount-quality gate.

---

## 7. How others validate against video or optical ground truth

| Study | Reference | Event definition | Tolerance / metric | Status |
|---|---|---|---|---|
| Martinez 2019 (boot IMU, ski ergometer) | 50 Hz video, 3 experts, averaged | turn switch marked by raters | inter-rater max precision 51.4 ms; method precision 6 to 12 ms (adjusted) / 0.056 s (abstract) | CONFIRMED, https://pmc.ncbi.nlm.nih.gov/articles/PMC6413051 |
| Martinez 2021 (27 methods) | 13-camera mocap, 100 Hz | Supej: CoM projection crossing mean ski position | accuracy within 0.25 s (10 within 0.05 s), precision 0.10-0.60 s | CONFIRMED |
| Gomes 2019 surf | beach video, Kinovea frame precision + live app button presses | wave start/end etc. | precision, recall over 327 waves | CONFIRMED |
| Groh 2015 skate | 25 fps video, manual labels | trick events | sensitivity, specificity on 343 events | CONFIRMED |
| Seel 2014 gait | 120 Hz optical, markers on anatomical landmarks not on IMUs | joint angle | RMSE per joint | CONFIRMED |
| OpenSense 2022 | 8-camera mocap 100 Hz | joint angle | RMS difference, Pearson r, drift slope by minute | CONFIRMED |
| gaitmap benchmarks | hand-labelled stride start/end, GAITRite, mocap | stride, IC/TC | 30 ms tolerance, F1, MAE | CONFIRMED |
| Kim and Park 2024 golf | 200 Hz mocap, club-head kinematics | address/top/impact/finish | ms error per event | CONFIRMED |
| Stanglmayr 2020 motorcycle | ADMA high-precision IMU/GNSS | roll angle trace | RMSE about 2 deg, max deviation 3 to 9 deg | CONFIRMED |
| Chuang 2022 bicycle | 2 laser ToF distance sensors | roll angle | RMSE per manoeuvre | CONFIRMED |

Design lessons (mix of CONFIRMED observations and INFERRED advice):
- Use multiple raters and report inter-rater precision as the floor for algorithm accuracy (Martinez 2019). The golf paper notes mocap sampling may be too coarse for impact timing (REPORTED via search); the same caveat applies to a 25-30 fps phone video for turn switch (INFERRED).
- Define events physically (Supej) and apply the same definition to the video labels.
- Time synchronisation is the dominant error: Stanglmayr found time lag the largest deviation source; Groh's miPod RTC sync accuracy was 150 ms (CONFIRMED). Use a clap/stomp or on-screen LED sync marker (INFERRED).
- Report both bias and limits of agreement for event times and per-event-type errors (golf, kayak).
- Use leave-participant-out splits (gaitmap/tpcp; kayak shows subject-level accuracy variation 91.9 to 98.9%).
- Report drift in deg/min and per-run bracket calibration (OpenSense, mountain bike).

---

## 8. Human activity recognition datasets

All CONFIRMED from UCI pages unless stated.

| Dataset | Subjects | Sensors, rate, placement | Licence | URL |
|---|---|---|---|---|
| PAMAP2 (Reiss and Stricker, ISWC 2012) | 9, 18 activities (12 protocol) | 3 Colibri IMUs 100 Hz at dominant wrist, chest, dominant ankle; HR monitor ~9 Hz; 3,850,505 instances | CC BY 4.0 | https://archive.ics.uci.edu/dataset/231/pamap2+physical+activity+monitoring |
| UCI HAR (Reyes-Ortiz, Anguita et al. 2013) | 30, 6 activities | Samsung Galaxy S II accel + gyro, 50 Hz, waist | CC BY 4.0 | https://archive.ics.uci.edu/dataset/240/human+activity+recognition+using+smartphones |
| WISDM smartphone and smartwatch (Weiss 2019) | 51, 18 activities, 3 min each | phone and watch accel + gyro, 20 Hz; placement not on page | CC BY 4.0 | https://archive.ics.uci.edu/dataset/507 |

Relevance: low for ski technique itself (daily-living and gym activities, no lean/turn labels, 20 to 50 Hz, wrist/waist/ankle not cuff). Useful for (i) generic pre-training or "is this skiing vs. walking/lift/standing" bout classification, (ii) testing orientation-invariant feature code across placements (PAMAP2 has ankle IMU) and (iii) benchmarking leave-subject-out evaluation. Orientation in these sets is uncontrolled in the phone ones (INFERRED).

---

## 9. Not found / unverified

- Open snowboard turn-detection or edge-angle code or dataset: none found. Holleczek snowboard turn recognition paper itself not located.
- Open surf manoeuvre (turn/cutback) detection code: none found; Smartfin material is thesis/project level (REPORTED).
- Open golf, cricket or baseball swing IMU datasets with event labels: none found (Kim and Park data on request).
- Skateboard trick datasets: no public release found in the papers read (data-availability statement in the PeerJ article not verified; fetch 403).
- Mountain-bike and the "static vs functional in sports" (Sensors 2023) papers: only seen via search summaries; fetches returned HTTP 403.
- Kok/Hol/Schoen tutorial, GaitPy, skdh licence file, Balzer82 notebook licence: not opened.
- Humaine accuracy figure: the PDF text lost its degree symbol; "median accuracy of 15" read with units assumed degrees from context.

---

## 10. Ranked reuse list

| Rank | Item | What to reuse | Licence/openness | Evidence |
|---|---|---|---|---|
| 1 | Stanglmayr 2020 smartphone motorcycle roll (IFZ/TU Dresden) | Three-step unattended mount alignment (gravity from steady data, median aggregation, yaw from a known-direction manoeuvre); frequency-separation Kalman roll estimate; error budget | paper only | CONFIRMED |
| 2 | gaitmap (+ gaitmap-datasets, gaitmap-bench, tpcp) | DTW/HMM segmentation with templates, ZUPT and de-drifted integration, tolerance-based event scoring, benchmark packaging, validation harness | MIT core, AGPL-3.0 `_mad` | CONFIRMED |
| 3 | Seel 2014 and Olsson/Seel 2019 | Functional hinge-axis calibration from gyro (and accel) constraints, heading-free complementary-filter joint angle; ~10 s calibration | papers; no code link seen | CONFIRMED |
| 4 | OpenSense (Al Borno 2022) + OpenSim IMU Placer | Calibration-pose workflow, mount-quality pre-screen, drift-slope reporting, public validation dataset (simtk opensense_val) | Apache 2.0 | CONFIRMED |
| 5 | Martinez 2021 comparison and Supej reference | Physically defined turn-switch reference; competing detector definitions for benchmarking | data on request | CONFIRMED |
| 6 | Fusion / imufusion (xioTechnologies) | Acceleration rejection, overrange recovery, gyro bias estimation (answer to Hummel 2023 saturation drift) | MIT | CONFIRMED |
| 7 | Gomes 2019 surf profiling | Dual ground truth (video plus live button presses), Madgwick-based rule detection pipeline | not stated | CONFIRMED |
| 8 | Kim and Park 2024 golf | Event-anchored drift correction; per-event timing error reporting | data on request | CONFIRMED |
| 9 | Humaine (arXiv 1411.2156) | PCA heading with ambiguity resolution as a diagnostic for forward direction | paper | CONFIRMED |
| 10 | Chuang 2022 bicycle roll | Centripetal-corrected accelerometer roll update | none | CONFIRMED |
| 11 | Groh 2015 skateboard | Event candidate detection then classification; video-matched sensitivity scoring | none seen | CONFIRMED |
| 12 | Mountain-bike drift (Brescia) | Start/end calibration movement as a per-run drift metric; Monte Carlo uncertainty | not known | REPORTED |
| 13 | PAMAP2 / UCI HAR / WISDM | Generic bout classification pre-training and orientation-invariance tests | CC BY 4.0 | CONFIRMED |
| 14 | skdh / GaitPy | CWT event detection, gait-bout detection | MIT (skdh, REPORTED) | REPORTED |

Reading of the ranking: items 1 to 4 each address one of the three OpenSki risks (mount variability, event detection, drift/validation) with the most transferable, best-documented recipes; none was validated on ski boots, so each needs a short pilot on our own data.
