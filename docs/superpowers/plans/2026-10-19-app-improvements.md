# Android application improvements — implementation plan

Date: 19 October 2026. Baseline: main `b6cfd68`, merged PRs through #43, and the current documentation refresh.

Input: [design, Kotlin and coaching panel](../../app-review-panel-2026-10-19.md). This plan defines proposed work; no application changes or new validation results are implied. Relative sizes describe scope, not calendar estimates.

## Outcome

A skier can prepare the boots, complete a reliable practice set without watching the screen, understand one useful result, and find or repeat that practice later. Demo exploration stays available without becoming a measured achievement. Saving, missing data and interrupted practice remain visible and recoverable.

## Product decisions for implementation

1. Retain Today / Path / Progress / Boots. Add a History route from Today and Progress, plus the most recent result on Today. Evaluate a tab rename only after testing these routes.
2. Separate **My practice** and **Demo** histories and progression. Demo exploration is freely accessible and never unlocks measured achievements. Preserve the existing experience-based starting level for My practice. Existing demo attempts remain visible under Demo.
3. Rename virtual drill rewards **practice points**. Reserve **vertical descent (m)** for GPS measurements. Measured practice still means a cuff-roll exercise, not validated skiing skill.
4. Keep the current preset targets and scoring available as legacy scoring version 1. New focus-based targets require an explicit scoring version and user confirmation. Never silently rescore old attempts.
5. Survive Activity recreation while the service lives. After process death, recover saved raw data and show an interrupted attempt; do not pretend to reconstruct an uninterrupted scored set. Full automatic drill reconstruction is outside this iteration.
6. Accessibility is an acceptance condition for each changed screen. Keep native Views and existing design tokens; no new UI framework is required.
7. Use existing recorder, BLE, calibration, audio and resort capabilities. Preserve staged recovery, validated-copy-before-erase, independent boot clocks, raw sample provenance and production-mode recording guards.

## Proposed user flows

**Prepare:** Today → Start a run or Start practice → compact left/right/audio checks → resolve a blocker in context → confirm target/settings → Start. Optional GPS failures explain their effect without becoming a new mandatory boot-recording requirement.

**Practise:** countdown → movement count and status → persistent Pause/Finish for drills or Stop for runs → completion/save state. Locking the screen continues an active set; it does not silently pause it. Pausing is explicit and excludes the interval from scoring.

**Review:** source and recording status → complete/incomplete/cannot judge → one observation and next action → Repeat target / Open exact record. Results awaiting flash recovery say so and refresh after recovery.

**Return:** Today’s last-result card or Progress → History → Runs / Drills / Ski days, with source filters. Existing session detail and ski-day detail remain the viewers; a merged timestamp-by-timestamp timeline is deferred.

## Delivery sequence

| PR | Scope | Size | Depends on | Release |
|---|---|---|---|---|
| 1 | Durable practice identity, attempt storage and source-aware progress | M | Current storage baseline | Trust and continuity |
| 2 | Service-owned drill controller and interruption handling | L | PR 1 | Trust and continuity |
| 3 | Shared accessible controls, stable updates and exact-result routing | M | PR 1 for drill routing; can start independently | Trust and continuity |
| 4 | Structured readiness, guided setup and background-save visibility | M–L | PR 2 ownership guards; PR 3 controls | Prepare and practise |
| 5 | Compact preparation and persistent phase actions | M | PRs 2–4 | Prepare and practise |
| 6 | Discoverable History and repeat-record settings | M | PRs 1–4 | Learn and return |
| 7 | Explained results, eligibility and optional reflection | M | PRs 1, 2, 6 | Learn and return |
| 8 | Pocket-friendly audio/pause and focus-target pilot | M–L | PRs 2, 5, 7 | Focused practice pilot |

PR 1 supplies completion identity before the controller moves. PR 2 remains the first major workflow change. Small semantic-control and routing fixes in PR 3 may run alongside these; merge related storage work sequentially. Each PR must leave a usable app and update relevant docs/checklists.

### PR 1 — Durable attempt identity and trustworthy progress

**Problem:** `Attempt.demo` already exists, but `Progress` combines demo and physical attempts. Attempts live in SharedPreferences, lack stable IDs/session links and cannot support durable exactly-once completion.

