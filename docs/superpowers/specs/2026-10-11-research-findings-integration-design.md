# Research findings integration: design

Date: 2026-10-11. Status: draft for review. Source: `D:\Projects\skivids\handover.md` and the documents it indexes (sensor spec, turn benchmark, calibration, MPU-6050 temperature, ski lab truth).

## Intent

Bring a richer, but still unvalidated, set of research assumptions into OpenSki's docs and software so the next round of boot and snow data can test them. The owner accepts that all of it is a guess at this stage; the aim is a build that can *discriminate* between the assumptions once real boot data exists, not one that claims any of them is right.

Evidence labels used here and in the docs: **CONFIRMED** (measured in this repo or read from a source), **REPORTED** (stated by a source or research agent, not independently checked), **INFERRED** (our reasoning).

## What the research says that bears on this repo

| Finding | Label | Where it lands |
|---|---|---|
| Heading rate (gyro along gravity) finds skidded and intermediate turns that roll reversal misses: held-out F1 0.71 to 0.74 at +-0.5 s against roll-like 0.12 to 0.41 for skidded and 0.32 to 0.37 for beginner and intermediate skiers. Calf-worn phone at 10 Hz, 10 skiers, 38 held-out runs. | REPORTED (benchmark run on public data in the research repo) | Sub-project 2 |
| A 1-axis roll filter was wrong by tens of degrees on coupled motion; 3D attitude (VQF 0.4 deg, ours 1.6 deg median on BROAD) is needed | CONFIRMED on BROAD, not on a boot | Docs; our tracker is already 3D |
| Ski accelerometers spike to 6 to 18 g and clipped a +-16 g part on 13 to 35 percent of samples | REPORTED | Sub-projects 2 and 4 |
| MPU-6050 offsets and temperature slopes differ by tens of times between units | CONFIRMED (Cranfield set) | Sub-project 3, docs |
| Rest-driven gyro bias estimation is standard practice (flight controllers, VQF) | REPORTED | Sub-project 3 |
| Sustained 1 to 2 g cornering with VQF is untested; cold behaviour of chips and batteries is unverified | open | Docs, acceptance checks |

## Decomposition

Each row is its own plan and test set. Order matters: 1 first, then 2 and 3, then 4 because it is the only breaking protocol change.

1. **Docs fold-in.** No code.
2. **Heading-rate turn signal**, additive.
3. **Rest-driven gyro bias.**
4. **Signal quality and protocol v3.**

Out of scope, recorded in the docs only: porting VQF, changing IMU (ICM-45686 and others), moving to an nRF52840, magnetometer, GNSS and insole sensor sets, a learned turn model. All need hardware or data we do not have.

## 1. Docs fold-in

New `docs/research-findings.md`: the table above, the evidence labels, the sensor and estimator recommendation as a *post-POC path* (not a change to the POC hardware), the unverified items, and a pointer to the research repo's handover, noting that footage, data and the Python code are not in this repository and the research repo has no remote.

Targeted edits:

- `docs/ski-analysis.md`: heading rate becomes the primary turn-timing candidate and roll reversal the secondary; state the transfer caveat (calf phone, 10 Hz, not a boot IMU). Replace "Next: improve segmentation" with the new comparison plan.
- `docs/poc-plan.md`: add the bench tests the research lists (Allan variance, temperature sweep, protractor mounting, clipping, g-sensitivity, cold soak) as owed items, next to the existing ones.
- `docs/imu-data.md`: record the range and clipping evidence behind the +-16 g research profile and the near-rail flag.
- `docs/ble-protocol.md`, `docs/feature-roadmap.md`, `docs/android-acceptance.md`, `README.md`: updated by the sub-project that changes the behaviour, in the same change.
- `docs/technique-research-integration.md`: one paragraph pointing at `research-findings.md`; the imported snapshot stays untouched.

## 2. Heading-rate turn signal (additive)

Heading rate is the rotation rate about the world vertical: `r = omega . up`, where `up` is the gravity direction in the sensor frame.

### Core detector (`lib/Motion/src/HeadingTracker.h`, portable, host-tested)

Takes `(timeUs, headingRateRadps)` plus a `skipped` flag and emits one **heading turn** per completed lobe. It is deliberately independent of how `up` is obtained, so firmware and Android share the same logic:

