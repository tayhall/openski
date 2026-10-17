# Starter calculations for turn metrics (hypotheses to tune on our data)

Companion to [turn metrics](turn-metrics.md). These are candidate calculations and starting parameters, to be tried on our own two-boot recordings and the public ski data ([datasets](skivids/sensor-research/06-open-datasets.md)). **Every number here is a starting value, not a validated threshold.** Where our data disagrees, our data wins, and the change is recorded in [bench results](bench-results.md).

The calculations are small and physically motivated, and each can be re-derived from skiing physics. Trained weights, regression coefficients and population-calibration constants are out of scope: ours come from our own data. Everything stays experimental dry-ski boot data, not on-snow technique scoring.

## Status key

Built = in `TurnMetrics.kt` today. Not built = described only. Limit = what the cuff-mounted MPU-6050 pair can and cannot support.

| # | Calculation | Starter definition | Status | Limit |
|---|---|---|---|---|
| 1 | Turn boundary from yaw | Start and end from yaw-rate sign changes. Look for the matching roll event in a window widened by about 30%. Roll that lags yaw, rather than leading or coinciding, suggests late or reactive edging. | Not built (we segment by roll and heading-rate lobes) | Needs yaw from the gyro and a gravity-aligned heading; we have `HeadingTracker`. |
| 2 | Robust roll rate | 3-sample rolling mean of roll angle, differentiate, summarise with the **median**, not the mean. Use medians for any per-turn summary. | Partly (we use means in `earlyRateDps`) | Switch to median and compare. |
| 3 | Left/right agreement | Resample both boots' roll-rate series to a common time grid, take the **derivative** of each, then **cosine similarity** between the two derivative sequences. Compare with the roll-curve Pearson already built. | Partly (Pearson on roll curves) | Needs boot clocks aligned. The boots have independent clocks, so use the alignment estimate and report its uncertainty; a few tens of ms of skew matters on a turn lasting a second. |
| 4 | Wedge percentage | Fraction of samples where one boot's roll is positive while the other's is negative. Parallel skis have the same sign. | Not built | Both boots must use the same sign convention (the per-boot `positive_left` polarity differs). |
| 5 | Tilt-compensated vertical acceleration | Smooth acceleration and roll **before** combining, with a 3rd-order Butterworth low-pass at about 6 Hz. Then project onto vertical using the current roll (`a_v = a_boot cos(roll) + a_other sin(roll)`). | Not built | Cuff mount and no snow plane, so noisy. See calculation 6. |
| 6 | Weightless fraction | Share of the turn with tilt-compensated vertical acceleration below about 0.25 g, averaged over both boots. A large share suggests an unweighted or floaty transition (excluding deliberate hops and powder). | Not built | Same limit as 5. Treat as a later target; set the threshold from our own recordings. |
| 7 | Per-turn roll re-baseline | Do not score absolute roll against a fixed "good angle". Re-centre each turn's roll on its own entry value, and correct for slope pitch when it is known. | Partly (shape metrics use the turn's own neutral-to-neutral window) | Slope pitch needs GPS or a fitted plane; we have run GPS but it is not fed into the roll metrics. |
| 8 | Turn shape | Peak yaw-change rate divided by the turn's average yaw-change rate. Near 1 is a round, even rotation; a high ratio is one sharp pivot with a flat remainder. | Not built | Yaw rate only. |
| 9 | Turn closure | Range (max minus min) of the **first half** of the turn's yaw trace. Front-loaded rotation scores high; direction change crammed into the back half scores low. | Not built | Yaw only, one boot. |
| 10 | Edge hold | Median edge (roll) angle over the middle 10 to 80% of the turn, divided by the **square root of the peak** roll. Rewards a sustained edge over a brief spike. | Not built | **Do not confuse with `buildMs`**, which is the time from 10% to 80% of peak. They are different metrics. Name this one `edgeHold`. |
| 11 | Good-versus-excellent compression | Raw differences shrink as skill rises, so any combined score should saturate per metric (log-like), fitted to our own distributions. | Not built | Applies only if a combined score is added. |
| 12 | Weight transfer timing (ours) | The moment weight crosses from one boot to the other, as a fraction of the turn, with the question of whether it sits near the mid-point. | Not built | Needs vertical load per boot (5). The crossing time, where the two signals swap dominance, may be more robust than absolute load because it needs no absolute calibration. |

## Further candidates (not yet specified)

- **Stem angle** from roll and yaw alone; **turn-start and transition checks**, not just mid-turn; **turn-path consistency** across consecutive turns.
- **Frequency signature:** a clean carve and a chattering skid differ in spectrum, which sets the sample-rate floor. Our 100 Hz recording is above the turn-scale needs; confirm on real data before changing the rate.
- **Fore-aft acceleration** as its own axis, not folded into vertical or lateral.
- **Angulation** (edge angle against body inclination) and **counter-rotation** (phone yaw against boot yaw) need the phone as a third sensor.
- **Speed control** and **slope pitch** are GPS-based and cannot come from a boot IMU alone, because double-integrating acceleration for speed drifts within seconds. Run GPS already supplies speed and drop.
- **Activity state** (turning, lift, standing, walking) by counting zero-crossings of the differenced left and right signals, as a cheap rhythm detector.

## Hardware consequences

- The dual-boot, 6-axis, 100 Hz design is consistent with all of the above.
- The risk is not poor-versus-good turns; it is the smaller good-versus-excellent gap, which a noisy or thermally drifting IMU can swallow. Our Cranfield MPU-6050 analysis ([temperature note](skivids/docs/mpu6050-temperature.md)) found gyro bias shifting by about 1 degree per second across a temperature sweep, far larger than the instantaneous noise. Per-turn re-baselining (7) and rest-bias learning (already in firmware) matter more than a chip change.
- Vertical-load calculations (5, 6, 12) are the ones most likely to need better placement, a slope reference or a different sensor before they can be trusted.

## How to use this

1. Pick calculations 2, 3 and 7 first: they need only data we already record.
2. Compute each on real recordings and the Beuken ski data, and look at the distributions before fixing any parameter.
3. Check each moves in the expected direction on labelled smooth and rough repetitions.
4. Record the final parameter values and the data they came from.
