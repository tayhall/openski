# 04 - Bench calibration and honest accuracy measurement for a low-cost IMU (MPU6050 class)

Date: 2026-10-11. Scope: practical, hobbyist-level procedures (ESP32/Arduino + bench + protractor + phone) for characterising, calibrating and honestly reporting the accuracy of the OpenSki boot IMU.

Label key: **CONFIRMED** = I read it in the cited source in this session. **REPORTED** = secondary source, forum post or search-result summary, not verified against a primary. **INFERRED** = my own arithmetic/engineering reasoning, not stated by a source. Where I could not obtain a source I say so. Nothing below was run on hardware.

---

## 0. Executive summary (what matters most for a ski boot)

1. The MPU6050 datasheet allows a **gyro zero-rate offset of +/-20 deg/s at 25 C and another +/-20 deg/s over -40..+85 C** [CONFIRMED, datasheet rev 3.1, https://cdn.sparkfun.com/datasheets/Components/General%20IC/PS-MPU-6000A.pdf]. A 20 deg/s bias would wreck an integrated angle within a second, so a per-power-on static gyro zero is mandatory and bias must be re-estimated or temperature-compensated.
2. Measured noise of real MPU6050 parts is small compared to bias: ARW around 0.003-0.010 deg/sqrt(s) and bias instability around 0.001-0.0025 deg/s at ~22 C (Section 6). Over a 60 s ski run white noise contributes well under 0.1 deg of angle error [INFERRED: 0.0095 * sqrt(60) = 0.07 deg]; **the real error terms are residual bias after warm-up/temperature change, scale factor (+/-3 % spec), axis misalignment (+/-2 %), g-sensitivity (0.1 deg/s per g) and mounting**.
3. A short Allan-variance run (1 h) plus a warm-up/temperature log (fridge-to-room, ~1-2 h) plus a six-position accelerometer test (~20 min) plus a 90-degree-step gyro scale test (~20 min) fits in a weekend and tells you more than any datasheet.
4. Report error against a reference whose own uncertainty you have estimated; split calibration data from validation data; give bias, RMSE, worst case, and drift in deg/min (Section 5).

---

## 1. Noise and stability characterisation with Allan variance

### 1.1 What the numbers mean (and where to read them)

| Quantity | Plot signature | How to read | Units | Source |
|---|---|---|---|---|
| Angle random walk (ARW) / white rate noise N | slope -1/2 | line fit, read value at tau = 1 s | (deg/s)/sqrt(Hz) = deg/sqrt(s) | CONFIRMED https://ch.mathworks.com/help/nav/ug/inertial-sensor-noise-analysis-using-allan-variance.html ; https://github.com/ethz-asl/kalibr/wiki/IMU-Noise-Model ; Woodman https://www.cl.cam.ac.uk/techreports/UCAM-CL-TR-696.pdf |
| Bias instability B | flat minimum (slope 0) | B = minimum ADEV / 0.664 (IEEE-952 convention) | deg/s (or deg/h) | CONFIRMED MathWorks page above; https://docs.inertialsense.com/user-manual/imx/performance/allan_deviation/ states the same /0.664. Note Woodman reads the minimum value directly without dividing by 0.664 - be explicit which convention you use. |
| Rate random walk K | slope +1/2 | line fit, read at tau = 3 s | (deg/s)*sqrt(Hz) | CONFIRMED MathWorks and Kalibr pages above |

Unit gotcha [INFERRED]: (deg/s)/sqrt(Hz) is numerically identical to deg/sqrt(s). Multiply by 60 to get deg/sqrt(hr). Datasheet "rate noise spectral density" and Allan ARW are the same quantity in principle, but the datasheet value is quoted "at 10 Hz" and conditions are not fully defined; a Pololu forum poster asked exactly this and got no definitive answer (REPORTED, https://forum.pololu.com/t/mpu6050-evaluation-rate-noise-density-rms-noise/8721).

Kalibr's guidance [CONFIRMED, Kalibr wiki above]: static Allan parameters ignore scale-factor error and temperature effects so are optimistic; "for low-cost MEMS IMUs" inflate them by 10x or more when feeding an estimator. White-noise scaling to discrete time assumes an ideal low-pass/decimation, not plain subsampling.

### 1.2 What to record and for how long

- Woodman: need enough data for **at least 9 bins** at the largest averaging time tau, otherwise results lose significance [CONFIRMED, https://www.cl.cam.ac.uk/techreports/UCAM-CL-TR-696.pdf s5.1]. So max usable tau ~ T/9 (a stricter overlapping-estimator limit m < (L-1)/2 is in the MathWorks example).
- Durations used by tools/papers [CONFIRMED]: imu_utils sample config 120 min (https://github.com/gaowenliang/imu_utils); allan_variance_ros "at least 3 hours", damped surface (https://github.com/ori-drs/allan_variance_ros); MathWorks example 6 h at 100 Hz; Woodman 12 h at 100 Hz; Rudyk et al. MPU-6050 1 h at 70 Hz (https://pmc.ncbi.nlm.nih.gov/articles/PMC7506677/).
- Practical rule [INFERRED from the above]: **1 h gives ARW well and a rough bias-instability minimum (tau_max ~ 400 s); 2-3 h overnight is better.** Rudyk et al. found the MPU-6050 minimum between about 20 s and 150 s [CONFIRMED], so 1 h is sufficient to see it. For ski use, tau of 1-120 s is what matters (one run); you do not need 12 h.
- Conditions: sensor stationary, firmly fixed on a heavy/damped surface, away from fans, door slams, phone vibration; constant room temperature (Rudyk: 22 +/- 3 C) [CONFIRMED]. Let the board warm up 10-30 min before the timed record so the die temperature transient does not enter (the 5-10 min figure in an Ameba example is a rule of thumb only: REPORTED https://ameba-doc-arduino-sdk.readthedocs-hosted.com/en/latest/ameba_pro2/amb82-mini/Example_Guides/I2C/MPU6050%20IMU%20Zero.html ; I would use 20-30 min and verify with the die-temperature log).
- Record raw int16 counts + die temperature + a monotonic timestamp. Use a fixed rate (100-200 Hz is plenty; sample rate = gyro output rate / (1 + SMPLRT_DIV), gyro output 1 kHz with DLPF on, 8 kHz off [CONFIRMED, register map https://cdn.sparkfun.com/datasheets/Sensors/Accelerometers/RM-MPU-6000A.pdf]). Log the DLPF and full-scale settings with the data - the noise you measure depends on them.
- Check for missing samples: count dropped/duplicated frames; I2C on an ESP32 with Wi-Fi/BLE logging can jitter. Allan analysis assumes uniform sampling [INFERRED].

### 1.3 Tools

| Tool | What it is | Notes | Source |
|---|---|---|---|
| allantools (Python) | Library: `oadev` (overlapping ADEV, "first choice"), `adev`, `mdev`, `tdev`, `hdev`, `totdev` ... noise generators; `pip install allantools`; LGPL-3.0 | General time/frequency tool, no IMU-specific guidance; you read N, B, K off the plot yourself. Call as `allantools.oadev(data, rate=fs, data_type="freq", taus="octave")` [INFERRED from memory of the API, not read this session - check docs] | CONFIRMED https://github.com/aewallin/allantools |
| imu_utils | ROS1 C++ package, Matlab for plots; outputs per-axis `gyr_n`, `gyr_w`, `acc_n`, `acc_w` (white noise at tau=1, bias instability near minimum); needs ROS workspace + libdw-dev; 2 h record | Heavy for non-ROS users; can play a rosbag | CONFIRMED https://github.com/gaowenliang/imu_utils |
| allan_variance_ros | ROS bag in, ARW/BI/RRW per axis for gyro and accel; writes allan_variance.csv + Kalibr-style imu.yaml; Ubuntu 20.04/devcontainer; >= 3 h | Best if you already have rosbags | CONFIRMED https://github.com/ori-drs/allan_variance_ros |
| Kalibr | Camera-IMU calibration; its wiki defines the IMU noise model and how Allan values map to its `noise_density` / `random_walk` inputs | Not an Allan tool itself; use for the model/units | CONFIRMED https://github.com/ethz-asl/kalibr/wiki/IMU-Noise-Model |
| MATLAB `allanvar` example | Reference implementation with explicit formulas | Needs MATLAB | CONFIRMED MathWorks page above |
| Roll your own | Woodman's 3-step recipe (bin, average, 1/(2(n-1)) * sum of squared successive differences) is ~15 lines of numpy | Fine for hobby use; validate against allantools on synthetic white noise | CONFIRMED Woodman s5.1 |

For hobbyists without ROS: log CSV from the ESP32 over serial, run allantools in Python [INFERRED recommendation].

### 1.4 Reading your plot - checklist [INFERRED, standard practice]

1. Left side should be a clean -1/2 slope. If you see -1, that is quantisation (low-resolution/low full-scale or heavy filtering). If the slope is shallower than -1/2, suspect filtering/oversampling artefacts or aliasing.
2. A bump or sinusoidal ripple means a periodic disturbance (fan, heater cycling, temperature cycling of the room, ESP32 power-supply noise).
3. A +1/2 or steeper rise at long tau is rate random walk or **temperature drift** - in an uncontrolled room these are indistinguishable; log temperature and look for correlation.
4. Do each axis and each unit separately; Rudyk et al. found X and Z about twice as bad as Y in bias instability on their samples [CONFIRMED].
5. Quote confidence honestly: at the longest taus there are very few independent clusters, so the estimate there is only good to tens of percent (NIST-type effective-degrees-of-freedom caveat: REPORTED, https://tf.nist.gov/phase/Properties/six.htm - I could not confirm the exact error formula).

### 1.5 Time budget
Setup 15 min, warm-up 30 min, record 1-3 h unattended, analysis 15 min.

---

## 2. Temperature calibration

### 2.1 What the MPU6050 gives you

- On-chip die temperature sensor: 340 LSB/degC sensitivity, offset -521 LSB at 35 degC, linearity +/-1 degC (best-fit line, -40..+85) [CONFIRMED, datasheet rev 3.1 above]. Conversion `T(C) = TEMP_OUT/340 + 36.53` [CONFIRMED, register map above]. Sensor is "untrimmed" so absolute accuracy is poor (a few degC) but **it is excellent as a monotone covariate for compensation, because it tracks the die that the gyro bias actually depends on** [INFERRED]. Do not compare it to a thermometer for absolute truth.
- Spec limits [CONFIRMED, datasheet rev 3.1; table layout garbled in text extraction for the accelerometer rows so double-check against the PDF]: gyro initial ZRO +/-20 deg/s (25 C), ZRO variation -40..85 C +/-20 deg/s, sensitivity tolerance +/-3 %, sensitivity variation over temperature +/-2 %, nonlinearity 0.2 %, cross-axis 2 %, linear-acceleration sensitivity 0.1 deg/s/g; accel: scale tolerance +/-3 %, sensitivity change 0.02 %/degC, nonlinearity 0.5 %, cross-axis 2 %, zero-g initial +/-50 mg (X,Y) / +/-80 mg (Z), zero-g change vs temperature (0..70 C) +/-35 mg (X,Y) / +/-60 mg (Z). Datasheet revision I could fetch is 3.1 (2011); a rev 3.4 exists (REPORTED https://www.radiolocman.com/datasheet/pdf.html?di=102365) - I could not download it, numbers probably identical but not verified.
- Worst-case tilt implied by zero-g tempco [INFERRED]: 35 mg/70 C = 0.5 mg/degC = ~0.03 deg/degC, i.e. about 0.9 deg across a 30 degC shift (warm room to cold slope). Gyro: 20 deg/s over 125 C is at most ~0.16 deg/s/degC if linear (a forum poster did the same arithmetic: REPORTED https://forum.sparkfun.com/viewtopic.php?p=142131 via search summary); real units are typically better but **you must measure your own**.
- Community thermal-chamber logging (-20 to +70 C, eight devices) reported **hysteresis** between heating and cooling and a stepped/discontinuous bias-vs-temperature relationship; unresolved on the thread (REPORTED, search summaries of https://forum.sparkfun.com/viewtopic.php?p=142131 , https://adm.invensense.tdk.com/mpu-6050-thermal-chamber-results-hysteresis and https://adm.invensense.tdk.com/mpu6050-shows-discontinuity-when-temperature-varies ; the InvenSense pages returned 403 to me). Consequence: a single polynomial in T may leave residuals; log both warming and cooling and validate.

### 2.2 What ArduPilot and PX4 do (so you can borrow the method)

- ArduPilot [CONFIRMED, https://ardupilot.org/copter/docs/common-imutempcal.html and source https://github.com/ArduPilot/ardupilot/blob/master/libraries/AP_InertialSensor/AP_InertialSensor_tempcal.cpp]: cold-soak below minimum operating temp, power on warm, **keep the board completely still**, let it self-heat; run completes at TMAX or after a 10-minute timeout with < 0.5 degC rise; minimum span 10 degC, **recommended >= 25 degC, e.g. kitchen freezer to desk**. Fit is a **3rd-order polynomial without constant term** in (T - 35 degC) per axis, for accel and gyro separately; coefficients stored x1e6. Offline check script `Tools/scripts/tempcal_IMU.py` plots corrected vs uncorrected; sudden jumps indicate movement.
- PX4 [CONFIRMED, https://docs.px4.io/main/en/advanced_config/sensor_thermal_calibration.html]: 3rd-order polynomials for accel/gyro/mag (5th for baro); on-board or off-board (log, then `process_sensor_caldata.py`); cold soak in home freezer (~-20 C) in a sealed anti-static bag with silica gel, warm slowly to avoid condensation; do a 6-point accel cal afterwards at a temperature inside the range.

### 2.3 Hobbyist procedure without a thermal chamber (ESP32 + MPU6050)

Safety: freezer-to-room moves can condense water on a bare PCB; seal in a zip bag with silica gel, leave sealed until it reaches room temperature [CONFIRMED practice from PX4 page]. Respect the datasheet range (-40..+85 C) and your board's other parts (regulator, LiPo - keep battery outside the cold box and out of freezer) [INFERRED].

A. Warm-up-only run (30-60 min, easy)
1. Board powered off for >= 1 h in the room, fixed to the bench on a rigid block, no cables pulling on it.
2. Start logging raw gyro, accel, TEMP_OUT at 50-100 Hz, device stationary.
3. Record for 45-60 min. Die self-heats several degC; gyro bias vs T and accel offset vs T appear immediately. This also tells you the **warm-up time** after which bias is stable - an acceptance-test item.

B. Cold-soak run (about 2-3 h, recommended)
1. Seal board (plus short cable, ESP32 outside if possible - or the whole ESP32 board in the bag) in a bag with silica gel. Freezer 2+ h (or fridge ~4 C if no freezer; smaller span, see below).
2. Take it out, place on the rigid block in a warm room, start logging **immediately** (the first minutes carry the largest temperature slope). Do not touch it. Log until the die temp has been flat for 10 min (typically 30-90 min, depends on thermal mass) [INFERRED duration; ArduPilot forum reports ~2 h for in-flight-controller runs, REPORTED https://discuss.ardupilot.org/t/imu-temperature-calibration-using-amc/142208].
3. Optional heating leg: a hair dryer on low from a distance or a desk lamp, slow ramp, again stationary. Do both directions to expose hysteresis [INFERRED, from hysteresis reports].
4. Target span >= 25 degC (ArduPilot recommendation) - fridge-only gives ~15-20 degC span at best, which is below ArduPilot's recommendation but better than nothing; you can only trust the fit **inside the span you measured** [INFERRED].

Fit (Python, offline) [INFERRED, following ArduPilot/PX4]:
1. For each axis, take 1-s averages of raw counts. Subtract the mean over the final stable (warm) segment to get bias(T) relative to the reference temperature.
2. Regress against `dT = T - T_ref` with polynomial order 1, 2 and 3. Choose the lowest order whose residual is near the Allan-noise floor; ArduPilot/PX4 use cubic, which overfits easily with a 15 degC span - cross-validate on a second run or the cooling leg.
3. Alternative: a 64-bin lookup table with linear interpolation, which handles steps/hysteresis shape but needs more coverage [INFERRED].
4. Gyro: the bias must be a smooth function of T only if the temperature rate is slow; large dT/dt (just out of the freezer) gives gradient effects, so also keep a "dT/dt" column and check residuals against it [INFERRED].
5. Accelerometer: do the temperature test with the board in **one fixed orientation** (e.g. Z up), so the offset change is directly the drift of that axis; gravity is constant at that orientation. Rotate to a second orientation (e.g. Z down) for a second run to separate offset from scale drift [INFERRED]. ArduPilot only fits offsets per axis (INS_ACCn_X..Z cubic) [CONFIRMED].
6. Validate on a held-out run: residual gyro bias after compensation, in deg/s, and the integrated angle error in deg over 60 s.

What the on-chip sensor allows [CONFIRMED + INFERRED]: it is the only practical in-situ temperature input; it gives die temperature, not board/ambient, with 1 degC linearity; on a ski boot the sensor will see a fast ambient step (warm car/lodge to snow), so the die lags - compensation by die temperature is fine, but re-zero the gyro on every power-up and again whenever the device is still (zero-velocity update) because compensation will not be perfect [INFERRED].

---

## 3. Static calibration

### 3.1 Accelerometer: six-position and ellipsoid fit

Model: `a_cal = M (a_raw - b)` with M containing scale, misalignment/cross-axis; static samples must have |a| = 1 g [INFERRED standard model; Ferraris-style, see below].

Six-position method (about 20-30 min):
1. Rigid cube/block (or a box with machined flat faces) taped/clamped to the IMU; place on a flat, level table (level it with a spirit level; error of 0.5 deg gives 0.0087 g = 8.7 mg on the in-plane axes at worst, so level to better than ~0.2 deg [INFERRED]).
2. For each of +X, -X, +Y, -Y, +Z, -Z up: wait 10 s for vibration/thermal settling, record 30-60 s at 100 Hz, average.
3. Offsets b_i = (mean_up + mean_down)/2; scale s_i = (mean_up - mean_down)/(2*g); cross-axis from the off-axis readings. Doing the flip pairs makes the result independent of the table's tilt to first order [INFERRED].
4. Repeat at a second temperature if you want a temperature check.

Ellipsoid fit (more orientations, fewer assumptions):
- Collect 12-50 static orientations spread over the sphere (hold each ~3-5 s) and fit an ellipsoid with |a| = 1 g constraint. The **Tedaldi et al. (ICRA 2014)** multi-position method and its open-source implementation **IMU-TK** estimate scale, misalignment and bias for accel and gyro with the sensor merely hand-placed in static positions [REPORTED (search results), https://github.com/Kyle-ak/imu_tk ; paper https://iris.uniroma1.it/handle/11573/794826]. A 2022 follow-up found **12 orientations sufficient** (vs 37-50 recommended by Tedaldi) and cut time from ~5 min to < 2 min, using a helper object, with no numerical error metrics in the abstract [CONFIRMED, https://arxiv.org/abs/2207.04801]. IMU-TK is BSD-licensed, C++ with Ceres, Eigen, Qt, OpenGL (Ubuntu 14.04-era) [CONFIRMED, https://github.com/Kyle-ak/imu_tk] - building it today may be work.
- **Magneto** (sailboatinstruments): linear-algebra ellipsoid fit program (PC/Mac, C), mainly known for magnetometers; accelerometer use works with the same math [REPORTED, https://atadiat.com/?p=4743 and https://forum.pololu.com/t/correcting-the-balboa-magnetometer/14315 via search summaries]; I could not open the official page, so I cannot confirm its accelerometer instructions. Treat as unverified.
- **imucal** (Python, MaD-Lab FAU): implements Ferraris (1994/95) calibration - **6 static positions for the accelerometer, 3 rotations about the principal axes for the gyroscope**; outputs a calibration matrix usable on dataframes; JOSS paper Kuderle et al. 2022, https://doi.org/10.21105/joss.04338 [CONFIRMED, https://github.com/mad-lab-fau/imucal ; docs https://imucal.readthedocs.io/en/stable/README.html]. No accuracy claims on its README. This is the closest "install and go" tool for your six-position + gyro rotation workflow.
- "Calibrate-IMU" repository: I could not find a repository by that name in search; the closest hits were imucal and `dpkoch/imu_calib` (ROS, accel calibration + gyro bias at startup) [REPORTED, https://github.com/dpkoch/imu_calib]. If you meant a specific repo, give me its URL.
- Ferraris et al. 1995 (Sensors and Materials 7(5):311-330): in-field procedure needing no angular-velocity or acceleration standards, using local gravity and a set of rotations returning to the original position [CONFIRMED abstract record via search, https://iris.polito.it/handle/11583/1404539 ; full text not read].

### 3.2 Gyro: bias, scale factor, axis misalignment with no turntable

Bias: average 30-60 s stationary (after warm-up). With the Allan ARW ~0.009 deg/s/sqrt(Hz) at ~100 Hz the standard error of a 60 s mean is tiny (INFERRED: 0.009/sqrt(60) ~ 0.001 deg/s); the limit is bias instability (~0.001-0.0025 deg/s over 20-150 s per Rudyk, CONFIRMED) - so a stationary zero is accurate to ~0.01 deg/s in a stable-temperature room [INFERRED].

Scale factor via known-angle rotation (about 20-30 min for 3 axes):
1. Make a right-angle fixture: the IMU mounted in a box pressed against two perpendicular stops (a corner of a sturdy rectangular table, two machinist squares, or a drilled wooden jig).
2. Zero the bias (30 s still), rotate the box 90 degrees about the axis being tested against the next stop, hold 5 s still, repeat four times (360 deg) and also reverse direction. Integrate the bias-corrected rate over each movement including the still segments (or use imucal's rotation labelling).
3. Scale error = (integrated angle / true angle) - 1. Repeat 5-10 times per axis and per direction; also at two different rotation speeds (slow ~30 deg/s, fast ~200-300 deg/s) to expose nonlinearity/bandwidth problems (spec nonlinearity 0.2 %) [INFERRED].
4. Good practice from Ferraris: use rotations that **return to the original position** so that gravity/stop geometry supplies the truth rather than the speed of your hand [CONFIRMED abstract, above].
5. Expected reference accuracy: a stop-to-stop 90 deg step is limited by stop squareness and slop - about 0.2-0.5 deg per step with a decent jig [INFERRED], i.e. 0.2-0.6 % on scale factor over 90 deg; for 360 deg cumulative (4 steps returning to the first stop) the closing error is the same ~0.5 deg out of 360 = 0.15 % [INFERRED]. That is well below the datasheet +/-3 % so you can resolve the spec.

Axis misalignment/cross-axis: rotate about one axis and check the other two gyro channels - the ratio of the integrated cross-channel angle to the primary angle is the cross-axis term (spec 2 %). Alternative accel-referenced method: with the sensor in a stable pose, rotate about the vertical axis and check which accelerometer axis changes - tests mounting/axis alignment of accel vs. gyro frames via gravity direction [INFERRED]. A full 3x3 estimation (scale + misalignment) is what imucal/IMU-TK do from ~3-12 rotations/positions (above).

Also test gyro g-sensitivity: ZRO changes ~0.1 deg/s per g (datasheet) so under sustained 1-2 g in carving turns the gyro bias can shift by 0.1-0.2 deg/s [INFERRED from spec]; confirm by bias reading in six orientations (gyro mean vs which axis is up).

### 3.3 Mounting calibration (existing repo work)
Out of scope (already done in simulation); but the bench accel/gyro matrix from this section must be applied **before** the mounting calibration in the pipeline [INFERRED].

---

## 4. DIY rigs and their expected reference accuracy

All "expected accuracy" figures are INFERRED from geometry unless a source is stated; measure your own rig's repeatability.

| Rig | How | Reference uncertainty (typical) | Tests |
|---|---|---|---|
| Flat table + flip | Place on table, read angle, rotate 180 deg about vertical, average | Tilt of the table cancels to first order; limited by IMU's own offset repeatability | Accel offset; zero tilt reference |
| Spirit level + 6-face block | Six-position method | Level to ~0.1-0.2 deg with a good level | Accel scale/offset/cross-axis (3.1) |
| Tilt table with shim | A hinge board of length L lifted by shim of height h: angle = asin(h/L) | h +/-0.5 mm on L = 300 mm gives +/-0.1 deg; machined/gauge blocks better | Static tilt accuracy 0-45 deg; use 5-10 angles, both directions |
| Printed/store protractor | Resolution ~1 deg, reading error ~0.5 deg | **Too coarse to certify better than ~1 deg**; fine for 90-degree checks against known squares | Gross checks |
| Phone as reference | Phone's inclinometer/level app on same rigid plate | Unknown unless you calibrate the phone against the table flip test first; clinical studies report good agreement (r > 0.9) with inclinometers for hips/knees but moderate at the ankle, and do not give a degree figure [REPORTED, https://doaj.org/article/d16d7b6eda6f46ad9c991ad2ad5cf569 via search summary] | Cross-check, not ground truth; its error is likely comparable to your MPU6050's, so it cannot certify it [INFERRED] |
| Pendulum | Rigid arm, IMU near pivot; period T = 2*pi*sqrt(L/g) (small angle) with L measured to +/-1 mm on 1 m gives ~0.05 % period error [INFERRED]; time 20 periods with a video/phone stopwatch to get ~0.1 % | Gyro scale at low rates (peak rate = amplitude * 2*pi/T for small angles) and accel-vs-gyro consistency; large-amplitude correction needed above ~10-15 deg | Dynamic: gyro scale, fusion filter response; keep IMU on/near the pivot axis or centripetal/tangential acceleration pollutes the accel angle [INFERRED] |
| Hand rotation with stops | Section 3.2 | 0.2-0.5 deg per 90 deg step | Gyro scale, cross-axis |
| Turntable (record player 33.33 rpm = 200 deg/s) | 33.333 rpm = 200 deg/s exactly if mains-synced motor is stable | Speed accuracy of a good belt/direct drive turntable ~0.1-1 % [REPORTED-ish, unsourced; verify with a stopwatch over 20 revs] | Gyro scale at a constant rate, bias at rate; I did not find a source quantifying this - treat as optional |

Do not use the rig under the same conditions you use to fit parameters - keep a held-out set (Section 5).

---

## 5. Reporting accuracy honestly

### 5.1 Metrics to report
For a given test condition (static tilt, 60 s dynamic, warm-up, etc.), with N repeats and reference uncertainty u_ref:

1. **Bias (mean signed error)** and **SD** of error - separately; mean error is calibratable, SD is not.
2. **RMSE** = sqrt(bias^2 + SD^2) - state that it mixes both.
3. **Worst case (max |error|)** over all trials, with N. State that with N = 10 the max is not a bound.
4. **Drift rate**: slope of the error vs time from a linear fit on stationary or reference-held data, in deg/min (and deg over a 60 s run); also the 95 % interval of the slope [INFERRED].
5. **Confidence**: 95 % interval or percentile over N trials, across power cycles and orientations, not within one run.
6. Conditions: temperature range, sample rate, DLPF, full-scale, warm-up time, orientation, whether calibration used independent data.
7. Reference accuracy u_ref and how derived; rule of thumb (metrology test-accuracy ratio, not sourced here [INFERRED]): reference should be ~3-4x better than the claim; if you claim 1 deg and your reference is protractor +/-0.5 deg, you cannot claim 1 deg.

Decomposition worth showing (for a gyro-integrated angle over time t): error ~ sqrt( (N sqrt(t))^2 + (B_resid t)^2 + (ε_scale * angle)^2 ) with N = ARW, B_resid = residual bias after temperature/zeroing [INFERRED from the Allan noise model, https://github.com/ethz-asl/kalibr/wiki/IMU-Noise-Model]. The datasheet/Allan values are the floor, not the system accuracy.

### 5.2 Common mistakes
1. Quoting datasheet noise density or an Allan minimum as "accuracy" (Kalibr explicitly warns static Allan numbers are optimistic by ~10x for cheap MEMS) [CONFIRMED].
2. Calibrating and validating on the same data.
3. Not warming up or not logging temperature; testing right after power-up, then claiming drift.
4. Using the single-axis tilt formula near vertical, or combining accel tilt with unmodelled linear acceleration (pendulum/hand shake) [INFERRED].
5. Reference error not subtracted/considered; using a phone as truth.
6. Reporting one run instead of repeats across power cycles; reporting only the best axis (Rudyk shows 2x differences between axes [CONFIRMED]).
7. Allan analysis with too-short record, non-uniform sampling, or reading BI without stating whether /0.664 is applied.
8. Forgetting that clone parts differ (Section 6.3); a library that accepts 0x98 may use different offset registers [REPORTED].
9. Mixing deg/s and raw counts, FS range changes between calibration and use (scale/offset change with range) [INFERRED].
10. Ignoring gyro g-sensitivity and vibration rectification: bench static tests are the easy case; ski boot is the hard case. Bench results are a lower bound [INFERRED].

---

## 6. Known measurements and clone detection

### 6.1 Datasheet (rev 3.1, 2011) - CONFIRMED
https://cdn.sparkfun.com/datasheets/Components/General%20IC/PS-MPU-6000A.pdf
- Gyro total RMS noise 0.05 deg/s-rms (DLPF_CFG = 2, 100 Hz), low-frequency RMS noise 0.033 deg/s-rms (1-10 Hz), rate noise spectral density 0.005 deg/s/sqrt(Hz) at 10 Hz.
- Accel noise PSD 400 ug/sqrt(Hz) at 10 Hz (AFS_SEL=0, ODR 1 kHz).
- Scale/ZRO/temperature limits as in 2.1. Temperature sensor 340 LSB/degC.

### 6.2 Independent measurements

| Source | Conditions | Result | Label |
|---|---|---|---|
| Rudyk et al., Sensors 2020, 20(17):4841 https://pmc.ncbi.nlm.nih.gov/articles/PMC7506677/ | MPU-6050, 1 h, 70 Hz, 22 +/- 3 C, stationary | "ARW" at tau=1 s: X 0.009145, Y 0.009997, Z 0.009533 (paper writes deg/s/sqrt(Hz)); bias instability (min/0.664): X 0.00239, Y 0.001277, Z 0.002449 deg/s; minimum between ~20 and 150 s; no rate random walk or Markov noise seen but record not long enough to see +1/2 slope; authors call MPU-6050 "not highly accurate" | CONFIRMED |
| Pololu forum post https://forum.pololu.com/t/mpu6050-evaluation-rate-noise-density-rms-noise/8721 | 500 Hz, duration/temperature not stated | SD 0.0331 deg/s; ARW 0.003 deg/sqrt(s); bias instability 0.0012 deg/s | CONFIRMED the post says this; unreviewed, REPORTED quality |
| arXiv:1608.07053 https://arxiv.org/abs/1608.07053 | MPU-6050 balloon-borne attitude sensor | Allan variance + PSD used; says performance degrades over time; no numbers in abstract | CONFIRMED abstract only |
| Woodman 2007 (for context, Xsens MTx, 12 h, 100 Hz) https://www.cl.cam.ac.uk/techreports/UCAM-CL-TR-696.pdf | Not MPU6050 | gyro ARW about 0.075-0.079 deg/sqrt(s) (4.6-4.8 deg/sqrt(h)); BI 0.009-0.012 deg/s (32-43 deg/h) at tau of 270-620 s | CONFIRMED (printed values partly garbled in extraction) |
| Temperature drift | -20..+70 C, 8 devices (SparkFun forum) | Bias-temperature relation shows hysteresis; no numeric slope found | REPORTED |
| "Typical +/-0.01..0.03 deg/s/degC" | calibration blog https://industrialmonitordirect.com/blogs/knowledgebase/calibrating-the-mpu6050-gyroscope-complete-procedure | source of figure unknown | REPORTED, unverified |

I did not find a peer-reviewed, per-degree gyro-bias-vs-temperature measurement for the MPU6050; this is exactly the gap your fridge test fills. Note Rudyk's ARW ~0.009 is 2-3x the datasheet and Pololu numbers (different DLPF/sample rate/units conventions; INFERRED), so expect **your** unit to land in 0.003-0.01 deg/sqrt(s).

### 6.3 Clone detection

- Documented WHO_AM_I register (0x75): contents are the upper 6 bits of the 7-bit I2C address, default **0x68**, bits 0 and 7 hard-coded to 0 (AD0 pin not reflected) [CONFIRMED, register map https://cdn.sparkfun.com/datasheets/Sensors/Accelerometers/RM-MPU-6000A.pdf]. 0x98 has bit 7 set, so it is not valid for an MPU-60X0 [INFERRED from that description; a forum poster made the same observation, REPORTED].
- 0x98 on GY-521 boards: widely reported; posters believe it is an ICM-20689 (whose WHO_AM_I is 0x98 per InvenSense forum posts) or a clone; behaviour "reasonable looking data" but libraries checking 0x68 fail, possible different offset-register locations and odd calibration results [REPORTED, https://forum.arduino.cc/t/mpu-6050-a-module-problems-who-am-i-reports-0x98-not-0x68-as-it-should-fake-mpu-6050/861956 ; https://forum.arduino.cc/t/bare-metal-i2c1-on-stm32f411-who-am-i-returns-0x98-not-0x68-gy-521-mpu6050/1453784 ; ICM-20689 0x98 via https://adm.invensense.tdk.com/icm-20689-porting-different-processor]. I did not read the ICM-20689 datasheet myself.
- 0x70 = MPU-6500 (Linux kernel patch discussion: 6000/6050/9150 share 0x68 and cannot be distinguished by WHO_AM_I) [REPORTED, https://lkml.iu.edu/1604.3/00997.html]. 0x71 = MPU-9250, 0x72/0x75 likely other/clone per a Mischianti sketch [REPORTED, https://mischianti.org/tdk-invensense-mpu-9250-module-high-resolution-pinout-datasheet-schema-and-specs/]. A PJRC forum user saw 0x72 on one GY-521 and 0x68 on another [REPORTED, https://forum.pjrc.com/index.php?threads/not-initialize-mpu6050-failed-to-find-mpu6050-chip.67594/]. One Klipper thread states counterfeits come up as 0x98 [REPORTED, https://klipper.discourse.group/t/gy-521-mpu-6050-no-accelerometer-measurements-found/11944].
- Therefore WHO_AM_I can flag "not an MPU-60X0" but **cannot prove authenticity**: 0x68 is shared by MPU-6000/6050/9150 and a clone can copy it [INFERRED].

Behavioural clone checks you can run [INFERRED]:
1. Read WHO_AM_I and log it with every dataset (also I2C address with AD0 low/high).
2. Temperature register: `TEMP_OUT/340 + 36.53` should read plausible room temperature (+ few degC self-heating); a wildly different value or sensitivity (check change per degC in the warm-up log vs 340 LSB/degC) suggests a different die.
3. Noise: the Allan ARW and RMS at DLPF 2 (100 Hz) should be near 0.03-0.05 deg/s-rms; accel noise ~400 ug/sqrt(Hz). Much higher/lower or different ADC steps is suspect.
4. Scale: at FS_SEL=0 sensitivities are 131 LSB/(deg/s) and 16384 LSB/g - a 90-degree-step test and 1 g flip will show if counts-per-unit match.
5. Self-test registers (XA_ST etc. in register 0x1B/0x1C) - the register map defines them; compare self-test response to datasheet limits [INFERRED; I did not verify exact limits].
6. Buy from a source that lists the part marking; datasheet-less clones often still reply at 0x68. Prefer a breakout with a known chip (e.g., ICM-20948/ICM-42688 or BMI270 boards) if repeatability matters, since MPU-6050 was end-of-life and most GY-521 modules carry non-original silicon (REPORTED single forum claim, not verified).

---

## 7. Step-by-step plan (hobbyist, ESP32/Arduino)

Common logger [INFERRED design]: ESP32 reads MPU6050 burst (registers 0x3B-0x48: accel, temp, gyro, 14 bytes) at 100-200 Hz from a hardware timer or a pinned FreeRTOS task, I2C at 400 kHz; stores `t_us, ax, ay, az, temp, gx, gy, gz` as raw int16 plus a one-line header of config (FS, DLPF, SMPLRT_DIV, WHO_AM_I). Log to SD or stream over serial at >= 460800 baud; check the timestamp gaps. Keep Wi-Fi/BLE off during Allan runs.

| Test | Time | Needs | Output |
|---|---|---|---|
| T0 Identity + sanity | 5 min | sketch | WHO_AM_I, I2C address, temp reading, noise at rest |
| T1 Warm-up log | 45-60 min | block, bag, no airflow | bias vs time and vs T; warm-up time |
| T2 Allan run | 1-3 h (after 30 min warm-up) | rigid mount | N, B, K per axis (1.3) |
| T3 Cold-soak/warm-up | 2-3 h incl. soak | freezer/fridge, bag+silica | bias(T), offset(T) fits (2.3) |
| T4 Six-position accel | 20-30 min | cube, level | offset, scale, cross-axis |
| T5 Tilt table | 20-30 min | shims, hinge board | accel-only angle error at 5-10 angles |
| T6 Gyro 90-degree steps | 20-30 min | corner jig | scale and cross-axis per axis |
| T7 Gyro integration drift | 10 min | stationary | drift deg/min after calibration |
| T8 Pendulum / dynamic check | 30 min | string/rod, protractor, stopwatch | filter lag, gyro scale at 20-100 deg/s, accel-gyro consistency |
| T9 Validation | 30 min | new orientations, not used for fit | final RMSE, bias, worst case |

Order: T0, T1, T2 (overnight), T4, T6, T3, then fit, then T5/T7/T8/T9 with the calibration applied.

---

## 8. Proposed acceptance checklist for a ski-boot sensor

The thresholds below are **proposals** (INFERRED) tied to what a ski-technique feedback tool plausibly needs; they are not from a standard. Adjust to the angles the app reports.

1. Identity: WHO_AM_I and I2C address logged; matches 0x68 (or documented alternate part with driver changes). PASS/FAIL
2. Noise: gyro ARW per axis <= 0.015 deg/sqrt(s) and BI <= 0.01 deg/s from a >= 1 h Allan run; accel noise at 100 Hz <= ~2 mg rms (INFERRED: 400 ug/sqrt(Hz) * sqrt(~50 Hz) ~ 2.8 mg at 100 Hz bandwidth). No axis >2x the others.
3. Warm-up: gyro bias within 0.05 deg/s of its steady value within X minutes of power-on (X from T1; if > 5 min, require a user "stand still" zeroing at start).
4. Temperature: after compensation, residual gyro bias <= 0.05 deg/s over the measured span (>= 15-25 degC) on a held-out run; accel offset residual <= 5 mg. State the span.
5. Static tilt: |error| <= 1 deg (95 %) at 0..45 deg in two orientations, referenced to a shim table with u_ref <= 0.3 deg.
6. Gyro scale: per-axis scale error <= 1 % (spec is +/-3 %); cross-axis <= 2 %; at 30 and 250 deg/s.
7. Gyro drift after zeroing: <= 0.05 deg/s -> <= 3 deg/min over 10 min stationary in a warm room, <= 1 deg over 60 s.
8. Repeatability: five power cycles; zero bias and tilt reading vary < 0.2 deg.
9. Dynamic/pendulum: angle (fused) within 2 deg RMS of the reference over 20 swing cycles (no linear-acceleration blow-up); no latency > 1 sample period beyond filter design.
10. Cold test: 30 min in a freezer-cooled bag then in a stationary position: bias settles within spec; no resets, no I2C errors; battery tests separate.
11. Documentation: record N, reference uncertainty, temperature, firmware version, calibration matrix, date, and whether the data were held out.
12. Caveat in the report: bench static performance is a lower bound; ski vibration, g-sensitivity (0.1 deg/s/g spec) and boot-flex mounting dominate on snow.

---

## 9. Source list (for convenience)

- InvenSense MPU-6000/6050 Product Specification rev 3.1: https://cdn.sparkfun.com/datasheets/Components/General%20IC/PS-MPU-6000A.pdf (read)
- MPU-6000/6050 Register Map rev 4.0: https://cdn.sparkfun.com/datasheets/Sensors/Accelerometers/RM-MPU-6000A.pdf (read)
- Woodman, UCAM-CL-TR-696: https://www.cl.cam.ac.uk/techreports/UCAM-CL-TR-696.pdf (read s5)
- Kalibr IMU noise model: https://github.com/ethz-asl/kalibr/wiki/IMU-Noise-Model (read)
- MathWorks Allan variance: https://ch.mathworks.com/help/nav/ug/inertial-sensor-noise-analysis-using-allan-variance.html (read)
- Inertial Sense Allan deviation: https://docs.inertialsense.com/user-manual/imx/performance/allan_deviation/ (read)
- allantools: https://github.com/aewallin/allantools (read)
- imu_utils: https://github.com/gaowenliang/imu_utils (read)
- allan_variance_ros: https://github.com/ori-drs/allan_variance_ros (read)
- ArduPilot IMU temperature calibration: https://ardupilot.org/copter/docs/common-imutempcal.html (read); source file AP_InertialSensor_tempcal.cpp (read via fetch summary)
- PX4 thermal calibration: https://docs.px4.io/main/en/advanced_config/sensor_thermal_calibration.html (read)
- imucal: https://github.com/mad-lab-fau/imucal (read), JOSS https://doi.org/10.21105/joss.04338
- IMU-TK: https://github.com/Kyle-ak/imu_tk (read); Webering et al.: https://arxiv.org/abs/2207.04801 (read)
- Rudyk et al. 2020: https://pmc.ncbi.nlm.nih.gov/articles/PMC7506677/ (read)
- Pololu thread: https://forum.pololu.com/t/mpu6050-evaluation-rate-noise-density-rms-noise/8721 (read)
- Arduino forum 0x98 thread: https://forum.arduino.cc/t/mpu-6050-a-module-problems-who-am-i-reports-0x98-not-0x68-as-it-should-fake-mpu-6050/861956 (read)
- Not accessible to me (403/404): InvenSense developer forum pages, mikrocontroller.net datasheet mirror, UW-hosted datasheet, ArduPilot batch-sample page, Magneto home page, SparkFun thread full text.