1. Smooth with a causal single-pole filter (default tau 0.25 s, standing in for the research's centred 0.8 s moving average; **tunable**).
2. Remove a slow baseline with an EMA (tau 4 s, as the offline `baseline_s`).
3. Amplitude threshold = `0.6 x` running SD of the de-trended rate (EMA of the square, tau 20 s) with an absolute floor (default 15 deg/s) so standing and walking do not produce turns. The offline detector has no floor because its input is always skiing; this is a deliberate deviation.
4. A lobe is a run of one sign whose peak exceeds the threshold. It completes at the zero crossing to the other sign, and is kept if it lasted at least 0.4 s.
5. Output per turn: start, end, duration, sign, peak rate (deg/s), **heading change** (integral of the rate over the lobe, degrees, the natural turn-size measure), and flags (samples skipped inside the lobe, any near-rail sample, a timing gap).
6. A gap over 100 ms abandons the open lobe, as `SkiTracker` does.

Timing: a causal filter delays zero crossings; the emitted `start` and `end` are shifted back by the smoothing tau so they estimate the unfiltered crossing. How far that is true is part of the check below.

### Firmware

`SkiTracker` already maintains a filtered gravity vector `up_` that fades its accelerometer pull out as |a| leaves 1 g, which is better than the research's 1 s accelerometer average during hard turns. It feeds `omega . up_` to a `HeadingTracker` and keeps a 16-entry ring like `SkiEvent`. `ski_v0` half-turn detection is **unchanged**.

Wire: new frame **version 4**, 16 bytes, on the existing movement characteristic. Clients that do not know a version ignore it (documented rule), so old apps keep working. Layout, little-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | version `4` |
| 1 | 1 | flags: bit 0 heading rate positive (about the up axis), bit 1 samples skipped, bit 2 near-rail sample, bit 3 timing gap |
| 2 | 2 | sequence, count modulo 65536 |
| 4 | 4 | start time ms, same clock as the live frame |
| 8 | 2 | duration ms, saturating |
| 10 | 2 | peak heading rate, value / 10 deg/s, signed |
| 12 | 2 | heading change, value / 100 deg, signed |
| 14 | 2 | reserved `0` |

`BluetoothService` sends them through the same one-frame-per-loop rule and per-type last-sent sequence as the existing frames. Heading turns are *not* given to the production-mode recorder or the coach; the coach keeps consuming half-turns.

### Android

- `HeadingTurnProtocol.kt` decodes frame version 4 into `MotionFrames`; `BleSensorClient` and `SensorSessionService.Listener` forward it; the Test lab "Boot sensors" card shows the latest heading turn next to the latest half-turn.
- `HeadingTurnAnalysis` runs the **same detector** over replayed samples, taking `up` from `BootOrientation.Tracker` (the same incremental code as the live path, so the existing streaming/replay equivalence test extends to it). Results are added to the Analysis tab as a second, labelled candidate set, to `detected-movements.csv` (a `method` column: `roll_lobe` or `heading_rate`) and to `analysis-summary.json`.
- Nothing is stored from the live v4 frames in this round: comparison happens by replaying recorded raw samples through both methods.
- All UI text keeps the existing framing: turn candidates, experimental, not ski turn classification.

### Verification

- Shared fixtures `test/fixtures/heading_turns.json` (rate series with expected turns, generated once by the C++ host tool and committed). Kotlin and C++ tests must agree on them.
- Synthetic tests: no turns when standing, walking and bench handling; sign alternation; skipped-sample and gap handling.
- Causal-versus-offline check: a small CLI wrapping the C++ detector reads a rate CSV so the research repo's SkiC benchmark can score it. The result is recorded in `docs/bench-results.md`. SkiC data is public (MIT repo) but is **not** present on this machine and must be re-downloaded; if that is not done, the doc records the detector as untested against public data.
- On a boot: owed. Recorded as acceptance items, not claimed.

## 3. Rest-driven gyro bias

`lib/Motion/src/RestBias.h`: while the sensor is demonstrably still (angular speed under 0.05 rad/s, |a| within 3 percent of 1 g, for at least 2 s), update a gyro bias estimate from the mean gyro reading.

- Rate bound: the estimate moves at most 0.002 rad/s per second of rest.
- Absolute bound: within +-0.15 rad/s of the fixed per-unit correction in `SensorCalibration.h`; outside that the update is refused and a counter incremented.
- Applied in `MotionService` on top of the static correction, to recogniser inputs only. Raw BLE, flash and HTTP samples stay uncorrected.
- Exposed as `gyro_bias_radps` and `bias_updates` in `/api/v1/motion`, so the behaviour can be watched without a debug build.
- Android `BootOrientation.Tracker` already detects rest; it adopts the same rule so live and replay stay equivalent. The one-shot calibration bias remains the starting value.
- Temperature compensation is **not** included: it needs bench sweeps per unit first.

Risks: a long still period during a temperature transient is tracked, which is the intent; a held sustained rotation under 0.05 rad/s would be absorbed as bias, which the speed gate and bounds limit. Both are covered by tests.

## 4. Signal quality and protocol v3

Firmware already tags samples with near-rail, timestamp-gap and wrap flags (uncommitted working tree, HTTP diagnostics only). This sub-project carries them to the places data is analysed.

### Flash record: flags without growing the record

The 16-byte record is full. Growing it to 20 bytes would cut the one retained session from about 10 to about 8 minutes and lengthen downloads by a quarter. Instead, pack three flags into the **low 3 bits of the microsecond timestamp** (resolution 8 us, irrelevant at 100 Hz):

- bit 0: accelerometer near rail
- bit 1: gyro near rail
- bit 2: one or more earlier samples were dropped immediately before this one (queue full or gap), which locates loss that the aggregate dropped counter cannot

The recorder control response version byte becomes `3`. Android treats `2` as no flags and masks nothing. The timestamp wrap flag is derivable and not stored. Alternative considered: a 20-byte record with a separate flags byte and sequence; rejected for capacity and download cost, and kept as the fallback if a fourth flag is ever needed.

### Live frame

Live frame version `2`, 20 bytes (the default ATT payload): version 1 plus one flags byte at offset 19, the OR of the flags of the two IMU samples a notification stands for (it is every second sample). Android decodes both frame sizes.

### Android

- `FlashRecord` and `SensorSample` gain a `quality` field. A new `captures.record_format` column (default 2) says whether stored flash BLOBs carry flags, so legacy sessions are never mis-decoded. Live samples gain `samples.quality`. Database version 9, with an instrumentation migration check; stored BLOBs are unchanged.
- `BootOrientation.Tracker` and `SessionAnalysis`: a near-rail accelerometer sample contributes no accelerometer correction (gyro bridge only), as the firmware tracker does on its 2.5 g impact gate. Intervals with near-rail samples are marked.
- Exports: `quality` appended as the last column of the raw CSVs; `analysis-summary.json` gains clipped-sample counts and percentage per boot. The recording quality card shows the same.
- `docs/ble-protocol.md` and `RecorderProtocolTest` change in the same commit as both code paths (the stated rule for this contract).

### Compatibility

Old firmware with the new app and the new firmware with an old app are both unsupported for live streaming, because the live frame version changes. The app is told by the version byte on the control response and the live frame, so it fails closed. Stored sessions from before the change replay unchanged.

## Testing and acceptance

- Host: `HeadingTracker`, `RestBias`, `SkiFrames` v4 encoding, recorder flag packing (extend `test/test_imu_monitor` and the standalone `tools/test_*.cpp`).
- JVM: `HeadingTurnProtocolTest`, `HeadingTurnAnalysisTest` with the shared fixtures, extended `RecorderProtocolTest`, `BootOrientationTest` for rest bias and rail bridging.
- Emulator: `StorageInstrumentation` migration v9.
- Owed on hardware, added to `docs/android-acceptance.md`: v4 frames on a real boot, a flash download with flags set by a deliberate hard knock, rest-bias convergence after a cold start, and the first snow-day comparison of roll-lobe against heading-rate candidates against video.

## Decisions for review

1. Heading turns are an **additive** frame; the coach and run flow stay on half-turns. (Agreed.)
2. Flash flags in the timestamp's low bits rather than a larger record. Please confirm, or choose the 20-byte record.
3. Live frame grows to 20 bytes and the version changes, so firmware and app must update together.
4. Heading-rate defaults are untuned guesses until a causal check on the public data is done.
