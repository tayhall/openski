# Flight-controller and robotics IMU practices, mined for a boot-mounted ski sensor

Research date: 2026-10-11. Scope: what ArduPilot, PX4, Betaflight/INAV, Crazyflie, x-io Fusion, VQF, ROS imu_tools, Kalibr and VINS do to make cheap MEMS IMUs accurate and trustworthy, and which of that is worth porting to OpenSki (boot-cuff sensor, unknown mounting, cold, vibration, 1-2 g sustained cornering, battery, BLE, MPU6050 proof of concept).

## 0. How to read this report

### Evidence labels
- **CONFIRMED**: the fact was extracted from the project source file or official doc at the URL given.
- **REPORTED**: from a secondary source (forum, search-result snippet, blog) or a source I could only see via a summary.
- **INFERRED**: my own reasoning or arithmetic; not stated by any source.

### Method caveat (read this)
WebFetch returns a model-written extract of each page, not the raw bytes. Function and constant names below are as that extract reported them; I did not diff them against raw files. Where an extract flagged ambiguity or a source inconsistency I kept the flag. Source files change on `master`/`main`; line numbers are not given because none were available. Items that I could not open (InvenSense thread pages returned 403; the MPU register map PDF would not load; VINS-Mono upstream values are for a different sensor) are labeled REPORTED or left out.

### Already done in this repo (not re-proposed)
Mahony-style quaternion complementary filter with accel-magnitude gating, startup mounting calibration (still pose + knee flex), mounting-slip monitor, BROAD benchmark at 1.6 deg median roll error. Everything below is additive to those.

### One-paragraph physics reminder (INFERRED)
In a sustained turn the accelerometer measures gravity plus centripetal acceleration. At 1 g lateral the specific-force vector is 1.41 g long and tilted 45 deg from true vertical; at 2 g lateral it is 2.24 g long and about 63 deg off. A magnitude gate (0.9-1.1 g) therefore rejects the whole turn and the filter runs gyro-only, so roll error in a turn is gyro bias times turn duration plus gyro scale error times angle. That makes gyro bias quality and bias-vs-temperature the dominant error term, which is why most of the ideas below are about bias.

---

## 1. Source inventory

| Project | Where I looked |
|---|---|
| ArduPilot | `libraries/AP_InertialSensor/AP_InertialSensor.cpp`, `..._tempcal.cpp`, `..._Backend.cpp`, `..._Invensense.cpp`; `libraries/AP_AccelCal/AP_AccelCal.cpp`, `AccelCalibrator.cpp`; `libraries/AP_AHRS/AP_AHRS_DCM.cpp`; `libraries/AP_NavEKF3/*`; ardupilot.org vibration and batch-sampling docs |
| PX4 | `src/modules/sensors/vehicle_imu/VehicleIMU.cpp`, `vehicle_angular_velocity/VehicleAngularVelocity.cpp`, `commander/HealthAndArmingChecks/checks/*`, `ekf2/EKF/*`; docs thermal calibration and EKF2 tuning |
| Betaflight / INAV | `src/main/sensors/gyro.c`, `acceleration.c`, `flight/imu.c`, `drivers/accgyro/accgyro_mpu.c`, `accgyro_spi_mpu6000.c`, INAV `common/calibration.c` |
| Crazyflie | `src/hal/src/sensors_bmi088_bmp3xx.c`, `sensors_mpu9250_lps25h.c`, `src/drivers/src/mpu6050.c` |
| x-io Fusion | `Fusion/FusionAhrs.c`, `FusionBias.c`, README |
| VQF | arXiv 2203.17024 text, vqf.readthedocs.io parameter table |
| ROS imu_tools | `imu_complementary_filter/src/complementary_filter.cpp`, `imu_filter_madgwick/src/imu_filter.cpp` |
| Noise models | Kalibr wiki IMU-Noise-Model, VINS-Mono `euroc_config.yaml` |
| MPU6000/6050 | InvenSense Product Specification rev 3.2 (PDF text extracted locally), i2cdevlib `MPU6050.h`, ArduPilot/Betaflight/Crazyflie drivers, forum threads |

---

## 2. Findings by project

### 2.1 ArduPilot

#### 2.1.1 Boot gyro calibration with motion rejection and best-effort fallback
- **What**: at boot, average gyro samples while still; reject the attempt if the accelerometer moved; accept only after two successive averages agree; if never converges, use the best average and flag the sensor as not OK.
- **How (CONFIRMED)**: `AP_InertialSensor::init_gyro()` calls `_init_gyro()` then `_save_gyro_calibration()`. Offsets are zeroed, then each attempt averages 50 samples at 5 ms spacing; the attempt is discarded if accelerometer changes by more than 0.2 m/s^2 (code comment equates this to about 5 deg/s of rotation); successive averages are blended 50/50 and converge when they differ by less than `GYRO_INIT_MAX_DIFF_DPS` = 0.1; up to `30*4` attempts (about 30 s); on failure `_gyro_cal_ok[k] = false` and the best average is used. Calibration temperature is stored (`caltemp_gyro`). Source: https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_InertialSensor/AP_InertialSensor.cpp
- **Also CONFIRMED** in the same file: `is_still()` compares vibration level to `_still_threshold` (default 2.5 for Copter, 0.1 for Rover/others); fallback bias limit 0.5 (get_gyro_bias_limit_rads) and init bias 2.5 deg/s defaults.
- **Applicability**: high. A boot is stationary at the lift, in the hut, or while standing between runs. Using accel-based motion rejection (not only gyro variance) catches slow tilting that gyro-only variance tests miss.
- **Implementation**:
```
def gyro_cal(stream):
    prev = None
    for attempt in range(MAX_ATTEMPTS):
        g_sum, a_first = 0, None
        for i in range(N):                      # N ~ 50-100 samples
            g, a = stream.next()
            a_first = a_first or a
            if norm(a - a_first) > ACC_MOTION:  # ~0.2 m/s^2
                break                           # motion: discard attempt
            g_sum += g
        else:
            avg = g_sum / N
            if prev is not None and max_abs(avg - prev) < 0.1: return avg, OK
            prev = avg if prev is None else 0.5*(prev + avg)
    return prev, NOT_OK    # still usable, but tell the app confidence is low
```

