# Running the initial measurement pipeline

Status: executable exploratory proof of concept, with geometry/contract tests and a real-model negative-control smoke test. Accuracy on skiing footage is not evaluated. No ski technique reference dataset has been produced.

## Setup

Use Python 3.11 or later with a compatible MediaPipe wheel. Run from the repository root:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -e .
```

Download the official Full Pose Landmarker task bundle linked from [Google documentation](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker) into `models/pose_landmarker_full.task`. Model and video files are ignored by Git. Model provenance for the local smoke test: Google storage path `mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task`. The analysis records its SHA-256; confirm applicable model terms before distributing model assets.

The local test environment has MediaPipe 1.1.0 and PyAV 19.0.1 on Python 3.13.15. `requirements-lock.txt` records its installed packages. PyAV decodes RGB frames and preserves presentation times. MediaPipe includes OpenCV as a dependency; NumPy and Pandas can be added for later analysis but are not needed by the current geometry/summary layer. A separate OpenCV video reader would discard timestamp guarantees on some inputs, so it is not used here.

## Analyse a recording

Copy `examples/recording-manifest.json` to a recording-specific manifest. Supply real attribution, source details and a permission evidence reference or ownership declaration. Explicitly confirm processing and numerical derivative retention, original playback timing, one skier, a continuous shot and upright decoded images. The supplied template deliberately fails the permission gate.

```powershell
.\.venv\Scripts\python -m openski analyse --video D:\Footage\owned-run.mp4 --model models/pose_landmarker_full.task --manifest my-recording.json --output outputs/run-001
```

Only local files are accepted. There is no video downloader or training command. Existing output directories are never overwritten. Outputs:

- `analysis.json`: manifest, asset hashes, versions, methods, image dimensions, time origin and limitations.
- `landmarks.jsonl`: per-frame presentation time, inference time, image and model-estimated world landmarks. World estimates are retained separately and not used as anatomical truth.
- `metrics.csv`: per-frame anatomical-side apparent knee flexion, leg inclination, torso inclination and torso–leg angulation. Blank numeric cells mean unavailable. Flags are JSON arrays.

Angles are calculated in pixel coordinates to preserve image aspect ratio. Inclination is signed relative to upward image vertical, positive toward image right; it is not gravity-relative or snow-relative inclination. Torso–leg angulation is a projected axis difference, not anatomical hip flexion. The default 0.5 visibility/presence mask is a configurable tracking heuristic, not validated confidence. Per-metric confidence remains null.

No raw video copies or frames are exported. In-memory frames are released by the decoder. Failed runs remove only their newly created staging directory. The tool never deletes source files. Derived data retention must still follow the recorded permission scope.

## Review turns and summarise

Manually review identity, anatomical sides, occlusions and camera suitability before interpreting trajectories. The initial tool flags the need for review; it does not automatically detect side swaps, cuts or occlusions reliably. Continuous-shot and upright-image requirements are operator declarations rather than automatic detectors.

Fill `examples/reviewed-boundaries.json` with actual reviewed complete-turn boundaries. Each event needs these fields (this is a field template, not a measurement):

```text
time_s: measured time relative to first decoded frame
uncertainty_s: reviewer timing uncertainty bound
reviewer: pseudonymous reviewer ID
definition: same operational boundary definition for all events
next_turn_direction: left, right, or unknown
direction_basis: heading/trajectory evidence or null
```

Do not equate an observed body reversal with ski-flat, edge engagement, release of pressure or load transfer. Use a consistent observable definition. Boundaries should delimit complete turns; unobserved start/end fragments are excluded.

```powershell
.\.venv\Scripts\python -m openski summarise --analysis outputs/run-001 --annotations reviewed-run-001.json --output outputs/run-001-turns.jsonl
```

The summariser validates every emitted record against `schemas/turn.schema.json`. It reports duration and median apparent angles, preserving missing data. Duration uncertainty is the sum of annotation bounds; it is not a statistical confidence interval. Validity coverage is explicitly a frame fraction, which differs from time coverage for variable frame rate. Full trajectories remain necessary for studying phase relationships. There is no smoothing, interpolation, automatic phase inference or automatic coaching classification in this version.

## Verification and remaining validation

```powershell
.\.venv\Scripts\python -m unittest discover -s tests -v
$env:MPLCONFIGDIR = Join-Path (Get-Location) '.venv/mpl-cache'
.\.venv\Scripts\python -m tests.smoke_video --model models/pose_landmarker_full.task
```

Eight unit tests cover angle/sign conventions, aspect-ratio handling, missing poses, quality masks, rights/timing gates, reviewed boundaries, the JSON Schema and turn summaries. The smoke test generates ten blank video frames, runs the real decoder and MediaPipe model, and checks that no poses or angles are invented. Its temporary video is not skiing reference material. These checks passed locally on 2026-10-08 after running outside the Windows filesystem sandbox, which blocked writes in Python-created temporary directories.

Next validation requires permitted skiing footage with manually reviewed landmarks and events. Assess detection coverage, side swaps, projected-angle annotation agreement and event uncertainty by view and turn style. Anatomical-angle validation needs an independent calibrated reference. Boot-IMU transfer requires synchronised sensor/video recordings, independent event definitions and participant-separated validation, as described in the research report.
