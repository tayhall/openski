# Sensor set and estimator: recommendation

Date: 2026-10-11. Status: recommendation from desk research plus two benchmarks run in this repo. **Nothing here has been validated on a ski boot.** Detailed, source-labelled reports are in `research/sensor-research/` (01 hardware, 02 flight controllers, 03 sports methods, 04 calibration, 05 products, 06 datasets).

## Decisions

| Area | Recommendation | Confidence | Why |
|---|---|---|---|
| IMU | Move off the MPU6050 for anything beyond the proof of concept. **Primary: TDK ICM-45686** (+-32 g, about 3.8 mdps per root Hz gyro noise, about 0.005 dps per C gyro temperature stability, 0.42 mA; evaluation board DK-45686), because accelerometer range and clipping, not gyro bias, limit roll quality on snow. Alternatives: ST LSM6DSV320X (clean +-16 g channel plus a +-320 g high-g channel; new, little independent data), Murata SCH16T-K10 (automotive grade, 2 deg per hour, -40 to +110 C, but +-16 g). Reference sensor for validation sessions: MicroStrain 3DM-CX5-IMU (temperature compensated, +-40 g option). The earlier hobby-price choice (LSM6DSV16X, +-16 g) remains a cheap fallback. | Medium to low | Cost is secondary. Measured MPU6050 offsets and temperature slopes vary by tens of times between units (Cranfield), a ski accelerometer spiked to 6 to 18 g and clipped a +-16 g part on 13 to 35 percent of samples, and VQF removes much of the gyro bias online. Several figures for the new parts are REPORTED, not read from datasheets (`research/sensor-research/09a-premium-chips-and-modules.md`). |
| Zero-solder pilot | XIAO nRF52840 Sense (LSM6DS3TR-C) | Medium | Same IMU family used in published boot-cuff turn detection. Worse bias, so for timing, not final roll accuracy. |
| MCU / radio | nRF52840 class BLE MCU, not ESP32-C3 | Medium | Much lower active current (ESP32-C3 reported at 13 to 23 mA), which matters for a 6 hour day in the cold. |
| Magnetometer | Do not use | High | Roll relative to gravity needs no heading; buckles and bindings disturb the field. |
| Range | At least +-16 g and +-2000 dps; flag clipped samples and bridge with gyro only | Medium | Published reports of edge-angle drift when the accelerometer saturates. BNO08x is +-8 g and power hungry, so not primary. |
| Estimator | **VQF** as the primary candidate, our 3D complementary filter (`estimate_attitude`) as fallback and readable reference | Medium | VQF 0.4 deg median, 1.3 deg worst on BROAD against 1.6 / 14 for ours. Its accelerometer low-pass in an almost inertial frame suits alternating turns. Authors also built BROAD, and cornering at 1 to 2 g is untested. |
| Bias handling | Rest-driven gyro bias estimation (lift rides, queues), bounded rate; bench temperature calibration using the IMU's temperature output | Medium | Both flight-controller projects and VQF do this. |
| Turn detection | Heading-rate (rotation about vertical) sign change as the primary signal; roll reversal as a secondary feature once a boot sensor is available; consider a learned model | Medium | On public ski data roll reversal alone scores F1 0.12 to 0.41 for skidded turns and beginner or intermediate skiers; heading rate reaches 0.71 to 0.74 on held-out skiers (intermediate 0.62). Fusing in the calf-phone roll proxy did not help. See `docs/ski-turn-benchmark.md`. |
| Sensor set | Two synchronised boot IMUs first. Then phone GNSS plus barometer. Then an experimental relative-signal insole. A ski-mounted IMU only for validation sessions. | Medium | Carv's second generation dropped its insole; a cuff IMU is validated for turn timing; insoles add load shape, not absolute force, and have no cold-weather data. |

## Requirements this implies

- Sustained 1 to 2 g during carves and several g impacts without losing orientation: clipping flags, gyro bridging, accelerometer trust that decays gracefully.
- Roll relative to gravity within a few degrees over a 6 hour day from -20 to +10 C: temperature-compensated bias, rest updates, recalibration prompt when the slip monitor fires.
- Turn timing good to a few tenths of a second on intermediate and skidded skiing: needs two boots and more than a single-axis roll signal.
- Mounting varies per fitting: keep the still-pose plus knee-flex calibration and the slip monitor.
- Raw logging at 100 Hz or more with replay, as flight controllers do, so algorithm changes can be tested on the same data.

## Not established

- No candidate sensor or estimator has been tested on a ski boot, on snow, or in the cold.
- Carv's accuracy claims have no independent validation that we could find.
- LSM6DSOX figures and some vendor pages could not be retrieved; prices are snapshots.
- Edge angle from a boot IMU is a proxy for cuff tilt, not ski edge angle.
- The turn benchmark used a calf-worn phone at 10 Hz, not a boot IMU.

## Suggested order of work

1. **Bench (about 1 to 2 days, needs hardware):** WHO_AM_I and clone check, Allan variance (1 to 3 hours static), temperature sweep with the IMU's own temperature output, protractor tilt and off-axis mount test, 90 degree gyro scale test. Procedures and a 12 item acceptance checklist are in `research/sensor-research/04-calibration-and-accuracy.md`.
2. **Estimator on ski data (no hardware):** done on one outdoor run of the Kaggle ski IMU set (see `docs/ski-imu-run.md`). Lab files with optical truth are now aligned (`docs/ski-lab-truth.md`): on a ski, VQF has 0.7 to 3.7 degrees median inclination error against our filter's 1.2 to 8.4. Still open: boot-mounted data and sustained high-g carving.
3. **Turn detector:** done for heading rate (`detect_turns_heading`, held-out F1 0.71 at +-0.5 s). Still open: test a boot-mounted roll signal as a second feature on real boot data, and a learned model.
4. **Order the prototype parts:** two LSM6DSV16X boards and two nRF52840 boards, plus a battery and enclosure for the cold; keep the MPU6050 rig as a baseline for side-by-side logging.
5. **First snow day:** follow `docs/first-snow-protocol.md` with both IMUs logging raw.

## Ready-made systems (from `research/sensor-research/09b-ski-wearables-and-boot-practicalities.md`)

Movesense is the only ready-made platform with published on-snow boot-cuff use, but its published configuration is +-8 g, +-500 dps, BLE streaming at 33 to 64 Hz, with no stated temperature rating, IP code or multi-unit sync. Xsens DOT and MTw are rated 0 to 50 C. MbientLab MetaMotionS is rated -40 to +85 C but has a small LiPo and about 275 ms drift between two units over 5 minutes. A custom logger on a cold-rated cell is the realistic route; Li-ion must not be charged below 0 C.
