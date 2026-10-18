# Garden trial protocol (first run on the new hardware)

Status: a plan, written 18 October 2026 before the kit arrived. Nothing here has been run. Every threshold is a starting value to revise once there is data, and every result goes into [bench results](bench-results.md) as a dated entry. The framing stays experimental: these trials check the hardware, the clocks and the data path, and look at the distributions of the turn metrics. They do not validate on-snow technique scoring.

Principle: few manual steps, each one producing a defined file. Use the exports and the health report to judge a trial, not memory or eyeballing.

## Setup

- **Boots:** left boot `xiao-left-mag` (XIAO ESP32S3, LSM6DSOX, LIS3MDL; hostname `ski-xiao-l`), right boot `xiao-right` (XIAO ESP32S3, LSM6DSOX; hostname `ski-xiao-r`). Both sample at 104 Hz and are not resampled.
- **Phone:** in the ski jacket pocket, so it is the upper-body reference. Screen off during trials unless a step says otherwise.
- **Firmware and app:** built from the same `main`. The first flash of each XIAO is by USB (it carries the 8 MB partition table); later updates go over the air. Check the serial output at boot for `IMU ready: LSM6DSOX`, the recorder line (`... at 104 Hz`) and, on the left unit only, the magnetometer.
- **Battery:** confirm polarity with a meter before the first connection, use a protected cell, and note the capacity. The battery divider pin is not defined yet, so battery percentage is not expected to work on the XIAO builds.
- **App:** pair both boots, set the mounting for each, run the six-face check in Bench tools and save the correction per sensor before any trial that uses roll.

## Trials

Each trial lists what to do, what to look at, and a starting pass line. Export the session ZIP after each one.

**T0. Bench soak (10 minutes, boots lying still, phone beside them).**
Look at: the Details tab health card and `session-health.json`. Pass: both boots usable; sample rate within about 1 Hz of 104 (flash) and 52 (live); no gaps; `stalls` is 0 in `/api/v1/acquisition`; clock slope between each boot and the phone under about 100 ppm; ping round-trip times recorded in `clock-sync.csv`. Also note the gyro bias and its drift over the 10 minutes from the raw samples, and the temperature range.

**T1. Stomp sync (2 minutes, both boots worn).**
Stand, wait 5 s, stomp both boots together once on a hard floor, stand 90 s, stomp again as you stop. Look at: `gesture-sync.json` and the Details line. Pass: status `measured`; the stomp offset and the ping offset agree to within about 15 ms. A larger, steady disagreement is the half-connection-interval ping bias described in [clock sync design](clock-sync-design.md), which is exactly what this trial exists to measure; record it with the connection interval if known. If the aligner refuses, record the reason and try a harder stomp or a firmer surface.

**T2. Turn on the spot and magnetometer (5 minutes).**
Turn through full circles on the spot, slowly, left then right, several times, with the left boot at the usual stance. Look at: `magnetometer.csv` (uncalibrated counts and gauss) and the heading-turn frames. Pass: the field magnitude stays roughly constant through a level turn (a spread under about 10 to 15 percent of the mean is a first sanity line), and the magnetometer heading follows the gyro yaw. Record the field magnitude with and without the boot on the foot to see what the buckles and battery do. This trial decides whether absolute heading is worth building.

**T3. Roll drills on foot (about 20 minutes, both boots live).**
Run two of the drills from the Today or Path tab, 20 repetitions each, once smooth and once deliberately rough (jerky, uneven, one boot late). Look at: `turn-metrics.csv` and the `turn_metrics` block. Pass lines are about direction, not numbers: rough repetitions should show lower `usable` share or higher `roughness`, a lower left-right derivative similarity, and a larger wedge fraction than smooth ones. Record the per-boot distributions before choosing any threshold.

**T4. Phone as upper-body reference (during T3).**
With the phone in the jacket pocket for T3, look at `phone-motion.csv`. Pass: `phone_clock_check` offset near zero (the sensor clock shares the elapsed-realtime base); about 100 rows a second with the screen off for the whole trial; no storage error. Note battery drain over 30 minutes. Compare the phone gyro against the boot roll rate for smooth and rough repetitions (torso quietness against boot motion). This is observation only; no torso metric is built.

**T5. Run flow dry run (one short run, garden or stairs).**
Use the 'ready at the top' flow: stomp when asked, make about ten turns, stop and wait for the auto end. Look at: the run saving, the flash download passing validation on both boots, the boots returning to the mode they started in, the run GPS track, and the stomp at the end being found (it is 20 s or more before the recording ends). Pass: no data loss; both downloads validated; `gesture-sync.json` measured.

**T6. Mount slip and strap check.**
After T3 to T5, check the Analysis tab's mount-slip line for each boot. Pass: no slip flagged. If flagged, retighten and repeat the roll drill.

## Before going on snow

- T0 to T5 pass with no stalls, and the ping-versus-stomp disagreement is measured and understood.
- Every flash download validated; nothing needed a second attempt.
- Phone motion delivered at about 100 Hz with the screen off, or the limits noted.
- The cold test and tilt checks still open in [POC plan](poc-plan.md) are done or consciously deferred.
- A dated entry per trial in [bench results](bench-results.md), with the raw ZIPs kept.

## Not covered

On-snow behaviour, vertical-load calculations (rows 5, 6 and 12 of the [starter calculations](turn-metric-starters.md)), and any combined score. Those wait on a weight-shift test with scales and on real recordings.
