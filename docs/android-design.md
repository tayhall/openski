# OpenSki Android design

The app uses a shared native design system in `SkiUi.kt` and `Theme.OpenSki`, with a charcoal background, lime primary actions and sky accents for the right boot. Screens use the same cards, typography, button states, dialogs and inputs. No external UI dependencies or generated image assets are required.

## Training experience

The app opens on a training programme rather than telemetry. `HomeActivity` has Today (next drill, week streak, progress toward the chosen goal), Path (piste map of drills that unlock in order), Progress (practice points, streak, best scores) and Boots (opens the original recording screens below). First run asks for a goal and starting level, then offers demo boots or pairing.

It uses a separate light "snow" look (`Snow.kt`): Barlow Condensed headlines and Barlow body text, one orange primary action per screen, and piste markers (blue circle, red square, black diamond) so level never depends on colour alone. A drill ends with the boot-roll trace drawn as a carved line over a ghost of the previous try, a 0 to 3 star score, one coaching cue, and practice points. Scores combine tempo, steadiness, left and right balance and depth from the existing dry-movement detector; they do not measure carving on snow. Drills run on a paired boot or on simulated demo boots, which are always labelled. A live drill connects to a streaming boot, asks once which sensor axis points toward the toe, calibrates from three still seconds, then counts rolls as you make them while the carved line grows. The set is saved to the logbook as a test session named "Drill · <name>", so it can be replayed and exported like any other recording. Tempo and steadiness are scored on the beat (the time between roll starts) because that is what "one roll every 3 seconds" means to a skier.

**Bench tools** (Boots tab) check a loose board from raw readings, with no calibration or recording: a six-face accelerometer check that reports offset and scale error per axis, tilt accuracy against a reference angle you type in, and still gyro bias and noise. After all six faces, the offset and scale can be saved as a per-sensor accelerometer correction; the tilt rows then show raw and corrected error side by side, and orientation uses the correction while recorded and exported raw data stay untouched. A Landmarks step learns which sensor axis points at the board's holes edge and chip side, so the mounting question reads "toward the holes edge" rather than "+Y toward the toe". Results can be copied or shared as text.

The Boots tab shows pairing and mounting and opens **Geek mode**: the original dark Record, Sessions and Test lab screens with raw telemetry, orientation, flash recovery and session analysis. Nothing from that workflow was removed. The recording and review screens below keep the earlier dark theme for now.

## Navigation and hierarchy (recording and review)

- **Record:** primary recording action and status, boot connections, live orientation, expandable raw telemetry and sensor setup/recovery. A demo shortcut leads to Test lab.
- **Sessions:** dated session history and a useful empty state. Synthetic demos and indoor tests retain clear labels.
- **Test lab:** prominent sensor-free demo, test/calibration workflow, guided sets and expandable observer markers.
- **Replay:** motion timeline, calibration, labels/alignment and video. Calibrated recordings open on estimated boot roll; recordings without calibration open on acceleration.
- **Analysis:** indoor movement summary, analysis settings, timing, roll profiles, dry-movement evaluation (with one line of experimental heading-rate candidates beside the roll-lobe count) and boot comparisons. Methodological details are expandable.
- **Details:** metadata, equipment, recording quality, sampling, export and protected deletion.

Tabs retain selection during rotation. Each tab has its own scroll container. Landscape headers become compact to preserve working space. The replay back control returns to the previous dashboard tab; selecting a detected movement opens Replay and seeks to its onset. Leaving Replay pauses video.

## States and accessibility

Controls use at least 48dp minimum touch targets, wrap their text and expose disabled/pressed states. Record/stop actions change appearance with recording state. Empty values remain unknown rather than invented measurements. Demo and experimental labels remain visible; destructive controls use a distinct danger treatment and confirmation.

Charts use density-aware geometry and scaled text. Right-boot connecting traces are dashed as well as blue. Axis ranges have a sensible minimum span so floating-point noise does not fill the chart. Timeline labels avoid overlapping one another. Dragging and keyboard left/right navigation select a moment. System bars and display cutouts are accounted for by the root layout.

## Practice history and provenance (implementation increment 1)

