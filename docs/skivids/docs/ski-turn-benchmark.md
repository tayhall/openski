# Turn detection on public ski data (SkiC-LSTM / Robak and Turek)

Date: 2026-10-11. Code: `tools/benchmark_skic.py`, `openski/turns.py`.

## Data

- Source: https://github.com/mszymocha/SkiC-LSTM (MIT repository, no separate data licence stated; derived from Robak and Turek, ICCS 2025).
- 105 runs, 10 skiers with ids (11 reported), phone IMU at 10 Hz worn on the calf in an unknown orientation, instructor labels from synchronised video: 3,561 turn START/STOP events with left/right, plus style (carving short/long, skidded short/long, snowplow, quick) and skier level (beginner to expert).
- The data is not stored in this repository.

## What this tests, and what it does not

It tests our **turn-switch logic on real skiing**. It does **not** test our sensor, mounting calibration or roll estimate: a calf-worn phone at 10 Hz is not a boot IMU. The phone's rotation axis is unknown, so two proxies were used: the principal gyro axis (integrated to an angle, called "roll-like"), and the heading rate about vertical from the phone's own gravity vector ("heading").

## Results

All runs, tolerance +-0.5 s (one-to-one matching of switch times), untuned:

| Signal | Precision | Recall | F1 | Notes |
|---|---|---|---|---|
| Roll-like (principal axis) | 0.47 | 0.58 | 0.52 | F1 0.87-0.94 for carved or quick turns; 0.12-0.41 for skidded and snowplow |
| Heading rate | 0.45 | 0.81 | 0.58 | Finds skidded turns but over-counts |

By skier level with the roll-like signal: advanced 0.92, expert 0.75, **intermediate 0.37, beginner 0.32**.

Heading-rate detector tuned on 5 skiers (smoothing 0.8 s, amplitude 0.6 x signal SD, minimum duration 0.4 s) and **evaluated on the other 5 skiers (38 runs)**:

| Tolerance | Precision | Recall | F1 |
|---|---|---|---|
| +-0.3 s | 0.50 | 0.53 | 0.51 |
| +-0.5 s | 0.72 | 0.77 | **0.74** |
| +-1.0 s | 0.81 | 0.86 | 0.83 |

Held-out by level (+-0.5 s): beginner 0.76, **intermediate 0.62 (11 runs)**, advanced 0.77, expert 0.91. By style: short carved 0.81, short skidded 0.78, long skidded 0.54, long carved 0.54 (2 runs).

## Fusion and the app detector

- **Fusing the roll-like signal with heading rate did not help.** On held-out skiers: roll-like alone F1 0.46, heading alone 0.74, union 0.60, fused (heading boundaries kept only if supported by a roll-like boundary or a strong lobe) 0.72. The calf-phone roll proxy is too weak to add information. A real boot-mounted roll signal may behave differently; this dataset cannot show that.
- **The app detector** (`openski.turns.detect_turns_heading`) uses only the accelerometer-averaged gravity direction and the gyro: held-out F1 **0.71 at +-0.5 s and 0.82 at +-1.0 s**; by level: beginner 0.74, intermediate 0.63, advanced 0.68, expert 0.85 (4 to 15 runs each). Parameters were tuned on the other five skiers.

## What it means

1. **Turn counting is much harder for intermediate and skidded skiing than for expert carving.** A boot roll reversal alone will not be enough for the skier this app is for. The roll-like detector's strong results are mostly on advanced skiers carving.
2. **Heading rate recovers skidded turns** that roll reversal misses, at the cost of false positives that tuning mostly removes. Fusing the calf-phone roll proxy with it did not help (see above), so heading rate is the primary signal. Whether a real boot roll signal adds value is still open.
3. **Timing is only good to roughly half a second here** (10 Hz, calf phone, simple thresholds). The 0.03-0.06 s reported for boot-cuff gyro methods in a lab is not what to expect from this setup.
4. **A learned model is the published route to higher scores.** The authors report F1 0.943 with an LSTM on this data; our threshold logic is far below that. Whether that is worth the complexity depends on what the app must promise.

## Limits

- Ten skiers and 38 held-out runs: wide uncertainty, especially for the 11 intermediate runs.
- The tuning sweep was small (27 settings) and done on one split; do not read the held-out numbers as final.
- Labels are one instructor's judgement of where a turn starts and stops; the match tolerance partly absorbs that.
- Phone on the calf, not a boot cuff; 10 Hz, not 100 Hz or more.
