# YouTube visual study: parallel turns, carving and transitions

Reviewed 2026-10-08. This is qualitative visual research using public YouTube material. It does not require a user-provided video collection. No pose inference, angle extraction, model training or reference-value calculation was performed on these videos. Only links, attribution and original research notes are retained; temporary viewing images are removed after inspection.

## What was actually inspected

Selected public player previews were visually inspected across three videos. Normal browser playback was also inspected for the Deb Armstrong demonstration around 02:13–02:20 and the Tom Gellie analysis around 06:35–06:42. These are excerpts, not a claim to have watched each complete lesson. Public auto-caption tracks were listed, but usable transcripts were not obtained; no spoken explanation is attributed on the basis of unheard audio. Visible written coaching annotations and the creators’ descriptions are identified separately below.

### V01 — Tom Gellie: toppling and transitions

[Carv — TOPPLING AND TURN TRANSITIONS](https://www.youtube.com/watch?v=RGgF-hQrZqc), published 2021-08-06. Tom Gellie coaches a skier through side-by-side analysis.

The preview material around 01:15–04:10 compares movement positions; 04:35–06:00 includes an indoor exercise. Around 06:40–07:55, visible annotations discuss patience in transition, movement into the new turn and allowing the outside ski to bend. The played excerpt contains held skiing images while commentary continues. These are teaching cues and selected visual comparisons, not measured pressure or an uninterrupted timing reference. Avoid classifying the analysed skier as an ideal demonstration merely because the coach is expert.

### V02 — Deb Armstrong: inside-leg use

[Use of the inside leg to change turn radius](https://www.youtube.com/watch?v=9VjjfIzDhos), published 2021-12-14.

The creator’s description connects inside-knee movement with edging and turn radius. In the played 02:13–02:20 sequence, the skier alternates between more upright transitions and inclined turn positions. Leg flexion differs across the turn, with the visibly bent leg changing as the turn reverses. Preview sections around 04:50–05:20 and 06:40–07:15 offer further skiing examples. This makes inside-leg activity a useful observation target. The following camera and oblique view prevent reliable anatomical angles, edge angles, loading estimates or a causal conclusion about radius.

### V03 — Nordica / PSIA-AASI: Level III tasks

[Nordica Pro Series Level 3](https://www.youtube.com/watch?v=QIyQ9F2aj4k), published 2023-10-30. The official PSIA-AASI announcement identifies Michael Rogan, Troy Walsh and Meagan Jones as contributors: [release announcement](https://thesnowpros.org/2023/10/new-video-nordica-pro-series-how-to-pass-your-level-iii/).

Inspected previews identify one-footed skiing, short-radius/pivot-slip combinations, ungroomed parallel turns and dynamic short turns. The dynamic-short-turn section begins around 13:30. The creator’s description presents continuous edge-angle development and release, while explicitly allowing peak edging to be moved to different phases for task variations. This is valuable evidence against a universal peak-timing rule. Snow, camera motion and edited task changes prevent timing measurements from preview sampling. Task demonstrations and variations need separate labels.

## Working understanding of mechanics and relative timing

The following is an OpenSki research interpretation to investigate, not an established measurement range or a universal recipe:

| Part of the movement | Observation to investigate | Timing relationship to record | Plausible boot-sensor connection |
|---|---|---|---|
| Leaving the old turn | Reduction of the previous side’s inclination/edging configuration | Does release begin before the visible change of turn direction? Keep body, ski and loading events distinct. | Change in boot angular-rate trajectory; pressure release itself is not measured |
| Transition | Legs and torso change their relative position as the skier moves between opposing inclined positions | Is the reversal continuous or does a visible pause occur? Does any leg-length change precede or accompany it? | Reversal candidates and angular-rate shape; no knee-angle claim |
| Entering the new turn | Ski/leg tipping and movement toward the new turn develop | When does inclination begin relative to ski heading and visible trajectory change? | Conditional boot tipping proxy; heading requires another reference |
| Developing the turn | Inside/outside leg flexion and torso–leg relationship evolve | When do projected flexion and inclination extrema occur relative to the same-definition boundaries? | Boot trajectories can be aligned with video events, but cannot recover the body angles |
| Moving into the next turn | The configuration changes again rather than remaining a static carving pose | Compare development, maximum observed inclination and reversal order across matched tasks. | Turn rhythm and trajectory consistency, subject to validation |

Do not merge all these observations into one “edge transition” time. A ski changing edge, a boot rotating, a body moving across the skis, and load transferring can have different event times. Nor does a near-upright image prove that skis are flat to a sloping snow surface.

The videos shift the initial research focus toward **movement trajectories and sequencing**, including both legs, rather than a checklist of peak angles. Carving, controlled steering and specialised drills must be compared within their intended outcomes. A static posture alone cannot establish successful speed control, clean carving or appropriate support.

## What timings these sources support

Video links and approximate chapter locations identify where to revisit an observation. They are not physical-time measurements. Browser playback was set to normal speed, but the source may contain slow motion, holds or edits. Preview spacing was five seconds for V01/V02 and ten seconds for V03, unsuitable for resolving individual turn events. Browser screenshots also introduce capture delay.

At this stage retain qualitative ordering: before, during, after, overlapping or unclear. Numerical turn durations, transition durations and phase fractions require a verified uninterrupted original-speed sequence and explicit event annotation. No such values are asserted here.

Public viewing for understanding and documenting coaching concepts is the current activity. Automated video processing, derivative datasets and model training remain separate permissions questions; they do not prevent continuing this qualitative research programme.

## Second viewing batch: V04–V06

Three additional videos were inspected through public previews and short normal-browser playback excerpts. They were not watched in full. No usable on-screen auto-caption fragments were obtained; spoken explanations are not attributed. Written annotations were directly visible. The retained [findings JSON](technique-findings.json) now contains nine records: three converted from the first study and six additions. The [schema](../schemas/technique-findings.schema.json) requires separate fields for observation, guidance, interpretation, sequence and hardware limitations.

| Source | Actual playback location, approximately | Contribution |
|---|---|---|
| V04: [Ted Ligety / Carv, One Big Carving Mistake](https://www.youtube.com/watch?v=fX9bgulqRrY&t=236s), 2026-01-31 | 03:56–04:07 | Linked modest-inclination arcs within a railroad-track drill; previews separately identify the hand-drag drill. Findings F004–F005. |
| V05: [Tom Gellie, Drill to learn carving at slower speeds](https://www.youtube.com/watch?v=S4M9AVh4YPU), 2024-04-02 | 00:00–00:11 | Exaggerated lateral leg reach and changing flexion; a visible outside-leg cue. Findings F006–F007. |
| V06: [Deb Armstrong, Ankle flexion for skiing, why and how](https://www.youtube.com/watch?v=oDTP-aUlloo&t=371s), 2023-01-07 | 06:10–06:21 | Linked inclination reversals, with the actual ankle joint obscured by boots and increasing distance. Findings F008–F009. |

The main interpretation is to keep **task-specific movement differences** separate from technique faults. A specialised drill can deliberately exaggerate differences between legs and hand positions. Another useful constraint is to distinguish the subject of a lesson from what its pictures permit us to observe: an ankle-focused video does not automatically provide measurable ankle flexion.

These excerpts motivate studies of boot-rate development and reversal, not validated scores. Leg-length changes, hand contact, pressure and grip cannot be read directly from boot orientation. Candidate sensor relationships in the JSON are explicitly hypotheses requiring synchronised validation.

The player was operated normally, and capture times bracketed each screenshot. Some sampled playback positions repeated or jumped, and source capture speed was unverified. The listed seconds identify where the material was found, not physical movement durations. No turn duration, joint angle or edge angle was measured. Temporary preview sheets, playback images and copied metadata were removed after the original research findings were saved.

## Expanded pilot: V07–V30

The pilot now includes **30 individually identified videos and 33 qualitative findings**. Twenty-four additional videos were inspected using up to 25 evenly sampled public player previews per video and 15 sampled frames from a selected normal-browser playback excerpt. This is a visual excerpt study, not complete viewing of 30 lessons. Some played excerpts contain standing explanations, composite images or inset demonstrations. The original descriptions and visible written annotations were used separately; spoken audio was not reviewed. The [viewing audit](viewing-audit.json) retains capture locators without retaining media.

The primary-topic allocation is five basic-parallel videos, five carving videos, six short-turn videos, five transition/coordination videos, four controlled-steering/comparison videos, three racing videos and two error/correction videos. Topics overlap; each video has one primary allocation for counting. This is a purposive conceptual sample, not a representative skier population or numerical reference dataset.

### What the expanded review adds

- **Consistency is different from the presence of a movement.** The three assessment runs V08–V10 have source-assigned task ratings; one is described as regularly satisfactory and the others as inconsistent. These are useful comparisons for future independent coaching annotation, not evidence that a particular angle caused the rating. They share one assessment source and do not count as independent agreement. [Candidate 1](https://www.youtube.com/watch?v=M8BnlV-Pdcw), [candidate 2](https://www.youtube.com/watch?v=X8A3yvxH6-s), [candidate 3](https://www.youtube.com/watch?v=QI6coO9t5QU).
- **Grip timing is task- and stage-specific.** The [CSIA Level 2 short-turn source](https://www.youtube.com/watch?v=NWMVF0KQz2k) differentiates acquisition and consolidation timing relative to the fall line. The [Level 3 source](https://www.youtube.com/watch?v=tBU0VJ0vBrs) explicitly says its consolidation skiers do not meet Level 3 requirements, whereas refinement skiers do. Preserve these labels rather than marking every official demonstration as expert-positive. Written grip guidance is not a measured grip event.
- **Advanced short turns can remain steered.** The [Level 4 source](https://www.youtube.com/watch?v=gbWvUannYnU) explicitly identifies steered ski performance. The [Warren Smith skidded-turn lesson](https://www.youtube.com/watch?v=S4hioJ4ThJA) illustrates a task where reduced edging is intentional. Low inclination cannot be treated as a general technique failure.
- **Relative motion matters, but boot sensors see only part of it.** The [braquage demonstration](https://www.youtube.com/watch?v=i4s-jOSeAWU) visibly changes ski heading relative to the upper body. This motivates separate tipping and heading-rotation features while showing why boot IMUs cannot establish torso-leg separation. The [REI/PSIA progression](https://www.youtube.com/watch?v=PfJe653v24I) separately identifies steering, release and weight transfer; those concepts need different event definitions.
- **Transition strategies differ.** [Stomp It's transition lesson](https://www.youtube.com/watch?v=nh0YYFXsiUw) separates crossover, cross-under and airborne exercises. Its [turn-shape lesson](https://www.youtube.com/watch?v=1u90Tke_5Bs) separates tactical arc-length changes from a proposed corrective movement sequence. Record body flexion, contact state and task separately rather than requiring an upward movement in every transition.
- **Race footage provides context, not recreational ideals.** [Shiffrin's GS run](https://www.youtube.com/watch?v=90NV7xg_vnc), [Brignone's training lesson](https://www.youtube.com/watch?v=XIqjWsa20kw) and [Ligety's follow-camera training](https://www.youtube.com/watch?v=t36ufnNFSSM) show gate-constrained turns from different perspectives. Gates, speed, equipment, edits and camera movement prevent direct transfer of apparent lean or rhythm into universal recreational targets.
- **Edited teaching images need explicit exclusions.** [Paul Lorenz's transition explanation](https://www.youtube.com/watch?v=OKCvnIV6pQU) superimposes body positions and arrows. Such illustrations cannot be treated as tracked motion, calibrated forces or a common physical timebase. Pose analysis must exclude diagrams, holds, duplicated bodies and independent insets.

### Initial OpenSki priorities

First validate device timing and calibrated multiaxis boot rates. Then investigate consistently defined turn intervals, rate-development timing, repeated-turn variability and synchronised bilateral reversal lag. Compare matched tasks and preserve inside/outside-boot roles. These are candidate descriptive features, not established technique scores. The [metric registry](../integration/metric-registry.json) provides definitions and limits for the developer handoff.

Do not estimate stance width from two orientation sensors, anatomical ankle flexion from boot pitch, or total outside-ski force from an IMU. Future insoles need their own calibration and force-path validation. A visual distinction becomes a production coaching recommendation only after independent sensor measurement and outcome validation.

### Coverage and remaining uncertainty

The [coverage JSON](coverage.json) records one primary topic per video, publisher and review depth. Publisher diversity does not establish independence: REI/PSIA, Nordica/PSIA and assessment clips overlap; Carv features coaches who also publish independently; the Warren Smith lineage appears on two channels; several Stomp It sources contain commercial Carv material. The sample is not balanced by participant demographics, speed, gradient, snow or equipment. An unavailable historical Northwest clip was excluded from the count and replaced by the accessible Level 4 source.

No turn duration, phase fraction, anatomical angle, ski edge angle, pressure or performance score was extracted from these public sources. All findings retain null confidence probabilities and explicit unvalidated status. The next step for numerical evidence is matched, permitted, uninterrupted footage with verified capture timing and synchronised sensor recordings.

## Subsequent provisional-angle follow-up

At the user's request, a later manual review annotated three selected frames from V05 and V08 and calculated eight coarse image proxies. These are retained separately in [provisional-video-estimates.json](provisional-video-estimates.json), with original [pixel annotations](manual-angle-annotations.json), marker-sensitivity calculations and explicit rejection as coaching thresholds. They do not alter the 33 qualitative findings or establish anatomical angles, true edge angles or physical turn durations. See the [provisional interpretation](../docs/provisional-interpretation.md) for estimates and the revision-2 developer settings.
