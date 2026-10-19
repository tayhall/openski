# Feature ideas implementation audit

Updated 19 October 2026 through PR #43; see [project status](project-status.md) for merged work and validation evidence. Originally compared against [OpenSki Feature Ideas](https://chatgpt.com/share/6ac4ee17-7a7c-83eb-bd0d-861be9e403f8) on 6 October 2026.

The table tracks the original 15 ideas against current code. Partial implementations include training and descriptive analysis; none establishes validated on-snow technique assessment. Demo/synthetic coverage and hardware evidence are distinguished in the project status.

| Idea | Status | Current implementation / remaining work |
|---|---|---|
| Turn detection | Partial | Gyro candidates, heading-rate candidates (experimental), plus calibrated dry-lean detection, guided sets, timing and labelled evaluation; real ski turns need validation. |
| Carve detection | Not implemented | No carved/skidded/mixed classifier. |
| Edge angle | Partial | Neutral/functional boot calibration, live and replayed roll/pitch/relative yaw; snow-relative ski edge angles remain unvalidated. |
| Symmetry | Partial | Directional mean duration/peak ratios, normalised profiles and cross-boot angle/timing comparisons; no validated technique score. |
| Pressure proxy | Research only | Raw acceleration and public-dataset vertical-load scripts exist; no validated force or pressure measurement. |
| Turn shape | Partial | Dry lean onset/neutral return, durations and normalised roll profiles; real turn shape and radius remain unimplemented. |
| Stability | Partial (experimental) | Per-candidate roll roughness and torso quietness descriptors are exported; no validated ski chatter or stability score. |
| Terrain | Partial (experimental) | Run GPS; whole-day phone GPS and barometer tracking split into runs, lifts and rests and named from cached OpenStreetMap pistes and lifts (`docs/ski-day-design.md`); synthetic tests only, unchecked on a phone or on snow. |
| Video sync | Partial | Shared timeline, manual SYNC correspondence, reviewed labels and on-screen orientation overlay; no automatic matching or burned-in export. |
| Ghost run | Partial | Drills show a previous-attempt ghost; runs compare a reconstructed half-turn wave with a target. No measured reference-run comparison. |
| Progression | Partial | Training path unlocks, stars, best drill scores, streaks and vert points; demo attempts count. No longitudinal on-snow metric trends. |
| AI coach | Partial (rule-based) | Metronome and chirp feedback judge half-turn windows against beat/depth targets. No AI model or validated technique diagnosis. |
| Challenges | Not implemented | No challenge tracking. |
| Equipment comparison | Partial (started here) | Editable session equipment labels, reuse suggestions and exported metadata; no metric comparison yet. |
| Open telemetry | Partial | Raw IMU, calibrated orientation, labels, battery/RSSI, detected movements and reproducible analysis settings/summary exports; ski days export raw fixes as GPX and the derived day as JSON. |

Other proposals outside that table are also outstanding: telemetry overlays burned into video, OpenSki Score, on-snow personal bests, leaderboards, friends, achievements, video sharing and a 3D ski replay.

## First implementation

Session detail now supports Set equipment, including an empty label to clear it. Labels describe the ski model, length and setup and are suggested from existing sessions. Labels belong to individual sessions, so editing one recording does not change historical recordings. SQLite version 3 adds an empty default for old sessions; the export includes an equipment field in session.json.

## Next steps

1. Complete powered-sensor recording/recovery acceptance checks.
2. Collect labelled skiing footage and mounting information to validate turn detection.
3. Check whole-day tracking and the ski day screens on a phone and then on a real ski day (`docs/android-acceptance.md`, "Ski day checks"), and tune the splitter thresholds on that data.
4. Validate the implemented descriptive turn/torso metrics on quality-qualified recordings; equipment metric comparison remains planned.
5. Validate the existing on-screen overlay and manual SYNC workflow against recorded video, then investigate automatic alignment and video clock drift correction.

Scores and coaching need validated measurements before they can make meaningful claims.
