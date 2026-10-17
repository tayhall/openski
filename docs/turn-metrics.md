# Per-candidate turn metrics (experimental)

Status: implemented as a pure Kotlin module (`TurnMetrics.kt`, tested in `TurnMetricsTest`). It is not wired into the UI, the coach or exports yet. Nothing here is a turn quality score, an edge angle or ski turn classification. All values describe dry-ski **boot roll** in the calibrated boot frame.

## Why

To learn which shape features of a roll excursion separate smooth, repeatable skiing from rough skiing **in our own recordings**, before choosing a scoring model or a sensor upgrade. Commercial systems score turns with an additive model over a dozen standardised metrics; the useful idea is that the score is a sum of readable per-metric contributions. We take the idea only. Thresholds and weights have to come from our own labelled data. No third-party weights or thresholds are used or stored in this repo.

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

## Not available from this IMU placement

True edge angle (no snow reference), pressure, weight release and lateral g in a snow frame (cuff mount, no slope plane). Do not label any metric above with those names.

## Next steps

1. Run the extractor over real recorded sessions and look at the distributions per drill and per skier (no UI needed; a replay test or a CSV export is enough).
2. Check that each metric moves in the expected direction on labelled good and rough repetitions of the same drill.
3. Only then decide on a combined score, and keep it additive and inspectable.
4. If wired into export, add a `turn-metrics.csv` and keep the experimental caveat in the header and in `analysis-summary.json`.
