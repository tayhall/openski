# Bench → Dry Ski → Snow validation

## Current indoor POC

The dashboard's Developer / Test Session controls use the existing foreground recording service and flash recovery. New test starts capture immediately, including the stationary calibration lead-in. Calibrate test boot uses the latest two saved seconds; START TEST marks the beginning of the actual movement trial. PAUSE excludes the interval until the next START TEST without stopping raw recording. Tests appear with a TEST label in history.

Without sensors, press Explore a demo to create a 66-second synthetic session with 40 alternating movements per boot. It exercises the actual calibration, orientation, detection, graph, label evaluation and export pipeline. DEMO remains visible even if its title is changed; synthetic reference labels are not presented as human observations. It supplies no fabricated battery/RSSI readings or video.

Live roll, pitch and relative yaw use the same estimator as saved-session review. Gyro integration follows sensor-clock intervals rather than notification arrival intervals; wall-clock alignment remains approximate. The mounting-gesture calibration waits for two seconds of rest before resuming live orientation.

Operator markers capture phone wall time at the tap, before the database write. They are observations with human reaction and BLE alignment uncertainty, not exact physical ground truth. Session review can add video-reviewed markers at the selected session time, assign them to either/both boots, add notes, seek to them or delete mistakes.

Test sessions include raw acceleration/gyro, derived boot roll/pitch/relative yaw, boot-axis angular rates, calibration metadata, labels and sensor-health snapshots in exports. Battery is blank when unavailable. RSSI is polled approximately every five seconds when the BLE operation queue is idle; snapshots include observation times so old readings are distinguishable from fresh ones. Current firmware does not provide battery level.

## POC 1: coordinate frame and fixture tests

1. Mount each unit rigidly to the boot shell, with its PCB axes documented. A boot cuff can flex relative to the ski; prefer a repeatable rigid location and record it in notes. A ski/boot fixture gives a better reference than an unconstrained leg.
2. Connect sensors and press New test. Place the ski flat on a known reference plane, hold neutral for at least two seconds, and calibrate each boot separately. Select the signed sensor axis pointing approximately toward the toe. Stationary calibration determines gravity and gyro bias, not heading.
3. For an oblique mount, perform five seconds of pure side-to-side rolling and press Learn mounting from pure roll gesture. The principal gyro axis refines the boot-forward direction; the previous axis/sign selection resolves direction. Mixed pitch/yaw, inadequate movement and inconsistent axes are rejected. This does not replace independent mounting checks.
4. Check known roll angles such as 0°, ±15°, ±30° and ±45° with a fixture/protractor. Repeat at several pitch angles. Verify sensor and boot signs separately. Note expected and observed angles rather than treating this list as skiing targets.
5. Check fore/aft fixture rotations and controlled relative yaw. Pitch describes boot orientation, not center of pressure or body balance. Yaw is an integrated relative heading, has no compass reference, drifts, and resets its heading origin after a gap/reinitialisation. Rest helps tilt but cannot determine yaw.
6. Hold stationary and repeat identical angle sweeps after several minutes. Test disconnect/reconnect, sensor restart and saturation. A gap invalidates orientation until a detected stationary interval; segment IDs are exported.

Record video and a fixture reading for each test. Synthetic unit tests verify maths and failure handling, but do not establish accuracy of the real sensor, mounting or rest detector.

## POC 2: labelled dry movements

