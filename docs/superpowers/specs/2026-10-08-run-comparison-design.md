# Run comparison: target wave against recorded wave (spec 3 of 4)

Status: draft for review, 8 October 2026. Experimental. Builds on the coaching engine (spec 1, merged in PR #9) and the run flow (spec 2, merged in PR #10), which store each run's half-turns, coaching verdicts and run settings. Everything here compares dry-ski boot roll with a training target; nothing claims on-snow technique, edge angle or carving (see [ski analysis](../../ski-analysis.md)).

## Where this fits

The app redesign is split into four specs:

1. Coaching engine and audio (spec 1, merged).
2. Run flow and mode control (spec 2, merged).
3. **Target model and run comparison (this spec), first version.**
4. Snow look everywhere: diagnostics moved into the Snow design as its own section, plus the audit fixes.

## Purpose

After a run the skier wants to see two waves on one chart: the wave they were aiming for and the wave they actually skied, so they can see at a glance where they were shallow, late or uneven. This is the app's counterpart to the comparison views in commercial boot-sensor apps, built from data the project already stores.

Success criteria:
1. For any stored run, a pure `RunComparison` produces the target wave, the recorded wave, the coaching windows with their verdicts, the summary numbers and a plain sentence, covered by JVM tests.
2. The comparison card appears on the run's Done screen and as a "Run" tab for runs in the logbook, and works for demo runs.
3. A reader is never misled about what the recorded wave is: it is labelled as reconstructed from half-turns.
4. On a real run the shapes and numbers look believable (device checklist; not verifiable here).

Decisions made with the user:
- The recorded wave is **reconstructed from the stored half-turns** in this version. A true measured roll trace from the raw samples may replace it later.
- The target is **the coaching target the run used** in this version. A best-run target and a shared target file come later.

## The two waves

**Target wave.** From the run's target (`beat` seconds, `depth` degrees): a smooth wave swinging to +depth and −depth, with each half-turn lasting one beat, `roll(t) = ±depth · sin(π·t / beat)`, alternating sign.

**Recorded wave.** From the stored `run_events` of the chosen boot. Each half-turn becomes a half-sine from its boot-clock start to start + duration, reaching its measured peak roll with its own sign: `roll(t) = peak · sin(π·(t − start) / duration)`. Half-turns start at roll's zero crossings, so consecutive pieces join at zero. Where a real gap separates two half-turns (the next start is later than the previous end), the line is flat at zero for the gap.
- Stored values are as the boot sent them; the right boot's roll is mirrored into the skier frame when read (`SkierFrame`), as everywhere else.
- Boot-clock differences use `bootClockDelta`, so a clock wrap mid-run is harmless.
- Drawing points are thinned for long runs (a fixed number of points per half-turn that always includes the peak).

**Re-anchoring.** A fixed target beat drifts out of phase with a skier who runs at a different beat, so the target wave is re-anchored at the start of each coaching window. Inside a window the difference is exactly the skier's turns against the aim.

**Windows.** Each stored verdict defines its window: the last `windowSize` half-turns (from `run_info`) received at or before that verdict. Half-turns after the final verdict form a partial window with no verdict. The coach's own resets (a pause, a missed turn) therefore need no re-implementation: windows simply follow what actually happened. Each window carries its verdict (`POSITIVE`, `NEGATIVE`, `NONE`, or none).

**Which boot.** The `coach_boot` stored for the run when it was left or right, or, when automatic, the boot with more stored half-turns (a tie goes to the left).

**Numbers** (all from the chosen boot's half-turns and the run's target):
- mean peak depth against the target depth, as a signed difference in degrees;
- mean beat (time between consecutive half-turn starts, only where they are less than twice the target beat apart) against the target beat, as a signed difference in seconds;
- left versus right mean depth;
- the share of windows with a verdict that matched.

**The sentence.** One plain sentence from those numbers, for example "Your turns were about 6° shallower than the 20° target and 0.3 s slower; left and right were even." Differences under 2° or 0.15 s are called "close to" the target. Left and right are called uneven when their mean depths differ by more than 20%. A run with no turns says so; a run without target data omits the comparison with the target.

## Units

| Unit | File | Responsibility |
| --- | --- | --- |
| `RunComparison` | `RunComparison.kt` | Pure logic above: builds `ComparisonWave`s (as `RollTrace`s per window), windows, numbers and the sentence from `RunData`, the session's start time and the run's settings. |
| `RunComparisonCard` | `RunComparisonCard.kt` | Builds the card from a `RunComparison`: the sentence, the numbers, the row of window tiles, one `CarvedLineView` for the selected window, a Play button and the caption. Styled in the light Snow look so it works inside either look. |
| Service | `SensorSessionService.kt` | Keeps the finished run's id after the run ends and loads `RunData` off the main thread for the Done screen. |
| Done screen | `RunActivity.kt` | Shows the card under the summary. |
| Logbook | `SessionDetailActivity.kt` | A new "Run" tab, first and selected for runs, showing the same card. |

No new chart view is needed: `CarvedLineView` already draws a `RollTrace` against a target depth band with a pale ghost line and a `progress` replay. The recorded wave is the trace, the target wave is the ghost, and the band is the target depth.

## The card

- The sentence and the four numbers.
- A scrolling row of window tiles, one per window, marked ▲ matched, ▼ off target, ● close, or grey with no verdict.
- One chart for the selected window: recorded solid blue, target pale, with the depth band. Tapping a tile selects it. The default is the first off-target window, since that is the most useful one, or the first window if everything matched.
- A Play button that animates the recorded line being drawn.
- A caption: "Reconstructed from your half-turns. The pale line is the target. Dry-ski boot roll against a training target, not on-snow technique."

Edge cases:
- fewer turns than one window: one partial window labelled "Too few turns for a verdict";
- no half-turns: "No turns were recorded";
- demo runs are labelled Demo;
- missing target data (an older or interrupted run): the turns are shown without a target line and the sentence leaves out the target comparison.

Only the selected window's points are built, so a ten-minute run stays cheap to draw.

## Testing

JVM tests of `RunComparison` (no screen):
- target wave: zero start, alternating sign, peak equal to the depth, one beat per half-turn;
- recorded wave: half-sine per event reaching its peak; joins at zero without jumps; right boot mirrored; a real gap drawn flat; boot-clock wrap; thinning keeps every peak;
- windows: each verdict takes the last `windowSize` half-turns before it; a trailing partial window; coach resets leave the right gaps; default selection is the first off-target window;
- boot choice: left, right, automatic by count, and the tie;
- numbers and sentence: signed depth and beat differences, left versus right, matched share, every sentence variant (shallower or deeper, slower or faster, close, uneven), no turns, one turn, no target data.

Emulator checks (screenshots of a demo run's Done card and the logbook Run tab). No database change, so no instrumentation change.

Device checklist additions: on a real run the reconstructed shapes and depth numbers look believable against what was skied, and the right boot's side is the right way round.

## Out of scope

The measured roll trace overlaid from raw data; targets from a best run or a shared file; pitch and flex; left and right overlaid on one chart; exporting or sharing the chart as an image; video sync; suggestions for the next run; zoom and pan beyond picking a window; restyling the logbook (spec 4).

## Open items

- Decide in a later version how a measured trace replaces or overlays the reconstructed wave once raw data is calibrated.
- Decide how a best-run target is chosen and stored (needs a notion of "same target").
- Tune the closeness thresholds in the sentence (2°, 0.15 s, 20%) against real runs.