Path and Progress provide My practice and Demo views. All demo drills are open for exploration. Only completed measured sets contribute to My practice bests, stars, streaks, points and unlocks; incomplete sets remain in recent history with their status. Virtual rewards are practice points, distinct from GPS vertical descent.

SQLite v14 stores stable attempt IDs, optional raw-session links, completion status, target snapshots and scoring versions. Old preference attempts are imported once in a transaction; duplicate-looking old attempts remain separate. Original scores and source flags are preserved; old targets and session links remain unknown. Malformed imports leave existing history intact and show a load/save error. A failed save can be retried under the same attempt ID without another reward. Raw-session deletion leaves the practice history intact.

Verification on 19 October 2026: 650 JVM tests with zero failures (one opt-in release check skipped), debug/instrumentation builds, lint and StorageInstrumentation passed. Emulator UI review covered the source picker, demo result and unchanged measured totals. Physical BLE was not exercised.

## Service-owned drills (implementation increment 2)

`DrillController` owns preparation, connection/mounting, calibration, capture, saving and terminal outcomes inside `SensorSessionService`. `DrillActivity` observes immutable snapshots; leaving, rotating or recreating the screen detaches only its subscription. Dedicated observers do not replace Geek mode's telemetry listener. Live orientation feeds the controller directly from the service.

An ACTIVE attempt and reserved raw-session ID are committed before live capture opens. Terminal saves update that identity once; repeated save requests return the committed result. Process startup or service recreation marks unfinished attempts interrupted and atomically closes only their matching open raw sessions, leaving sensor recovery protections intact. Failed recovery remains retryable through history loading. A saved summary can be restored after background completion without another reward. Full scored-set reconstruction after process death is deliberately not claimed.

Live practice uses the connected-device foreground service, wake lock and a return/Finish notification action. A bounded sensor-free demo uses a short started service without requiring Bluetooth permission or pretending to play media; if Android kills it, restart reconciliation marks it interrupted. Manual recordings, runs, coaching, sensor replacement and calibration cannot silently take ownership from an active drill. Mounting opens explicitly and repeated updates do not stack pickers.

Pause/audio practice controls remain planned for increment 8. New run layout and guided setup changes remain separate increments.

## Verification

### Accessible controls and result identity (implementation increment 3)

Shared Snow actions are platform buttons. Home navigation and comparison-window controls expose selected state; onboarding choices expose checked radio semantics and text headings expose heading semantics where supported. Coaching status/verdict updates retain the same scroll container and controls. Only phase/status titles announce politely; per-window metrics are available for exploration without an automatic announcement on every update. Run announcements are limited to phase changes rather than a live region around the entire updating screen.

`CarvedLineView` uses scaled text and an accessibility summary generated from its actual trace, target and comparison. Callers label measured, simulated and reconstructed data explicitly. A missing target has no invented target instruction. Comparison strokes are dashed and legend text distinguishes a previous attempt from a target wave.

`PracticeResultActivity` reads the exact committed `attempt_id`; it never rescores or awards a result. Standalone imported/demo results retain their summary without raw data. Unknown historical targets and missing/deleted linked sessions are explicit. Run completion captures its `session_id` in the action and opens `SessionDetailActivity` directly; missing sessions offer a visible fallback, and load failures offer retry.

The redesign passed debug/instrumentation builds, 30 JVM unit tests and Android lint. Emulator review covers dashboard navigation, demo creation, Replay/Analysis/Details, 130% text, portrait/landscape and selected-tab restoration. Existing SQLite/export/recovery instrumentation remains part of acceptance. Outdoor readability with gloves and physical camera/video timing require real-device field checks.

## Guided preparation and shared recovery (implementation increment 4)

`Readiness.issues` carries issue kind, severity, affected boot and resolution action; the first blocking message remains available as `reason`. Run readiness reports all relevant problems, with mounting shown as guidance for dry-ski drills. Capture conflicts block demos as well as measured starts; demo previews do not require physical boot readiness. Run starts recheck pending capture records before changing modes. Optional GPS does not become a blocker.

