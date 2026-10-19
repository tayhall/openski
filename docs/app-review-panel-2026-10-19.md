# Android application review panel — 19 October 2026

Three specialist AI reviewers independently inspected the current Android implementation: mobile product design/ergonomics, Kotlin engineering, and ski training/coaching. Each produced a ranked top five, then reviewed the others' findings in a panel round. The chair consolidated the build order below.

Scope: main `b6cfd68` through PR #43, plus the current documentation refresh. This was a source-based product review, not an instrumented usability study or an assessment by human-certified trainers. ADB showed no attached device/emulator. No new application builds, runtime tests, outdoor/glove tests or snow observations were performed. Effort estimates are relative engineering scope, not delivery commitments: S = local change; M = several screens or state changes; L = cross-cutting workflow/storage work.

## Independent top fives

Code references are relative to `android/app/src/main/java/com/openski/android/` at the reviewed revision. Proposed behaviour is a recommendation, not a feature already implemented.

### Mobile design and ergonomics

| Rank | Recommendation | Evidence and user problem | Proposed experience | Effort |
|---|---|---|---|---|
| D1 | Keep run readiness and primary controls visible | `RunActivity.kt:207,244,296` puts all phases in a scroller; Ready has decoration, checks and settings before Start. Large text or warnings can push actions down. | Compact readiness checklist, expandable settings and a persistent Start/Stop area; preserve the existing pocket/audio flow. | M |
| D2 | Make activity history discoverable | `HomeActivity.kt:20,396` puts session history behind Geek mode; `RunActivity.kt:336` opens MainActivity without a record ID. Days have a separate history. | Direct last-result and History routes; later consider a consolidated Runs/Drills/Days view with source badges. Keep advanced telemetry secondary. | M–L |
| D3 | Separate demo progress and remove ambiguous currency | `Programme.kt:153–170` aggregates all attempts; `HomeActivity.kt:168,332` labels virtual rewards as vert. | Demo and My practice views; practice points for rewards, vertical descent in metres for GPS measurements. | M |
| D4 | Add native control semantics and readable chart alternatives | `Snow.kt:72` uses clickable TextViews; `HomeActivity.kt:66` uses them for tabs; `CarvedLineView.kt:46–57` uses dp-sized canvas labels and a generic description. | Correct button/selected/toggle semantics, scalable chart labels, explicit legends and summaries of actual observations. | M |
| D5 | Guide boot setup outside engineering tools | `HomeActivity.kt:375–386` shows saved sensor IDs and sends management into Geek mode. Saved pairing alone does not establish live readiness. | Power on → identify/assign → mounting → hold still/zero → audio test → ready, with a useful action beside every blocker. | M–L |

Acceptance priorities: reach Start/Stop without scrolling on a compact phone with large text; open the exact last record in one tap; identify data source without opening details; complete first-time pairing without Geek mode; understand charts and controls with TalkBack.

### Kotlin engineering

| Rank | Recommendation | Evidence and user problem | Implementation direction | Effort |
|---|---|---|---|---|
| K1 | Preserve drills across Activity recreation | `DrillActivity.kt:33–45,71–94` owns phase/capture in the Activity, starts at intro on creation, and stops unfinished owned recording on destruction. Rotation can interrupt practice. | Service-owned drill controller/capture, following the existing run pattern; Activity observes state. Explicit cancel, stable session identity and idempotent completion. | M–L |
| K2 | Make readiness and background recovery actionable | `RunController.kt:129–138` sends users to Geek mode to recover flash; `RunActivity.kt:210–250` mainly presents checks and disabled Start. | Structured readiness issues with connect/recover/audio/storage actions; shared pending-save state across Home, Run and history. | M |
| K3 | Separate simulated and measured achievements | `Attempt.demo` exists but `Progress.best`, stars, unlocks and days aggregate all attempts; `ProgressStore.progress()` passes them together. | Source-aware aggregates and explicit exploration/unlock policy; preserve historical attempt source. | S–M |
| K4 | Preserve focus and accessibility on live screens | `CoachingActivity.kt:38,130–131` rebuilds the scrolling hierarchy on updates; Snow controls lack explicit button semantics. | Stable screen structure with in-place value updates, as RunActivity already attempts; semantic shared controls and incremental token consistency. | M |
| K5 | Link results to the exact saved session and next practice | `RunActivity.kt:312–338` has useful results, but its logbook action lacks a record ID; `RunComparisonCard` already supplies target differences. | Exact record links, prominent quality/source, repeat-target action and comparable history with explicit eligibility. | M |

