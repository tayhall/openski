# ski_v0: on-boot half-turn recogniser (spec A)

Status: draft for review, 8 October 2026. Experimental. Nothing here is validated on snow; the angles are cuff lean in a sensor-derived frame, not ski edge angle (see [ski analysis](../../ski-analysis.md)). Turn classification is not implemented or claimed.

## Purpose

The boot sensor decides locally when a half-turn has happened and sends a small event, so the radio and CPU can stay quiet between events (battery life, less BLE chatter). The phone does the interpretation using all its signals (GPS, barometer, events from both boots). This spec covers the boot side, the wire format and the minimum app changes. Phone-side context (spec B) and extra hardware (spec C) are separate.

Success criteria:
1. Given synthetic ski-like traces, the firmware emits one correctly signed event per half-turn, with peak roll within ±3° and no phantom events (host tests, no hardware).
2. In production mode the boot has Wi-Fi off and sends no live samples, and a phone receives events and a 1 Hz state frame. In diagnostics mode (the default) Wi-Fi and the raw stream behave as they do today.
3. The existing app keeps working, and the new app code decodes the new frames.

Assumptions (not yet measured): cuff mounting from [poc-plan](../../poc-plan.md); the expected-value envelope in this document; the radio saving. The saving is expected to be large but is unmeasured, so no figure is claimed until the Phase 0 baseline exists.

## Modes

The boot has two runtime modes. Diagnostics is the default after every boot; production is chosen by the phone and never survives a reboot.

| | Diagnostics (default) | Production (on the slope) |
| --- | --- | --- |
| Wi-Fi, HTTP API, OTA | on | off |
| Live 50 Hz sample stream | on | off |
| `ski_v0` events (version 2) | on | on |
| 1 Hz state frame (version 3) | on | on |
| Flash recorder | unchanged, full 100 Hz when recording | unchanged |

- Mode is set by recorder-control command `08 mode` (`0` diagnostics, `1` production) and reported in response flag bit 6 (set = production) and in the state frame.
- Switching to production disconnects Wi-Fi and powers the radio down; switching back rejoins. A power cycle always returns to diagnostics, so OTA is always reachable after a reboot. A boot that loses its phone link in production stays in production.
- OTA and the HTTP server stop with Wi-Fi and restart when it returns. Unverified on hardware: OTA after a production-to-diagnostics round trip (checked once in the final task of the plan).
- The state frame is sent in both modes (a change from the first draft, which sent it only in events-only mode) so the phone side can be developed without switching modes. Its flag bit 1 says which mode is active.

## Mounting map

Compile-time default in `include/AppConfig.h`: which sensor axis is L (up the leg), F (forward) and S (lateral), and each sign. Roll is rotation about F, positive towards +S. Pitch is rotation about S, positive forward. Boots mounted identically on the outer cuff have mirrored lateral sign, so the right boot uses the flipped map. The map can later be overridden over BLE.

## SkiTracker (`lib/Motion/src/SkiTracker.h`)

Portable, host-testable, no Arduino dependency, one `update()` per sample with corrected sensor-frame inputs. `tilt_v1` is unchanged and keeps running for comparison.

- **Gravity fusion.** Own gravity vector carried by the gyro. The accelerometer pull weakens linearly as |a| leaves 1 g and is zero beyond 15% error, so hard turns follow the gyro. Samples are skipped (state held) only for impacts above 2.5 g, free fall below 0.3 g, or gyro readings above 12 rad/s.
- **Angles.** After a zero pose, roll about F and pitch about S are separate signals.
- **Half-turn event.** Starts when roll leaves ±8°; ends when it crosses zero and exceeds ±8° on the other side (or on return inside the band after at least 150 ms). Tracks peak roll, peak roll rate (derived from the fused roll, smoothed with a 25 ms time constant), duration, and pitch at the peak. Gap over 100 ms abandons the event and re-seeds.
- **Envelope flag.** Outside envelope when peak roll exceeds 60°, rate exceeds 300 °/s, duration is outside 0.3–4 s, or pitch is outside −15° to +45°. A plausibility flag, not a quality score.
- **Zero.** Explicit command only. Captures the pose after 1 s of stillness (under 0.12 rad/s, within 5% of 1 g). No auto-zero at boot while the unit may be off the leg.

## Wire format (movement characteristic `…0006`, shared with `tilt_v1`)

Byte 0 is the frame version. Version 1 is the existing 15-byte `tilt_v1` event. All fields little-endian.

Version 2, 18 bytes, one per half-turn:

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | version | `2` |
| 1 | 1 | flags | bit 0 side `+` (roll > 0), bit 1 outside envelope, bit 2 pitch outside its envelope range, bit 3 samples were skipped (impact gate) during the event |
| 2 | 2 | sequence | `ski_v0` event count mod 65536, starts at 1 after boot |
| 4 | 4 | start time | ms, same ESP32 clock as the live frame |
| 8 | 2 | duration | ms, saturating |
| 10 | 2 | peak roll | signed, /100 = degrees, sign is the side |
| 12 | 2 | peak roll rate | unsigned, /10 = °/s |
| 14 | 2 | pitch at peak | signed, /100 = degrees forward of the zero pose |
| 16 | 2 | reserved | `0` |

