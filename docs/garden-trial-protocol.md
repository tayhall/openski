# Garden trial protocol (first run on the new hardware)

Status: a plan, written 18 October 2026 before the kit arrived. Nothing here has been run. Every threshold is a starting value to revise once there is data, and every result goes into [bench results](bench-results.md) as a dated entry. The framing stays experimental: these trials check the hardware, the clocks and the data path, and look at the distributions of the turn metrics. They do not validate on-snow technique scoring.

Principle: few manual steps, each one producing a defined file. Use the exports and the health report to judge a trial, not memory or eyeballing.

## Setup

- **Boots:** left boot `xiao-left-mag` (XIAO ESP32S3, LSM6DSOX, LIS3MDL; hostname `ski-xiao-l`), right boot `xiao-right` (XIAO ESP32S3, LSM6DSOX; hostname `ski-xiao-r`). Both sample at 104 Hz and are not resampled.
- **Phone:** in the ski jacket pocket, so it is the upper-body reference. Screen off during trials unless a step says otherwise.
- **Firmware and app:** built from the same `main`. The first flash of each XIAO is by USB (it carries the 8 MB partition table); later updates go over the air. Check the serial output at boot for `IMU ready: LSM6DSOX`, the recorder line (`... at 104 Hz`) and, on the left unit only, the magnetometer. Without a serial link, `GET /api/v1/mag` reports whether the LIS3MDL answered (`present`), the latest raw counts and gauss (uncalibrated), the sample count and read errors; a build without the magnetometer answers `"built":false`. With a boot connected, the Test lab "Boot sensors" card also shows the live reading.
- **Battery:** confirm polarity with a meter before the first connection, use a protected cell, and note the capacity. Battery sense is a 2 x 100 kohm divider with 100 nF on D0 (GPIO1), ratio 2.0 ([battery diagnostics](battery-diagnostics.md)); check `/api/v1/battery` against a multimeter at the start and end of the soak.
- **App:** pair both boots, set the mounting for each, run the six-face check in Bench tools and save the correction per sensor before any trial that uses roll.

## Trials

Each trial lists what to do, what to look at, and a starting pass line. Export the session ZIP after each one.

**T0. Bench soak (10 minutes, boots lying still, phone beside them).**
Look at: the Details tab health card and `session-health.json`. Pass: both boots usable; sample rate within about 1 Hz of 104 (flash) and 52 (live); no gaps; `stalls` is 0 in `/api/v1/acquisition`; clock slope between each boot and the phone under about 100 ppm; ping round-trip times recorded in `clock-sync.csv`. Also note the gyro bias and its drift over the 10 minutes from the raw samples, the temperature range, and the battery reading against a multimeter (it should agree within about 50 mV; a larger error points at the high-impedance divider).
Run `python tools/analysis/still_noise_report.py <export.zip>` for the rate, bias, drift and Allan noise figures (the export carries no temperature, so the temperature range has to come from elsewhere).

**T1. Stomp sync (2 minutes, both boots worn).**
Stand, wait 5 s, stomp both boots together once on a hard floor, stand 90 s, stomp again as you stop. Look at: `gesture-sync.json` and the Details line. Pass: status `measured`; the stomp offset and the ping offset agree to within about 15 ms. A larger, steady disagreement is the half-connection-interval ping bias described in [clock sync design](clock-sync-design.md), which is exactly what this trial exists to measure; record it with the connection interval if known. If the aligner refuses, record the reason and try a harder stomp or a firmer surface.

**T2. Turn on the spot and magnetometer (5 minutes).**
Turn through full circles on the spot, slowly, left then right, several times, with the left boot at the usual stance. Look at: `magnetometer.csv` (uncalibrated counts and gauss) and the heading-turn frames. Pass: the field magnitude stays roughly constant through a level turn (a spread under about 10 to 15 percent of the mean is a first sanity line), and the magnetometer heading follows the gyro yaw. Record the field magnitude with and without the boot on the foot to see what the buckles and battery do. This trial decides whether absolute heading is worth building.
Run `python tools/analysis/mag_heading_check.py <export.zip>` for the magnitude spread, hard-iron and soft-iron fit, and the heading against gyro yaw.

