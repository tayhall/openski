# Clock sync design (phone side built, untested on hardware)

Status: the phone side is built (18 October 2026, branch `feat/android-sync-and-mag`): `ClockSync.kt`, the opcode 09 codec, ping scheduling, the version 11 tables and the export. It has been tested only against simulated clocks and recorded byte layouts. Nothing has run against real boots; the firmware side was built in parallel from the wire formats below. The offline gesture aligner (`ClockAlign.kt`) is built and validated on public data; see 'Gesture sync results'. Every number is a starting value to tune on hardware. Written 18 October 2026 while the XIAO ESP32S3 and LSM6DSOX kit was on order.

## Why

Each boot runs its own clock (`esp_timer`, microseconds since boot), and the protocol says the clocks "are not synchronized" ([BLE protocol](ble-protocol.md)). Boot-to-boot agreement, left/right depth and the planned phone-as-third-sensor metrics all assume the streams line up in time. A crystal drifting tens of ppm moves a boot by roughly 10 to 30 ms over a 10-minute run, which is large next to a turn lasting about a second.

Today the app estimates alignment from live samples and phone receipt times (`LiveSensorClock`). That has two weaknesses: live notifications are fire-and-forget at 50 Hz and arrive with BLE and Android callback jitter, and downloaded flash samples carry no phone receipt times at all.

## Goals and non-goals

- Goal: a measured offset and drift per boot against the phone clock, with an honest uncertainty, kept as raw pairs so the fit can be redone offline.
- Goal: the same mapping applies to live and flash samples, including across a BLE disconnect.
- Goal: detect that a boot's clock is drifting or mismatched and say so in the export.
- Non-goal: steering or correcting the boot's clock. Raw timestamps stay untouched, as with all raw data.
- Non-goal: changing the 16-byte flash record, the live frame or the download protocol.
- Non-goal: sub-millisecond accuracy. BLE timing and Android callback jitter set a floor of a few ms, and one sample is 10 ms at 100 Hz.

## Protocol: one new control opcode

Recorder-control opcode `09`, "time sync". It is additive, so the control version stays `3`. A firmware without it answers result `6` (invalid command, the existing default case), which the app treats as "not supported, use gesture sync only".

Request (3 bytes, fits the 5-byte `kControlRequestSize`): `09 token[2]`, token a little-endian `uint16` chosen by the phone. Any other length returns result `6`.

Response (the usual 16-byte envelope on the control characteristic, with its own layout for this opcode):

| Offset | Size | Field |
| --- | ---: | --- |
| 0 | 1 | protocol version `3` |
| 1 | 1 | opcode `09` |
| 2 | 1 | result (`0` success) |
| 3 | 1 | flags, as in every response |
| 4 | 2 | token echoed from the request |
| 6 | 8 | sensor time in microseconds, `uint64`, little-endian |
| 14 | 2 | reserved `0` |

Firmware rules:

1. Take `esp_timer_get_time()` in `ControlCallbacks::onWrite`, in the BLE host task, and carry it in the queued `ControlRequest` (a new `uint64_t receivedUs`). Do not read the clock when the request is later processed. Requests are dequeued one per loop tick (queue depth 4), so processing time can lag the radio by a tick or more.
2. Handle it before the storage check, next to zero and mode, so it works with flash unavailable and in both modes.
3. Send the full 64-bit value. The 32-bit timestamps in flash records wrap every 71.6 minutes and the live frame every 49.7 days; the ping resolves both ambiguities.

## Phone side

A pure Kotlin `ClockSync` (no Android types, so it is JVM-tested):

- A ping records `tSend` (`elapsedRealtimeNanos` just before the write) and `tRecv` (in the notification callback). RTT is the difference. The sensor time is taken to correspond to `tSend + RTT/2`, which assumes symmetric delay.
- Keep the **raw pairs** (boot, token, `tSend`, `tRecv`, sensor microseconds) in a new `clock_sync` table (database version 11, with a migration and an instrumentation check) and export them as `clock-sync.csv`.
- Fit `phone = a + b * sensor` per boot using the lowest-RTT pairs in a sliding window (min-RTT filtering, because slow callbacks inflate RTT and bias the midpoint). Report skew in ppm and an uncertainty that grows with the gap since the last good pair.
- Flash samples map through the same fit. Across a disconnect, interpolate between the pairs on either side of the gap; no marker inside the flash stream is needed, because the pairs live on the phone. (Earlier in the discussion I suggested writing markers into flash. That is not needed and would have required a record-format change.) The pings also tie each 32-bit flash timestamp to the 64-bit clock, so wraparound is unambiguous.
- Two boots align to each other by composing their two maps, and their uncertainties add.
- The phone's own motion sensors are in the phone clock already (`SensorEvent.timestamp` uses the `elapsedRealtimeNanos` base on most devices). Verify that on the target phone; do not assume it.

