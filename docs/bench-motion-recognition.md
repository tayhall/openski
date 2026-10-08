# Handheld movement recognition

The S3 evaluates each new IMU sample with `bench_rules_v3`. Read
`http://ski-s3.local/api/v1/motion` or use its current IP address.
This is a preliminary rule-based recognizer, not a trained model or a ski-turn
classifier. Labels use the MPU6050 sensor axes, not calibrated ski axes.

It reports positive/negative rotation about X, Y or Z, a stationary candidate,
acceleration change or mixed motion. Rotation requires at least 0.35 rad/s on
the dominant axis and dominance over the other axes. Rotation and acceleration
labels require 80 ms of sustained evidence; stationary candidates require
300 ms. Labels describe sustained recent motion and may lag a movement change
by that dwell time. Stationary candidates can include steady straight-line
travel because an IMU cannot distinguish it from rest. These thresholds require
validation against hand-labelled recordings.

Stationary candidates require angular speed below 0.12 rad/s, acceleration
magnitude between 7 and 13 m/s², and a vector deviation below 0.25 m/s² from
a slowly adapting baseline. This avoids rejecting a stable sensor solely for
accelerometer offset; it does not correct that offset or establish true rest.
The baseline follows each new sample with a 0.05 smoothing factor. The endpoint
retains the last 16 label changes with sequence numbers and sensor timestamps;
pollers can detect missed events when the sequence gap exceeds this history.

Version 3 also retains the last 16 completed rotation gestures in
`recent_gestures`. Each includes sequence, axis, signed integrated angle,
start/end sensor times, duration, peak angular speed and axis fraction.
These short-excursion angles are gyro estimates, not validated boot angles.
There is no gyro-bias correction in this version.

Gesture detection smooths gyro readings with a 25 ms time constant, starts
above 0.28 rad/s and completes after 250 ms below 0.16 rad/s. Accepted gestures
require at least 8 degrees net rotation, 150 ms duration, 65% of angular travel
on one axis and 70% directional consistency. Established reversals split
excursions. Sample gaps over 100 ms discard an unfinished excursion; movement
longer than eight seconds is rejected. The endpoint reports rejected attempts
and sample gaps. Counts reset on reboot. Pickup/put-down can still produce a
valid rotation; these are not automatically labelled as intentional gestures.

`tools/test_gesture_tracker.cpp` exercises the same portable tracker used by
firmware: opposite 45-degree excursions, tremor rejection, mixed-axis rejection,
sample loss, timestamp wrap, ring history and continuous reversals. Compile it
with a C++17 compiler and `lib/Motion/src` on the include path.

The first captured sessions and provisional interpretation are preserved in
[`../data/bench/README.md`](../data/bench/README.md). Wi-Fi snapshots are sparse
and not synchronized to precise human labels, so they are development examples,
not a scored classifier evaluation or full-rate training data.

Keep the board and MPU6050 secured together; move the assembly rather than
pulling the wires. Use the board's axis markings to make repeatable movements.

1. Rest the assembly on a firm surface for five seconds.
2. Rotate back and forth around one marked axis, ten repetitions, pausing
   between movements. Repeat slowly and then at a moderate pace.
3. Repeat for the other two axes.
4. Slide the assembly forwards and backwards while keeping orientation fixed.
   The recognizer should report acceleration changes or mixed motion, not
   claim a forward/backward displacement.
5. Include intentionally mixed rotations and pauses as negative examples.

Record video with spoken labels, including three distinct starting rotations
for synchronization. Existing Android test-session event markers and raw-data
export can provide labelled data. Handheld data validates recognition of these
motions; boot-mounted recordings and snow validation are still needed for ski
interpretation. Gravity removal, calibrated mounting axes and gyro-bias
compensation are not part of this first recognizer.

## Tilt recognition (`tilt_v1`)

The rate-threshold gesture tracker above integrates bursts of gyro movement, so
a slow or hesitant return can fall under its stop threshold and be rejected.
`tilt_v1` measures absolute tilt from gravity instead, fused with the gyro, and
reports a movement when the tilt leaves and returns to the neutral pose. Both
trackers run; the old one stays until `tilt_v1` has been compared with it on
recorded movements. The result is in the `tilt` object of
`/api/v1/motion`.

- **Fusion.** A gravity vector is carried forward by the gyro and pulled toward
  the accelerometer with a 0.5 s time constant, only while the accelerometer
  reads within 8% of 1 g. Fast movement therefore follows the gyro, and gyro
  drift is removed whenever the sensor is quiet.