**T3. Roll drills on foot (about 20 minutes, both boots live).**
Run two of the drills from the Today or Path tab, 20 repetitions each, once smooth and once deliberately rough (jerky, uneven, one boot late). Look at: `turn-metrics.csv` and the `turn_metrics` block. Pass lines are about direction, not numbers: rough repetitions should show lower `usable` share or higher `roughness`, a lower left-right derivative similarity, and a larger wedge fraction than smooth ones. Record the per-boot distributions before choosing any threshold.

**T4. Phone as upper-body reference (during T3).**
With the phone in the jacket pocket for T3, look at `phone-motion.csv`. Pass: `phone_clock_check` offset near zero (the sensor clock shares the elapsed-realtime base); about 100 rows a second with the screen off for the whole trial; no storage error. Note battery drain over 30 minutes. Compare the phone gyro against the boot roll rate for smooth and rough repetitions (torso quietness against boot motion). Also look at `torso-metrics.csv` and the `torso_metrics` block (experimental torso quietness, total torso tilt against boot roll (sideways and fore-aft together, not lateral roll), torso yaw against boot heading per candidate; definitions in [turn metrics](turn-metrics.md), "Torso metrics"). This is observation only: the torso figures are unvalidated, pocket placement is unverified and the phone-to-boot timing carries the uncertainty the block reports, so `trial_report.py` shows them as INFO (WARN when refused or when the timing is unmeasured), never PASS or FAIL. Record the figures for smooth and rough repetitions before choosing any line.

**T5. Run flow dry run (one short run, garden or stairs).**
Use the 'ready at the top' flow: stomp when asked, make about ten turns, stop and wait for the auto end. Look at: the run saving, the flash download passing validation on both boots, the boots returning to the mode they started in, the run GPS track, and the stomp at the end being found (it is 20 s or more before the recording ends). Pass: no data loss; both downloads validated; `gesture-sync.json` measured.

**T6. Mount slip and strap check.**
After T3 to T5, check the Analysis tab's mount-slip line for each boot. Pass: no slip flagged. If flagged, retighten and repeat the roll drill.

## Judging a trial

One command per trial, on the exported ZIP: `python tools/analysis/trial_report.py export.zip --trial T1` (T0 to T6, or `all`). It prints one row per check: the measured value, the starting pass line from this document and a verdict. PASS and FAIL mean the value is on the right or wrong side of the line; WARN means measured but marginal or the recording does not suit the check (for example the still-noise figures on a recording that is moving); N/A means the export cannot show it, with the reason (stalls, the battery against the multimeter, the temperature range, the run GPS track and the phone battery drain are checked by hand); INFO is a figure the protocol gives no line for, to copy into the bench-results entry. The exit code is 1 when any selected check FAILs (a tool error while judging a trial counts as a FAIL) and 2 when the export cannot be read at all, and `--json out.json` writes the same table. T3 needs both sessions for the direction checks: `--trial T3 --compare smooth.zip rough.zip`. With `--trial all` the checks that make no sense for the recording are N/A; with one trial named they FAIL or WARN, because that trial should have produced the matching recording. All lines are the starting values in constants at the top of the script; change them there when real data says so.

`python tools/analysis/trial_report.py --regress <dir>` runs the tool over the dress-rehearsal ZIPs and asserts the verdicts each fault should give (it exits 1 if an expectation is not met, which means the tool or the exporter changed). It also runs the tool's own selftest on mutated copies of `clean.zip` (null fields, blank phone elapsed column, no magnetometer overlap, a still soak and so on); `trial_report.py --selftest DIR` runs that part alone. The ZIPs are not committed. To regenerate them, from `android/`:

```
$env:OPENSKI_DRESS_REHEARSAL_DIR = "D:\path\to\dress-rehearsal"
.\gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "com.openski.android.GardenDressRehearsalTest"
```

## Before going on snow