#### 2.1.2 Accelerometer calibration: ellipsoid fit with pose-diversity gate and sanity limits
- **What**: 6-position fit for offsets and scales (optionally 9-parameter with cross-axis).
- **How (CONFIRMED)**: `AccelCalibrator` fits an ellipsoid with a Gauss-Newton iteration (no Levenberg-Marquardt damping per the extract), fitness is mean squared residual of `GRAVITY_MSS - |corrected sample|`, success requires `_fitness < ACCEL_CAL_TOLERANCE`. Samples are rejected if closer than `_min_sample_dist` to an earlier sample (spread assumed like a polyhedron with `2N-4` faces). `accept_result()` requires each offset magnitude <= `GRAVITY_MSS` and each diagonal scale in 0.8-1.2. Models: `ACCEL_CAL_AXIS_ALIGNED_ELLIPSOID` (6 params, min 6 samples) and `ACCEL_CAL_ELLIPSOID` (9 params, min 8 samples). Source: https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_AccelCal/AccelCalibrator.cpp . Higher-level 6-position flow with `ACCELCAL_VEHICLE_POS_*` and 1 s prompt resend: https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_AccelCal/AP_AccelCal.cpp
- **Also CONFIRMED** (`AP_InertialSensor.cpp`): accepted accel calibration ranges scale 0.8-1.2, offsets +/-3.5 m/s^2; `accel_calibrated_ok_all()` requires non-zero offsets and scale; calibration is stored with `caltemp_accel`.
- **Applicability**: partial. A boot cannot be placed in 6 faces by the user, but the knee-flex sweep gives orientation spread. The transferable parts are (a) refuse to fit when pose diversity is low, (b) bounds on result, (c) store the temperature of calibration.
- **Implementation**: collect stationary-ish averages (per knee-flex dwell) as unit-sphere samples; if `min_pairwise_angle` and `span` of samples are inadequate fit only one scale parameter `|a|=1g` (Crazyflie does exactly that, see 2.4); else fit offset+3 scales; accept only inside bounds above.
```
if span_deg(samples) < 60: fit scale_only
else: fit 6-param, reject if any |scale-1|>0.2 or |offset|>0.35 g (3.5 m/s^2)
```

#### 2.1.3 Temperature calibration (INS_TCAL)
- **What**: learn a polynomial gyro/accel bias vs die temperature during warm-up.
- **How (CONFIRMED, `AP_InertialSensor_tempcal.cpp`)**: 3rd-order polynomial with no constant term (three coefficients per axis); the fit variable is `T - 35.0` (`TEMP_REFERENCE`) so the final temperature need not be known; samples are low-pass filtered and binned: a bin closes only after at least 100 samples and at least 0.5 C rise; bin value is paired with the midpoint temperature and fed to a polynomial fitter; accelerometer bins are baseline-subtracted from the first bin, gyro bins are not; minimum span `TEMP_RANGE_MIN` = 10 C before saving; partial saves at most every 15 s; timeout 10 min after the 10 C span is reached; `TMIN`/`TMAX` clamp the correction (defaults 0 and 70). Coefficients stored scaled by 1e6. Source: https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_InertialSensor/AP_InertialSensor_tempcal.cpp
- **Applied in** `AP_InertialSensor_Backend::_rotate_and_correct_gyro/accel`: temperature correction, then offset, then scale, then board rotation (CONFIRMED): https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_InertialSensor/AP_InertialSensor_Backend.cpp
- **Applicability**: high for gyro bias; skiing sensors start cold (pocket, car, outdoors) then self-heat and are insulated by the boot. A bench thermal sweep per unit (or per lot) is realistic; per-unit is not realistic at scale, so see 2.2.4 for the on-the-fly alternative.
- **Implementation**: keep the same structure but use a 1st or 2nd order poly (range for a ski day is perhaps -15 to +25 C, INFERRED) to avoid overfitting; clamp to calibrated range; always add residual online bias estimation (2.5) because inter-unit and hysteresis variance remain.
- **Caveat (REPORTED)**: InvenSense developer-forum threads report MPU-6050 zero-rate output showing hysteresis between heating and cooling and a discontinuity near 32 C while cooling (search-result summaries; pages themselves returned 403): https://adm.invensense.tdk.com/mpu-6050-thermal-chamber-results-hysteresis , https://adm.invensense.tdk.com/mpu6050-shows-discontinuity-when-temperature-varies . So a static polynomial will not fully remove bias; treat it as a prior.

