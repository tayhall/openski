# OpenSki repository handoff

Research snapshot: 2026-10-08, revision 2, interface draft 0.1.0. This package is usable for designing sensor acquisition, experimental features and validation. It now includes eight coarse manual image proxies and 17 provisional parameter entries, separately labelled. It contains no validated performance targets, numerical reference distributions or production coaching rules.

## What to copy

Extract `handoff/openski-research-2026-10-08-v2.zip` into a directory such as `docs/technique-research/` in the OpenSki repository. Preserve its relative directories so evidence links and source IDs continue to resolve. It contains original JSON/CSV research records, schemas, unmeasured integration examples and documentation; no third-party footage, frames, models or Python environment. The earlier archive is retained as revision 1.

| File | Use in OpenSki |
|---|---|
| `integration/metric-registry.json` | Definitions, hardware requirements and validation gates for five initial sensor features |
| `integration/provisional-parameters.json`, `schemas/provisional-parameters.schema.json` | Experimental acquisition settings, published offline replication parameters and explicit unresolved values |
| `research/provisional-video-estimates.json`, `research/manual-angle-annotations.json` | Eight frame-specific image proxies with marker sensitivity; research-only, never boot-angle targets |
| `docs/provisional-interpretation.md`, `schemas/provisional-video-estimates.schema.json` | Methods, signed-angle conventions, uncertainty and the provisional-estimate contract |
| `docs/sensor-integration.md` | Raw acquisition, boot coordinate frame, clock alignment and capability boundaries |
| `research/technique-findings.json` | 33 qualitative findings with observation/guidance/hypothesis separated and source IDs retained |
| `schemas/technique-findings.schema.json` | Validate the findings file; it is a research knowledge contract, not an IMU sample contract |
| `research/source-catalogue.csv` | Resolve citations, publisher attribution, review scope and permission status |
| `research/coverage.json` | Coverage of 30 videos, source dependence and outstanding sample gaps |
| `research/youtube-observations.json`, `research/viewing-audit.json` | What was actually viewed and where; player timestamps are not physical turn measurements |
| `research/knowledge-base.json`, `research/initial-research.md` | Initial technical sources, measurement taxonomy and scientific rationale |
| `docs/scoring-recommendations.md`, `docs/validation-protocol.md` | Feedback limits and experiments needed before coaching use |
| `schemas/turn.schema.json`, `examples/` | Draft video-research exchange format and unmeasured examples; these do not describe boot-derived joint angles |

The local MediaPipe tool remains in this research repository. The production app does not need its dependencies or third-party videos. No code was changed in the OpenSki repository by this handoff.

## Implementation priorities

1. Check the current ESP32/BLE implementation against the acquisition contract. Preserve device sample timestamps, sequence numbers, raw accelerometer/gyro channels, units, boot side and mounting/calibration metadata. Do not substitute phone BLE arrival time for acquisition time.
2. Implement calibrated boot angular-rate logging and quality diagnostics. Test axes with known bench rotations. Keep six-axis orientation estimates explicitly conditional under dynamic acceleration.
3. Add experimental turn-boundary detection with a versioned event definition, then descriptive duration/rhythm summaries. Store missing and ambiguous events rather than inventing values.
4. For dual boots, establish clock offset/drift and event-pairing reliability before reversal-lag or bilateral trajectory features. One boot cannot produce between-foot symmetry.
5. Collect synchronised, permitted video and OpenSki raw samples for the same skier. Evaluate the event definitions and candidate features against independent annotations before enabling a skiing-technique recommendation.

The metric registry defines what to calculate, not a complete validated detector or firmware implementation. Sensor-output schemas and BLE packet changes must be designed against the actual OpenSki code and hardware, which have not been inspected here. Unknown or unsupported values remain null/unavailable with reasons.

## What the current evidence changes

Compare trajectories and timing within a declared task rather than rewarding a large maximum angle. Short steered turns, skidded turns, railroads, exaggerated drills and racing have different intended outcomes. The CSIA Level 3 source explicitly contains below-standard consolidation examples as well as qualifying refinement examples; an official video is not a blanket positive label. These distinctions are recorded in findings F020–F021 and F031–F033.

Boot rotation is not a body joint angle, pressure measurement, grip observation or true snow-relative ski edge angle. Findings about body movement should guide a validation experiment rather than be translated directly into sensor thresholds. A low-quality recording means a metric is unavailable; it does not mean the skier performed badly.

## Prompt for the OpenSki developer

> Read `docs/technique-research/docs/openski-handoff.md`, then the metric registry, provisional parameters and sensor integration specification within that snapshot. Inspect the existing ESP32 firmware, BLE protocol and Android sensor handling. Implement the missing acquisition metadata, sample-clock handling, raw logging and quality flags appropriate to the current hardware. Treat the provisional hardware settings as engineering starting choices requiring device/library checks. Keep the published zero-phase detector parameters offline and do not mistake them for a complete real-time detector. Propose experimental turn-duration and boot-rate outputs using versioned definitions and explicit unavailable states. Keep single-boot and dual-boot capabilities separate. Preserve provenance and validation status. Do not import the manual projected image angles as boot targets or introduce joint-angle estimates from boot IMUs, ideal angle thresholds, composite ski scores or technique diagnoses. Report the changes and bench/replay checks, and identify synchronised skiing validation still required.

## Snapshot checks

The handoff manifest lists file hashes and the research counts. JSON schema validation and cross-file source checks establish package integrity only. They do not establish skiing measurement accuracy. Public playback review has not established rights for automated video processing or model training.
