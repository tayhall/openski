# Pilot validation protocol

Status: proposed experiment. No participant data, errors or technique ranges have been measured.

## Questions and independent references

| Question | Reference | Report |
|---|---|---|
| Are pose landmarks usable in skiing footage? | Independent manual 2D annotation on permitted frames | Pixel error normalised by image/body scale, coverage and side swaps by view |
| Are projected angles repeatable? | Two independent reviewers marking the same image-plane joints | Reviewer agreement and algorithm differences; no anatomical accuracy claim |
| Are turn events detected? | Blinded reviewers using the same explicit event definition | Precision, recall, unmatched events and timing error |
| Are boot orientations accurate? | Independently calibrated dynamic orientation reference | Bias, absolute error, drift and failure coverage |
| Does a sensor metric relate to effective skiing? | Independent task outcome and blinded coaching assessment | Association and uncertainty on held-out skiers; confounding and applicability |

Manual 2D agreement is not validation of 3D joint angles. A body-pose reversal is not an independent reference for ski-flat or pressure-transfer timing. Select the reference to match the claimed quantity.

## Capture programme

Obtain permission covering analysis and derived-record retention, plus participant consent. Use pseudonymous identities. Collect parallel carved and controlled steered tasks, short and long turn tasks, and repeated runs. Include task failures rather than selecting attractive demonstrations. Keep distinct labels for instructed task and observed technique. Do not assign skill from clothing, speed or a single pose.

Record continuous original-speed footage with visible body, boots and skis where possible. Capture frontal/rear and side views separately, or synchronise multiple cameras. Record mounting, equipment, terrain, snow, independently available speed, camera movement and calibration. Unknown conditions remain unknown. Do not infer metric speed or slope from an uncalibrated image.

Use a physical synchronisation event visible on video and recorded by the IMUs, repeated at the end. Estimate clock offset/drift and uncertainty. Confirm that an event is identifiable in both streams rather than assuming every tap is visible. Dual-device clocks need their own alignment.

## Annotation and analysis

Define events before labelling. Two reviewers independently mark boundaries and uncertainty intervals while blinded to candidate algorithm output. Preserve both annotations and disagreements. Report ambiguous/unobservable events separately; do not hide them by forcing labels. Select annotated frames across turn phase, view, occlusion and algorithm detection failures, rather than only successful poses.

Separate development and held-out assessment by skier and run/day. Adjacent turns from the same run must not straddle training and validation. For small pilots, report descriptive uncertainty and individual failures instead of claiming generalisation. Count participants and independent runs as well as frames/turns.

Match predicted and reference events one-to-one using a prespecified operational timing tolerance derived from reference uncertainty and feedback needs. Select and record the tolerance before evaluating held-out runs. Report sensitivity across tolerances, missed/extra events and timing distributions. Do not optimise tolerance on the final evaluation set.

Evaluate changes in mounting, sampling, dropout, speed, turn type and view. Compare single-boot and dual-boot configurations explicitly. A dual-boot result does not validate one boot. Report coverage alongside error; low error obtained by rejecting most turns can be unusable.

## Advancement criteria

The pilot estimates errors and informs sample-size planning; it does not establish ideal technique thresholds. Before a confirmatory study, specify numeric acceptance bounds from intended use, reference precision and acceptable feedback risk, then freeze methods and splits. A feature advances only when it is measurable, repeatable, independently validated and associated with a relevant outcome in a declared domain. Prescriptive coaching additionally requires evidence that acting on the feedback improves the intended outcome.