Acceptance priorities: rotate/background/recreate without losing phase/count/session; exactly one attempt on completion; honest interrupted state after process death; direct resolution of each readiness blocker; visible pending/completed/failed saving; TalkBack focus survives incoming verdicts.

Clarification from cross-review: **Finish later does not cancel flash recovery.** `SensorSessionService.abandonRunSave()` calls `RunController.abandonSave()`, which stops waiting and finishes the run screen; independent recovery continues. Automatic mode restoration is skipped on that completion path (`RunController.kt:335–336`). The improvement is clearer wording and visible state, plus a properly gated route back to Training, not a claim that the current action discards data.

### Sports training and coaching

| Rank | Recommendation | Evidence and user problem | Proposed coaching experience | Effort |
|---|---|---|---|---|
| T1 | Separate demonstration from personal practice | `ProgressStore.kt:32`, `Programme.kt:150–170` allow simulated success to affect personal-looking bests/unlocks/streaks. | “Demo complete — explore the next drill. Your measured practice starts when you connect boots.” Separate histories and points from GPS descent. | S–M |
| T2 | Choose one focus and explicit personal practice targets | `Programme.kt:113–123` weights four components equally; fixed depth targets can dominate feedback. Research does not validate universal ideal cuff angles. | “Today: keep an even beat. Use a comfortable roll range.” Familiarisation plus explicit user-confirmed target; keep presets as optional exercises. | M |
| T3 | Explain feedback and support cannot-judge states | Combined chirps and matched/off-target counts do not identify what to change; `Programme.kt:135–143` calls a direction weaker from movement differences. | One measured observation, target and next action; “rolls in one direction were smaller.” Separate neutral/no-verdict from insufficient reliable data. | M |
| T4 | Make dry practice possible without watching the phone | `DrillActivity` centres trace/counter/text; `CoachAudio` already has metronome and route handling. | Test sound → countdown → practise with phone put down → completion sound → review; explicit pause/resume/incomplete semantics. | M, after K1 |
| T5 | Connect intent, reflection and comparable history | `Progress.next()` mainly follows unlocks/best scores; Progress emphasises rewards. Day/run condition tags already exist in `SkiDayRecords` and `SkiDayActivity`. | Pick a focus, record an optional reflection, then offer to repeat it. Compare compatible measured attempts and show existing condition tags. | M–L |

Acceptance priorities: demo cannot alter measured achievements; focus and target source remain explicit; no automatic escalation to deeper angles; cues describe observed exercise differences; incomplete/poor-quality data produces an explanation instead of a technique judgement; repeat sessions preserve settings.

## Panel discussion and decisions

**Reliability before a broad redesign.** Design initially ranked discoverable history highly; Kotlin identified a higher-consequence lifecycle issue. The panel moved drill continuity ahead of navigation restructuring. Trainer's pocket/audio loop depends on service-owned drill state and defined interruption semantics.

**Four tabs for the next iteration.** Keep Today / Path / Progress / Boots while adding direct History and last-result routes and fixing exact-record links. Prototype a consolidated history before deciding whether to replace or add a primary destination. A fifth tab alone would not connect the separate record types. This is a panel recommendation to validate, not a settled navigation specification.

**Personal targets need an explicit contract.** Trainer favoured one-focus exercises and comfortable personal targets; engineering highlighted compatibility with stored scores and summaries. Keep existing presets available, prototype familiarisation and focus selection, and version scoring/targets before changing achievement logic. Do not silently rescore history or infer an ideal on-snow angle from a baseline.

**Accessibility is part of every change.** The current Snow buttons already have 56dp minimum height and Stop run uses 64dp. The issue is not universally undersized buttons: placement, semantics, focus preservation, font scaling and real glove testing also matter. Android recommends a minimum 48 × 48dp interactive area; that alone does not prove glove usability. See [Android Views accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views).

**Use existing capabilities before adding more metrics.** Reuse audio, source flags, day tags, target comparisons and protected recovery. Lead with usable observations and one next action. Defer new composite performance scores and technique diagnoses until measurement validation supports them.

## Recommended build shortlist

The [implementation plan](superpowers/plans/2026-10-19-app-improvements.md) expands this shortlist into eight proposed PRs with contracts, migration rules and release gates.

