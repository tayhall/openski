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
