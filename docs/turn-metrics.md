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
| `earlyRateDps` | Median absolute roll rate (after a 3-sample rolling mean) over the first quarter of the shape window | How early the edge is engaged |
| `peakPhase` | Peak position in the shape window, 0 to 1 | Early or late peak |
| `roughness` | RMS roll acceleration (second difference) divided by peak | Jerky edging |
| `headingChangeDegrees` | Heading-rate lobe with the greatest overlap | Rotation about vertical during the roll |
| `edgeHold` | Median absolute roll over the middle 10 to 80% of the shape window, divided by the square root of the peak. Null if the window has no span. Not the same as `buildMs` | Sustained edge versus a brief spike |
| `entryDegrees` | Roll at the first sample of the shape window | The turn's own starting roll, so roll can be judged relative to it |
| `peakFromEntryDegrees` | Peak roll minus the entry roll, in the turn's direction | Edge change not caused by a non-zero starting roll |

Per-run spread (`TurnMetricsExtractor.summarise`): mean and sample SD per metric, null below two turns. Repeatability (SD) is the main thing to look at first.

Cross-boot: `BootAgreement` (also `wedgeFraction`) and `YawTurnShape` (turn shape, turn closure, roll-versus-yaw lead) are separate experimental modules, described in [turn metric starters](turn-metric-starters.md). `TurnMetricsExtractor.agreement(first, second, startMs, endMs)` is the Pearson correlation of the two boots' roll curves over a window, resampled to 50 points. The caller must give both boots the same sign convention. Null on gaps or too few samples. It is the nearest thing we have to "skis parallel", and says nothing about edge angle.

## Design principles

Four ideas to base measurements and interpretation on. Every number we use has to come from our own recordings. They are hypotheses about what separates good from poor skiing, to be checked against labelled data before any is treated as true. Each is phrased the way a ski instructor would say it.

1. **Edge engagement should happen early, not late.** Look at the start of the turn separately from the whole turn. A rushed, late edge-set (typical of a skidded or defensive turn) and a smooth early commitment can have the same average roll rate over the turn yet look different in the first part. Do not collapse a turn into one summary number. `earlyRateDps` (first quarter of the shape window) exists for this reason; the window length is our choice and should be tuned on our data.
2. **Left and right boots should move together, and that is agreement, not equal tilt.** Compare how each boot's roll *changes over time* (the shape of its roll rate and its derivative), not how far each is tilted. Two boots can sit at the same angle on average while one drags or stems into the turn. A fluid, parallel turn has both boots speeding up and slowing down at the same moments. This can be read from two raw boot traces with no trained model. The current `agreement` function correlates the roll curves, which is a coarser version; a derivative-based variant is the natural refinement and should be compared with it on real data.
3. **Good weight transfer shows as rising load, not a dip towards weightless.** Vertical load that builds as weight commits onto the new edge suggests a committed turn. A large part of the turn spent near weightless suggests an unweighted, floaty or mistimed transition (except in deliberate powder or air moves, where the same quantity means something else). **Limit for us:** this needs vertical acceleration in a snow-relative frame, which a cuff-mounted IMU without a slope reference gives only noisily, so we do not report it yet. Treat it as a target for later hardware or fusion work, with the "too light" threshold set from our own data.
4. **"Excellent" is a ceiling you approach.** The raw signal difference between poor and average is large; between strong and excellent it is small and subtle, matching how coaching gains arrive (fast early, fine refinements late). If we ever combine metrics into a score, use a saturating, log-like curve per metric and not a linear one, with the shape fitted to our own distributions. Keep the score additive and inspectable so each metric's contribution can be shown.

Starter calculations and parameters to try for each are in [turn metric starters](turn-metric-starters.md).

How these map to what is built: principle 1 is `earlyRateDps`, principle 2 is `BootAgreement.derivativeSimilarity` (the roll-curve correlation `agreement`, now sharing its resampler, is kept to compare against), principle 3 is not measurable with the current placement, and principle 4 applies only if a combined score is added.

Export status: these metrics are now written by the session export (`TurnMetricsExport`, called from `SessionExport`). `turn-metrics.csv` has one row per detected roll candidate per boot (all `TurnMetrics` fields, a `usable` flag, direction from the saved per-boot polarity, plus the yaw shape, closure and lead descriptors), with `#` comment lines at the top saying the data is experimental. `analysis-summary.json` gains a `turn_metrics` block: per-boot `TurnMetricsSummary` spreads and, for each left candidate paired with its most-overlapping right candidate, derivative similarity, roll-curve Pearson and wedge fraction (both boots put on a positive-roll-is-left convention first), with their means. Everything is still unvalidated: it has only been unit-tested on synthetic signals and has not been compared with video or labelled real recordings, so treat the numbers as things to look at, not scores.

## Torso metrics (experimental, unvalidated)

Status: built 18 October 2026 (`TorsoMetrics.kt`: `TorsoMetrics` and `TorsoMetricsExport`, tested in `TorsoMetricsTest` and the garden dress rehearsal). Exported as `torso-metrics.csv` and the `torso_metrics` block of `analysis-summary.json`. Synthetic data only. The phone is assumed to ride with the upper body in a ski jacket pocket; whether a pocket follows the torso, and how firmly it holds the phone, is unverified, and pocket movement reads as torso motion. Nothing here is a quality score or technique classification.

