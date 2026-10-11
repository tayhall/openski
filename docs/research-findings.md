# Research findings

## Status

Snapshot 2026-10-11. Everything here is an assumption or benchmark evidence. No boot-mounted data exists, nothing has been validated on a boot on snow, and the author is an intermediate skier, so "good" and "poor" technique are judged from published footage and lab data, not from this user's runs.

The source is the research repository at `D:\Projects\skivids` (no git remote). Its footage, data, outputs and the Python reference implementation are not in this repository.

Evidence labels: **CONFIRMED** (measured in this repo or read from a source), **REPORTED** (stated by a source or research agent, not independently checked), **INFERRED** (our reasoning).

Ski turn classification is not implemented or validated in OpenSki. Heading-rate turns, like roll-lobe candidates, are experimental.

## Findings and where they land

| Finding | Label | Where it lands |
|---|---|---|
| Heading rate (gyro along gravity) finds skidded and intermediate turns that roll reversal misses: held-out F1 0.71 to 0.74 at +-0.5 s against roll-like 0.12 to 0.41 for skidded and 0.32 to 0.37 for beginner and intermediate skiers. Calf-worn phone at 10 Hz, 10 skiers, 38 held-out runs. | REPORTED (benchmark run on public data in the research repo) | Sub-project 2 |
| A 1-axis roll filter was wrong by tens of degrees on coupled motion; 3D attitude (VQF 0.4 deg, ours 1.6 deg median on BROAD) is needed | CONFIRMED on BROAD, not on a boot | Docs; our tracker is already 3D |
| Ski accelerometers spike to 6 to 18 g and clipped a +-16 g part on 13 to 35 percent of samples | REPORTED | Sub-projects 2 and 4 |
| MPU-6050 offsets and temperature slopes differ by tens of times between units | CONFIRMED (Cranfield set) | Sub-project 3, docs |
| Rest-driven gyro bias estimation is standard practice (flight controllers, VQF) | REPORTED | Sub-project 3 |
| Sustained 1 to 2 g cornering with VQF is untested; cold behaviour of chips and batteries is unverified | open | Docs, acceptance checks |

## Turn signal

Heading rate is the rotation rate about the world vertical, `r = omega . up`, where `up` is the gravity direction in the sensor frame. A turn candidate is one lobe of that signal between zero crossings.

On public ski data (SkiC-LSTM, from Robak and Turek; 105 runs, 10 skiers, labelled from video) the research repo reports, at +-0.5 s tolerance (REPORTED):

- Roll-like (principal gyro axis, integrated): F1 0.52 over all runs; 0.12 to 0.41 for skidded and snowplow turns; 0.32 (beginner) and 0.37 (intermediate) by level; 0.87 to 0.94 for carved or quick turns.
- Heading rate, tuned on five skiers and evaluated on the other five (38 runs): F1 0.74. The accelerometer-plus-gyro detector used by the research app reaches 0.71 held out, 0.82 at +-1.0 s.
- Fusing the calf-phone roll proxy with heading rate did not help (union 0.60, fused 0.72, heading alone 0.74).

Transfer caveat (CONFIRMED from the benchmark's own limits): the data is a calf-worn phone at 10 Hz in an unknown orientation, from ten skiers and 38 held-out runs (11 intermediate runs), not a boot IMU at 100 Hz. The tuning sweep was small (27 settings) and done on one split. Timing is good to roughly half a second in that setup; the 0.03 to 0.06 s reported for lab boot-cuff gyro methods should not be expected. Whether a real boot roll signal adds value over heading rate is open.

## Estimator

A 1-axis roll filter was wrong by tens of degrees on coupled motion, so orientation needs a 3D attitude estimate. On BROAD, VQF reached 0.4 deg median (1.3 deg worst) against 1.6 deg median (14 deg worst) for the research repo's complementary filter (CONFIRMED on BROAD, not on a boot). On ski lab truth VQF is reported at 0.7 to 3.7 deg inclination error against 1.2 to 8.4 deg for the complementary filter.

Our `SkiTracker` (firmware) and `BootOrientation` (Android) are already 3D complementary filters with the accelerometer pull faded out as |a| leaves 1 g (a gated accelerometer pull). A VQF port is deferred until boot data exists to show that it matters. Sustained 1 to 2 g cornering with VQF is untested (INFERRED risk: a filter that suits alternating turns may behave differently in a long carve).

## Sensor path after the POC

Not a POC change. The proof of concept keeps the MPU-6050 and ESP32. The research repo's recommendation for later, all vendor figures REPORTED and several not read from datasheets:

- IMU primary: TDK ICM-45686 (+-32 g, about 3.8 mdps per root Hz gyro noise, about 0.005 dps per C gyro temperature stability, 0.42 mA). Alternatives: ST LSM6DSV320X (clean +-16 g channel plus a +-320 g high-g channel, new, little independent data) and Murata SCH16T-K10 (2 deg per hour, -40 to +110 C, +-16 g). Reason: accelerometer range and clipping, not gyro bias, limit roll quality on snow.
- MCU and radio: an nRF52840 class BLE MCU, for much lower active current than an ESP32-C3 (reported at 13 to 23 mA), which matters for a 6 hour day in the cold.
- Zero-solder pilot: XIAO nRF52840 Sense (LSM6DS3TR-C), for timing rather than final roll accuracy.
- No magnetometer: roll relative to gravity needs no heading and buckles and bindings disturb the field.
- Range: at least +-16 g and +-2000 dps, flag clipped samples and bridge with the gyro only.

## Not established

- No candidate sensor or estimator has been tested on a ski boot, on snow, or in the cold.
- Carv's accuracy claims have no independent validation that the research found.
- LSM6DSOX figures and some vendor pages could not be retrieved; prices are snapshots.
- Edge angle from a boot IMU is a proxy for cuff tilt, not ski edge angle.
- The turn benchmark used a calf-worn phone at 10 Hz, not a boot IMU.
- Cold behaviour of the candidate chips and batteries is unverified. Li-ion must not be charged below 0 C (INFERRED: general knowledge, not from the research repo).

## What this repo does with it

Design: `docs/superpowers/specs/2026-10-11-research-findings-integration-design.md`. The plan was split into four sub-projects, in order, and all four are built.

1. Docs fold-in: this document and the targeted edits to the analysis, POC plan and roadmap docs. No code.
2. Heading-rate turn signal (built): an additive `HeadingTracker` and wire frame version 4, replayed on Android as a second candidate set next to roll-lobe timing, with `heading-turns.csv` in exports. Half-turn detection and the coach are unchanged.
3. Rest-driven gyro bias (built): `RestBias`, a bounded bias update while still, applied to recogniser inputs only and exposed on `/api/v1/motion`. The Kotlin orientation tracker learns the same bias in replay.
4. Signal quality and protocol v3 (built): near-rail, gap and drop flags carried into flash records, live frames (v2), storage (database version 9) and exports.

Causal check status: the causal heading-rate detector has not yet been scored against public data. The SkiC data is not on this machine, so no F1 or by-level figure exists for the C++ detector; the figures in this document are the research repo's own and describe its Python detector. Only the firmware-to-Kotlin parity fixture and synthetic tests have been run.

Related: `docs/technique-research-integration.md` (the earlier technique-research snapshot), `docs/ski-analysis.md`, `docs/poc-plan.md`.
