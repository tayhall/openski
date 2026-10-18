# Ski angles and turn timing

## Definitions

Turn size/rhythm and ski–snow interaction are separate dimensions. Short parallel turns can be carved, steered/skidded or mixed. Cadence alone cannot classify carving.

- Edge angle: angle between the ski running surface and the local snow surface.
- Boot roll: orientation of the boot about its longitudinal axis in a stated reference frame. It is not automatically snow-relative edge angle.
- Attack angle: angle between the ski's longitudinal direction and its velocity, projected onto the snow surface. Small sideways slip supports carving; high edge angle alone does not prove it.
- Inclination: body lean into a turn. Angulation: joint movements that let edging differ from whole-body lean. Two boot sensors cannot reconstruct those body measurements.

[Reid et al., Alpine Ski Motion Characteristics in Slalom](https://pmc.ncbi.nlm.nih.gov/articles/PMC7739813/) defines ski reference angles and examines carving and skidding. Its idealised geometric relationship is R = R_sidecut × cos(edge angle). It is a model, not an actual trajectory measurement. For a 15 m sidecut ski, 30°, 45° and 60° give approximately 13.0, 10.6 and 7.5 m. Snow deformation, ski loading and flex complicate this model. No universal angle threshold establishes carving or skill level.

[PSIA's performance guide](https://thesnowpros.org/download/Alpine_PG_10_21.pdf) relates edging and rotation to intended turn outcomes rather than fixed angle targets.

## Current experimental timing implementation

Session review uses existing filtered gyro lobes on a user-selected mounting axis. Choose one boot and the direction sign; both boots are never added together into one turn count.

An interval is measured between consecutive, opposite-sign lobe onsets. This is a repeatable candidate timing measurement, not a directly observed edge-change transition. The final lobe has no complete onset-to-onset interval. Same-sign motions, intervals outside 0.5–20 seconds, more than 0.5 seconds between lobe end and next onset, and sample gaps over 0.25 seconds invalidate an interval. These are engineering gates inherited from the experimental detector, not skiing standards.

Reported measurements:

- Median valid interval in seconds.
- Cadence = 60,000 / mean interval in milliseconds.
- Timing variation = population standard deviation / mean × 100, requiring at least three intervals.
- Mean intervals for each rotation direction, mapped to left/right using the mounting sign.
- Mean two-interval cycle for adjacent alternating intervals. Cycles overlap to use all available complete pairs.

A 1 second interval gives 60 candidates/min and a 2 second left–right cycle. This describes rhythm, not radius. Even for circular turns, duration depends on radius, speed and swept direction angle: T ≈ R × change_in_heading_radians / speed. Low timing variation indicates consistency, not necessarily quality. Bench movements, walking and non-skiing motions can still trigger candidates.

## Before displaying angle estimates

1. Establish sensor-to-boot/ski axes and mounting signs for each sensor. A boot cuff can move relative to the ski; document the mounting location.
2. Measure stationary gyro bias and validate known static roll angles against a fixture.
3. Fuse three-axis gyro and acceleration into orientation, accounting for sample timestamps, gaps, saturation and restarts. Dynamic acceleration must not be treated as gravity.
4. State the reference frame. Gravity-relative orientation requires a different reference from the local snow surface. Slope and changing travel direction prevent a constant roll correction from establishing true edge angle.
5. Validate dynamic motion and on-snow orientation against independent measurements/video. Six-axis IMUs lack an absolute heading reference; yaw drift matters for attack-angle estimates.

[Snyder et al., boot-IMU validation](https://onlinelibrary.wiley.com/doi/10.1080/17461391.2021.1970236) found promising edge-angle estimates but substantial variability; its simulator results do not establish accuracy on snow or validate our hardware/algorithm.

## Validation recordings

### Force-based reference

[Vaverka, Vodickova and Elfmark (2012), Kinetic Analysis of Ski Turns Based on Measured Ground Reaction Forces](https://www.researchgate.net/publication/221723820_Kinetic_Analysis_of_Ski_Turns_Based_on_Measured_Ground_Reaction_Forces) studied 30 carved slalom turns by six experts on a 26° slope using binding force sensors at 100 Hz. Mean turn duration was 1.344 s (range 0.980–1.680 s), with 0.487 s initiation and 0.858 s steering. Their phase boundary used measured force versus m × g × cos(slope angle).

These are study observations, not coaching targets or carving thresholds. OpenSki cannot substitute boot acceleration for binding force. Use this as a reference for future phase validation, without imposing its timing proportions on our detector.

Record stationary and walking controls, then annotated skiing with short steered, short carved, longer carved and mixed turns. Include both directions, mounting details, ski sidecut radius, slope/snow context and video timestamps for actual edge changes. Compare event precision/recall, timing error and angle error. Do not tune and evaluate on the same recordings.

## Implemented orientation review

Session detail now accepts a two-second stationary neutral reference per boot, with a signed sensor axis toward the toe. It rejects missing samples, excessive motion, unsuitable acceleration and a forward axis close to vertical. The reference estimates constant gyro bias and a boot basis. It belongs to that session; changing either boot's alignment offset clears the references.

The estimator integrates an orientation quaternion using all three gyro axes and exact incremental rotations. It reports roll/pitch about the neutral boot basis and relative yaw, plus angular rates in that basis. Acceleration corrects tilt only after two seconds of low rotation and near-gravity acceleration. This is a heuristic rest detector, not proof of rest: sustained acceleration can fool it. Stationary corrections do not establish yaw; yaw remains relative and drifts. After a gap, detected rest recovers tilt and a new yaw origin. The estimator does not infer the snow plane, pressure or carving.

Test mode also learns an oblique boot-forward axis from a controlled pure roll gesture using the principal gyro direction. A toe-direction hint resolves its sign. See [bench validation](bench-validation.md) for workflow, dry-movement segmentation, labels and video overlay.

Missing intervals over 250 ms invalidate the orientation until detected rest. Time since the last reference is shown as a drift-age indicator, not a numerical uncertainty estimate. Mounting changes, gyro saturation, temperature bias and long motion can invalidate the estimates. Video and fixture validation remain outstanding.

Normalised profiles interpolate absolute boot roll at 21 bins from 0–100% of valid candidate intervals, then average separately for each rotation direction. Each direction shows the peak of its mean curve and its phase. These are not the mean of individual peak values. Profiles require calibration coverage and reject gaps over 100 ms; direction comes from the selected turn-axis/sign setting. The timeline still uses experimental gyro onsets, not actual edge changes.

### Mount-slip check (experimental, replay only)

`MountSlipMonitor` (Kotlin, ported from the research repo's `MountMonitor`) asks whether the strap or sensor moved after calibration. In the calibrated boot frame it compares, per 4 s window (2 s step), the principal horizontal gyro axis (window mean removed, so a constant bias is not motion) and the mean horizontal specific force with the run's own baseline.

- **Baseline:** the first five windows with turning motion about the forward axis (horizontal gyro RMS of at least 0.1 rad/s and principal axis within 30 degrees of forward), so walking or a lift ride after calibration cannot set it. Without five such windows the check reports "unavailable".
- **Checked windows:** those with motion whose axis is within 45 degrees of the baseline. Moving windows further away are another activity (walking pitches about the lateral axis) and are counted as skipped. The cost is that a yaw slip over 45 degrees is not flagged either.
- **Flag:** the axis moved more than 8 degrees, or the mean force moved more than max(0.15 g, twice the baseline spread), in two consecutive checked windows. Persistence keeps a load or terrain transient from flagging, and delays a real flag by one step.
- **Frame:** only the latest calibration of each boot is used, from its time to the end of the session. Near-rail samples and windows with gaps over 250 ms are excluded.
- **Output:** one line on the Analysis tab (time is after calibration) and a `mount_slip` object per boot in `analysis-summary.json`, always present, with `available:false` and a reason when there is no calibration or no baseline.

Evidence: the research repo's simulation flagged 8 degrees of yaw slip 6 of 6 (about 9 s after it happened), 25 to 40 degrees within 2 s, but 15 degrees about the forward or lateral axis only 2 to 3 of 6. Our unit tests use synthetic signals too. It is not validated on a boot, it does not measure the slip angle, and the live path and coach do not use it. Treat a flag as "re-calibrate before trusting roll".

### Heading-rate candidates (experimental, firmware and replay)

Heading-rate lobes are a second candidate set alongside roll-lobe timing. Heading rate is the rotation rate about the vertical, `omega . up`, with `up` taken from the same gravity estimate as the orientation tracker. Each lobe is reported with its start, end, duration, peak rate and heading change in degrees. The rate is smoothed with a 0.25 s time constant, so lobes are confirmed that much later than they happen. On the firmware, `SkiTracker` feeds the tracker from `omega . up_` and sends each lobe as frame version 4 on the movement characteristic (additive; the coach and run flow ignore it); the Android app decodes those frames live and replays the same detector over stored samples. Neither set is ski turn classification and neither is validated on a boot.

Transfer caveat: the research that motivates this signal (see [research findings](research-findings.md)) used a calf-worn phone at 10 Hz on 10 skiers, not a boot IMU, and reports timing good to about half a second in that setup. Detector defaults are untuned guesses until compared with labelled video.

Replay: `BootRoll.headingRateRadps` is computed in the shared orientation tracker as `omega . g`, with `g` the current vertical in sensor coordinates, so a leaned boot still reads the rotation about the true vertical and streaming and replay stay equivalent. `HeadingTurnAnalysis` runs the same detector as the firmware over those rates (breaking at orientation segment changes). The Analysis tab shows one line: heading-rate candidates (count, median duration) next to the roll-lobe count.

Export: `heading-turns.csv` (`side,direction,positive_heading_rate,start_ms,end_ms,duration_ms,peak_heading_rate_dps,heading_change_deg,detector`, detector `experimental_heading_rate`; direction is fixed: a positive heading rate (right-handed rotation about world-up, counter-clockwise seen from above) is LEFT and a negative one RIGHT, independent of mounting and of the per-boot roll polarity `positive_left` that `detected-movements.csv` uses). `analysis-summary.json` gains per-side `heading_turns` (`count`, `median_duration_ms`, `cadence_per_minute`, the last null below two turns) and a `heading_detector` note carrying the experimental and transfer caveats.

### Turn metrics export (experimental)

`turn-metrics.csv` and the `turn_metrics` block of `analysis-summary.json` export the shape descriptors from `docs/turn-metrics.md` for each roll-lobe candidate (build time, early roll rate, peak phase, roughness, edge hold, entry roll, overlapping heading change, yaw shape/closure/lead) with a `usable` flag, and boot-to-boot agreement (derivative similarity, roll-curve correlation, wedge fraction) per left/right candidate pair. They describe dry-ski boot roll only. They are exported so they can be inspected on real recordings, but they are still unvalidated (tested on synthetic signals only) and are not turn classification, edge angle or a quality score. The CSV starts with `#` comment lines; skip them when parsing. Raw samples are untouched.
Exports preserve calibration metadata and a boot-roll.csv trace including drift age. Next: validate fixtures and real skiing, compare roll-lobe candidates against heading-rate candidates on labelled video from both boots, and establish a snow reference before claiming edge angle. Ski heading versus trajectory, with suitable positioning data, can later support a confidence-based carved/steered/mixed classifier.

## Recording health report (screening aid)

`SessionHealth` (`session-health.json` in the export; summary in the Details tab) answers "is this recording usable?" from the stored samples. It is read-only: raw samples are never altered. Each boot gets one entry per source (live and flash are never merged), a verdict of usable, marginal or poor per source, a boot verdict (its best source, since graphs prefer flash) and a session verdict (its worst boot).

Figures per boot and source:

- `rate_hz`: intervals divided by the time they span within continuous segments, gaps included. `median_rate_hz` is the median interval as a rate, and `interval_sd_ms` is the spread of the non-gap intervals. Nominal is 50 Hz live (BLE is throttled) and 100 Hz flash. Live intervals come from sensor timestamps, not phone receipt, so BLE bursts do not appear as jitter.
- `gap_count`, `gap_total_ms`, `longest_gap_ms`, `gap_percent`: an interval over 1.5 times the nominal interval is a gap; its length is the interval minus nominal. Segments break (no gap counted) at a live receipt gap over 30 s, a sensor clock reset, or a new flash capture, so a disconnection is not measured here; the Analysis tab coverage figure covers the session timeline.
- `dropped_flag_count`: samples carrying the dropped-before flag (flash records; bit 16). `firmware_gap_flag_count`: live samples with the firmware timestamp-gap bit. `capture_dropped_samples` is the count reported by the recovery download.
- `accel_near_rail_percent`, `gyro_near_rail_percent`: share of samples flagged within 8 counts of full scale. Evidence of possible clipping, not proof. Legacy flash records (record format 2) carry no flags, so these read zero for them; zero there means unknown.
- `clock_slope_ppm` (live only): least-squares slope of phone receipt time against the sensor clock (rollover handled by `LiveSensorClock`), minus 1, weighted by segment length; segments under 60 s are ignored. It mixes the sensor oscillator, the phone clock and BLE delay variation, which alone can show about 100 ppm over ten minutes, so treat it as a rough indicator of skew and not a calibration. Positive means the phone clock runs faster.
- Temperature: reported as not available, since neither the live frame nor the flash record carries it.

Verdict thresholds are named constants in `SessionHealth` and are starting values, not validated on snow: rate shortfall 5% / 20%, gap time 2% / 10%, dropped flags 0.5% / 5%, near-rail samples 0.5% / 5%, clock slope 300 / 2000 ppm (marginal / poor), and fewer than 100 samples is poor. The file lists them. A usable verdict does not mean the data is a good ski recording, and a poor one may still contain usable stretches.