1. Start recording video with both boots visible. Keep a neutral stationary lead-in and calibrate both boots. Use a separate camera/phone if the test phone is needed for observer buttons; background recording remains available.
2. Tap SYNC at an unmistakable visible movement and record its meaning in the notes. Three deliberate movements provide a recognisable signature, but one clearly identified corresponding event is used for alignment.
3. Return to neutral, then choose 20 Slow, 20 Medium or 20 Brisk. Each starts a labelled trial, counts completed movements from the first fresh, calibrated neutral boot (left preferred), and marks PAUSE at 20. Pace is the test intention, not an enforced speed or measured skiing classification. Manual START TEST / PAUSE is also available. Define one movement as neutral → lean to one side → return to neutral, so a left/right pair contains two movements. Stopping/restarting the service ends the in-memory guided counter; recorded samples and markers remain stored.
4. An observer taps LEFT/RIGHT at movement onset and TRANSITION on return through neutral. Tap PAUSE between sets, return to neutral before START TEST, and include smooth/abrupt, asymmetric, pitch/yaw, vibration and stationary controls. These are simulated movements, not ski turns or real chatter.
5. Stop and keep sensors powered until recovery completes. Attach the video in session review.
6. Choose Align video using a SYNC marker and enter the timestamp of the matching visible event in video seconds. The app saves video offset = video timestamp − session marker elapsed time. This is manual correspondence, not automatic video recognition. Check alignment against later visible events to detect drift; one offset cannot correct different recording clock rates.
7. Select Estimated boot roll/pitch or Relative yaw in the graph. Video playback includes a live on-screen orientation overlay; the original video is not modified or exported with burned-in telemetry.
8. Add video-reviewed labels after scrubbing to actual movements. These usually give a better reference than observer taps. Use LEFT/RIGHT sign consistently, and choose the boot under review.

## Detector and comparisons

Dry-ski detection operates on calibrated roll, independently from the existing gyro-lobe candidates. A 100 ms time-constant filter and 8° entry / 3° neutral thresholds detect completed lean excursions lasting 0.3–20 s. Gaps above 100 ms, unobserved neutral starts and incomplete endings are excluded. These thresholds are test settings, not textbook edge-angle requirements. START TEST / PAUSE gates prevent calibration and sync gestures from inflating counts.

Each completed movement shows its direction, onset, duration in the edged state and maximum estimated boot roll. Normalised test profiles average absolute roll over those completed movements at 5% phase bins. Mean duration and mean peak-roll ratios compare directions after at least two movements each; 100% means equal means, not perfect technique. A full ski turn duration is a different measurement.

Browse detected movements opens a selectable list and seeks to a movement's onset. Compare both boots reports mean signed-frame roll difference, correlation and median right-minus-left onset/neutral-return lags with one-to-one movement pairing. Angle interpolation rejects gaps over 100 ms and orientation-segment boundaries, and requires bracketing samples within 50 ms. Save each boot's review axis and direction before exporting; the settings, detections and summary are retained in the ZIP. Correlation needs varying angles and at least three paired samples. These comparisons do not measure pressure or technique quality.

Label evaluation uses closest-error, one-to-one matching with a ±500 ms tolerance and matching direction/boot. It reports matched/missed labels, unmatched detections in the annotated window and signed/absolute mean error. Neutral-return labels are evaluated separately. Evaluation is limited to the first-to-last relevant label window, expanded by the tolerance; it is not a whole-session false-positive rate. Threshold tuning needs separate evaluation recordings.

The sampling check reports raw live/flash counts, median sample interval, effective rate including gaps, gaps over 100 ms and duplicate times. Overlapping flash captures remain separate raw data and can duplicate timestamps. Raw exports retain those sources for investigation.

## POC 3: snow validation

Validate boot-to-ski orientation and real turn boundaries on snow after the indoor checks. Carve/skid classification needs an independent reference to sideways slip, not just a large roll angle or quick cadence. Snow alone does not give us ski pressure: direct load validation still requires suitable force/pressure instrumentation. Radius validation needs a trajectory reference, such as sufficiently accurate GNSS or calibrated video.

The debug build, thirty JVM unit tests and Android lint pass. SQLite migrations from versions 1–6, demo creation, review settings, recovery safeguards and exports passed instrumentation on the Pixel 9a Android emulator; dashboard/demo review was exercised there too. Physical BLE, screen-off recording/reconnection, camera/video timing and real boot/sensor accuracy remain hardware acceptance checks.
