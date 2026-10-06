# OpenSki proof-of-concept plan

Status as of 6 October 2026. The goal is a trustworthy basic pipeline before any on-snow data exists: two boot sensors, a repeatable mounting, a roll measurement we can check against known angles, and drills that score what we can actually measure. Claims about carving, edge angle on snow and turn quality wait for snow data (see [ski analysis](ski-analysis.md)).

## Where we are

| Area | State |
|---|---|
| Sensors | MPU-6050 (GY-521 breakout) on an iSpindel 4.0 PCB with an 18650 and charger board. Running on a classic **ESP32 DevKit V1** for now (default PlatformIO environment `esp32-devkit-v1`, SDA 21 / SCL 22). |
| Next hardware | ESP32-S3 Super Mini. The S3 has no GPIO22, so `kImuSclPin` must change and the board may need its own PlatformIO environment. Not started; do it when the board arrives, then repeat Phase 0. |
| Firmware | 100 Hz IMU (±1000 °/s, ±8 g, ~42 Hz filter), 50 Hz BLE live stream, ~10 minutes of full-rate flash recording per boot. No battery level reported yet. |
| App | Training path with drills on demo boots or a live boot; Geek mode keeps raw telemetry, test lab, flash recovery and session replay/export. The live drill flow has not been run on real hardware yet. |

## Decision: mount at the top of the cuff, near the calf

We are mounting each sensor on the outside of the boot cuff, just below the top buckle, rather than on the lower shell. Reasons: it is away from snow, ice, crud and the other ski, it is easier to strap on, and it is nearer to where the skier's body drives the turn.

What this means for the data:

- **Orientation.** The long axis of the iSpindel board runs up the leg. The "toward the toe" axis the app asks for is therefore the axis across the board's width that faces forward, in the direction the shin faces. It is not the board's length. Neutral standing puts gravity along the long axis, which the calibration accepts (the forward axis only has to be roughly horizontal).
- **Forward flex is both a signal and a confound.** The cuff pitches forward and back relative to the ski by tens of degrees. That is useful information (see [Flex and load](#flex-and-load-as-signals)), but the estimator must also keep it from leaking into roll. It tracks pitch separately from roll, and Phase 1 measures any cross-talk directly.
- **What the roll number means.** It is lower-leg lateral lean about the forward axis. It includes the ski's roll plus any lateral give in the boot, cuff cant and ankle motion. Treat it as "cuff roll", not as ski edge angle. The app still labels it "boot roll"; renaming it is a follow-up.
- **Slip.** A strap can rotate or slide, which changes the calibration silently. Calibrate before every run and compare a neutral stance afterwards.
- **Known trade-off.** Less ski vibration and chatter reaches the cuff than the shell, so any future carve-versus-skid cue based on vibration will be weaker from this position.

Supersedes the "rigid location" advice in [bench validation](bench-validation.md) for this POC. Keep the position fixed and recorded for every session.

### Mounting record (copy into session notes)

Boot model and size, left/right sensor code (last four characters of its address), position (distance below the top buckle, side of the cuff), board orientation (which axis faces forward, which way is up), strap type and tension, firmware commit, and a photo.

## Flex and load as signals

A cuff sensor sees how far the shin has been driven forward into the boot. That is worth capturing, not just filtering out.

**What it can measure**

- **Shin angle and flex.** Cuff pitch relative to the calibrated neutral stance, and its rate of change. This relates to fore/aft balance and to how hard the skier is pressing the tongue.
- **Flex timing and rhythm.** When flex builds and releases against each turn, and the range between turns.
- **Left/right difference.** Flex depth and timing per boot, including a weaker side.
- **Dynamic load changes.** The accelerometer shows the leg absorbing or extending. Treat it as a rough proxy for load building and unloading, not a force measurement.

**What it cannot measure**

- **Static weight shift.** Moving weight between skis changes force, not acceleration, so an IMU cannot see it. Real load needs pressure sensing such as an insole or force sensor, which is a later hardware step.
- **Shin angle relative to the ski.** Pitch is relative to gravity. Slope angle adds an offset that neutral calibration absorbs but that changes between traverse and fall line. A second sensor on the ski or lower shell would be needed.

**Boot stiffness is context, not a calibration.** Record the boot model and its flex index in the mounting record. Typical bands, per retailer guides:

| Skier level | Men's flex index | Women's flex index |
|---|---|---|
| Beginner | 70 to 90 | 60 to 70 |
| Intermediate | 80 to 100 | 70 to 80 |
| Advanced | 90 to 120 | 80 to 100 |
| Expert | 100 to 130 | 90 to 110 |
| Racing | 140 to 150 | 120 to 140 |

The index is **not standardised between manufacturers**, so one brand's 100 is not another's, and cold weather makes the plastic noticeably stiffer. A stiffer boot should give less pitch travel for the same effort, but compare pitch range per boot and per temperature rather than trusting the number. Sources: [Ski Boot Flex: What's Best for You](https://www.the-house.com/portal/ski-boot-flex-whats-best-for-you/), [Ski Boot Flex Number Ratings Chart](https://theproskiandride.com/blogs/news/what-ski-boot-flex-ratings-really-mean-and-how-to-choose-the-right-one).

**Planned drill:** "Flex and return", scoring pitch depth, tempo and left/right balance. It is easy to check in the garden. Not built yet.

## Phases

### Phase 0: bench, no boots

1. Flash both boards with the current firmware over USB, then confirm OTA works. Label each case with its sensor code.
2. Pair both in Geek mode and confirm both stream at ~50 Hz with no gaps over 5 minutes.
3. Find the axes: flat on the desk, which axis reads ±9.8 m/s²? Tilt one end up and see which axis changes. Write down which axis faces forward for the mounting you intend.
4. Roll the bare board on a wedge or ramp at about 10°, 20° and 30° and compare the reported roll with the true angle.
5. Check battery run time, charging behaviour and the BLE range through a jacket pocket. Li-ion loses capacity in the cold and must not be charged below 0 °C. Check that the charger board is the protected variant so a flat cell is not over-discharged.
6. Record a 10-minute flash session on each board, download it, and confirm the app erases only after validation.

### Phase 1: garden, boots on, skis on, standing still

Run these with video and the SYNC marker. Skis on grass can't carve, so this phase validates the measurement, not skiing.

1. **Calibration repeatability.** Calibrate ten times per boot, re-standing each time. Spread of the neutral reference should be small.
2. **Roll accuracy.** Stand the ski edge on wedges of known angle (10°, 20°, 30°). Compare the app's roll with the wedge angle, left and right.
3. **Cuff cross-talk.** Flex the knees forward with the skis flat and the ankles still, 20° or more of pitch. Roll should stay close to zero.
4. **Slip check.** Walk and sit with the boots, then compare a neutral stance with the original calibration.
5. **Drills.** Run Slow rolls, Even both ways and Steady rhythm on live boots. Compare detected rolls with the video. Check timing error and missed or extra detections in the Analysis tab.
6. **Left/right symmetry.** Do deliberately uneven sets and confirm the balance score notices.
7. **Link quality.** Phone in a jacket pocket for a full 5-minute set; note dropouts and reconnects.
8. **Flex range and repeatability.** Flex fully forward into the boot and return, five times per boot. Record the pitch range, its repeatability and the left/right difference, with boot model, flex index and room temperature in the notes. Repeat in the cold if you can.

### Phase 2: first snow

1. Flat standing and shuffling on snow, to check neutral drift.
2. Gentle traverses, then slow linked turns on an easy run. Film every run and mark SYNC.
3. Label turns from the video (left, right, transition) so detection can be evaluated honestly; do not tune and test on the same runs.
4. Add phone GPS recording alongside, since speed is needed for any radius or skid estimate.
5. Record the equipment label, snow conditions, slope and mounting record every time.

## Proposed pass criteria

Placeholders to agree after Phase 0. They are targets for the POC, not claims.

| Check | Target |
|---|---|
| Calibration repeatability | within ±2° |
| Roll against known wedge angles | within ±3° at 10° to 30° |
| Pitch-to-roll leakage with 20° forward flex | under 3° |
| Neutral drift after walking and sitting (slip) | under 3°, otherwise re-calibrate |
| Flex range repeatability, five flexes per boot | to be set after the first measurements |
| Dry-set detection against video | at least 90% matched, median timing error under 200 ms |
| BLE live stream over 5 minutes | no gap over 250 ms |
| Flash recovery | download validates and erases without duplicate samples |

## Ski theory we are using

These come from general knowledge, not from measurements in this project. Treat the numbers as ballpark and check them before relying on them.

- **Turn radius.** For a pure carve, radius is about the sidecut radius times the cosine of the edge angle. A 15 m ski needs roughly 37° of edge angle for a 12 m turn and 48° for a 10 m turn. Short turns on a typical ski are usually steered or partly skidded rather than fully carved.
- **Turn tempo.** Roughly 1 s per short turn, 2 s medium, 3 s or more for long turns. The drill paces (3.0 s down to 0.9 s) cover that range.
- **Edge angle is more than the leg.** It combines inclination, knee and hip angulation, and ski and snow geometry. A cuff sensor sees lower-leg lean only.
- **Carve versus skid needs speed.** A hypothesis to test on snow: compare the radius the ski should make (sidecut and edge angle) with the radius travelled (speed divided by yaw rate). A much larger travelled radius suggests skidding. The sensor alone cannot separate the two.
- **Accelerometer roll is unreliable in turns.** Centripetal force tilts apparent gravity, so we integrate the gyro and correct at rest. Expect drift over long runs.

## What we can and cannot claim

| Can claim after Phases 0 and 1 | Cannot claim yet |
|---|---|
| Cuff roll angle within the measured accuracy | Ski edge angle on snow |
| Roll rhythm, depth and left/right balance in dry sets | Carved versus skidded turns |
| Cuff flex angle, range and rhythm, per boot | Load, pressure or weight shift between skis |
| Reproducible sessions with video and exports | Turn shape, radius or technique scores |

## Open questions

- Which GPIOs will the ESP32-S3 Super Mini use for I2C, and does it need its own PlatformIO environment?
- Strap or clip design for the cuff, and whether the iSpindel tube needs padding against falls.
- Rename "boot roll" to "cuff roll" in the app once the mounting is settled.
- Mounting-picker wording: ask which way the shin faces rather than "toward the toe".
- Should the ADC divider on the iSpindel board feed a battery-level reading into the BLE battery service?