**Work:**

- Extend the practice model with stable attempt ID, optional session ID, source, completion status, target snapshot and scoring version. Preserve original score/stars/reward fields. Represent interrupted or unscored attempts explicitly rather than as zero-score failures.
- Add a small SQLite practice-attempt store using the next available schema version (currently 13 → proposed 14). Include an optional link to the raw session, but allow standalone demo and historical attempts. Session deletion must not cascade-delete achievements; handle a missing referenced session honestly.
- Import existing `ProgressStore` attempts once, transactionally and idempotently. Use a stable legacy import key so an interrupted migration or identical-looking attempts cannot duplicate or collapse history. Keep source/order/original values. Legacy target metadata is unknown, not invented; legacy attempts remain visible but are excluded from target-specific comparisons.
- Make SQLite the single writer after import. Retain SharedPreferences for profile, mounting, corrections and ghost traces. Do not dual-write achievements across stores.
- Add explicit source selection to progress rules. Measured bests/stars/unlocks/streaks/points use eligible measured completions only; Demo remains separately browsable and freely explorable. Explain the correction to users whose previous aggregate included demos.
- Replace visible virtual “vert” labels with practice points. Keep legacy stored values unchanged. Audit Today, Path, Progress and drill results for consistent badges and units.

**Primary files:** `Programme.kt`, `ProgressStore.kt`, `LocalSessionStore.kt`, `HomeActivity.kt`, `DrillActivity.kt`; proposed `PracticeRecords.kt`, `PracticeStore.kt`.

**Done when:** all-demo history produces no measured personal best, streak or unlock; real completed attempts retain their original values; imported history appears once after repeated upgrades; interrupted/unscored attempts do not award stars; missing old session links do not break history.

**Verification:** extend `ProgrammeTest`; migration/round-trip and repeated-import checks in `StorageInstrumentation`; cover mixed histories, duplicate-looking legacy attempts, no attempts, incomplete attempts and session deletion. No scoring formula change in this PR.

### PR 2 — Reliable service-owned drills

**Problem:** `DrillActivity` owns phase/recorder; `onCreate` opens intro and `onDestroy` stops unfinished owned recording.

**Work:**

- Introduce a pure `DrillController` with immutable snapshots and commands. Model preparing, connecting, mounting, calibrating, ready, running, saving, completed, cancelled and interrupted states; distinguish successful completion from early Finish.
- Host controller and `LiveDrillRecorder` in `SensorSessionService`, following the run-controller adapter pattern. Feed orientation directly from the service pipeline, independently of whether an Activity is subscribed. Serialize controller commands and completion on a defined thread; UI callbacks receive snapshots on the main thread.
- Keep calibration success as a typed result rather than parsing text for the word “calibrated”. Reject stale calibration callbacks using attempt/session identity.
- Add drill-specific subscriptions; do not replace the single generic service listener in a way that detaches another screen. Removing an Activity subscription must not clear unrelated listeners or stop capture.
- Persist attempt identity before capture, link the created session, and commit completion/reward once using the attempt’s unique ID. Route demo completion through the same result-persistence contract.
- Explicitly arbitrate recording ownership: one raw boot recording owner among run/drill/manual test; starting another flow returns an actionable conflict. Whole-day phone tracking remains independent.
- On rotation/navigation/background, detach only the view. Add a notification/return route for an active drill and an explicit stop/cancel path. Preserve foreground/wake-lock and recovery behaviour.
- Use monotonic time for deadlines; retain existing sample/session clock conventions for stored data. On process restart, reconcile an unfinished attempt with the existing open-session/recovery mechanism and mark it interrupted. Recover raw data without granting a completed reward.

**Primary files:** `DrillActivity.kt`, `LiveDrill.kt`, `SensorSessionService.kt`; proposed `DrillController.kt`; PR 1 practice store.

**Done when:** rotation/recreation preserves phase, count and session; leaving and reopening returns to the active set; late callbacks cannot finish another attempt; repeated completion produces one record/reward; run/manual/drill conflicts cannot stop each other’s recordings; process death produces a truthful interrupted result.

**Verification:** pure controller tests for every interruption boundary, duplicate commands and stale callbacks; storage completion idempotency; emulator rotation/recreation tests; phone background/lock, reconnect and service restart checks. A surviving Activity and a restarted process are distinct test cases.