### Cadence (starting values)

- A burst of 8 pings, 100 ms apart, at connect, at run start and at run end, with connection priority raised to high for the burst to shorten the connection interval.
- One ping every 60 s while recording, then back to low priority.
- Extra bursts when the drift monitor fires.

## Drift monitor

The phone already sees each boot's sensor-clock intervals against its own receipt times. The monitor watches, per boot: the residual of each new ping against the current fit, the slope of sensor interval against receipt interval, live sequence gaps, and bursts. When the residual exceeds about 20 ms, the slope moves by more than about 30 ppm, or a gap or burst appears, it sends a fresh burst and marks the affected stretch `clock_uncertain` in the export. These thresholds are guesses until we see real data. This is the feedback loop that replaces an application-level acknowledgement: the notifications themselves stay unacknowledged, and the flash download remains the lossless record.

## Android side as built

Built (JVM tested with simulated offset, skew, jittery round trips, outliers and gaps; not yet run with real boots):

- `ClockSync.kt`, pure Kotlin. `PingPair` holds the raw exchange; RTT is `tRecv - tSend` and the sensor time is mapped to the midpoint. `ClockFitter` takes the lowest-RTT quarter of each burst (pairs less than 5 s apart form a burst, at least one pair each), drops any whose RTT is over 3 times the best plus 20 ms, fits drift as the median of pairwise offset slopes between pairs at least 10 s apart (clamped to 200 ppm; with no such pairs the drift is 0 and only the offset is fitted) and takes the median offset. `ClockTimeline` anchors a local fit at every accepted pair and interpolates linearly between anchors, so a mapping across a disconnect gap is interpolated from the pairs on either side. `uncertaintyNs` is half the best RTT plus the scatter of the nearest good pair, plus 30 ppm of the distance to it. `BootAlignment.map` composes two boots through the phone clock and adds their uncertainties. All thresholds are named constants in `ClockSyncDefaults`.
- Drift monitor, inside `ClockSync.add`. A trusted pair (RTT within 2 times the best plus 30 ms) more than 20 ms from the fit made before it raises `residual`; a fitted slope that moves more than 30 ppm from the last accepted one raises `slope_change`, judged only when the fit spans at least 120 s because shorter spans are noise at a 60 s ping cadence. `LiveDisturbanceMonitor` raises `live_gap` for 5 or more missing live samples and `live_burst` for a 500 ms silence. Each alarm opens a `clock_uncertain` stretch (from the last good pair, or from the start of the disturbance) that closes at the next trusted pair that sits within 10 ms of the fit. A real offset step therefore leaves the stretch open until the fit adapts, which is the intended honest answer. A sensor time that goes backwards by more than 1 s starts a new epoch (the boot restarted).
- Codec: `RecorderCommand.timeSync(token)` and `ClockPong.decode` (version 3, opcode 09, token echo, 64-bit microseconds). A reply with the wrong token is dropped by the service (`decode(bytes, expectedToken)` also rejects it); result 6 marks the boot "gesture sync only" and stops its pings. Control notifications for opcode 09 are routed away from the recorder status decoder. The magnetometer frame (version 5 on the movement characteristic, about 10 Hz) is `MagnetometerFrame`, dispatched by `MotionFrames`; unknown versions are still ignored and reserved flag bits and bytes are ignored rather than rejected.
- Scheduling, `ClockSyncCoordinator`: a burst of 8 pings 100 ms apart at connect and at recording start, with the boots interleaved round by round (`PingPlan`), then one ping per boot every 60 s while recording, and an extra burst for a boot whose monitor fires (at most one per 30 s per boot). Connection priority goes to high for a burst and back to balanced about 1.5 s after the last ping; Android decides what interval it actually grants. `tSend` is taken inside the queued GATT operation just before the write, and `tRecv` in the GATT callback before the hop to the main thread.
- Storage (database version 11): `clock_sync`, `mag_samples`, and `phone_samples` (created for the planned phone-as-third-sensor task and not written yet). Pings from before a recording started (the last 24 of each boot) are stored with it, and a `clock_ref` session event ties the wall clock to `elapsedRealtimeNanos` so the export can place the live stream. Export files are described in [Ski analysis](ski-analysis.md).