- **Neutral pose.** Captured automatically after one second of stillness
  following boot (still means under 0.12 rad/s and within 5% of 1 g). Hold the
  sensor in the pose to treat as zero while it starts. `POST
  /api/v1/motion/zero` recaptures it at any time. There is no per-run
  calibration step.
- **Movement.** Starts when the tilt passes 20°, ends when it returns inside 10°.
  Reported with the peak tilt, the signed angle about the dominant sensor axis
  (positive is the sensor rotating in the right-hand sense, as for the gyro) and
  the axis fraction. Rejected: under 150 ms, or held away from neutral for more
  than 15 s. A sample gap over 100 ms abandons an excursion and re-seeds.
- **Speed independence.** The angle comes from the pose, not from how fast the
  sensor moved.
- **What it cannot see.** Rotation about the axis that is vertical in the
  neutral pose does not change gravity and is never reported. The response gives
  that axis as `vertical_axis` (for example `+x`), or `none` when the neutral
  pose is not aligned with a sensor axis.
- **Fixed correction.** `include/SensorCalibration.h` holds the per-unit values,
  applied only to what the recogniser sees (raw BLE, recorder and `/api/v1/imu`
  samples are unchanged): the six-face accelerometer offset and scale and the
  60 s still gyro bias from [bench results](bench-results.md) of 7 October 2026.
  They are enabled for the `esp32-s3-supermini` environments. The gyro bias
  varies with temperature and settling by a few tenths of a degree per second;
  the accelerometer term removes that drift from the tilt, so it is not
  re-measured each run.

**Axes.** `x` points toward the mounting-hole edge, `z` toward the way the
right-angle header pins exit the board (measured: +10.04 m/s² with the wires
pointing up), and `y` follows from the right-hand rule (derived, not yet
measured).

**Verification.** A Python model of the same algorithm, run on synthetic
movements at 100 Hz with the measured bias, reported 44.9° to 46.1° for 45°
rotations about each axis, at 0.3 s and 4 s durations and with 2 m/s² of
vibration, and nothing for yaw about the vertical axis, a 10° wobble or a 20 s
hold. `tools/test_tilt_tracker.cpp` repeats those cases against the firmware
header and passes with GCC on the host. The firmware builds for `esp32-s3-supermini`. It has not been run on the board or
compared with recordings of real movements, and the Y axis calibration rests on
two bench tilt readings.

## Half-turn recognition (`ski_v0`)

`ski_v0` runs beside `tilt_v1` and is built for a sensor on the boot cuff, with the board's length up the leg. It separates **roll** (lean about the forward axis) from **pitch** (forward flex) relative to a pose captured by `POST /api/v1/motion/zero` or the app's Zero button, so a skier who holds 20° of forward flex all run still produces roll events. A half-turn runs between zero crossings of roll and is reported if its peak reaches 8°; very short ones (under 150 ms) are counted as rejected.

- **Mounting map.** `include/AppConfig.h` says which sensor axis points up the leg, forward and sideways (`OPENSKI_MOUNT_*` build flags). The defaults are assumptions for the first mounting; confirm them and flip the lateral sign on the other boot.
- **Fusion.** The accelerometer pull fades out as |a| leaves 1 g and is zero beyond 15%, so hard turns follow the gyro. Samples above 2.5 g, below 0.3 g or with a gyro reading above 12 rad/s are skipped and the state is held.
- **Expected values** (general ski mechanics, not yet measured here): cuff roll ±10° to ±25° on easy runs and ±30° to ±50° carving; roll rate 60 to 200 °/s at an edge change; forward flex 10° to 30° beyond the zeroed stance; a half-turn lasting 0.5 to 1.5 s. Events outside the envelope are flagged, not dropped.
- **Slope.** A slope does not change the lean of the leg relative to vertical in a fall-line stance, but a traverse adds an offset on one side. Symmetric turns show it as unequal left and right peaks.
- **Verification.** `tools/test_ski_tracker.cpp` and `tools/test_ski_frames.cpp` run synthetic ski-like traces and golden frame bytes on the host. They show the code matches the model, not that the model matches a skier; that needs the Phase 1 and 2 runs in the [POC plan](poc-plan.md).

Build and run on the host: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_tracker.cpp` (and `test_ski_frames.cpp`).