### PR 3 — Accessible components and exact-record links

**Work:**

- Give Snow actions button roles and navigation explicit selected state; expose toggle values, headings and actionable disabled reasons. Use platform controls or delegates while preserving visual tokens. Audit callers before changing the shared return type.
- Update `CoachingActivity` values in place rather than replacing its entire scroller on each verdict. Keep focus, scroll and editable settings stable. Use bounded phase/status announcements rather than announcing every tick.
- Make `CarvedLineView` labels respect font scaling and provide a textual summary/legend based on the actual result. Label reconstructed and measured traces distinctly. Avoid a static target instruction where there is no target.
- Route Run’s Open result action directly to its session; use the existing session-detail contract after inspecting its required extras. Add equivalent drill-attempt routing using PR 1 IDs; standalone historical attempts open their saved summary.
- Change visible “Finish later” wording to “Continue saving in background” and explain current mode handling accurately. Shared save state comes in PR 4; do not alter transfer semantics here.

**Primary files:** `Snow.kt`, `HomeActivity.kt`, `CoachingActivity.kt`, `CarvedLineView.kt`, `RunActivity.kt`, `SessionDetailActivity.kt`, `DrillActivity.kt`.

**Done when:** TalkBack identifies actions and selection; verdict updates preserve focus/scroll; large text remains readable; result routes open the correct record, including after relaunch; deleted/missing results have a clear fallback. Inspect chart accessibility with and without targets/ghosts.

### PR 4 — Actionable readiness and saving

**Work:**

- Replace the single first-failure `Readiness.reason` with a list of typed issues, severity, affected boot and available action. Keep a compatibility message while migrating existing run adapters/tests.
- Actions cover connect/assign boot, recover retained flash, mounting/zero setup, test audio/choose permitted route, resolve recording conflict and manage/export storage. Display all relevant issues while emphasizing the next useful one.
- Build a skier-facing setup route from Boots/readiness using existing discovery, assignment and `MountingPicker` capabilities. Show saved assignment separately from live connection; retain guarded swap/replacement. Bench diagnostics remain an advanced route.
- Introduce a shared per-session/per-boot recovery summary: waiting, downloading, validating, saved, paused, failed. Derive it from existing capture/recovery records, not a second independent transfer state machine. Restore it after screen/service recreation.
- Surface pending saving on Today, Run and result screens. Leaving the saving screen never cancels recovery. Offer retry when eligible; distinguish a validated phone copy from pending sensor erase.
- Show mode explicitly. Offer return to Training only once recovery permits it; preserve acknowledgement/retry rules and never mark a mode restored before confirmation. Do not automatically change boot modes while their retained data still needs recovery.

**Primary files:** `RunController.kt`, `SensorSessionService.kt`, `RunActivity.kt`, `HomeActivity.kt`, `MainActivity.kt`, `LocalSessionStore.kt`; proposed `PreparationState.kt` and a guided setup screen.

**Done when:** a novice can resolve each blocker from context; failed downloads retain protected data; background save completion is visible after relaunch; no mode-switch shortcut bypasses recording/recovery guards; optional GPS remains optional.

**Verification:** readiness/action rules and ownership tests; existing recorder/recovery regressions; device tests for retained flash, reconnect, low storage, audio route loss, mode-command failure and interrupted background saving.

### PR 5 — Compact layouts and persistent controls

**Work:**

- Split active flow screens into a scrolling content region and an inset-aware action region that never overlays content. Keep the current meaningful touch-target sizes; test actual glove operation before increasing all dimensions.
- Ready: brief goal/target, boot/audio checks, blocker action, Start. Move mode/GPS/coaching settings into an expandable preparation section and reduce decorative space.
- Running: phase, timer/count, concise connection/status message, persistent Stop. Drill: equivalent Finish/cancel semantics; Pause appears with PR 8.
- Saving: concise per-boot progress, explanation of retained data and Continue saving in background. Keep error details expandable and actionable.
- Avoid forcing the whole checklist onto one screen at large font sizes. The primary action and current blocking reason stay visible; the rest may scroll. Update content in place as state changes.

