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

Exports preserve calibration metadata and a boot-roll.csv trace including drift age. Next: validate fixtures and real skiing, improve turn segmentation against labelled video, and establish a snow reference before claiming edge angle. Ski heading versus trajectory, with suitable positioning data, can later support a confidence-based carved/steered/mixed classifier.