**Frame rule.** The phone's orientation in the pocket is unknown, so only quantities that do not depend on it are used, all built with gravity from the game rotation vector (`up` in the phone frame is the third row of its rotation matrix):

| Field | Definition | Sign |
|---|---|---|
| `torso_gyro_rms_dps` | RMS of the phone's gyro magnitude over the candidate window | magnitude |
| `torso_horizontal_accel_sd` | Total SD (m/s^2) of the horizontal acceleration in the gravity frame, `sqrt(var x + var y)`, so the arbitrary game-rotation yaw does not matter | magnitude |
| `torso_lean_peak_deg` | Largest angle (5-row moving mean) between the phone's up direction and its neutral baseline: the mean up direction over phone rows in the 10 s before the candidate where that boot's roll is within 3 degrees of neutral (at least 20 rows; else the run-wide neutral mean) | unsigned, never compared by sign |
| `boot_roll_peak_deg` | The candidate's peak absolute boot roll (turn-metrics.csv) | magnitude |
| `torso_heading_change_deg` | Phone gyro dot up (rotation about the vertical) integrated over the window | signed: rotation about vertical is the same in any frame; positive = counter-clockwise from above, as the boots' heading rate |
| `boot_heading_change_deg` | The boot's heading rate (`headingRateRadps`, rotation about its own vertical) integrated over the window | signed |
| `torso_to_boot_heading_ratio`, `torso_boot_yaw_rate_correlation` | Ratio of the two heading changes, and Pearson of bin means (20 bins) of the two rotation rates. Only when the boot turned 5 degrees or more | signed |

Rows: one per boot turn candidate (turn-metrics.csv start and end, each boot on its own timeline) with any phone coverage; metrics are blank below 80 % coverage (rows more than 50 ms apart are a gap). Summary per boot: lean against roll (spreads, lean-to-roll ratio, Pearson across turns), yaw (heading-change Pearson across turns, ratio and yaw-rate correlation spreads, sign agreement over turns where the boot turned 5 degrees and the torso 2, null below three), and run-wide quietness (gyro RMS and horizontal SD over the whole session, per-turn spreads). Lower quietness figures mean a quieter upper body or pocket, nothing more.

**Timing.** Phone rows are placed on the wall clock through the `phone_clock_check` offset and the `clock_ref` event. Each boot's analysis timeline is phone-receipt referenced with an unmeasured BLE delay bias. When that boot's clock pings exist (one clock epoch, a single-valued timeline anchor), the bias (boot timeline minus phone clock, at each candidate's midpoint) is measured through the ping map and removed, and the uncertainty is the ping uncertainty plus 5 ms for the phone clock check (about 12 ms and a 15 to 17 ms bias in the synthetic garden session). Otherwise the bias is taken as zero with an allowance of 50 ms plus the boot's user offset plus 5 ms. The figures are windowed statistics over whole candidates, chosen to tolerate an error of about 100 ms; each row is recomputed with the phone moved by plus and minus the uncertainty, `timing_sensitivity` is the largest relative change (absolute for the correlation) and `timing_limited` is true above 0.15. The boot-to-boot alignment (`clock_alignment`) is not used here: it relates the boots to each other, not to the phone; its source is copied into the block for context.

**Refusals** (status `refused`, a reason, CSV header only): no phone rows; no `phone_clock_check` event or an offset beyond 50 ms (the sensor clock may not share the elapsed-realtime base); no `clock_ref` event; the phone covering under half the session; or under half the candidates having 80 % coverage. All numbers go through `TurnMetricsExport.toJson`, so the JSON has nulls, never NaN.

**Tested** (synthetic): a quiet torso reads lower than a moving one; lean tracks a known fraction of boot roll; yaw ratio, rate correlation and sign agreement match a known torso-to-boot heading ratio, and a counter-rotating torso disagrees in sign; rotating the phone in the pocket by arbitrary fixed rotations (and changing the game-rotation yaw) leaves every figure unchanged; moving the phone by plus or minus 100 ms barely moves the windowed figures; missing phone data, a missing or failed clock check and poor coverage are refused. The garden dress rehearsal (`GardenParams(torso = GardenTorso(), bootHeadingPerTurnDeg = 40.0)`, ZIP `torso.zip`) recovers the lean ratio 0.40 and the heading ratio 0.50 (0.475 on the right boot, which turns 60 ms after the torso), and `fault-no-phone.zip` and `fault-phone-clock-unchecked.zip` refuse.

**Not verified:** whether a jacket pocket follows the torso; phone gyro bias and the game rotation vector on a real phone in a pocket; the phone clock base and ping bias on hardware; whether any figure separates smooth from rough skiing.

## Not available from this IMU placement

True edge angle (no snow reference), pressure, weight release and lateral g in a snow frame (cuff mount, no slope plane). Do not label any metric above with those names.

## Next steps

1. Run the extractor over real recorded sessions and look at the distributions per drill and per skier (no UI needed; a replay test or a CSV export is enough).
2. Check that each metric moves in the expected direction on labelled good and rough repetitions of the same drill.
3. Try the derivative-based boot agreement from principle 2 next to the roll-curve correlation and keep whichever separates smooth from rough repetitions better.
4. Only then decide on a combined score, and keep it additive and inspectable (principle 4).
5. If wired into export, add a `turn-metrics.csv` and keep the experimental caveat in the header and in `analysis-summary.json`.
