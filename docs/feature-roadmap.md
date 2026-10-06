# Feature ideas implementation audit

Compared against [OpenSki Feature Ideas](https://chatgpt.com/share/6ac4ee17-7a7c-83eb-bd0d-861be9e403f8) on 6 October 2026.

The conversation's main table contains 15 ideas. Before this work, 3 were partial and 12 were not implemented. The indoor POC now gives 7 ideas a partial implementation; 8 remain unimplemented. None meets its full on-snow product description yet. The Android app can now exercise the indoor pipeline with a sensor-free demo while the physical POC is being built.

| Idea | Status | Current implementation / remaining work |
|---|---|---|
| Turn detection | Partial | Gyro candidates plus calibrated dry-lean detection, guided sets, timing and labelled evaluation; real ski turns need validation. |
| Carve detection | Not implemented | No carved/skidded/mixed classifier. |
| Edge angle | Partial | Neutral/functional boot calibration, live and replayed roll/pitch/relative yaw; snow-relative ski edge angles remain unvalidated. |
| Symmetry | Partial | Directional mean duration/peak ratios, normalised profiles and cross-boot angle/timing comparisons; no validated technique score. |
| Pressure proxy | Not implemented | Acceleration is recorded; no loading metric. |
| Turn shape | Partial | Dry lean onset/neutral return, durations and normalised roll profiles; real turn shape and radius remain unimplemented. |
| Stability | Not implemented | No vibration/chatter metric. |
| Terrain | Not implemented | No phone GPS/altitude recording or mapping. |
| Video sync | Partial | Shared timeline, manual SYNC correspondence, reviewed labels and on-screen orientation overlay; no automatic matching or burned-in export. |
| Ghost run | Not implemented | No comparison against a reference run. |
| Progression | Not implemented | Sessions grouped by ski day; no metric trends over time. |
| AI coach | Not implemented | No coaching engine or AI integration. |
| Challenges | Not implemented | No challenge tracking. |
| Equipment comparison | Partial (started here) | Editable session equipment labels, reuse suggestions and exported metadata; no metric comparison yet. |
| Open telemetry | Partial | Raw IMU, calibrated orientation, labels, battery/RSSI, detected movements and reproducible analysis settings/summary exports; no GPS. |

Other proposals outside that table are also outstanding: telemetry overlays burned into video, OpenSki Score, personal bests, leaderboards, friends, achievements, video sharing and a 3D ski replay.

## First implementation

Session detail now supports Set equipment, including an empty label to clear it. Labels describe the ski model, length and setup and are suggested from existing sessions. Labels belong to individual sessions, so editing one recording does not change historical recordings. SQLite version 3 adds an empty default for old sessions; the export includes an equipment field in session.json.

## Next steps

1. Complete powered-sensor recording/recovery acceptance checks.
2. Collect labelled skiing footage and mounting information to validate turn detection.
3. Record phone GPS alongside sessions for speed, distance and terrain context.
4. Build descriptive turn metrics and equipment comparison using quality-qualified recordings.
5. Validate the existing on-screen overlay and manual SYNC workflow against recorded video, then investigate automatic alignment and video clock drift correction.

Scores and coaching need validated measurements before they can make meaningful claims.
