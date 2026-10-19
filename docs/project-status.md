# OpenSki status — 19 October 2026

Reviewed against merged PRs #1–#43 and main commit `b6cfd68`. This is a repository and PR review, not a new hardware validation run. Test counts below are reported by PR authors at their respective revisions.

OpenSki now comprises boot-sensor firmware, an Android training/recording/analysis app, and phone-only whole-day tracking. The main development need is integrated validation on the new hardware. Cuff roll is not snow-relative ski edge angle.

## Merged work

Later PRs supersede earlier limitations where explicitly described.

| Merged PRs | Result |
|---|---|
| [#1–#5](https://github.com/tayhall/openski/pull/5) | Indoor POC, training home and nine drills, bench tools, accelerometer correction, capture-rate fixes, IMU retry and build IDs. |
| [#6–#8](https://github.com/tayhall/openski/pull/8) | S3 Super Mini, persistent idle BLE monitoring, Wi-Fi Bluetooth diagnostics, gesture/tilt recognition, ski_v0 half-turns, zeroing and diagnostics/production modes. |
| [#9–#11](https://github.com/tayhall/openski/pull/11) | Rule-based audio coaching, Ready/Zeroing/Running/Saving/Done flow, target comparison reconstructed from half-turn events. |
| [#12–#16](https://github.com/tayhall/openski/pull/16) | Research integration, heading-rate detector, rest gyro bias, quality flags, recorder protocol v3, replay mount-slip monitor, run GPS, firmware/Kotlin parity checks and GPS hardening. |
| [#17–#21](https://github.com/tayhall/openski/pull/21) | Eight technique videos, experimental turn descriptors, battery voltage/low-power support, design guidance and public-dataset analysis scripts. |
| [#22–#27](https://github.com/tayhall/openski/pull/27) | Offline stomp alignment, XIAO/LSM6DSOX and LIS3MDL drivers, session-health and turn-metric exports, clipping/settling fixes, IMU stall recovery and faster analysis windows. |
| [#28–#31](https://github.com/tayhall/openski/pull/31) | BLE clock sync, left/right XIAO builds, live magnetometer, phone-motion recording, gesture-sync export, garden protocol and XIAO battery-divider configuration. |
| [#32–#37](https://github.com/tayhall/openski/pull/37) | Synthetic end-to-end rehearsal, bench/trial tools, long-gap/reset health flags, end-of-recording ping burst, phone-motion switch, trials checklist, aligned boot agreement and torso metrics. |
| [#38–#39](https://github.com/tayhall/openski/pull/39) | Datasheet verification; configured LSM6DSOX gyro bandwidth reported as 33 Hz. This is a datasheet figure, not a measured response. |
| [#40–#43](https://github.com/tayhall/openski/pull/43) | Whole-day design, descent/lift/rest splitter, piste matching, GPX codec, cached Overpass resort data, tracking service, reports, tags and GPX/JSON screens. |

Each grouped link opens the final PR in that range; the full history is in [merged PRs](https://github.com/tayhall/openski/pulls?q=is%3Apr+is%3Amerged).

## Current features and architecture

- **Firmware:** cooperative acquisition, flash recording, BLE, Wi-Fi/HTTP and OTA services. MPU-6050 builds acquire/stream at 100/50 Hz; LSM6DSOX builds at 104/52 Hz. Diagnostics is the default at boot; production disables Wi-Fi and raw live streaming while retaining movement/state events. Recogniser corrections leave raw samples unchanged.
- **Hardware:** DevKit V1 remains the default build; S3 Super Mini is supported. New boot targets are `xiao-left-mag` (XIAO ESP32S3, LSM6DSOX, LIS3MDL) and `xiao-right` (without magnetometer), with an 8 MB partition layout. Magnetometer is live-only in diagnostics, not flash-recorded. XIAO battery sense assumes a 2 × 100 kohm divider with 100 nF on D0/GPIO1; wiring and accuracy need measurement.
- **Training:** Today/Path/Progress/Boots launcher, demo/live one-boot drills, scores, stars, unlocks, streaks, ghost traces, technique videos and metronome/chirp coaching. In the merged baseline demo attempts count toward progression; the practice-records branch separates them from measured achievements. Scores describe dry boot movements, not skiing technique.
- **Runs:** two-boot mode/zero flow, stored half-turns and coaching verdicts, flash recovery and reconstructed target comparison. Optional phone GPS supplies distance, speed and vertical drop. Run GPS is stored locally but is not included in session ZIPs.
- **Recording/analysis:** `SensorSessionService` owns BLE, recording and recovery; activities observe it. The merged baseline uses SQLite version 13; the practice-records implementation branch adds version 14. Live and flash sources remain separate; staging and committed-copy validation protect flash erase. Exports include health, quality, heading turns, turn descriptors, mount-slip checks, ping/stomp timing, magnetometer, phone motion and torso metrics where available. Clock alignment shifts derived boot agreement results while preserving raw clocks.
- **Ski days:** the separate `DayTrackingService` records phone GPS/barometer data without boots. `DayStore` retains days, fixes and tags; reports split/name descents, lifts and rests and can associate ended boot sessions. Active sessions are excluded from boot-stat reads. Overpass resort data is cached with OSM attribution. Days export separately as `ski-day.gpx` and `ski-day.json`; GPX tracks can be imported.

## Evidence and validation gaps

Implementation branches now include durable source-separated practice records (draft PR #45) and service-owned drill lifecycle (`feat/drill-lifecycle`). The latter preserves capture across screen detachment and reconciles abandoned attempts on process startup without granting completion rewards. Debug/instrumentation builds, lint, focused JVM checks and emulator storage/demo lifecycle checks pass; real BLE and screen-lock evidence remains outstanding. See the [implementation plan](superpowers/plans/2026-10-19-app-improvements.md) for branch tracking.

`feat/accessible-practice-ui` adds native action roles, selected navigation, coaching updates that preserve controls/focus/scroll, accessible chart facts and links to the exact saved run or practice attempt. At this revision 663 JVM tests have zero failures (one opt-in release check skipped); builds, lint and emulator storage/UI regressions pass. Portrait demo results and saved summaries were reviewed at 130% font. Full TalkBack and field ergonomics still need device validation.

`feat/guided-boot-readiness` adds guided discovery/assignment, mounting/zero, typed readiness actions and shared recovery status on Today, Run and saved results. Mode changes are guarded during capture/recovery and restoration waits for reported confirmation. Paused recovery persists; previously validated phone copies remain visible after retry failure. The final full JVM suite passes at 683 tests with zero failures (one opt-in release check skipped). Final builds, lint and full emulator storage/setup/UI checks pass. Real BLE discovery/recovery, acknowledgement loss, low storage/audio-route recovery and field ergonomics remain unverified.

Historical MPU-6050 evidence includes roughly 9.5 hours of USB-powered Wi-Fi acquisition at 100 samples/s with zero I²C failures, cold-start bias measurements and handheld/tilt trials. See dated [bench results](bench-results.md) and [bench data](../data/bench/README.md). These do not validate XIAO behaviour, battery life or snow recognition.

Earlier PRs exercised demo/training/run screens and storage instrumentation on a Pixel 9a emulator; PR #16 reports instrumentation passing. PR #43 reports **644 JVM tests**; PR #39 reports **40 native tests** and an xiao-left-mag build. These historical results were not rerun as part of this documentation review. New schema v11–v13 migration checks and ski-day Android flows still need device/emulator execution. Timing/torso metrics and the ski-day engine have synthetic coverage, without real boot/day validation.

Next priorities:

1. Execute [Android acceptance](android-acceptance.md), especially v11–v13 migrations, real BLE recovery, run/audio/GPS behaviour, screen-off phone sensors and ski-day lifecycle/export flows.
2. Run [garden trials T0–T6](garden-trial-protocol.md) on the XIAO pair; use [analysis tools](../tools/analysis/README.md) on real exports. Pass lines are starting values. Compare ping and stomp alignment; asymmetric BLE timing remains a limitation.
3. Verify XIAO acquisition, magnetometer, battery wiring/voltage, cold behaviour and runtime. Check cuff angles and mounting/slip against independent measurements.
4. Test real Overpass downloads, cached/offline naming and a real GPS day. Set `HttpResortFetcher.CONTACT` before a public build and run the opt-in release check. Day-tracking battery cost is unmeasured.
5. Obtain labelled snow recordings before claiming turn accuracy, carve/skid classification, true edge angle, pressure, radius or technique quality.

Recorder protocol has no recording ID or independent checksum. Recover and erase retained pre-v3 recordings before upgrading firmware; otherwise old timestamp bits can be interpreted as quality flags. See [BLE compatibility](ble-protocol.md).

## Documentation guide

| Need | Start here |
|---|---|
| Setup and hardware | [Root README](../README.md), [Android README](../android/README.md), [bring-up](bring-up.md), [POC plan](poc-plan.md), [battery diagnostics](battery-diagnostics.md) |
| Shared wire contract | [BLE protocol](ble-protocol.md), [IMU data](imu-data.md) |
| Definitions and limits | [Ski analysis](ski-analysis.md), [turn metrics](turn-metrics.md), [clock sync](clock-sync-design.md), [research findings](research-findings.md) |
| Whole-day architecture | [Ski-day design](ski-day-design.md) |
| Validation | [Android acceptance](android-acceptance.md), [garden trials](garden-trial-protocol.md), [bench results](bench-results.md) |
| Feature status | [Feature roadmap](feature-roadmap.md) |
| Proposed app improvements | [Design, Kotlin and coaching review panel](app-review-panel-2026-10-19.md) — independent top fives, panel decisions and build shortlist |
| App improvement delivery | [Implementation plan](superpowers/plans/2026-10-19-app-improvements.md) — eight proposed PRs, dependencies and release gates |

`docs/superpowers/specs/` and `plans/` preserve designs/plans. `docs/skivids/` and `docs/technique-research/` preserve imported research. Read dated snapshots alongside this status and the implementation.

## Compact practice layout validation

The compact-layout branch keeps phase commands outside scrolling explanations. Debug/instrumentation builds and lint pass. Emulator UI regressions pass, including READY, ZEROING Retry/Cancel and SAVING at 130%/200% font, portrait/landscape, and 120/160dp keyboard insets. These synthetic geometry checks do not replace real-device TalkBack or glove testing.
