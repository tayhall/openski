# Run flow and mode control (spec 2 of 4)

Status: draft for review, 8 October 2026. Experimental. Builds on the coaching engine (spec 1, PR #9, branch `feat/coaching-engine`) and the `ski_v0` half-turn events (merged in PR #8). Everything here describes dry-ski boot roll compared with a training target; nothing claims on-snow technique, edge angle or carving (see [ski analysis](../../ski-analysis.md)).

## Where this fits

The app redesign is split into four specs:

1. Coaching engine and audio (spec 1, built, PR #9).
2. **Run flow and mode control (this spec).**
3. Target model and run comparison: the target-versus-recorded wave view, targets from a best run or a shared file.
4. Snow look everywhere: diagnostics moved into the Snow design as its own section, plus the audit fixes.

## Purpose

The skier stands at the top of a run with the phone going into a pocket and earbuds in. They want one calm screen that says whether everything is ready, one big button, and then the app looks after the rest: it zeroes the boots, switches them to battery mode, starts recording and coaching, notices when the run is over, saves the boots' data while they ride the lift, and puts everything back.

Success criteria:
1. A run can be started, can end by itself after a quiet spell, and ends with the boots' data safely saved and the boots back in the mode they were in, with every rule above covered by JVM tests using fake boots and a fake clock.
2. Half-turn events and coaching verdicts of a run are stored, survive a crash mid-run, and are exported.
3. The same flow can be walked through with demo boots, without hardware.
4. On real boots and earbuds the flow works end to end (device checklist; not verifiable here).

Assumptions, not yet measured:
- 20 seconds without a half-turn is a good "the run is over" signal (120 s before the first one); a long stop mid-run ends the run early and the skier starts another.
- Downloading a boot's flash (about 1 MB) over Bluetooth takes a few minutes, so it completes during a lift ride. The device checklist measures it.

## The run lifecycle

A run is a recording session with a new purpose. It reuses the existing recording and flash-recovery machinery, and adds a state machine in front of it.

| State | What happens |
| --- | --- |
| **Ready** | Checks: both boots connected, earbuds connected (or phone speaker allowed), enough phone storage (20 MB, the existing recording rule), and no earlier run still on a boot's flash (if there is, it is downloaded first and the screen says so). Start is disabled with the reason shown. |
| **Zeroing** | Start sets the boots to on-snow mode, then sends the zero command to both. The screen says "Stand upright. Hold still." and waits until both boots' state frames report zeroed. A state frame already in flight when the command was sent still reports the old zero, and zeroing needs at least a second of stillness, so only a report arriving a second or more after the command counts (`ZeroTracker`). Without a zero the boots emit no half-turns. After 15 s with a boot not zeroed it names the boot and offers Retry and Cancel; Cancel restores the mode. |
| **Running** | A start chime plays. The session starts, which starts each boot's on-board recording, and coaching (spec 1) begins. Half-turn events and verdicts are stored. |
| **Ending** | After 20 s with no half-turn from either boot (120 s if no half-turn has happened yet, because the skier is still pocketing the phone and getting going), or on a manual Stop, an end chime plays and the session stops. The reason is recorded: `quiet`, `manual` or `error`. |
| **Saving** | The existing recovery downloads, checks and erases each boot's flash. Progress per boot is shown. The app can be closed meanwhile. |
| **Done** | Once both boots are erased, the boots return to the mode they were in before the run, but only if the run changed it. The summary is shown. |

Mode rules:
- The Ready screen shows a mode switch, **Training** (Wi-Fi and raw stream on) or **On snow** (Wi-Fi and raw stream off, for battery). It can be flipped by hand at any time outside a run. The switch may be removed at ship.
- Starting a run sets On snow automatically and remembers the mode it found. The mode is restored only after the flash is saved (the radio stays quiet during the download). If saving fails, the boots stay as they are and the user is told.
- The production-mode recording guard from PR #8 keeps blocking drills and test recordings, which need the raw stream, with its message naming the boot and telling the user to switch to training mode. Runs are exempt, because their raw data comes from boot flash.

Failure rules:
- A boot dropping mid-run does not end the run. A banner names the boot. If it was the coach boot, coaching pauses with a message; the gap is recorded.
- The boots' flash holds about 10 minutes per boot. A longer run keeps all its half-turn events, but its raw data covers only the first 10 minutes, and the summary says so.
- If the phone link drops, the boots keep recording to flash, but events and coaching during the gap are lost and flagged.

## Architecture

| Unit | File | Responsibility |
| --- | --- | --- |
| `RunController` | `RunController.kt` | The state machine above. Pure Kotlin. Talks to the boots, clock, coach and store only through small interfaces (`RunBoots`, `RunClock`, `RunSink`), so it is JVM-testable. |
| `RunReadiness` | `RunController.kt` | The readiness checks and their reasons. |
| `RunSummary` | `RunController.kt` | Counts of turns by side, counts of verdicts, flash-capped note, gaps. |
| Service host | `SensorSessionService.kt` | Hosts one `RunController`, supplies the real `RunBoots` (zero and mode commands, start and stop recording, recovery progress), forwards raw `ski_v0` events, state frames and coach verdicts, and posts the run notification with a Stop action. The run logic does not go into the service itself. |
| `RunActivity` | `RunActivity.kt` | The Snow-look screens: Ready, Zeroing, Running, Saving, Done. It only shows the controller's state and sends commands. |
| Chimes | `ToneSynth.kt` | Two new short sounds: a start chime and an end chime, distinct from the coaching chirps. |
| Zero confirmation | `ZeroTracker.kt` | Ignores zeroed reports that were already in flight when a zero command was sent. |
| Run records | `RunRecords.kt` | The stored row types, the flag bits, and the CSV for the export. |
| Entry points | `HomeActivity.kt` | A "Start a run" card at the top of Today (the drill's button becomes secondary, so each screen keeps one primary action), and the existing coaching card on Boots (the coaching screen remains the settings page). |

No firmware change: the state frame already reports zeroed and mode, and the zero and mode commands already exist.

## Storage

The database goes from version 7 to **version 8** (CLAUDE.md still says 6 and is corrected in this work):

- `sessions.kind` (`session` or `run`, default `session`). Demo runs keep the existing demo `origin`.
- `run_events`: session, side, sequence, boot-clock start, duration, peak roll, peak roll rate, pitch, flags, phone receipt time. Stored as the boot sent them, before the right-boot mirroring, so raw data stays raw. The mirroring is applied on read.
- `run_verdicts`: session, phone time, side, verdict, score, tempo, steadiness, depth, balance, mean beat, mean depth, outside-range flag.
- `run_info`: session, target beat, depth and label, window size, boot followed, the mode before the run, why it ended, first and last half-turn times, and the recorded gaps.

Rows are written as they arrive on the existing background executor, so a crash mid-run keeps what was saved. Deleting a run deletes its rows. The export gains `half-turns.csv` and `coach-verdicts.csv`, and the run info goes into the metadata JSON. A migration needs an instrumentation check (CLAUDE.md rule); it is written and compiled but runs on an emulator.

## Screens

All in the Snow look. Targets are 56 dp or more (64 dp for the main button), no orange text, and every status carries words and a shape, not colour alone. Phase changes are announced to TalkBack ("Both boots zeroed. Run started."). The run lives in the service, so rotating or closing the screen never changes it.

- **Ready:** a tall header with a simple ridge-line drawing and a large "Ready?". Large status rows: left boot, right boot, earbuds, the mode switch, and the coaching target (tap to open the coaching settings). A full-width Start run button in the thumb zone, with the reason it is disabled just above it. A smaller "Try with demo boots" button.
- **Zeroing:** "Stand upright. Hold still." with two large marks that turn solid as each boot zeroes. The screen stays on. Retry and Cancel appear on timeout.
- **Running:** elapsed time, turn count, and the last verdict as a word plus a shape; one large Stop button. The screen may turn off. The notification reads "Run in progress · 03:12 · 24 turns" and has a Stop action. A banner appears if a boot drops.
- **Saving:** "Run ended. Saving boot data" with a progress line per boot ("Left 40% · Right 12%"). The run notification shows the same progress ("Saving run data · Left 40% · Right 12%"), so it is visible with the app closed. (A progress card on Today would need Today to bind the service; the notification does the job.)
- **Done:** duration, total turns with left and right counts, counts of matched, off-target and unclear windows, notes for a capped flash or a gap, and Back to ready and Open in logbook buttons. A caption states that it compares dry-ski boot roll with a training target. The wave comparison arrives with spec 3.

## Testing

JVM tests of `RunController`, with fakes for the boots and a fake clock:
- readiness: each missing condition gives its own reason; all OK is ready; demo skips the boot checks; an earlier run on flash blocks Start with the right reason;
- start sequence: mode set, zero sent, waits for both boots, then recording and coaching start; a boot that never zeroes times out and is named; Retry works; Cancel restores the mode;
- ending: events reset the quiet timer; 20 s of quiet ends with `quiet`; Stop ends with `manual`; Stop with no run does nothing;
- mode: restored only if the run changed it, only after the flash is saved, not restored (and the user told) if saving fails; an already-on-snow boot is left on-snow;
- losses: a dropped boot does not end the run; losing the coach boot pauses coaching; gaps are recorded;
- storage sink: events and verdicts reach the sink with boot and phone times;
- summary: side counts, verdict counts, the flash-capped note, gaps;
- the guard: runs exempt, drills and tests still blocked (updates the existing guard tests).

Instrumentation (compiled, not run here): the version 8 migration, run deletion, and the two new export files.

Device checklist (not run here): the chimes; zeroing with real boots; the mode round trip during a run; auto-end timing on a real lift ride; how long the flash download takes during the ride; what happens when the 10-minute flash fills; the run notification's Stop action with the screen off.

## Out of scope

The target-versus-recorded wave view and targets from a best run or a shared file (spec 3); restyling diagnostics (spec 4); boot battery level (needs firmware to report it); automatic start; spoken cues; a day summary of many runs; resuming a run after a long stop; any firmware change.

## Open items

- Tune the quiet-spell length after real lift rides.
- Measure the flash download time and decide whether the 10-minute cap needs a firmware change (chunked recording) later.
- Decide in spec 3 how the Done screen links to the comparison view.
- Decide in spec 4 whether the Ready screen's header drawing is replaced by a proper illustration.
