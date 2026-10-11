# OpenSki research handover (index)

Snapshot: 2026-10-11. Read this first. Everything here is **assumptions and benchmark evidence**: nothing has been validated on a boot on snow, and the author is an intermediate skier, so "good" versus "poor" is judged from published footage and lab data, not from this user's runs.

Evidence labels used in the documents: **CONFIRMED** (measured here or read from the source), **REPORTED** (stated by a source or a research agent, not independently checked), **INFERRED** (our reasoning).

## Reading order

1. `docs/sensor-spec.md`: the sensor and IMU recommendation (latest; replaces earlier hobby-grade assumptions).
2. `docs/first-snow-protocol.md`: what to record on the first snow day and the bench tests to do before it.
3. `docs/imu-calibration.md`: how the sensor-to-boot mounting is recovered at each fitting (still pose plus a knee-flex gesture), so units are interchangeable between people and boots.
4. `docs/imu-benchmark.md`, `docs/ski-turn-benchmark.md`, `docs/ski-imu-run.md`, `docs/ski-lab-truth.md`: estimator and turn-detector results on public data.
5. `docs/mpu6050-temperature.md`: measured MPU-6050 offsets and temperature behaviour.
6. `research/sensor-research/01` to `09b`: the sensor-selection research reports (chips, flight-controller practice, wearables, calibration, datasets, premium parts, boot practicalities).
7. `docs/ski-video-reference.md` and `drills/*.json`: what good technique looks like, and the drill definitions behind the animated videos.
8. `docs/handover-triage.md`: status of each resource in the earlier research handover document.

The older technique-research package (`README.md`, `docs/openski-handoff.md`, `handoff/*.zip`, `integration/`) predates the sensor work and still applies to the metric registry and schemas.

## Code (reference implementations, with tests)

| Path | Purpose |
|---|---|
| `openski/imu.py` | Mounting calibration, boot-frame conversion, 3D attitude estimate, slip monitor |
| `openski/turns.py` | Turn detection (heading rate), streaming detector |
| `tools/` | Benchmarks, lab alignment, drill rendering (Blender), dataset analysis |
| `tests/` | Unit tests |

Port the logic, not the Python. Run `python -m pytest tests` in a virtual environment with the packages in `requirements-lock.txt`.

## Key findings to carry forward

- Heading rate (gyro along gravity) is the primary turn signal; roll reversal alone fails for skidded or intermediate skiing.
- Use a 3D attitude filter. The 1-axis roll filter was wrong by tens of degrees on coupled motion; VQF was best (0.4 degrees on BROAD, 0.7 to 3.7 degrees on ski lab truth).
- Ski-mounted accelerometers spike to 6 to 18 g and clip a +-16 g part on 13 to 35 percent of samples, so accelerometer range matters more than gyro bias.
- Real MPU-6050 units differ widely in offset and temperature slope; calibrate per unit, and choose a better part for anything beyond the proof of concept.
- Cold behaviour of the candidate chips and batteries is unverified.

## Data and footage: what is not in the repository

`footage/`, `data/`, `outputs/`, `models/`, `*.mp4`, `*.xlsx` and `research/sensor-research/samples/` are gitignored and exist only on the original machine.

- **Public datasets** (re-downloadable): BROAD (CC BY 4.0), Beuken ski IMUs on Kaggle (Apache 2.0), Prochazka "Skiing Data Analysis", Zenodo 17186128 (CC BY 4.0), TUM VI imu-static, Cranfield MPU-6050 temperature set. Attribute the authors. The scripts in `tools/` document each file layout.
- **YouTube footage** was collected for personal research only under the project policy. Do not copy or host it; use the links and notes in `docs/ski-video-reference.md` and watch it at source.
- **Drill videos** are re-renderable with `tools/make_drill.sh` (needs Blender 4.5) and are published as a private page: https://claude.ai/artifact/PaNPULQhw7orsn57d9TKeg

## Open items

- No boot-mounted data exists yet. Bench tests (Allan variance, temperature, protractor mounting check, cold soak, clipping, g-sensitivity) are listed in `docs/first-snow-protocol.md` and `research/sensor-research/09a`.
- Sustained high-g cornering with VQF is untested.
- Unverified datasheet figures are listed at the end of `09a`.
- Not done: OpenAXES calibration reproduction, reading the Prochazka paper for sensor placement, SkiPose keypoints.
- This repository has no git remote.