- T0 to T5 pass with no stalls, and the ping-versus-stomp disagreement is measured and understood.
- Every flash download validated; nothing needed a second attempt.
- Phone motion delivered at about 100 Hz with the screen off, or the limits noted.
- The cold test and tilt checks still open in [POC plan](poc-plan.md) are done or consciously deferred.
- A dated entry per trial in [bench results](bench-results.md), with the raw ZIPs kept.

## Dress rehearsal (synthetic, runs on the JVM)

Before any of the trials above, one synthetic garden session with known truths goes through the real analysis and `SessionExport`: `SyntheticGardenSession` (test sources) builds about 185 s of two boots (flash record format 3 at 104 Hz with quality flags, live frames at 52 Hz with connection-event jitter, each boot on its own clock with a known offset and ppm skew, one wrapping variant), stomps at 5 s and 25 s before the end, 20 alternating half-turns with the right boot 60 ms late, ping pairs (connect burst, start burst, one a minute, optional asymmetric-delay bias), phone rows at 100 Hz, magnetometer rows at 10 Hz (left boot, hard-iron offset) and the events and health snapshots the recorder writes. Every offset, skew, stomp time, dropped run, clipping share and missing source is a `GardenParams` argument. `GardenDressRehearsalTest` unzips the export and compares it with the truths, then runs the fault cases (missing end stomp, stomp on one boot only, clock reset, clock wrap, missing right boot, no phone, no magnetometer, no pings, flash gap with and without live cover, live only, heavy clipping, ping bias, and a right-boot live stream 40 ms late (`rightLiveExtraDelaySec`), which biases the right boot's analysis timeline but not its clock, so the boot-to-boot alignment must remove it: the aligned median onset lag is about 60 ms, the unaligned one about 100 ms; and both boots up for hours, 3 and 5 clock wraps before the recording, so their 32-bit sample clocks and 64-bit ping clocks differ by whole wraps). `trial_report.py` shows the alignment the T3 left-right agreement used as the row "left-right alignment" (INFO when stomp or pings were applied, WARN when nothing was applied or stomp and ping disagree; experimental, never a FAIL). Torso metrics: the default phone rows sway only a little about the vertical, so the clean session's torso block is computed with no tilt and null yaw comparisons. A separate run with a known torso (`GardenTorso`: sideways lean 0.4 of the boot roll and no fore-aft pitch, heading 0.5 of the boot heading, phone at an arbitrary angle in the pocket, both boots turning 40 degrees per half-turn; off by default, so the other ZIPs are unchanged) is written as `torso.zip`, and `fault-phone-clock-unchecked.zip` keeps the phone rows but drops the clock-check event; with `fault-no-phone.zip` it must refuse.

```
cd android
.\gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.GardenDressRehearsalTest"
# also keep the ZIPs (clean.zip and fault-*.zip) for offline tools:
$env:OPENSKI_DRESS_REHEARSAL_DIR = "D:\path\to\dress-rehearsal"
.\gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "com.openski.android.GardenDressRehearsalTest"
```

Use the environment variable: a system property set on the Gradle command line does not reach the forked test JVM. `cleanTestDebugUnitTest` is needed because Gradle does not treat the variable as a task input and would otherwise skip an up-to-date test run. Synthetic data only: a pass says the pieces fit together, not that the thresholds suit real boots.

Findings from the first run (18 October 2026): the stomp aligner lost the start window when a boot's 32-bit microsecond clock wrapped mid-session, because live samples were not unwrapped (fixed in `GestureSync.series`); the ping map is a few milliseconds off at the middle pairs but about 11 ms off at the last single one-a-minute ping, inside its reported 13 ms uncertainty; a live-only session (no flash) refuses the stomp as `low_correlation` when the stomp rings at 18 Hz, since the 52 Hz live stream aliases it differently on each boot.

## Not covered

On-snow behaviour, vertical-load calculations (rows 5, 6 and 12 of the [starter calculations](turn-metric-starters.md)), and any combined score. Those wait on a weight-shift test with scales and on real recordings.