Version 3, 16 bytes, one per second in both modes (a state heartbeat so the phone can see stillness, lift-like riding and drift without the raw stream):

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | version | `3` |
| 1 | 1 | flags | bit 0 zeroed, bit 1 production mode, bit 2 sample gap in the last second |
| 2 | 2 | sequence | heartbeat count mod 65536 |
| 4 | 4 | time | ms |
| 8 | 2 | roll now | signed, /100 degrees |
| 10 | 2 | pitch now | signed, /100 degrees |
| 12 | 2 | vibration | unsigned, /100 m/s², RMS of high-passed acceleration over the second |
| 14 | 2 | gyro activity | unsigned, /10 °/s, RMS over the second |

Delivery: one notification per event or heartbeat, at most one per loop, no replay for frames missed while disconnected (gaps show in the sequence; the flash recording is the backstop). Last-sent sequence is tracked separately for each frame type so one cannot mask another. A read returns the latest frame.

## Commands (recorder control characteristic, 16-byte response unchanged)

- `07` zero: starts the 1 s stillness capture for `SkiTracker` and `TiltTracker`. Both new opcodes work without recorder storage (they are handled before the storage-ready check).
- `08 mode`: `0` diagnostics, `1` production (see Modes). Any other value or length is result `6`. Mode is volatile. Flash recording is independent of mode and keeps the full 100 Hz stream, so raw data remains the reference for validating the recogniser. Response flags gain bit 6 = production.
- Older apps send neither; unknown opcodes already return result `6`.

Wi-Fi (diagnostics mode only): `GET /api/v1/motion` gains a `ski` object (algorithm, zeroed, roll, pitch, counts, last 16 events); `POST /api/v1/motion/zero` zeroes both trackers.

## App changes (`android/`)

Needed because `MovementEvent.decode` accepts only version 1 / 15 bytes and silently drops anything else.

- `SkiEvent` and `SkiState` decoders for versions 2 and 3 with strict validation; `MovementEvent` keeps its decoder and gains only a shared `MotionFrame` marker.
- `BleSensorClient` movement branch dispatches on the first byte.
- `SensorSessionService.Listener` gains `onSkiEvent` and `onSkiState` (empty default bodies).
- `SensorSessionService` gains `zeroSensor`, `setSensorMode` and `connectedSides`, and `RecorderStatus.production`.
- One small Test lab card, "Boot sensors · experimental": zero both boots, switch both to production or diagnostics, and show the latest half-turn and state per boot. This is the only way to drive the new commands, so it is in scope; there is no other UI for events.
- `RecorderProtocol` gains opcodes `07` and `08` and response flag bit 6.
- No persistence or export of events in this spec.
- Update `docs/ble-protocol.md` and `docs/bench-motion-recognition.md`; keep the experimental framing.

## Testing

Host: `tools/test_ski_tracker.cpp` with a synthetic generator.

| Case | Expectation |
| --- | --- |
| ±30° roll at 1 s, 2 s, 3 s per turn | One event per half-turn, alternating signs, peak within ±3° |
| Constant 20° forward flex plus roll swings | Events found, roll leakage under 3° |
| 15° slope offset on roll | Events found, unequal left and right peaks |
| 1.2–1.5 g centripetal load | Roll from gyro, no samples dropped |
| 2 °/s gyro bias over 60 s of turning | Drift bounded, straight stretches near 1 g pull it back |
| 6 g spike for 20 ms | No phantom event |
| Gap over 100 ms | Event abandoned, tracker re-seeds |
| Mirrored mounting map | Signs flip, magnitudes unchanged |
| Walking tremor, sub-8° wobble | No events |
| Peak over 60° or rate over 300 °/s | Emitted with the outside-envelope flag |
| Heartbeat in stillness and in motion | Vibration and gyro activity separate the two |

Synthetic tests show the code matches the model, not that the model matches a skier; the envelope is checked by poc-plan Phases 1 and 2. Android unit tests: v2 and v3 valid/invalid/truncated/out-of-range, v1 still decodes, dispatch by version, opcodes `07` and `08` in `RecorderProtocolTest`. Firmware must also build for both environments.

## Out of scope

Phone-side context (stopped, walking, skiing, lift) from GPS, barometer and events (spec B). Barometer, ski IMU and insole hardware (spec C). Any UI for events. Persistence and export of events. A claim of turn classification or edge angle.

## Open items

- Verify the connection-interval and latency settings that actually reduce radio use in production mode, and measure current draw (poc-plan Phase 0 step 6 baseline first).
- Whether the heartbeat needs a second rate for long stops.
