# Coaching engine: metronome and section chirps (spec 1 of 4)

Status: draft for review, 8 October 2026. Experimental. Builds on the `ski_v0` half-turn events (PR #8, branch `feat/ski-v0-recogniser`). Scores and cues compare dry-ski boot roll with a training target. They are never an on-snow technique, edge-angle or carving score (see [ski analysis](../../ski-analysis.md)).

## Where this fits

The app redesign is split into four specs, each with its own design, plan and build:

1. **Coaching engine and audio (this spec).**
2. Run flow and mode control: the "ready at the top" screen, the user-facing training/on-snow switch, arming the boots' on-board recording, reshaping the production-mode guard.
3. Target model and run comparison: the target-versus-recorded wave view, targets from a best run or a shared file.
4. Snow look everywhere: diagnostics moved into the Snow design as its own section, plus the audit fixes (contrast, targets, keep-screen-on, Production button spacing and confirm).

## Purpose

The skier has the phone in a pocket and cannot look at it, so coaching has to be audible. Two features, delivered through Bluetooth earbuds or a helmet speaker:

- **Metronome:** ticks at the target beat so the skier can match the pace and frequency of their turns.
- **Section chirps:** after every window of 3 to 5 half-turns, a rising chirp if the window matched the target, a falling chirp if it clearly did not, silence if unclear. One half-turn is too noisy to judge, so feedback is semi-real-time and sectional.

Success criteria:
1. Synthetic on-target half-turn sequences produce a positive verdict; shallow, too fast, too slow or uneven ones produce a negative verdict; pauses and missed turns produce none (JVM unit tests, no hardware).
2. The sounds pass objective generator tests (length, fade at both ends, peak level, frequency, rising versus falling).
3. Demo boots drive audible coaching with the screen off and the phone in a pocket.
4. On a real phone with earbuds the sounds play, mix with other audio, and coaching pauses when the earbuds disconnect. This item can only be checked on a device.

Assumptions, not yet measured:
- A window of 4 half-turns and the thresholds 70 and 40 are starting guesses, tunable in settings.
- Dry-ski rolls in the garden produce clean half-turn events. Only the garden test shows this; the engine discards windows it cannot trust.
- Bluetooth earbuds add roughly 100 to 250 ms of output delay, which is why version 1 judges tempo and not whether the skier turns exactly on the tick.

## Architecture

All judgement is pure Kotlin with no Android or Bluetooth dependency, so it is unit-testable. The Android parts are thin shells.

| Unit | File | Responsibility |
| --- | --- | --- |
| `CoachTarget` | `Coach.kt` | `beatSeconds`, `depthDegrees`; built from a preset `Drill` or custom values. |
| `Coach` | `Coach.kt` | Takes half-turn events, keeps the current window, emits a `CoachVerdict` (`POSITIVE`, `NEGATIVE`, `NONE`) with the component scores when a window completes. |
| `ToneSynth` | `ToneSynth.kt` | Renders the tick, accent tick, positive chirp and negative chirp as 16-bit PCM; builds the metronome beat buffers. |
| `CoachAudio` | `CoachAudio.kt` | Android shell: owns the `AudioTrack`s, plays the metronome stream and chirps, handles routing and failures. |
| `CoachSettings` | `CoachSettings.kt` | Persisted settings (SharedPreferences, like `ProgressStore`). |
| `CoachingActivity` | `CoachingActivity.kt` | A simple Snow-look screen reached from the Boots tab: target, Start/Stop, settings, last verdict line, test sounds. Absorbed into the Run screen by spec 2. |
| `DemoCoachFeed` | `DemoCoachFeed.kt` | Feeds synthetic half-turns to `Coach` for demo boots. |

`SensorSessionService` hosts the session: it receives `SkiEvent`s already mirrored into the skier frame (`SkierFrame.of`), passes the coach boot's events to `Coach`, and passes verdicts to `CoachAudio`. It is the existing foreground service that keeps Bluetooth and a wake lock alive with the screen off.

## Judgement rules (`Coach`)

- **Input:** the coach boot's `SkiEvent`s. The default is the first connected boot; the setting can pin left or right. The other boot is ignored in this slice.
- **Beat:** the time between the starts of consecutive half-turns, computed from the boot's own `startMs` (a wrap-aware difference, since the boot clock wraps at 4,294,967 ms), so Bluetooth jitter does not affect it. This is the same definition `DrillScoring` uses.
- **Window:** `N` consecutive valid events (3 to 5, default 4), non-overlapping. A verdict is produced when the window fills, then the window clears.
- **Component scores** (0 to 100), reusing the drill formulas:
  - tempo: closeness of the mean beat to `beatSeconds`, zero at 50% either side;
  - steadiness: from the spread of the beats relative to their mean, zero at 35%;
  - depth: closeness of the mean peak roll magnitude to `depthDegrees`, zero at 60% either side;
  - balance: the smaller of the mean positive and mean negative peaks over the larger, as a percentage. Because a repeated side resets the window, every window holds both sides, so balance is always present; the code still omits it defensively if it is ever absent.
- **Score and verdict:** the score is the mean of the available components. A mean alone would let one total failure (for example no depth at all) hide behind three good parts, so the weakest component also counts:
  - `POSITIVE` needs a score of at least 70 and every component at least 60;
  - `NEGATIVE` is a score below 40, or any one component below 20 (a clear miss);
  - anything else is `NONE`, and plays no sound.
- **Resets, no verdict:**
  - the gap between two event starts exceeds twice the target beat (the skier stopped or paused);
  - two events on the same side in a row (a half-turn was missed);
  - a different boot is chosen or the target changes.
- **Out-of-envelope events:** an event flagged outside the expected range stays in the window but caps that window's score at 60, so it cannot earn a positive chirp.
- **Honest limits:** the verdict means "this window matched the training target", not "good skiing".

## Audio

- **Sounds**, all generated in code (no audio files):
  - tick: about 1.2 kHz and 30 ms with a quick decay; the accent on every second beat is about 1.6 kHz;
  - positive chirp: two rising notes, about 880 then 1320 Hz, about 110 ms each;
  - negative chirp: two falling notes, about 660 then 440 Hz, about 110 ms each;
  - every sound has a short fade in and out so it does not click, and a hard cap on peak amplitude.
- **Metronome:** one `AudioTrack` in streaming mode writes a continuous stream in which each beat is an exact number of samples. Fractional samples carry forward, so 100 beats of 1.7 s total 170 s to within one sample. The count-in is the first four ticks: chirps are muted for those four beats so a verdict cannot play before the skier has started. It has no effect when the metronome is off.
- **Chirps:** a second `AudioTrack` plays short sounds on top of the metronome.
- **Mixing:** no audio focus is requested, so music or a podcast keeps playing and the cues mix over it. Ducking music is a possible later setting.
- **Volume:** the phone's media volume, a gain setting in the app, and a cap on peak level. The app never changes system volume. A "play test sounds" button lets the skier set the volume before starting.
- **Foreground service type:** the service is declared `connectedDevice` only. Add `mediaPlayback` and the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission for long background playback on current Android; confirm the rule against the Android documentation when building.
- **Routing:** coaching starts only if the output is a headphone-type device (Bluetooth earbuds, headset, wired, or a hearing device) unless "allow phone speaker" is on. If the route falls back to the phone speaker mid-session (the audio-becoming-noisy broadcast or the device callback), coaching pauses and a notification says "Coaching paused: earbuds disconnected". It resumes when headphones return.
- **Failure:** if the audio track cannot be created, coaching turns itself off with a notification. Boot connections and recording are not affected and nothing crashes.
- **Battery:** a continuous metronome keeps the earbud link streaming, which costs some phone battery. A setting turns the metronome off and keeps only the chirps.

## Settings

Stored on the phone: metronome on or off; count-in on or off; section chirps on or off; window size 3, 4 or 5; coach boot (left, right, automatic); target (a preset drill, with the beat and depth from `Programme`, or custom: beat 0.8 to 3.5 s, depth 10° to 45°); gain; allow phone speaker.

## The Coaching screen

A Snow-look `CoachingActivity`, entered from the Boots tab. It shows the target, a Start/Stop button, a demo-boots button, the settings, one line for the last window's verdict, and the test-sounds button. An always-visible card explains what each sound means, and the test-sounds button plays them. A caption states that it compares dry-ski boot roll with a training target, not on-snow technique. The target is chosen from a stock dialog listing the drills plus a custom beat and depth, as the mounting picker does.

Coaching consumes half-turn events, which flow in both firmware modes, so it works in the garden in training mode and unchanged on the slope in on-snow mode. It needs no raw stream and records nothing, so the production-mode recording guard does not apply to it.

Demo boots feed `Coach` through `DemoCoachFeed`, so the metronome and chirps can be heard without hardware.

## Testing

JVM unit tests (no device):
- `CoachTest`, with an injected sequence of events (the shallow-roll case is the one that proves the weakest-component rule): on-target windows give `POSITIVE`; shallow, too deep, too fast, too slow and uneven windows give `NEGATIVE` or `NONE` as expected; a pause (gap over twice the beat) resets with no verdict; two same-side events in a row reset; an out-of-envelope event caps the score; the boot clock wrapping mid-window gives the right beats; a target change resets; window sizes 3 and 5.
- `ToneSynthTest`: lengths, zero (or near-zero) first and last samples, peak below the cap, frequency by zero-crossing count, the rising chirp rising and the falling chirp falling, the metronome buffer placing the tick at sample 0 with silence after, and no tempo drift over 100 beats.
- `CoachSettingsTest`: defaults, clamping of custom values.

Not testable without a device, and stated as such in the plan: the `AudioTrack` shell, mixing with other audio, the foreground-service behaviour with the screen off, and the headphone route handling. A short manual checklist covers them.

## Out of scope

Phase alignment (whether the skier turns on the tick); spoken cues; a continuous roll-following tone (which needs a faster live frame from the boots); ducking music; the Run screen and the mode control (spec 2); the target-versus-actual wave view and targets from a best run or a shared file (spec 3); the Snow restyle of diagnostics (spec 4); saving verdicts into sessions or exports; per-side verdicts and using the second boot.

## Open items

- Tune the window size and thresholds after the garden test.
- Decide in spec 2 how the Coaching screen merges into the Run screen.
- Decide in spec 3 whether verdicts are saved with a run for the post-run comparison.