`BootSetupActivity` provides discovery using the existing service UUID, guarded Left/Right assignment, mounting, neutral zero, recovery/retry, audio/settings and storage/export routes. It displays saved assignment separately from live readiness and confirmed mode/zero state. Screen updates retain existing controls and scroll. Setup observes via snapshots and never replaces the Geek telemetry listener. Mounting is checked again when a choice is saved, protecting a capture that began while the chooser was open.

`RecoverySummary` projects persisted captures plus the matching capture ID's transfer progress, validation stage, pause and error facts. A fully downloaded transfer is not a validated phone copy; `verified` still means sensor erase is pending. `erased` is the completed recovery state. Missing sensor copies and live-only captures have explicit explanations. Passive store reads run on the service I/O executor; readiness uses its latest pending-side snapshot, while commands recheck the store. No new transfer state machine or erase path is introduced.

A later retry can reset capture status to pending while an earlier validated copy remains in `flash_samples`. Recovery summaries check that committed-copy presence and explicitly retain it in the explanation without declaring the current sensor recovery complete. Assignment guards read saved addresses as well as live connection state, so a fresh setup service cannot assign the same sensor to both boots or erase saved assignments during a swap.

`RecoveryCard` is shared by Today, Run saving/completion, persisted practice results and raw session review. It owns a small observer binding, not recovery itself. Leaving a screen detaches its card; transfer work continues. Persisted records restore pending/checked/error states, and the pause preference restores paused status across service creation. Opening preparation does not resume recovery; connect/resume and retry actions do so explicitly.

Mode commands in either direction refuse active capture and pending recovery. The run adapter may issue its own mode request only when recovery is clear; repeat requests are bounded while waiting for telemetry. Queued Training requests stay pending in the run controller until reported mode confirms them. A disconnected boot has unknown current mode; its previous neutral-zero report is discarded. No firmware/protocol or scoring change is included.

`ModeRequestTracker` correlates ordered explicit SET_MODE acknowledgements, rather than accepting an old periodic heartbeat as command confirmation. Initial cancellation always queues Training behind the prior On snow request, even while the last report still says Training. Pending requests keep the mode unknown until their acknowledgement ordering permits confirmation; tests cover immediate cancellation, older acknowledgements, missed acknowledgements and rejected writes.

## Compact phase layouts

`PhaseLayout` separates a weighted scrolling content region from persistent native action buttons. Parent screens apply system/cutout/keyboard insets once. Stable action keys preserve button identity and focus while status or readiness details change. Run Ready leads with the current target and boot/audio checks; settings expand separately. Drill instructions and secondary setup tools expand without placing more content before the active action. Long readiness reasons remain available in the scrolling details; the footer retains a bounded two-line summary (one line when available height is short). In short windows, Retry/Cancel share one row so keyboard insets cannot force both below the content area. No Pause control is introduced before active-interval scoring supports it.


## Practice history and repeat

History uses bounded lightweight SQL keyset pages ordered by timestamp and ID; linked drill raw sessions do not appear as duplicate runs. Repeat preparation revalidates the stored source/target/version on the service IO path. The controller receives the snapshot-adjusted Drill, so detection, scoring, targets and saved results agree; fresh IDs and calibration are retained. No schema migration is needed.
## Explained practice results (increment 7)

SQLite v15 adds nullable review_json/score_eligible to practice_attempts and an independent practice_reflections table. Review facts and reward eligibility commit in the same transaction as the terminal attempt; immutable terminal identities remain idempotent. Null legacy eligibility preserves original achievements. Optional notes update separately and never rescore an attempt. Raw deletion has no cascading practice/reflection relationship.

PracticeEligibility separates completion, insufficient evidence, interrupted, active, unknown historical evidence and awaiting recovery. Replay quality cannot retroactively upgrade live scoring evidence. PracticeComparisonPolicy excludes unknown/mismatched source, target, version, timing policy, scored boot and mounting. Same unsupported versions are excluded. Simulated sources have no invented physical context. New live review retains sensor rail/drop/timestamp-gap flags, motion-gate rejection, changed orientation references and the existing detector timing-reset rule (>100 ms); any such issue makes this set unscored under conservative starting screening rules. No new numeric technique-quality threshold is introduced. Continuous scoring is unchanged; the review collector accepts explicit movement groups for a later segmented timing policy and never pairs beats across groups.
