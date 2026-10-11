# Research findings

## Status

Snapshot 2026-10-11. Everything here is an assumption or benchmark evidence. No boot-mounted data exists, nothing has been validated on a boot on snow, and the author is an intermediate skier, so "good" and "poor" technique are judged from published footage and lab data, not from this user's runs.

The source is the research repository at `D:\Projects\skivids` (no git remote). Its documents, drill definitions and Python reference code are copied unchanged into [`docs/skivids/`](skivids/README.md) (imported 2026-10-11); its footage, datasets, outputs, models and tools are not in this repository.

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
| On the only labelled 100 Hz leg-IMU data found (Prochazka, run 1, untuned heading-rate detector): recall 0.58 and 0.64, precision 0.59 and 0.64 at +-0.6 s; with left/right direction required, recall 0.47 and 0.48. About 75 to 80 percent of matched turns had the right direction. | CONFIRMED (run in the research repo; one run, probably one skier, placement undocumented) | Turn signal section; sets a more realistic expectation than the calf-phone F1 |
| Real carved skiing seen in the Beuken video charts: turn about 1.3 s median, peak roll 31 to 40 degrees (p25 to p75), roll p5 to p95 -37 to +38 degrees, turn-duration CV 0.61 | REPORTED (chart digitised from one clip, no matched raw data) | Reference ranges section |
| Sustained 1 to 2 g cornering with VQF is untested; cold behaviour of chips and batteries is unverified | open | Docs, acceptance checks |

## Turn signal

Heading rate is the rotation rate about the world vertical, `r = omega . up`, where `up` is the gravity direction in the sensor frame. A turn candidate is one lobe of that signal between zero crossings.

On public ski data (SkiC-LSTM, from Robak and Turek; 105 runs, 10 skiers, labelled from video) the research repo reports, at +-0.5 s tolerance (REPORTED):

- Roll-like (principal gyro axis, integrated): F1 0.52 over all runs; 0.12 to 0.41 for skidded and snowplow turns; 0.32 (beginner) and 0.37 (intermediate) by level; 0.87 to 0.94 for carved or quick turns.
- Heading rate, tuned on five skiers and evaluated on the other five (38 runs): F1 0.74. The accelerometer-plus-gyro detector used by the research app reaches 0.71 held out, 0.82 at +-1.0 s.
- Fusing the calf-phone roll proxy with heading rate did not help (union 0.60, fused 0.72, heading alone 0.74).

Closer to our hardware, on 100 Hz WITmotion leg IMUs (Prochazka, Zenodo 17186128, CC BY 4.0; one run with gyro data, two sensors, 146 labelled turn apexes; CONFIRMED, run in the research repo): the same untuned heading-rate detector gave 145 and 147 detections. Matching within 0.6 s, recall was 0.58 and 0.64 and precision 0.59 and 0.64; requiring the right left/right direction cut recall to 0.47 and 0.48, and about 75 to 80 percent of matched turns had the right direction. Both sensors independently gave the same video-to-sensor offset (-14.56 s) once direction was included. Limits: one skier probably, labels are arc apexes rather than turn switches, offset estimated from the same data, placement not documented, and the other two runs have no gyro. A direction-free offset search gave recall 0.76 to 0.79 with a different offset per sensor, so treat any figure that skipped direction or an independent clock event as inflated. Reading: expect roughly 0.5 to 0.65 for turn matching on the first boot data, not 0.74.

On all 105 SkiC runs, untuned, heading rate over-counts (precision 0.45, recall 0.81, F1 0.58); the 0.74 figure is the held-out, tuned result. Tuning was on one split of five skiers.

