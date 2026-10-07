# First handheld bench examples

Recorded on 7 October 2026 with the S3 and MPU6050. Files contain UTC host
timestamps, raw IMU Wi-Fi snapshots and on-device predictions (version 1 for
bench-rest-03, version 2 for the initial handheld tests).
Sample rate is determined by HTTP latency; these are not the full 100 Hz stream.
Motion and IMU snapshots are separate requests and may have different sensor
timestamps. Network errors are retained. Each test includes pickup/put-down and
additional rest before/after the user's instructed movement.

| File | Instructed action | Provisional interpretation |
| --- | --- | --- |
| bench-rest-03.jsonl | Leave untouched on bench | Stable gyro magnitude around 0.034 rad/s; acceleration magnitude median 10.319 m/s². |
| hand-left-04.jsonl | Hold level, roll left, pause, return, pause | Main positive Z excursion then negative Z; pauses recognised. |
| hand-right-05.jsonl | Hold level, roll right, pause, return, pause | Main negative Z excursion then positive Z; pauses recognised. |
| hand-forward-07.jsonl | Tilt away-facing edge down, pause, return | Predominantly Y motion; both signs in raw readings but only negative Y labelled reliably. |

Left/right mapping applies only to the grip used in these sessions. There are
no frame-exact video annotations; pickup movements can resemble valid gestures.
The user's "done" messages confirm completion, not exact event timestamps.
Do not treat the firmware's predicted labels as human ground truth. These files
inform regression scenarios and threshold choices; they do not establish
classification accuracy or validate real ski turns.

## Version 3 diagnostic replay

The exact firmware tracker was replayed against linearly interpolated HTTP
gyro snapshots, reconstructing 100 Hz samples only across gaps up to one second.
Larger gaps discard an unfinished gesture. This approximation cannot restore
missing peaks, so estimated angles below are not physical validation.

| Recording | Accepted reconstructed excursions | Larger gaps |
| --- | --- | --- |
| Bench rest | None | 0 |
| Left test | One Z negative, about 33° (consistent with return) | 8 |
| Right test | One Z positive, about 42° (consistent with return) | 2 |
| Forward test | One Y negative, about 75° | 1 |

Replays confirm that quiet bench data creates no gesture and that sampled
rotation produces axis-specific excursions. They do not recover both halves of
each instructed movement. Sparse raw capture remains a limitation, and the
forward replay's angle especially must not be taken as a measured 45° tilt.
Use `tools/replay_gesture_tracker.cpp` with timestamp/gyro XYZ input to reproduce
the diagnostic replay; full-rate recordings are required for scored evaluation.

## Version 3 live gesture tests

These additional files retain complete gestures computed on the device from
its IMU stream, even when HTTP snapshots have gaps:

| File | Instructed action | Complete gestures observed |
| --- | --- | --- |
| gesture-left-08.jsonl | Left roll and return | Z +52.02° / 1.492 s; Z −47.68° / 1.447 s. |
| gesture-right-09.jsonl | Right roll and return | Z −46.98° / 1.770 s; Z +50.69° / 1.980 s. |
| gesture-forward-10.jsonl | Forward tilt and return | Y +27.91° / 1.329 s; return not accepted. |

The instructed target was approximately 45°, without an independent angle
measurement. All three reported zero on-device sample gaps at review time.
The forward-return rejection is an open issue; these examples do not establish
general accuracy or validate ski movements. Each file includes device history
from preceding tests, so compare gesture sequences against its initial count.
