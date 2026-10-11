# Technique research integration

Later research on sensors, estimators and turn signals (2026-10-11) is summarised with evidence labels in [research findings](research-findings.md). The imported snapshot below is untouched.

Imported snapshot: 2026-10-08, revision 2, interface draft 0.1.0. Start with the [interpretation guide](technique-research/docs/provisional-interpretation.md), [handoff instructions](technique-research/docs/openski-handoff.md), [sensor acquisition contract](technique-research/docs/sensor-integration.md) and [metric registry](technique-research/integration/metric-registry.json). The snapshot contains 33 structured findings from 30 videos, eight approximate image-angle estimates, 17 provisional parameters, provenance, schemas and validation requirements. No videos or frames are included. Preserve the imported files and relative paths; record OpenSki-specific decisions here rather than editing the evidence snapshot.

## What the numbers mean for OpenSki

The [provisional parameter profile](technique-research/integration/provisional-parameters.json), `openski_pre_field_experiments_v0.1`, separates eight engineering starting choices, four published offline replication settings and five unresolved measurements. Importing it does not apply it to firmware. Record the profile ID, actual chosen settings and method version with recordings; a profile ID alone must not imply that the hardware matches it.

| Setting | Research starting choice | Current OpenSki / next experiment |
| --- | --- | --- |
| Acquisition | 100 Hz | Driver already requests 100 Hz; measure achieved intervals and loss, then compare 200 Hz. Live BLE sends 50 Hz, so use full-rate raw recordings for this experiment. |
| Gyro range | ±1000°/s | Matches driver configuration; check scale, sign, noise and clipping. This is headroom, not desirable skier rotation. |
| Acceleration range | ±16 g | Driver currently uses ±8 g. Compare noise/clipping before changing the range and its conversion constant together. |
| Gyro hardware bandwidth | Nominal 20 Hz | Driver currently configures DLPF 3, described in code as about 42 Hz. A 20 Hz experiment needs verified register semantics, coupled acceleration filtering and delay checks. |
| BLE batch maximum age | 50 ms | Proposed transport budget, not current batching behaviour or event accuracy. Preserve every acquisition timestamp. |
| Stationary bias/noise capture | 10 s | Proposed capture experiment; does not replace mounting calibration or establish stillness thresholds. |
| Timestamp-gap review | >20 ms at 100 Hz | Diagnostic only; inspect sequence loss too. Do not apply to the 50 Hz live stream as though it were 100 Hz. |
| Relative dual-boot clock error | 10 ms budget | Design target, not achieved synchronisation or acceptable skier asymmetry. |

The published offline replication settings are 0.5 Hz coarse filtering, 3 Hz refinement, a 0.6 neighbour-search fraction and a strict 0.3–5 s coarse interval gate. They are not a complete detector: amplitude/decision rules remain unresolved here. Zero-phase filtering uses future samples; a causal implementation needs separate timing evaluation. Keep replication of the bilateral method separate from a single-boot experiment.

The literature examples of 2.97 ± 0.43 s for nine carving turns and 1.04 ± 0.41 s for sixteen short turns are source-sequence means and standard deviations, not desired durations or population limits. Playback locations in the reviewed clips do not establish physical turn durations.

## Hypotheses about useful skiing patterns

These are OpenSki experiment proposals, not established good-skiing rules. Compare within a declared task and retain the skier's intended outcome; a drill deliberately changing rhythm or exaggerating movement needs a different interpretation.

| Hypothesis to test | Observable candidate | Evidence needed before feedback |
| --- | --- | --- |
| A skier executing a constant-rhythm drill may produce repeatable turn timing. | Individual turn durations and their coefficient of variation. | Reliable boundaries, independent task/outcome labels and separate validation recordings; lower variability is not universally better. |
| Repeatable boot rotation trajectories may reflect repeatable execution within the same drill. | Calibrated boot +X rate trajectories, peak sign and peak phase. | Bench-checked axes and independent motion/outcome comparison; neither larger rates nor a particular peak phase are established ideals. |
| Relative reversal timing may describe how two boots coordinate during a specified task. | Signed right-minus-left event lag and uncertainty. | Clock validation, unique event matching and independent bilateral annotations; zero lag is not an established target. |
| Changes in timing or trajectories may distinguish execution strategies. | Within-skier descriptive comparisons under matched context. | Independent labels and control for terrain, speed, turn shape and measurement quality before calling a change an improvement. |

