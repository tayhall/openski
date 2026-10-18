# Analysis scripts

Run from the repo root. All use numpy; the older research scripts also need scipy and vqf (`pip install numpy scipy vqf`). The two bench tools need numpy only and have a `--selftest` that proves them on synthetic data with known truths (non-zero exit on failure).

| Script | Purpose | Run |
| --- | --- | --- |
| `still_noise_report.py` | T0 bench soak: sample rate, gyro bias and drift, Allan-deviation noise densities and floors, accel magnitude, quality flags. Prints no datasheet values; compare them yourself. | `python tools/analysis/still_noise_report.py export.zip [--json out.json]`, `--selftest` |
| `mag_heading_check.py` | T2 turn on the spot: magnetometer magnitude spread, hard-iron and soft-iron fit, tilt-compensated heading against integrated gyro yaw. Experimental. | `python tools/analysis/mag_heading_check.py export.zip [--side L] [--json out.json]`, `--selftest` |
| `export_io.py` | Shared reader for an export ZIP or a directory of its CSVs (used by the two tools above). | library |
| `clock_align_validate.py` | Prototype and validation of the offline gesture clock aligner (mirrored by `ClockAlign.kt`), on public ski data or `--synthetic`. | `python tools/analysis/clock_align_validate.py <run10.txt> [--synthetic]` |
| `ski_vertical_load.py` | Tilt-compensated vertical load from a ski-mounted IMU (public data; scipy and vqf). | `python tools/analysis/ski_vertical_load.py <run10.txt>` |
| `ski_turn_pattern_imu5.py` | Turn pattern re-check on IMU 5 of the public data (scipy and vqf). | see the script header |

The bench tools take an export as written by the app (`samples.csv`, `magnetometer.csv`); the export carries no temperature, so no bias-versus-temperature figure is produced.