**Primary files:** `RunActivity.kt`, `DrillActivity.kt`, setup screen, `Snow.kt`.

**Done when:** actions stay reachable on compact portrait/landscape at large font/display scales, keyboard and system insets do not cover them, warnings cannot displace Stop, and phase changes preserve sensible focus. Check bright-light readability and gloves on a phone.

### PR 6 — History and repeating a target

**Work:**

- Add a reusable history screen reached from Today and Progress, with Runs / Drills / Ski days sections and explicit Demo/My practice filters where applicable. Reuse existing stores and viewers; keep record types distinct rather than inventing a common metric score.
- Add a last-result card and pending/active practice return card to Today. Display source, time, completion and save status before secondary numbers.
- Provide Repeat this target using the stored target/settings snapshot. Recheck readiness and mounting before starting; do not blindly reuse calibration or switch modes. Legacy records with unknown settings explain why exact repeat is unavailable and offer a current preset.
- Show optional links between a drill attempt and raw session; avoid displaying both as duplicate practice entries. Preserve independent day data and current session deletion protections.
- Keep date/time-zone behaviour consistent and load large histories off the main thread with bounded reads.

**Done when:** users find the last result without Geek mode; each row opens its exact record; standalone/imported/demo records are clearly distinguished; repeat retains known target values and source but runs fresh preparation.

### PR 7 — Explained results, eligibility and reflection

**Work:**

- Add an explicit result eligibility model: completed and judgeable, incomplete, insufficient data, awaiting recovery, or interrupted. Keep score/verdict separate from recording quality. Use existing quality facts and clearly documented starting rules; do not invent validated technique thresholds.
- Results lead with source/quality, one observed difference from the selected target and one suggested next action. Replace anatomical “weaker side” wording with observed direction/size/timing descriptions.
- Give no-verdict reasons their own states; missing data must not become a zero-performance score or an unqualified off-target judgement. Treat live firmware coaching eligibility separately from post-recovery replay quality, which is unavailable during the set.
- Add optional intention/reflection metadata linked to the attempt/session. Reuse existing day/run condition tags; do not duplicate their forms. Reflection is user-reported context, never a sensor diagnosis.
- Build a comparison eligibility rule using source, drill/target, scoring version and available quality/mounting context. Unknown or incompatible records remain visible but are not merged into a misleading trend. Explain exclusions.
- Add metadata fields through an additive migration in the next free schema version if PR 1 did not include them. Update affected export metadata with explicit versions while preserving current raw/export contracts.

**Primary files:** `Programme.kt`, `Coach.kt`, `RunComparison.kt`, `RunComparisonCard.kt`, `SessionHealth.kt`, result screens, practice store and export metadata.

**Done when:** a user can state what was measured and what to try; poor data yields a useful cannot-judge explanation; a directional observation cannot be read as pressure/strength diagnosis; incompatible targets/versions never silently share a trend.

### PR 8 — Pocket-friendly practice and focus-target pilot

**Work:**

- Reuse `CoachAudio`/metronome capabilities for dry drills: audio test, explicit countdown, progress/completion sounds, route/focus-loss handling. Audio ownership must be coordinated with run/coaching flows; no overlapping tone generators or leaked demo audio.
- Extend service-owned drill state with Pause/Resume. Record pause markers while retaining raw recording for provenance; exclude paused time and do not compute a beat or movement across a pause. Reset detector transition state and require a neutral stance before resuming. Retain already completed movement segments.
- Store explicit active intervals/segments for replay and scoring; aggregate beats only within contiguous active segments. A mid-movement pause discards that unfinished movement. Stop/Finish early remains incomplete. Add rules/tests before exposing controls.
- On route loss, pause audio-guided drill scoring and show a recoverable state; never silently switch to speaker unless explicitly allowed. Raw recording/recovery protections remain intact. Do not change existing on-snow run interruption behaviour as a side effect.
- Pilot an explicit focus picker (rhythm, directional consistency, or roll range) and user-confirmed target. Start with existing presets/manual choices and optional familiarisation. Preserve legacy four-component scoring as v1; publish a separately versioned focus scoring contract only after prototype feedback and tests establish its meaning.
- Demonstrate cue meanings before practice. Personal baselines are descriptive and opt-in; never automatically increase depth or claim a comfortable choice is biomechanically safe or an ideal snow angle.

