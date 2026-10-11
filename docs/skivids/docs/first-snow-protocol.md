# First-snow protocol

Status: proposed. Purpose: collect data that is useful **without assuming anyone knows what "excellent" is**. The skier is an intermediate; expert footage is a weak prior, not a label source.

## Principle

Three tiers of claim, in order of how much we trust them:

1. **Measured:** physical quantities from calibrated sensors (turn timing, rhythm, boot tilt change, left/right difference).
2. **Contrast:** differences between deliberately different executions by the same skier on the same slope.
3. **Anchored:** resemblance to expert footage or a coach's judgement. Weakest; never a target.

Do not label runs "good" or "bad". Label them by **intent** (what the skier tried to do) and **conditions**.

## Before the day (at home)

- Protractor tilt check on both boots: record reported angle at 0, 15, 30, 45 degrees left and right.
- Repeat with the sensor deliberately mounted about 25 degrees off-axis; reported angles should agree within the pass criterion fixed beforehand.
- Run the stand-still and knee-flex calibration routine; store the result per boot and per session.
- Film a 30 s dry-land clip of leaning to protractor-measured angles from behind: measures pose-lean vs sensor tilt for this skier.

## On the slope

Same gentle blue slope for everything. Warm up first. Do each block twice, uphill-to-downhill, and note snow and visibility.

| Block | Intent label | Notes |
|---|---|---|
| A | `carved_intended` | Slow, round turns; try to leave two clean lines in the snow |
| B | `skidded_intended` | Deliberate pivoted turns with sliding tails |
| C | `short_turns_intended` | Quick rhythm, narrow corridor |
| D | `long_turns_intended` | Wide, relaxed arcs |
| E | `sloppy_intended` | Deliberately stiff or hurried; exaggerate the error |
| F | `bumps_attempt` | Only if comfortable; label as attempt |

For each run: phone video from behind or from the side (declare which), one clap or pole tap at start and end for synchronisation, and a short voice note of intent and anything unusual (fall, stop, traffic).

## After

1. Check clock offset and drift using the clap events.
2. Mark turn boundaries on a sample of runs from video; compare with sensor-detected turns. Report missed and extra turns first, before any quality metric.
3. Test only this question: **do the intent labels separate in the sensor features?** (carved vs skidded, short vs long, clean vs sloppy). If they do not, the feature is not yet meaningful.
4. Compare to the expert-video task signatures as a sanity check on timing only.

## Not claimed

No ideal angles, no skill score, no judgement of the skier. A feature graduates only when it separates intent labels repeatably, across days, and (later) agrees with a qualified coach's blinded labels.