These five items consolidate overlapping recommendations. Their order balances dependency and user impact; the source/progress fix and basic semantic-control work can proceed independently of the drill controller.

The reviewers differed on ordering after reliability: engineering put readiness second, while design and coaching preferred the provenance/progress fix second. The chair places the smaller progress correction second and keeps readiness as the next workflow increment. All three agreed on the same underlying priorities. Engineering suggested evolving Progress into Progress & History; this remains a navigation prototype to compare with direct History links rather than an approved rename.

| Order | Build item | What ships | Main dependency | Done when |
|---|---|---|---|---|
| 1 | Reliable, resumable practice | Service-owned drill state, explicit cancel/interruption, one session/attempt, stable reattachment. | Recording ownership and existing recovery protections. | Rotation and Activity recreation preserve phase/count/session; background/return works; process death honestly recovers or marks interruption; no duplicate reward/save. |
| 2 | Trustworthy progress | Separate Demo/My practice aggregates, explicit demo exploration, practice points wording, consistent source badges. | Policy for unlocks; historical source preservation. | All-demo history produces no measured best/streak; real attempts remain available; GPS vertical and points cannot be confused; existing data is preserved. |
| 3 | Guided readiness with reachable controls | Compact left/right/audio/GPS checks with direct actions; pinned Start/Stop; expandable settings; clear background saving and mode state. | Structured readiness + shared save state; real-device checks. | A novice resolves blockers without hunting through Geek mode; action stays visible at large text; recovery can finish after leaving the screen and its result is visible. |
| 4 | Accessible, discoverable review | Exact-result deep links, Last practice/run and History entry, semantic controls, stable live updates, chart summaries. | Session identity from item 1; source rules from item 2. | Result action opens that record; TalkBack roles/state/focus work; charts have equivalent text; users find history without knowing Geek mode. |
| 5 | Focused practice and next-step feedback | Explicit focus/target, explained outcome or cannot-judge reason, repeat target, optional reflection; prototype then add shared countdown/metronome to dry drills. | Item 1 lifecycle, item 2 provenance, quality rules and versioned scoring if changed. | User can explain the next action; poor data cannot become a negative technique judgement; repeats retain target; audio interruption is recoverable; changed scores do not silently mix with old ones. |

### Suggested release slices

1. **Trust and continuity:** items 1–2, basic semantic-control fixes, exact-record link, clear background-save wording. Highest-confidence changes; verify lifecycle, source aggregates and recovery state with focused tests.
2. **Prepare and practise:** item 3, stable coaching updates, large-text/TalkBack review, prototype of the focused practice loop. Exercise real BLE, retained flash, audio routing and screen locking.
3. **Learn and return:** finish items 4–5, compatible-history comparisons and optional reflection. Validate novice comprehension before committing to larger navigation changes or new scoring.

### Deferred

- Full navigation redesign or a universal merged day/run/drill timeline before observing the simpler routes.
- A wholesale Compose/UI-framework migration or cosmetic restyle without a user problem.
- AI coaching, additional live numeric dashboards, universal ideal angles, technique scores or automatic depth progression.
- Claims that a rising score establishes improved skiing, or that directional cuff-roll differences diagnose strength or pressure.

## Validation plan

**Automated:** drill state transitions and idempotent completion; demo/measured progress rules with historical data; explicit readiness actions; exact-result routing; compatible target/scoring comparisons; paused/incomplete/insufficient-data outcomes. Add migration/instrumentation coverage only where storage changes require it.

**Device/emulator:** compact portrait and landscape, largest supported font/display settings, TalkBack, focus/scroll stability during verdict updates, rotation during calibration/running/saving, background/return, process death, no audio route and route loss, reconnect/recovery. Treat process survival and Activity recreation as different cases.

**Observed usability:** ask a novice to pair boots, recover a pending recording, start practice, understand a result and find it later. Measure task completion, wrong turns, screen glances and correct interpretation of demo/quality/points labels. These are proposed evaluation measures, not current results.

**Field checks:** bright light, gloves, cold-device battery behaviour and phone-in-pocket audio/recording delivery. First validate dry practice, then use the existing [garden protocol](garden-trial-protocol.md) and [Android acceptance checklist](android-acceptance.md). On-snow technique claims remain gated by labelled real recordings.

The panel recommends improving the reliability and clarity of the existing preparation → practice → review loop before adding more sensing features.