Transfer caveat (CONFIRMED from the benchmark's own limits): the data is a calf-worn phone at 10 Hz in an unknown orientation, from ten skiers and 38 held-out runs (11 intermediate runs), not a boot IMU at 100 Hz. The tuning sweep was small (27 settings) and done on one split. Timing is good to roughly half a second in that setup; the 0.03 to 0.06 s reported for lab boot-cuff gyro methods should not be expected. Whether a real boot roll signal adds value over heading rate is open.

## Reference ranges for real carved skiing

From roll charts digitised from one POV video (Beuken et al. lab dataset, 100 s, 60 turns found; skier level unstated; REPORTED, reading error a couple of degrees; no raw IMU file could be matched to it, best correlation 0.38):

| Measure | p25 / median / p75 or range |
|---|---|
| Single-turn duration | 1.1 / 1.3 / 1.5 s (left-right cycle about 2.6 s) |
| Peak roll per turn | 31 / 35 / 40 degrees |
| Roll range p5 to p95 | -37 to +38 degrees |
| Coefficient of variation of turn duration | 0.61 |

These agree with the lean and cycle figures from YouTube footage (cycle about 3 s, lean half-range about 37 to 40 degrees), though not independently (different skier and slope). They are plausibility ranges for drill depths and beats, not targets. Real rhythm is irregular, so beat-based feedback should tolerate uneven turns; see `docs/skivids/docs/ski-video-reference.md`.

### Drill and coach targets against these ranges

Checked 2026-10-11 against `Programme.kt` and `Coach.kt` (INFERRED, no code change). The beat the app scores is the time between consecutive half-turn starts, i.e. one side per beat, which is the same unit as the single-turn duration above.

| Item | App value | Reference | Reading |
|---|---|---|---|
| Beat, blue drills | 3.0, 2.4, 2.0 s | 1.1 to 1.5 s on snow | Slower than real skiing on purpose (slow practice); fine for dry-ski, not an on-snow expectation |
| Beat, red drills | 2.2, 1.6, 1.8 s | 1.1 to 1.5 s | Close |
| Beat, black drills | 1.2, 1.0, 0.9 s | 1.1 to 1.5 s (short turns about 1.0 s in the technique-research snapshot) | Consistent |
| Depth, blue / red / black | 15 to 20 / 30 to 32 / 25 degrees | 31 to 40 degrees peak roll | Red matches the low end. Blue and black are lower; dry-ski cuff roll is not on-snow ski roll, and the video's sensor placement is unknown, so no change |
| Coach beat and depth limits | 0.8 to 3.5 s, 10 to 45 degrees | as above | Cover the reference range |
| Steadiness | zero at a spread of 35 percent of the mean beat | CV 0.61 over 100 s | See below |

The 0.61 is driven by outliers: a median of 1.3 s with a p25 to p75 of 1.1 to 1.5 s implies a typical spread near 0.2 of the mean (robust estimate from the quartiles, INFERRED). Coach windows are 3 to 5 consecutive half-turns and are cleared after a gap over two beats, so a stop or traverse does not enter a window. The 35 percent gate therefore looks plausible for windows, but it has never been tried on real boot runs. First-snow check: record the per-window steadiness distribution on a real run before trusting negative chirps for rhythm; if most windows of ordinary skiing score near zero, widen the gate.

## Estimator

A 1-axis roll filter was wrong by tens of degrees on coupled motion, so orientation needs a 3D attitude estimate. On BROAD, VQF reached 0.4 deg median (1.3 deg worst) against 1.6 deg median (14 deg worst) for the research repo's complementary filter (CONFIRMED on BROAD, not on a boot). On ski lab truth VQF is reported at 0.7 to 3.7 deg median inclination error against 1.2 to 8.4 deg for the complementary filter (ten IMU-takes on a hand-driven ski, ICM-42688 class, 714 Hz). The tails matter more for turn-by-turn feedback: in the rougher take (rotations up to about 70 deg and hand shocks) p95 was 22 to 28 deg for VQF and 34 to 50 deg for ours, with p99 46 to 62 and 59 to 75; some of that may be mocap error. IMU position along the ski mattered (CONFIRMED in the research repo; `docs/skivids/docs/ski-lab-truth.md`).

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

## Research items taken after the first round

- The research repo's documents, drill definitions and Python reference code are imported unchanged under `docs/skivids/`.
- The Prochazka 100 Hz result and the Beuken reference ranges are recorded above, and the drill and coach targets were checked against them (no change).
- The research repo's mounting-slip monitor is ported as a replay-side `MountSlipMonitor` (`docs/ski-analysis.md`). The still-pose plus knee-flex mounting recovery (`docs/skivids/docs/imu-calibration.md`) and the intent-labelled first-snow protocol are not built in the app: the app keeps its neutral-pose calibration and mounting picker, and the first-snow protocol is a manual procedure.

## What this repo does with it

Design: `docs/superpowers/specs/2026-10-11-research-findings-integration-design.md`. The plan was split into four sub-projects, in order, and all four are built.

1. Docs fold-in: this document and the targeted edits to the analysis, POC plan and roadmap docs. No code.
2. Heading-rate turn signal (built): an additive `HeadingTracker` and wire frame version 4, replayed on Android as a second candidate set next to roll-lobe timing, with `heading-turns.csv` in exports. Half-turn detection and the coach are unchanged.
3. Rest-driven gyro bias (built): `RestBias`, a bounded bias update while still, applied to recogniser inputs only and exposed on `/api/v1/motion`. The Kotlin orientation tracker learns the same bias in replay.
4. Signal quality and protocol v3 (built): near-rail, gap and drop flags carried into flash records, live frames (v2), storage (database version 9) and exports.

Causal check status: the causal heading-rate detector has not yet been scored against public data. The SkiC data is not on this machine, so no F1 or by-level figure exists for the C++ detector; the figures in this document are the research repo's own and describe its Python detector. Only the firmware-to-Kotlin parity fixture and synthetic tests have been run.

Related: `docs/technique-research-integration.md` (the earlier technique-research snapshot), `docs/ski-analysis.md`, `docs/poc-plan.md`.
