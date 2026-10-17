# Per-candidate turn metrics (experimental)

Status: implemented as a pure Kotlin module (`TurnMetrics.kt`, tested in `TurnMetricsTest`). It is not wired into the UI, the coach or exports yet. Nothing here is a turn quality score, an edge angle or ski turn classification. All values describe dry-ski **boot roll** in the calibrated boot frame.

## Why

To learn which shape features of a roll excursion separate smooth, repeatable skiing from rough skiing **in our own recordings**, before choosing a scoring model or a sensor upgrade. A score built as a sum of readable per-metric contributions can be inspected and explained, which a black-box classifier cannot. Thresholds and weights have to come from our own labelled data.

## Candidate and window

A candidate is a `DryMovement` from `DrySkiAnalysis` (roll leaves neutral by 8 degrees, returns inside 3 degrees). That detector is low-passed, so its start and end lag the roll. Shape metrics therefore use the **shape window**: raw roll samples from the roll's own return to within 2 degrees of neutral before the peak, to the one after it. A window is unusable (shape metrics null) if it has under 10 samples, a gap over 100 ms, or does not reach neutral at both ends. `peakDegrees` and `durationMs` always come from the detector.

## Metrics

| Field | Definition | Intended reading |
|---|---|---|
| `peakDegrees` | Peak absolute roll | How far the boot rolled |
| `durationMs` | Detector start to end | Turn time |
| `buildMs` | Time from 10% to 80% of peak, up to the peak | Fast = decisive edge change |
| `earlyRateDps` | Mean absolute roll rate over the first quarter of the shape window | How early the edge is engaged |
| `peakPhase` | Peak position in the shape window, 0 to 1 | Early or late peak |
| `roughness` | RMS roll acceleration (second difference) divided by peak | Jerky edging |
| `headingChangeDegrees` | Heading-rate lobe with the greatest overlap | Rotation about vertical during the roll |

Per-run spread (`TurnMetricsExtractor.summarise`): mean and sample SD per metric, null below two turns. Repeatability (SD) is the main thing to look at first.

Cross-boot: `TurnMetricsExtractor.agreement(first, second, startMs, endMs)` is the Pearson correlation of the two boots' roll curves over a window, resampled to 50 points. The caller must give both boots the same sign convention. Null on gaps or too few samples. It is the nearest thing we have to "skis parallel", and says nothing about edge angle.

## Design principles

Four ideas to base measurements and interpretation on. Every number we use has to come from our own recordings. They are hypotheses about what separates good from poor skiing, to be checked against labelled data before any is treated as true. Each is phrased the way a ski instructor would say it.

1. **Edge engagement should happen early, not late.** Look at the start of the turn separately from the whole turn. A rushed, late edge-set (typical of a skidded or defensive turn) and a smooth early commitment can have the same average roll rate over the turn yet look different in the first part. Do not collapse a turn into one summary number. `earlyRateDps` (first quarter of the shape window) exists for this reason; the window length is our choice and should be tuned on our data.
2. **Left and right boots should move together, and that is agreement, not equal tilt.** Compare how each boot's roll *changes over time* (the shape of its roll rate and its derivative), not how far each is tilted. Two boots can sit at the same angle on average while one drags or stems into the turn. A fluid, parallel turn has both boots speeding up and slowing down at the same moments. This can be read from two raw boot traces with no trained model. The current `agreement` function correlates the roll curves, which is a coarser version; a derivative-based variant is the natural refinement and should be compared with it on real data.
3. **Good weight transfer shows as rising load, not a dip towards weightless.** Vertical load that builds as weight commits onto the new edge suggests a committed turn. A large part of the turn spent near weightless suggests an unweighted, floaty or mistimed transition (except in deliberate powder or air moves, where the same quantity means something else). **Limit for us:** this needs vertical acceleration in a snow-relative frame, which a cuff-mounted IMU without a slope reference gives only noisily, so we do not report it yet. Treat it as a target for later hardware or fusion work, with the "too light" threshold set from our own data.
4. **"Excellent" is a ceiling you approach.** The raw signal difference between poor and average is large; between strong and excellent it is small and subtle, matching how coaching gains arrive (fast early, fine refinements late). If we ever combine metrics into a score, use a saturating, log-like curve per metric and not a linear one, with the shape fitted to our own distributions. Keep the score additive and inspectable so each metric's contribution can be shown.

Starter calculations and parameters to try for each are in [turn metric starters](turn-metric-starters.md).

How these map to what is built: principle 1 is `earlyRateDps`, principle 2 is `agreement` (with the derivative variant still to do), principle 3 is not measurable with the current placement, and principle 4 applies only if a combined score is added.

## Not available from this IMU placement

True edge angle (no snow reference), pressure, weight release and lateral g in a snow frame (cuff mount, no slope plane). Do not label any metric above with those names.

## Next steps

1. Run the extractor over real recorded sessions and look at the distributions per drill and per skier (no UI needed; a replay test or a CSV export is enough).
2. Check that each metric moves in the expected direction on labelled good and rough repetitions of the same drill.
3. Try the derivative-based boot agreement from principle 2 next to the roll-curve correlation and keep whichever separates smooth from rough repetitions better.
4. Only then decide on a combined score, and keep it additive and inspectable (principle 4).
5. If wired into export, add a `turn-metrics.csv` and keep the experimental caveat in the header and in `analysis-summary.json`.