The eight [image-angle proxies](technique-research/research/provisional-video-estimates.json) are isolated projected configurations from an exaggerated drill and an assessment candidate. Their marker-sensitivity grids are neither confidence intervals nor bounds on camera/model error. The bend proxy is not anatomical knee flexion, and image inclination cannot be transferred to boot roll. Use these examples to design clearer reference filming and annotation, not to populate target angles.

Keep ideal body/ski angles, loading targets, bilateral fault thresholds, detector amplitude thresholds and dynamic fusion gains unresolved. No runtime or coaching settings are changed by this import.

## Current acquisition and implementation priorities

The existing [BLE protocol](ble-protocol.md) provides device milliseconds and a sequence counter for the 50 Hz live stream. Flash recording preserves device microseconds and six raw channels at 100 Hz. `src/RecorderService.cpp` encodes 16-byte records with no acquisition sequence or per-sample quality flags; download record indices identify stored records, not missing acquisitions. Aggregate dropped-record counters cannot locate every missing sample. `RecorderProtocol.kt` retains the device timestamp. Phone notification arrival time must remain transport information.

`include/SensorCalibration.h` applies fixed per-unit corrections only to the firmware recogniser; raw BLE, flash and HTTP samples remain uncorrected. Preserve those raw values and associate any derived output with its actual calibration reference. Sensor-axis vectors must not be relabelled as boot-frame vectors without an explicit, bench-checked transform.

Prioritise the following work against the current hardware and protocol:

1. Define a versioned session metadata and sample contract: device/session identity, boot side, firmware/sensor model, mounting location and sensor-to-boot rotation, calibration identity, sample rate, SI units and whether acceleration includes gravity. Add acquisition sequence and sample-level clipping/dropout diagnostics through a coordinated firmware/Android protocol revision.
2. Preserve clock segments across wrap and reconnect. The flash microsecond counter is uint32 and wraps after about 71.6 minutes of device uptime, even during a short recording. The two boots currently have independent clocks; establish repeated offset/drift observations and residual uncertainty before bilateral timing calculations.
3. Validate calibrated boot +X angular rate with known bench rotations. Keep orientation estimates conditional under dynamic acceleration. The research boot frame is +X forward, +Y left and +Z up on both boots; anatomical reflection is a separate operation.
4. Define experimental boundaries with explicit method versions and missing/ambiguous states. Existing `ski_v0` half-turn events use their own zero-crossing definition and cuff-lean estimates; they do not establish validated research turn boundaries or ski edge angles.
5. Validate event detection and metrics with synchronised, permitted skiing video and independently annotated events before enabling technique recommendations. Retain failed detections and exclusions when reporting agreement.

## Candidate outputs and gates

Every output needs value/unit, interval, sensor configuration, method/event-definition version, calibration reference, task context, quality flags and validation status. Unavailable values remain null with reasons; poor recording quality is not poor skiing performance.

| Registry metric | Minimum capability and gate |
| --- | --- |
| `boot_roll_rate_rad_s` | One boot; known units, supported bias correction and bench-checked boot transform; invalid/clipped samples excluded explicitly. |
| `turn_duration_s` | One boot candidate; consecutive accepted alternating boundaries under one definition, without missing events or clock discontinuities. |
| `peak_rate_phase_fraction` | Validated boundaries and resolved peak timing; record filtering, tie handling and peak sign. No preferred phase is established. |
| `turn_duration_variability` | Reliable detection within a declared constant-rhythm task; sample standard deviation divided by mean, at least two durations, with counts and exclusions. |
| `boot_reversal_lag_s` | Two synchronised boots; uniquely paired events and usable clock/event uncertainty. Unavailable for one boot. |

The registry supplies definitions and validation gates, not a complete detector. Boot IMUs do not directly measure body joint angles, foot pressure, carving quality or true snow-relative ski edge angle. This qualitative evidence supplies no ideal-angle thresholds, composite performance scores or technique diagnoses. See the [validation protocol](technique-research/docs/validation-protocol.md) and [feedback limits](technique-research/docs/scoring-recommendations.md).

The first firmware follow-up adds opt-in research acquisition builds, configuration read-back and HTTP timing/near-rail diagnostics; see [IMU data](imu-data.md). BLE packets and Android processing remain unchanged. Durable sample/session metadata, clock synchronisation and validated metric processing above remain implementation work.