Not built yet: a burst at run end (only connect, recording start, the 60 s cadence and monitor alarms are wired), applying the fit to flash samples inside analysis (the maps exist and the export carries them, but `BootComparison` and the graphs still use `LiveSensorClock`), showing sync state in the app other than a "gesture sync only" note on the sensor card, phone sensors in `phone_samples`, and any hardware validation. The monitor's residual threshold (20 ms) is close to the expected scatter of a bad connection interval, so expect false alarms and a rate-limited stream of extra bursts until it is tuned.

## Gesture sync (independent cross-check)

A stomp or sharp tap at the start and end of each session. An offline aligner cross-correlates the spike in each stream (angular-rate or acceleration magnitude) to get offset and drift to within a sample or two. Agreement or disagreement between gesture sync and protocol sync is the confidence measure. It needs no protocol change and is built first. It can be tested before any hardware exists: the Beuken ski data has ten IMUs sharing one clock, so two streams can be offset and stretched artificially and the aligner checked against the known answer.

### Gesture sync results (offline aligner, 18 October 2026)

Built as `ClockAlign.kt` (JVM tested in `ClockAlignTest`), prototyped and validated first in `tools/analysis/clock_align_validate.py`. Method: find the strongest transient in a window near the start and the end of each stream (peak of the magnitude's deviation from its median, which must stand 8 robust standard deviations above the window), reject a window with a second comparable peak, then refine with a normalised cross-correlation of a 0.8 s patch on a 2 ms grid, parabolic interpolation of the peak, and require a correlation of at least 0.7. Two ends give the offset at the start and a straight-line drift. It rejects rather than guesses: no transient, double transient, no match, low correlation, windows too close, or an implied drift above 2000 ppm.

Validation used the Beuken run10 file (10 IMUs on one clock), decimated from 714 Hz to about 102 Hz, with one IMU stretched, shifted and jittered against another. The skiing itself has transients as large as a stomp from the first second, so a stomp (about 11 g, damped 18 Hz, 4 ms rise) was injected at the quietest stretch of the first and last 30 s. With only natural ski transients the aligner correctly refuses (ambiguous), which is the intended behaviour.

- Clean to moderately noisy input: offset error median 0.4 to 0.7 ms (worst 2.1 ms), drift error median 1.3 to 2.5 ppm (worst 8 ppm) over a 168 s span, i.e. well inside one sample (10 ms). Over a 600 s span the drift error scales down.
- Timestamp jitter on synthetic stomps, 20 trials per level: 0, 0.5, 1, 2 ms all accepted 20/20 with worst offset error 2.2 ms and worst drift error 4.3 ppm; 3 ms accepted 19/20 (worst 2.4 ms, 10.6 ppm).
- Failure cases, all rejected not guessed: no stomp at one end, stomp seen by one boot only, flat signal, two stomps 0.4 s apart (ambiguous), 5000 ppm skew (implausible drift), and noise above about 1 m/s2 for a 30 m/s2 stomp (the peak no longer stands out, or correlation falls below 0.7).
- A stomp with an instantaneous onset is far more fragile: half a millisecond of timestamp jitter dropped correlation under the threshold in most trials. Real sensors low-pass their output, so this is not expected, but a stomp shorter or sharper than the sensor's bandwidth will be refused rather than mis-aligned.
- The reported uncertainty (about 5 ms offset, 12 ppm over 600 s) is deliberately conservative, several times the observed error. Tune it against hardware.
- Not yet checked: real stomps on a boot, the effect of the boot's own bandwidth, and agreement with the protocol-level sync, which does not exist yet.

## Files that change together

- `docs/ble-protocol.md` (opcode `09` and the response layout), `src/BluetoothService.cpp` (queue field, handler), a host test of the response encoding.
- `RecorderProtocol.kt` and `RecorderProtocolTest` (request and response codecs).
- New `ClockSync.kt` and `ClockSyncTest` (fit, min-RTT filter, uncertainty, drift detection, simulated offset, skew and jitter).
- `LocalSessionStore` (migration v11), `SensorSessionService` (ping scheduling), `SessionExport` (`clock-sync.csv` and the `clock_uncertain` flag).
- A separate `ClockAlign` for the gesture aligner.

## Expected accuracy and unknowns

The floor is about half a connection interval (roughly 5 to 25 ms depending on what Android negotiates) plus callback jitter, reduced by min-RTT filtering. We do not yet know the real numbers on the target phone; the first measurement on hardware settles it. Two sync methods that agree to within a sample or two would be good enough for the planned metrics; if they disagree, the gesture result is the one to trust, and the cause needs investigating.

## Hardware check (after the kit arrives, kept to a minimum)

1. Ping burst against one boot, plot RTT distribution and the fitted skew over 10 minutes (no skiing needed).
2. Stomp at start and end with both boots and the phone, and compare gesture offsets with the protocol fit.