#### 2.1.4 Vibration and clipping metrics, IMU health
- **How (CONFIRMED, `AP_InertialSensor.cpp`)**: `calc_vibration_and_clipping()` counts per-axis samples beyond `get_clip_limit()` into `_accel_clip_count`. Vibration level: accel low-passed at `..._VIBE_FLOOR_FILT_HZ` (comment: 5 Hz), squared residual filtered at `..._VIBE_FILT_HZ` (comment: 2 Hz), `get_vibration_levels()` returns the square root. Health: `get_gyro_health_all()`, `get_accel_health_all()`; `gyros_consistent(threshold)` and `accels_consistent(threshold)` compare IMUs; error counts compared with start-up counts (2000 ms window); `pre_arm_check_gyro_backend_rate_hz()` requires sample rate >= 1.8x loop rate.
- **Docs**: Vibe values below about 15 m/s/s normal, above 30 risky, above 60 nearly always breaks position hold; clip count ideally zero, under 100 usually tolerable, steadily rising means a real problem (https://ardupilot.org/copter/docs/common-measuring-vibration.html). The page also notes a symptom of accelerometer aliasing, "the leans", detectable by comparing AHRS2 vs EKF attitude differing by more than a few degrees.
- **Applicability**: high. Ski boots see impulsive shocks (snow chatter, ice, landings) that will clip a +/-16 g MPU6050 and alias into the low-rate accel. The count and a vibe score make a cheap per-turn quality flag.
- **Implementation**:
```
clip = any(abs(raw_axis) >= 32000)     # +-16 g range, 2048 LSB/g -> ~15.6 g, INFERRED threshold
vibe_floor = lpf(a, 5 Hz); vibe = sqrt(lpf((a - vibe_floor)^2, 2 Hz))
turn.quality = {clip_count, vibe_rms, accel_gated_fraction}
```
ArduPilot sets its raw accel scale as `INT16_MAX/(16*GRAVITY_MSS)` (CONFIRMED, same file), i.e. +/-16 g; Betaflight's equivalent for the MPU6000 is `acc_1G = 512*4` (CONFIRMED, `accgyro_spi_mpu6000.c`).

#### 2.1.5 Sample timing, gaps, delta-velocity integration
- **How (CONFIRMED, `AP_InertialSensor_Backend.cpp`)**: samples carry timestamps; `dt` from timestamp differences; a gap over 100000 us zeroes the delta accumulator and sets `dt=0`; observed sample rate estimated over >1 s windows and clamped to +/-5% (0.5x-2x while converging); accel integrated as `accel*dt` into delta-velocity; gyro by trapezoid with a coning term (weights 0.5 and 1/6). Notches then low-pass for gyro; single LPF for accel; NaN output resets the filter.
- **Why it matters**: the DCM code accumulates delta-velocity (not point samples) "to prevent an aliasing effect" (CONFIRMED, `AP_AHRS_DCM.cpp`, https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_AHRS/AP_AHRS_DCM.cpp).
- **Applicability**: high. A BLE sensor that sub-samples at 50-100 Hz and averages by integrating the 1 kHz internal stream avoids aliasing of vibration into the tilt estimate. INFERRED: integrate/average all internal samples in the MCU (FIFO burst), then emit.

#### 2.1.6 DCM (the lightweight ArduPilot attitude filter) - directly comparable to what we have
All CONFIRMED from `AP_AHRS_DCM.cpp` (extract of `drift_correction()`):
- Reference vector: gravity `(0,0,-1)` corrected by `velocity delta` (centripetal/linear acceleration removed using GPS velocity derivative) when GPS lock or forward flight; normalized so only direction matters. No explicit magnitude gate in this function.
- Error from multiple accels: for each healthy accel computes `GA_b % GA_e`, chooses the instance with smallest error (comment: reduces vibration-harmonic aliasing). A 180 deg flip guard sets `best_error = 1.0` if the dot product is negative.
- Gain scheduling `_P_gain(spin_rate)`: 1.0 below 50 deg/s, 10.0 above 500 deg/s, linear in between.
- Fast gains `use_fast_gains()`: not armed and within 20 s of startup => `_omega_P *= 8`.
- Integral (gyro bias) only accumulated when `spin_rate < radians(20)` (`SPIN_RATE_LIMIT`, comment cites a fast-rotation paper); sum is flushed every 5 s and clamped to `get_gyro_drift_rate() * time`.
- Smoothed error `_error_rp = 0.8*prev + 0.2*best_error`, healthy if no failure in last 5000 ms.
- **Applicability**: the 20 deg/s integral gate and the 5 s clamped-bias-flush idea are directly portable: learn bias only during low-rate phases and bound how fast it can change. The GPS centripetal subtraction is not portable (no velocity), but see 3.1 for the VQF equivalent.

#### 2.1.7 EKF3 details worth knowing
CONFIRMED from `AP_NavEKF3_core.cpp`, `..._Control.cpp`, `..._PosVelFusion.cpp` extracts (https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_NavEKF3/AP_NavEKF3_Control.cpp, ..._PosVelFusion.cpp):
- Gyro-bias states are inhibited until tilt alignment completes (`tiltAlignComplete` when `tiltErrorVariance < sq(radians(5.0))`), then seeded with `InitialGyroBiasUncertainty()`.
- `checkGyroCalStatus()`: gyro bias considered learned when bias variances fall below `sq(radians(0.15 * dtEkfAvg))`, i.e. sigma under 0.15 deg/s.
- Bias variance is clamped; bias states clamped (`ConstrainStates`).
- `badIMUdata` detector: sets when height innovation and GPS vertical-velocity innovation have the same sign and both exceed a gate (3-sigma, tightening to 2- and 1-sigma shortly after an event). While set, delta-velocity bias learning is inhibited and measurements are fused regardless of test ratio "to prevent aliasing and clipping pulling the state estimate away".
- **Applicability**: the transferable concepts are (a) publish "bias converged" and "tilt aligned" states with numeric sigma thresholds, (b) stop learning bias when contamination is detected, (c) detect aliasing/clipping through disagreement between two independent estimates having a consistent sign. A boot has no baro/GPS; the closest independent signal is left/right turn symmetry (INFERRED, see 3.1 notes).

#### 2.1.8 Batch sampling for offline spectra
- Docs (CONFIRMED): https://ardupilot.org/copter/docs/common-imu-batchsampling.html - log raw IMU at highest rate for post-flight FFT; FFT resolves up to half the sample rate and down to rate/sample count; used to choose LPF/notch and to detect aliasing.
- **Applicability**: a "debug capture" mode that logs 1 kHz raw for 10-30 s during field testing would let you see boot chatter spectra and choose the DLPF. Not for production.

### 2.2 PX4

#### 2.2.1 IMU integrator pipeline and clipping flags
- **How (CONFIRMED, `VehicleIMU.cpp`)**: gyro and accel are integrated to delta-angle/velocity, then calibration applied to the rate (delta/dt) and multiplied back; per-axis clip counters are rotated into body frame, set `CLIPPING_X/Y/Z` in `delta_velocity_clipping`, and the user is notified when total count grows by more than 1000 and 3 s have elapsed. Status also publishes rates, means, variances, temperature, error counts and a coning metric. Source: https://raw.githubusercontent.com/PX4/PX4-Autopilot/main/src/modules/sensors/vehicle_imu/VehicleIMU.cpp
- **Vibration metrics (CONFIRMED)**: `accel_vibration_metric = 0.99*prev + 0.01*|a_k - a_{k-1}|` and the same for gyro. Limited value per the PX4 tuning guide, which says aliasing offsets may not appear in the vibe metric (https://docs.px4.io/main/en/advanced_config/tuning_the_ecl_ekf.html).
- **Applicability**: the single-pole `|a_k - a_{k-1}|` metric costs nearly nothing and works as a jerk/chatter proxy. Use it as a quality score, not a correction.

#### 2.2.2 Gyro calibration on the fly, with a stability flag and thresholded persistence
- **How (CONFIRMED, same file)**: `SensorCalibrationUpdate()` only accepts a bias from the estimator when `gyro_bias_valid` and `gyro_bias_stable` are set, the message is under 1 s old and device id matches. `SensorCalibrationSaveGyro()` fuses estimates weighted by `bias_variance` and commits only if the change exceeds 0.01 (units not stated in the extract; presumably rad/s) or the sensor is uncalibrated; learning runs when armed or uncalibrated at most once per second, saving only when disarmed.
- **Applicability**: high. Maps to: learn bias continuously, persist only when (a) estimate is stable, (b) change is material, (c) device is not in use. Avoids flash wear and avoids a bias step mid-run.

#### 2.2.3 Zero gyro update (rest as a direct bias measurement)
- **How (CONFIRMED, `ZeroGyroUpdate.cpp`)**: when `vehicle_at_rest`, accumulate delta angle over 0.2 s windows (about 5 Hz), average to a rate, and fuse that as a direct measurement of the gyro bias with variance `clamp(gyro_noise,0,1)^2`. No innovation gate. Source: https://raw.githubusercontent.com/PX4/PX4-Autopilot/main/src/modules/ekf2/EKF/aid_sources/ZeroGyroUpdate.cpp . The code that decides `at_rest` was not visible in the files I read (it arrives as a buffered system flag).
- **Applicability**: high; this is the cleanest formulation of "stand still => bias observation".

#### 2.2.4 Gravity fusion with magnitude gate and at-rest bypass
- **How (CONFIRMED, `gravity_fusion.cpp`)**: enabled only when horizontal aiding is off and tilt alignment complete; gate: low-passed accel norm within 0.9-1.1 g or vehicle at rest; measurement is the unit specific force minus accel bias; variance `max(EKF2_GRAV_NOISE^2, 0.01^2)`; innovation gate 0.25; skipped if any axis clipped; axes fused sequentially. Source: https://raw.githubusercontent.com/PX4/PX4-Autopilot/main/src/modules/ekf2/EKF/aid_sources/gravity/gravity_fusion.cpp
- **Applicability**: our existing gating matches this. New element: the **at-rest bypass** (still accept accel when truly stationary even if magnitude check fails, e.g. a bad scale factor) and the **clipping veto**.

#### 2.2.5 Accel noise inflation instead of switching off
- **How (CONFIRMED, `covariance.cpp`)**: for a clipped axis or `bad_acc_vertical`, accel process variance becomes `sq(BADACC_BIAS_PNOISE)` rather than `sq(accel_noise)`. Gyro bias process noise is only added while `P(i,i) < sq(gyro_noise)` (bias uncertainty is capped at white-noise level). Source: https://raw.githubusercontent.com/PX4/PX4-Autopilot/main/src/modules/ekf2/EKF/covariance.cpp
- **Applicability**: moderate. Use if we move from complementary to a small Kalman filter. Soft inflation is better than a binary gate when only some axes are polluted.

#### 2.2.6 Thermal calibration
- **How (CONFIRMED, docs)**: 3rd-order polynomials for accel, gyro, mag; 5th for baro. Onboard method uses `SYS_CAL_TDEL`, `SYS_CAL_TMIN`, `SYS_CAL_TMAX`; offboard method logs a warm-up and runs `process_sensor_caldata.py`. Cold-soak in a freezer (about -20 C home, -40 C commercial) in an anti-static bag with silica gel; warm slowly; scale factors are treated as temperature invariant; a 6-point accel cal must be redone inside the temperature range; reboot after calibration so offsets do not step in flight. Out-of-range temperatures are clipped. https://docs.px4.io/main/en/advanced_config/sensor_thermal_calibration.html
- **Applicability**: the freezer procedure is the right bench method for ski conditions. Note "reboot before use because sudden offset changes disturb the estimator" - same concern for us: apply new bias with a short blend, not a step.

#### 2.2.7 Filter chain and sample-rate handling
- **How (CONFIRMED, `VehicleAngularVelocity.cpp`)**: ESC-RPM dynamic notch, FFT dynamic notch, two static notches, then low-pass `IMU_GYRO_CUTOFF`; angular acceleration from differentiated, low-passed gyro (`IMU_DGYRO_CUTOFF`); sample rate accepted only 10-10000 Hz and filter reset when it changes by more than 1%; sensor reselected if no publish for 500 ms. Source: https://raw.githubusercontent.com/PX4/PX4-Autopilot/main/src/modules/sensors/vehicle_angular_velocity/VehicleAngularVelocity.cpp
- **Applicability**: low for notches (no tonal motor noise). The angular-acceleration estimate is interesting: it is a direct handle on the lever-arm contamination discussed in 3.4.

#### 2.2.8 Preflight checks
- **How (CONFIRMED)**: `accelerometerCheck.cpp` verifies presence, nonzero device id and timestamp, data younger than 1 s, and a matching calibration entry; `imuConsistencyCheck.cpp` compares inconsistency values against `COM_ARM_IMU_ACC` (m/s^2) and `COM_ARM_IMU_GYR` (rad/s) only while disarmed; no hysteresis. https://raw.githubusercontent.com/PX4/PX4-Autopilot/main/src/modules/commander/HealthAndArmingChecks/checks/accelerometerCheck.cpp , .../imuConsistencyCheck.cpp
- **Applicability**: a single-IMU product cannot cross-check, but "device present, data fresh, calibration valid, temperature plausible, |a| ~ 1 g at rest" is a good boot-time self-check.

#### 2.2.9 Bias magnitudes PX4 considers a problem (CONFIRMED, EKF tuning guide)
Switch-on bias up to 5 deg/s tolerated if the filter has time to settle; a bias change over about 3 deg/s during warm-up from cold is called excessive and the guide suggests recalibration, replacement or insulating the sensor to slow temperature change (https://docs.px4.io/main/en/advanced_config/tuning_the_ecl_ekf.html). **Applicability**: an insulated enclosure in the boot cuff slows thermal transients - plausible mechanical mitigation (INFERRED).

### 2.3 Betaflight / INAV

#### 2.3.1 Boot gyro calibration with a std-dev motion test
- **How (CONFIRMED, Betaflight `gyro.c`)**: `gyroCalibrationDuration` default 125 (1.25 s); cycles from `gyroSampleLooptime`; running variance via `devPush`; on the final cycle `devStandardDeviation()` is compared with `gyroMovementCalibrationThreshold` (default 48; raw units not confirmed) and if exceeded calibration restarts; otherwise zero = mean. https://raw.githubusercontent.com/betaflight/betaflight/master/src/main/sensors/gyro.c
- **INAV `common/calibration.c` (CONFIRMED)**: generic `zeroCalibrationStartV/AddValueV` with window (ms), std-dev threshold and an allow-failure flag; on exceeding, either restart or mark `ZERO_CALIBRATION_FAIL`; no settling delay; the source carries a TODO that an indefinite restart needs a safeguard. https://raw.githubusercontent.com/iNavFlight/inav/master/src/main/common/calibration.c . INAV's accel calibration uses it with a 5% tolerance ("Tolerate 5% variance in accelerometer readings") per the acceleration.c extract, though the extract was tagged by the fetcher as Cleanflight; the files are common ancestry. https://raw.githubusercontent.com/iNavFlight/inav/master/src/main/sensors/acceleration.c
- **Applicability**: shows the minimal viable design; note the lack of a timeout, which we must add (the boot may be moving while the user walks).

#### 2.3.2 Accelerometer weighting and rate-ignore (INAV)
- **How (CONFIRMED, INAV `imu.c`)**: `accWeight = imuGetPGainScaleFactor() * imuCalculateAccelerometerWeightNearness(...) * imuCalculateAccelerometerWeightRateIgnore(...)`. Nearness is a bell curve with `MAX_ACC_NEARNESS = 0.2` (0.8-1.2 g band). Weight dropped entirely if <= 0.001. Rate-ignore (`acc_ignore_rate`, `acc_ignore_slope`): weight ramps from 1 to 0 around the ignore rate, only when armed. Integral gated by `SPIN_RATE_LIMIT` = 20 deg/s; gyro drift integral clamped to `dcm_i_limit = radians(2.0) * (kp_acc + kp_mag)/2`. Fast gains: 10x for the first 20 s while disarmed. `imuCheckVibrationLevels()` only logs vibration; nothing uses it to weight (so vibration-based trust is not implemented in INAV). https://raw.githubusercontent.com/iNavFlight/inav/master/src/main/flight/imu.c
- **Betaflight (CONFIRMED, `flight/imu.c`)**: binary `imuIsAccelerometerHealthy` (0.9-1.1 g); `imuCalcKpGain`: armed Kp, 10x disarmed, 100x for 500 ms after disarm (quiet period 250 ms; reset if gyro > 15 deg/s). The comment says 20 percent in one place and code uses 10 percent. https://raw.githubusercontent.com/betaflight/betaflight/master/src/main/flight/imu.c
- **Applicability**: high. This is the soft, multiplicative form of what we have as a hard gate; plus the gyro-rate term. During edge changes the boot rotates fast and has angular acceleration; both "rate-ignore" and the weight product reduce accel trust there.
- **Implementation**:
```
w_near = bell(abs(|a|/g - 1), max=0.2)             # 1 at 1 g, 0 beyond 0.2
w_rate = ramp(|omega|, ignore=rate0, slope=s)      # 1 -> 0 around rate0
w = w_near * w_rate * (not clipped) * startup_boost
kp_eff = kp * w ; ki_eff = ki * w ; integrate bias only if |omega| < 20 deg/s
```

#### 2.3.3 Gyro overflow and glitch protection
- **CONFIRMED (Betaflight `gyro.c`)**: overflow trigger at 31980 (97.5% full scale), reset below 30340 (92.5%), cleared after 50 ms below; slew limiter rejects jumps greater than `1<<14` for certain ICM/MPU6500 parts; yaw spin recovery. In `accgyro_spi_mpu6000.c`, first raw gyro read equal to -1 on both axes triggers `FAILURE_GYRO_INIT_FAILED`. INAV additionally exposes a LULU impulse filter that runs first in its gyro filter chain (CONFIRMED in the INAV `gyro.c` extract: https://raw.githubusercontent.com/iNavFlight/inav/master/src/main/sensors/gyro.c).
- **Applicability**: a median/LULU-style first stage removes single-sample impulse spikes from snow impacts without adding the lag of a LPF; the all-ones read check is a free guard for an I2C bus fault.

#### 2.3.4 Dynamic notch / RPM filter
- **CONFIRMED** that INAV/Betaflight use FFT-derived dynamic notches (`gyroDataAnalyse`, `dynamicGyroNotchFiltersApply`) and an RPM filter, driven by motor harmonics.
- **Applicability**: low. A boot has no tonal source. A fixed LPF is enough for 50-100 Hz output. INFERRED: ski vibration is broadband; a 1 kHz debug capture (2.1.8) would confirm.

### 2.4 Crazyflie (Bitcraze)
- **Gyro bias (CONFIRMED, `sensors_bmi088_bmp3xx.c`)**: circular buffer of 512 samples; bias accepted when per-axis variance < `GYRO_VARIANCE_BASE` (100, raw LSB^2) and at least 1 s since last attempt; alternative light-weight path averages 1000 samples with no stillness check. Note a macro-name mismatch (`GYRO_GYRO_BIAS_LIGHT_WEIGHT` vs `GYRO_BIAS_LIGHT_WEIGHT`) the extract flagged. https://raw.githubusercontent.com/bitcraze/crazyflie-firmware/master/src/hal/src/sensors_bmi088_bmp3xx.c
- **Accel scale (CONFIRMED)**: after bias is found, average `|a|` in g over 200 samples; store `accScale` and divide subsequent readings; done once. This is the one-parameter scale calibration. Filters: 2nd-order `lpf2p` 80 Hz gyro, 30 Hz accel at 1 kHz.
- **MPU9250 path (CONFIRMED, `sensors_mpu9250_lps25h.c`)**: gyro +/-2000 dps, accel +/-16 g, accel DLPF 41 Hz, gyro DLPF 98 Hz, sample divider 0 (1 kHz), clock PLL on gyro X; self-test retried up to 300 times 10 ms apart; manufacturing test requires gyro bias found within 2 s and board level within 5 deg roll/pitch; variance threshold there is 50 over 1024 samples. https://raw.githubusercontent.com/bitcraze/crazyflie-firmware/master/src/hal/src/sensors_mpu9250_lps25h.c
- **MPU6050 self-test (CONFIRMED, `src/drivers/src/mpu6050.c`)**: discard 20 samples (comment: first values can read zero), baseline, enable all six self-test bits, wait, second measurement, difference compared with limits in `mpu6050.h` (values not seen); factory-trim reads are commented out; Y gyro limits are negated. Single attempt, no retry. https://raw.githubusercontent.com/bitcraze/crazyflie-firmware/master/src/drivers/src/mpu6050.c
- **Applicability**: the whole sequence is a good factory/EOL test for MPU6050-class parts and is cheap. Caveat: self-test response limits in the datasheet are loose (gyro 10-105 deg/s in Table 6.1 per extraction below), so it detects dead or cloned parts, not 0.1 deg/s bias.

### 2.5 x-io Fusion (the closest drop-in analogue)
- **Gyro offset (CONFIRMED, `FusionBias.c`)**: subtract current offset; if any corrected axis exceeds `stationaryThreshold` = 3.0 deg/s reset a timer; once still for `stationaryPeriod` = 3.0 s (timeout = period x sample rate), update `offset += corrected * 2*pi*0.02/sampleRate` (cutoff `CUTOFF_FREQUENCY` = 0.02 Hz; time constant about 8 s). https://raw.githubusercontent.com/xioTechnologies/Fusion/main/Fusion/FusionBias.c . README says the offset can be saved and restored (`FusionBiasGetOffset/SetOffset`) per the search extract (REPORTED, https://github.com/xioTechnologies/Fusion).
- **Acceleration rejection and recovery (CONFIRMED, `FusionAhrs.c`)**: threshold on the residual `0.5*sin(angle)` squared; accepted samples decrement `accelerationRecoveryTrigger` by 9, rejected increment by 1, clamped to `[0, sampleRate*rejectionTimeout]`; when it exceeds the threshold the accelerometer is forced back in until the trigger drains to 0. Rejection disabled by default. Startup gain ramp 10 -> 0.5 over 3 s (`INITIAL_STARTUP_GAIN`, `STARTUP_PERIOD`); gyro overrange at 0.98 x range triggers `SoftRestart()` that keeps the quaternion and re-enters startup. https://raw.githubusercontent.com/xioTechnologies/Fusion/main/Fusion/FusionAhrs.c
- **Applicability**: high. The recovery logic solves a failure we are likely to have with hard gating: if the filter has drifted and the gate is based on angular disagreement, it can lock itself out. For us the recovery would pull contaminated accel back in during a long carve, so use it with a ramped (small) weight and only after bias uncertainty has grown past a budget, not unconditionally (INFERRED).

### 2.6 ROS imu_tools
- **Complementary filter (CONFIRMED, `complementary_filter.cpp`)**: `getAdaptiveGain`: relative error `|a|-g|/g`; factor 1 below 0.1, linear 1 -> 0 between 0.1 and 0.2, 0 beyond; off by default. `checkState` steady-state test: |a| within 0.1 m/s^2 of g, per-sample gyro change under 0.01, gyro within 0.2 of current bias (units rad/s per source naming); bias update `b += 0.01 * (w - b)` while steady. https://raw.githubusercontent.com/CCNYRoboticsLab/imu_tools/rolling/imu_complementary_filter/src/complementary_filter.cpp
- **Madgwick (CONFIRMED, `imu_filter.cpp`)**: accel is normalized with no magnitude gate; zeta (gyro-drift gain) defaults to 0 so bias estimation is off by default; gain defaults to 0. Gradient step cites Madgwick's report equations 15, 21, 22, 34. https://raw.githubusercontent.com/CCNYRoboticsLab/imu_tools/rolling/imu_filter_madgwick/src/imu_filter.cpp
- **Applicability**: the three-condition steady-state test with a bias proximity test (|w - b| < 0.2 rad/s) is a nice cheap additional guard so that a slow, smooth rotation at 1-2 deg/s is not mistaken for rest.

### 2.7 VQF (Laidig and Seel) - highest-value discovery
Paper: arXiv 2203.17024 (Information Fusion 2023) https://arxiv.org/abs/2203.17024 ; docs https://vqf.readthedocs.io/ ; parameter table https://vqf.readthedocs.io/en/latest/ref_cpp_params.html
- **Idea 1 (CONFIRMED, paper section 2.3 and Fig. 5)**: rotate the measured acceleration into an "almost-inertial" frame using the strapdown gyro integration, then low-pass filter each component (second-order Butterworth, `tauAcc` default 3.0 s). In that frame accelerations due to velocity changes average out ("allowing for acceleration and deceleration to cancel out"), while the residual shows the drift of the frame; low-passing in the sensor frame "does not give a meaningful output".
- **Idea 2 (CONFIRMED, section 2.4)**: gyro bias is estimated in a single Kalman filter that does a rest update (small measurement uncertainty) or a motion update (large uncertainty, from accel/strapdown disagreement), with no magnetometer feedback.
- **Defaults (CONFIRMED, parameter table)**: `restMinT` 1.5 s, `restFilterTau` 0.5 s, `restThGyr` 2.0 deg/s, `restThAcc` 0.5 m/s^2, `biasSigmaInit` 0.5 deg/s, `biasForgettingTime` 100 s (uncertainty grows 0 -> 0.1 deg/s), `biasClip` 2.0 deg/s, `biasSigmaMotion` 0.1 deg/s, `biasSigmaRest` 0.03, `biasVerticalForgettingFactor` 0.0001 (unobservable vertical bias during motion), `tauAcc` 3.0 s. The table lists `biasSigmaRest` with the odd unit "0.03 deg" - treat unit as deg/s per its description.
- **Results (CONFIRMED, paper)**: on a collection including **BROAD** the 6D (no magnetometer) variant attained 1.1 deg average RMSE vs 2.4-6.3 deg for the literature methods, and the bias estimator compensated about 90% of bias (40-60% without rest phases). Note: the paper's metric (RMSE of orientation, average over trials) is not your median roll error, so the numbers are not directly comparable to 1.6 deg median.
- **Applicability**: very high, with a caveat. The almost-inertial low pass assumes accelerations average out. Slalom-like alternating turns (period perhaps 1.5-4 s, INFERRED) do average out for a 3 s time constant, but a long same-direction carve or sustained pitch on a steep fall line will leave a bias in the filtered vertical (the tilt shows the resultant, not true vertical). The bias-estimation machinery is portable regardless.
- **Implementation sketch** (6D):
```
# per sample, sensor frame -> inertial frame via gyro-integrated quaternion q_gyro
a_i  = rotate(q_gyro, a_b)
a_lp = butter2(a_i, tau=3.0)                 # slow, in almost-inertial frame
# inclination correction: rotate q_gyro so a_lp aligns with +z
dq   = shortest_arc(a_lp / |a_lp|, z_hat)    # small, applied gradually
q    = dq * q_gyro                           # corrected estimate
# bias: if at rest (|w|<2 deg/s, |da|<0.5 m/s^2 for 1.5 s) -> Kalman rest update w/ w_lp
#       else motion update from error of dq about horizontal axes only (vertical unobservable)
# clip bias to +-2 deg/s
```

### 2.8 Allan-variance and noise models
- **Kalibr (CONFIRMED, https://github.com/ethz-asl/kalibr/wiki/IMU-Noise-Model)**: model = bias (Wiener process) + white noise; YAML `gyroscope_noise_density`, `accelerometer_noise_density`, `*_random_walk`; read white noise at tau = 1 s and random walk at tau = 3 s of the Allan deviation; recommends about 15-24 h of static data; static Allan data is "optimistic" (excludes scale-factor and temperature-driven bias); for low-cost MEMS suggests inflating noise parameters by 10x or more; the discretisation scaling `sigma/sqrt(dt)` assumes proper low-pass decimation, not plain subsampling.
- **VINS-Mono (CONFIRMED, euroc config)**: example `acc_n` 0.08, `gyr_n` 0.004, `acc_w` 0.00004, `gyr_w` 2e-6, with the comment that more accurate parameters improve performance (https://raw.githubusercontent.com/HKUST-Aerial-Robotics/VINS-Mono/master/config/euroc/euroc_config.yaml). The file contains alternative commented values for lower-grade sensors; those are the right order of magnitude to start from but are not MPU6050 numbers.
- **Applicability**: if we move to a Kalman filter, set Q and R from a measured Allan curve of OUR sensor at ski temperatures and inflate. Allan test is a bench job: leave a boot-mounted unit running for hours at 0 C in a fridge and room temperature.

### 2.9 Ideas I looked for and did not find
- INAV/Betaflight temperature compensation of gyro: not implemented in the files read (`acc_temp_correction` is set but unused per the INAV extract). Not a source of ideas.
- ArduPilot `pretty_arm` style consistency checks for a single IMU: the extract did not contain them.

---

## 3. Cross-cutting design notes for a boot sensor

### 3.1 Using symmetry (INFERRED)
A ski turn pair is roughly mirror-symmetric. Lateral acceleration contaminates tilt with the sign of the turn. So over a left-right pair the contamination in roll cancels in a mean, and any residual mean roll offset is bias or mounting. This is the same property VQF's almost-inertial LPF exploits and is a cheap diagnostic ("left minus right mean should be zero at equal radius"). It also gives a physically meaningful analogue to ArduPilot's `badIMUdata` sign-consistency test.

### 3.2 Accelerometer in a turn is not wrong, it is a different quantity (INFERRED)
For a boot-relative-to-snow measurement the resultant force direction is arguably the more relevant reference than true vertical. Define both outputs: roll relative to gravity (gyro-propagated) and roll relative to resultant force (accel direct). The difference is a per-turn lateral-g estimate for free.

### 3.3 Cold and battery
- **Warm-up drift**: PX4 treats a more-than-3 deg/s bias change during warm-up as a hardware problem (above). The MPU-6000/6050 spec lists gyro zero-rate output (ZRO) initial tolerance +/-20 deg/s at 25 C and ZRO variation +/-20 deg/s from -40 to +85 C (Product Specification rev 3.2, Table 6.1; text extracted locally from the PDF at https://alliance.seas.upenn.edu/~medesign/wiki/uploads/Guides/MPU6050_datasheet.pdf). Those are worst-case spec limits, typical parts are far better; they are why a boot-time calibration is mandatory (REPORTED/CONFIRMED mix).
- **Linear acceleration sensitivity**: spec lists 0.1 deg/s per g (same table). At 2 g that is 0.2 deg/s of apparent rotation correlated with specific force, an error that grows exactly when cornering loads are high. Over a 3 s turn: about 0.6 deg (INFERRED arithmetic). A compensation `w_corr = w - S * a_lateral` is possible but requires per-unit S; likely below the noise for now.
- **Accel zero-g**: spec table lists zero-g initial tolerance in the tens of mg per axis; every 17.5 mg of offset is about 1 deg of tilt (asin(0.0175)). The text extraction of this table was garbled across columns so I do not quote the exact values; verify in the PDF section 6.2.
- **Brownout**: no source covers this directly. ArduPilot re-verifies critical registers and resets the FIFO on faults (2.9 below); applying that to a cold-sagging battery is INFERRED.

### 3.4 Lever arm and angular acceleration (REPORTED, secondary)
Paper "On the effects of angular acceleration in orientation estimation using IMUs" (https://arxiv.org/abs/2502.03681, abstract only read): linear acceleration produced by rotational acceleration is not merely disturbance; it changes the filter dynamics and introduces non-minimum-phase behaviour; Mahony and Madgwick can attenuate it only at the cost of bandwidth. The sensor on the boot cuff rotates about the ankle; at edge change high angular acceleration is expected (INFERRED). Use the PX4-style angular-acceleration estimate (`IMU_DGYRO_CUTOFF` path) as a weight term.

### 3.5 Quaternion integration quality
PX4's `imu_down_sampler.cpp` composes delta angles as quaternions rather than summing rotation vectors, and rotates the accumulated delta velocity into the latest body frame (CONFIRMED: https://raw.githubusercontent.com/PX4/PX4-Autopilot/main/src/modules/ekf2/EKF/imu_down_sampler/imu_down_sampler.cpp). If our firmware reduces 1 kHz to 100 Hz on the MCU, use the same trick; summing vectors costs coning error during vibration.

---

## 4. MPU6050 / MPU6000 / MPU9250 lessons

| Topic | What the community/firmware does | Source and label |
|---|---|---|
| Bus | MPU-6050 is I2C only (400 kHz); MPU-6000 has SPI at 1 MHz for all registers and 20 MHz for sensor and interrupt registers | Datasheet rev 3.2 Overview text, CONFIRMED (PDF at upenn link above) |
| ArduPilot bus policy | Uses SPEED_LOW for init, FIFO reset, register checks; SPEED_HIGH otherwise; fast sampling (8 kHz gyro, DLPF 256) enabled only on SPI; register checks run every 200 reads on I2C vs 20 otherwise | CONFIRMED, `AP_InertialSensor_Invensense.cpp` https://raw.githubusercontent.com/ArduPilot/ardupilot/master/libraries/AP_InertialSensor/AP_InertialSensor_Invensense.cpp |
| Betaflight bus | MPU6000 SPI max 20 MHz; detection at 1 MHz | CONFIRMED, `accgyro_spi_mpu6000.c`, `accgyro_mpu.c` |
| DLPF register values | `DLPF_CFG` 0..6 = 256, 188, 98, 42, 20, 10, 5 Hz gyro bandwidth | CONFIRMED names, i2cdevlib `MPU6050.h` https://raw.githubusercontent.com/jrowberg/i2cdevlib/master/Arduino/MPU6050/MPU6050.h |
| DLPF delays and Fs | gyro 188 Hz 1.9 ms, 98 Hz 2.8 ms, 42 Hz 4.8 ms, 20 Hz 8.3 ms; accel 184 Hz 2.0 ms, 94 Hz 3.0 ms, 44 Hz 4.9 ms, 21 Hz 8.5 ms; gyro output 8 kHz when DLPF is 0/7 and 1 kHz otherwise | REPORTED (a driver comment reproducing the register map, via search results: https://git.boxtec.ch/boxtec/witty/src/branch/Reto/MPU6050.cpp ; Sming doc https://sming.readthedocs.io/en/stable/_inc/Sming/Libraries/MPU6050/index.html). I could not open the InvenSense register map PDF myself. |
| ArduPilot DLPF choice | `BITS_DLPF_CFG_188HZ` unless fast sampling; MPU6000 has no accel DLPF2 so a software filter `set_cutoff_frequency(1000, 188)` is applied; `SMPLRT_DIV` = 0 | CONFIRMED, Invensense driver |
| Crazyflie MPU9250 | accel DLPF 41 Hz, gyro DLPF 98 Hz, 1 kHz, then software 30/80 Hz lpf2p | CONFIRMED, `sensors_mpu9250_lps25h.c` |
| Clock source | PLL on gyro axis (ArduPilot Z `BIT_PWR_MGMT_1_CLK_ZGYRO`; Betaflight `MPU_CLK_SEL_PLLGYROZ`; Crazyflie X). Internal 8 MHz oscillator avoided. | CONFIRMED |
| Temperature conversion | raw/340 + 36.53 for MPU6000/6500; MPU9250 uses 21.0 offset | CONFIRMED, ArduPilot driver; datasheet lists 340 LSB/C sensitivity (CONFIRMED) |
| FIFO corruption | ArduPilot checks raw temperature against a cached value with thresholds 400 and 800 to detect misaligned FIFO reads; resets FIFO; 10 resets within 10 s raises an internal error; over-full FIFO reads limited (comment: more than 32 samples can produce corrupt samples) | CONFIRMED, Invensense driver |
| Register corruption | `setup_checked_registers(13, ...)` re-verifies config registers periodically; mismatches counted | CONFIRMED, Invensense driver |
| Revision differences | MPU6000 rev C has different gyro scaling than rev D (ArduPilot special-cases rev C4/C5 accel as 8 g / 4096 LSB/g); Betaflight `mpu6050FindRevision()` returns `MPU_HALF_RESOLUTION` for revision 1 and fails for unknown revisions | CONFIRMED, drivers |
| WHO_AM_I | MPU6050 should read 0x68. Reports of boards reading 0x72 (and rejecting init), 0x70 (usually MPU6500 class), 0x98 (possibly ICM-20689) from clones or different dice; some boards reading 0x68 still misbehave | REPORTED, https://eevblog.com/forum/projects/counterfeit-mpu6050-chips-from-china and search summary; no authoritative list found |
| Genuine-chip marking | ArduPilot ties support to WHO_AM_I whitelist (`MPU_WHOAMI_6000`, `_6500`, `_MPU9250`, `_MPU9255`) and refuses unknown | CONFIRMED, Invensense driver |
| Self-test | MPU6050: gyro response X 10..105, Y -105..-10, Z 10..105 deg/s (Table 6.1); accel limits in Table 6.2. Crazyflie procedure in 2.4 | Gyro limits read in datasheet text, CONFIRMED (column order reconstructed); accel limits not read |
| Gyro noise spec | total rms noise 0.05 deg/s (FS_SEL=0 per spec table), 0.033 deg/s at DLPF 100 Hz, spectral density 0.005 deg/s/rtHz at 10 Hz | Datasheet rev 3.2 Table 6.1; column alignment reconstructed from extracted text; verify |
| Temperature behaviour | Hysteresis and a discontinuity near 32 C reported by users in InvenSense forum threads | REPORTED (see 2.1.3) |
| Product-level hardware advice | MPU6000 widely described as vibration-tolerant and robust; modern FC sensors listed as ICM42688, BMI270 | REPORTED (search-result summary of ArduPilot forum "Hardware to avoid" thread https://discuss.ardupilot.org/t/hardware-to-avoid-when-building-your-first-multirotor/114014 , not read directly) |

### Practical configuration recommendation for the PoC (INFERRED)
- Internal rate 1 kHz (DLPF on, `SMPLRT_DIV` 0), DLPF 3 (42 Hz) if output after MCU decimation is 100 Hz or below, or DLPF 2 (98 Hz) if you decimate with an averaging stage; do not read at 100 Hz from an 8 kHz/256 Hz-bandwidth stream, you will alias.
- Burst-read the FIFO and integrate, rather than polling single samples; verify raw temperature continuity as ArduPilot does.
- Accel +/-8 g or +/-16 g: 16 g reduces clipping on impacts (2048 LSB/g, cost 6 mg resolution). Keep +/-16 g until a 1 kHz capture shows otherwise. Gyro +/-500 dps likely enough, but Betaflight/Fusion overrange logic needs the range as a parameter so keep +/-1000 as a safe start.
- For the product move to an SPI part (MPU6000 class or newer) for timing determinism and FIFO throughput; the MPU6050 is I2C only.

---

## 5. Ranked list: ten ideas most worth porting

Ranking weighs expected roll-accuracy gain under 1-2 g cornering, robustness in cold and vibration, and implementation cost.

| # | Idea | Origin | Why it helps us | Cost |
|---|---|---|---|---|
| 1 | Low-pass the accelerometer in an almost-inertial (gyro-rotated) frame, tau about 3 s, apply as slow inclination correction instead of / on top of hard magnitude gating | VQF (2.7) | Turn accelerations cancel over alternating turns; recovers a usable gravity reference through cornering where the 0.9-1.1 g gate leaves us gyro-only | Medium; one second-order filter on 3 axes + small quaternion correction |
| 2 | Rest-driven gyro bias estimation (stationary 1.5-3 s at < 2-3 deg/s and accel jitter < 0.5 m/s^2) with a Kalman/leaky update (cutoff about 0.02 Hz, clip to +/-2 deg/s) | VQF, Fusion, PX4 ZeroGyroUpdate, ROS | Lift rides, queues, boot-up; makes bias observable every run; dominant error in gyro-only turns | Low |
| 3 | Soft accel weight = near-1g bell * rate-ignore ramp * not-clipped; integral learning only when |w| < 20 deg/s; startup gain boost 10x decaying over 3-20 s | INAV, Betaflight, ArduPilot DCM, Fusion | Smoother than binary gate, handles edge-change angular acceleration, standard values to start from | Low |
| 4 | Clip/overrange detection per axis (+/-16 g, 97.5% gyro FS with 92.5% reset) with veto of accel correction and a per-turn quality tag; vibration metric `0.99*prev+0.01*|a_k - a_{k-1}|` | PX4, ArduPilot, Betaflight, Fusion | Snow impacts alias and clip; quality flags let the app discount bad turns instead of showing wrong numbers | Low |
| 5 | Bench thermal calibration of gyro bias (3rd/lower-order polynomial about a fixed reference temperature, bin 100 samples and 0.5 C, clamp to calibrated range) with temperature stored alongside runtime calibration | ArduPilot INS_TCAL, PX4 | Cold start to body-warm drift is large for MPU6050 class (spec +/-20 deg/s worst case); reduces what the online estimator must absorb | Medium (bench rig) |
| 6 | Boot-time gyro calibration with accel-based motion rejection, two-pass agreement, best-effort fallback and "calibration quality" output | ArduPilot, Betaflight, Crazyflie, INAV | Already partly in repo (still-pose); adding explicit convergence criterion (0.1 deg/s) and a failure state prevents silent bad zero | Low |
| 7 | Chip integrity layer: WHO_AM_I whitelist, periodic re-check of CONFIG/GYRO_CONFIG/ACCEL_CONFIG/PWR_MGMT_1, raw-temperature continuity to catch FIFO misalignment, gap > 0.1 s resets integrators, all-0xFF/-1 read guard, |a| ~ 1 g sanity | ArduPilot, Betaflight, Crazyflie | Clone chips and cold-battery brownouts silently corrupt data; cheap to detect | Low |
| 8 | Sampling pipeline: 1 kHz internal with DLPF at about 42-98 Hz, FIFO burst, integrate to delta-angle/velocity (quaternion-composed) and then emit at 50-100 Hz | ArduPilot, PX4, Crazyflie | Removes aliasing of boot chatter into tilt; deterministic timing for BLE | Medium |
| 9 | Accel scale and offset online calibration with pose-diversity gate and bounds (scale 0.8-1.2, offset <= g); fall back to single-scale `|a|=1g` fit | ArduPilot AccelCalibrator, Crazyflie | Corrects the +/-3% scale class errors and clone rev differences; protects against fitting from a narrow pose set | Medium |
| 10 | Report confidence: bias-uncertainty sigma and thresholds (bias converged if sigma < 0.15 deg/s, tilt aligned if tilt sigma < 5 deg), per-turn gated fraction and quality, and derive noise parameters from a measured Allan curve inflated about 10x | ArduPilot EKF3, Kalibr | Lets the app say "don't trust this run"; gives principled tuning if we move to a Kalman filter | Medium |

### Honorable mentions
- Fusion-style recovery trigger so the filter cannot lock out accel forever (use ramped weight, see 2.5).
- LULU/median impulse stage before low-pass (INAV).
- Gyro g-sensitivity compensation (0.1 deg/s/g spec) if bench shows it matters.
- Debug 1 kHz capture mode for field FFT (ArduPilot batch sampling).
- Self-test at manufacture (Crazyflie sequence) to screen out dead or fake parts.
- Left/right symmetry diagnostic (3.1).

### What I would not port
FFT/RPM dynamic notch filters, multi-IMU consistency checks and lane switching, GPS-derived centripetal compensation, barometer-vertical-innovation `badIMUdata` logic (no independent sensor), magnetometer machinery (not in scope and unreliable near binding hardware).

---

## 6. Gaps and unverified items
- InvenSense register map (DLPF table) and thermal-chamber threads could not be fetched; DLPF delays are REPORTED only.
- PX4 `at_rest` detection logic not read; ArduPilot `get_clip_limit()` value not seen.
- Unit of PX4 bias persistence threshold (0.01) and Betaflight `gyroMovementCalibrationThreshold` (48) not confirmed.
- INAV `acceleration.c` extract was tagged by the fetcher as Cleanflight; treated as INAV-lineage code.
- MPU6050 accelerometer zero-g tolerance and temperature coefficient: table text garbled, not quoted.
- No flight-controller source I read quantifies behaviour at sustained 1-2 g lateral for hours; extrapolations are INFERRED and need bench/slope validation on BROAD plus your own ski data.