**Done when:** a dry set can be completed with the phone put down; countdown, pause, route loss and completion are understandable; no paused/gap interval affects timing scores; each result records focus, target source and scoring version; the old score history remains unchanged.

## Storage and compatibility checklist

- Confirm schema version immediately before implementation; other merged work may use proposed v14. Every actual schema change gets a sequential migration and instrumentation coverage, including fresh install.
- Define one durable completion authority before moving recording ownership. Session raw rows, attempt completion and points must reconcile after a crash without duplicates. Partial persistence must be retryable by attempt ID.
- Import preferences without deleting history before a validated SQLite copy exists. Do not infer a legacy target, quality grade or session link from a drill title.
- Preserve `Attempt.demo`, synthetic session identity and existing raw quality/source fields throughout UI/export changes.
- Explicitly document deletion semantics, scoring versions and old-record fallbacks. Existing raw session ZIPs and ski-day GPX/JSON remain separate formats.

## Validation and release gates

### Every implementation PR

- Run focused JVM tests for changed behaviour, then the normal Android unit suite, debug build and lint once the increment is ready. Build instrumentation APK when storage/instrumentation changes.
- Use meaningful behavioural tests: duplicate completion, crash/import retry, stale calibration result, source isolation, wrong-record routing, transfer protection, pause-boundary timing. Do not add tests that merely mirror layout construction.
- Update [Android acceptance](../../android-acceptance.md), [Android design](../../android-design.md), [Android README](../../../android/README.md) and [project status](../../project-status.md) as behaviour lands. Update protocol docs only if the shared contract actually changes.
- Keep PR descriptions explicit about JVM, emulator and phone evidence; never substitute compilation for a hardware pass.

### Release 1: Trust and continuity — PRs 1–3

- [ ] Existing and fresh-install storage paths pass; preference import is repeatable and lossless.
- [ ] Demo-only history cannot produce measured achievements.
- [ ] Rotation/recreation/background return preserve an active drill and completion occurs once.
- [ ] Process death has a truthful interrupted result and raw recovery remains possible.
- [ ] Record links open the intended session/attempt; TalkBack button/selection basics pass.

### Release 2: Prepare and practise — PRs 4–5

- [ ] Novice pairing and retained-flash recovery can be completed from the preparation flow.
- [ ] Device tests cover disconnect/reconnect, pending save after leaving the screen, mode acknowledgement and low-storage behaviour.
- [ ] Start/Stop remain reachable at large font/display settings, portrait/landscape and system insets.
- [ ] Bright-light/glove and screen-lock checks are recorded, with failures fixed or explicitly held back.

### Release 3: Learn and return — PRs 6–7

- [ ] Users can find, interpret and repeat a result without entering advanced tools.
- [ ] Unknown targets, partial recovery and incompatible versions produce clear fallback states.
- [ ] Results distinguish data quality, observed target difference and user reflection.
- [ ] No history comparison implies a validated improvement in skiing technique.

### Pilot: Focused pocket practice — PR 8

- [ ] Dry practice can finish without watching the phone; audio meanings are understood.
- [ ] Pause/neutral-resume, route loss, lock and reconnect have demonstrated behaviour.
- [ ] Timing excludes pauses and never pairs beats across inactive intervals.
- [ ] Focus/target prototype has observed user feedback before a new score contract is enabled broadly.

Use the existing [garden trials](../../garden-trial-protocol.md) for integrated boot measurements. Phone/boot availability gates hardware claims, not preparation of code or automated checks. Do not extend pilot conclusions to on-snow technique without labelled field data.

## Deferred scope

Full navigation replacement, merged universal activity timeline, Compose migration, new sensing hardware, cloud/social features, automatic personal-target escalation, pressure/true edge-angle inference and a new overall skiing score. These can be reconsidered after the preparation/practice/review loop has real usage evidence.

## First executable task

Begin PR 1 by specifying the attempt/source/completion contract and adding the source-aware progress cases, then implement the transactional legacy import and practice store. Before moving drill state in PR 2, establish a stable attempt ID and an idempotent completion operation. This prevents the reliability fix from moving duplicate-reward risks into the service.
