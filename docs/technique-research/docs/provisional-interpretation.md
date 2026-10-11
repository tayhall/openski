# Provisional angles, timing examples and experimental settings

Snapshot revision 2, 2026-10-08. No OpenSki field tests have taken place. This follow-up adds manual image estimates and explicit starting settings to the qualitative research handoff. It does not create anatomical reference ranges or production coaching thresholds.

## What was estimated from footage

Four paused public-player frames from two sources were visually inspected; three were manually annotated. A third-source playback attempt failed and supplied no measurements. The [annotations](../research/manual-angle-annotations.json) retain original pixel-marker choices, frame size, player locator and limitations. No MediaPipe inference or automated body tracking was performed on this footage. Temporary viewing images were deleted after inspection.

The [derived JSON](../research/provisional-video-estimates.json) contains eight projected-angle proxies. Inclination is signed relative to the upward image vertical: image-right is positive, image-left negative. The bend proxy is 180° minus the angle between proximal-leg, knee and boot markers. The hip region is inferred under clothing and the boot marker is not a visible anatomical ankle centre, so **the bend proxy is not anatomical knee flexion**.

| Selected frame/context | Proxy | Rounded central estimate | Marker sensitivity grid |
|---|---|---:|---:|
| V05, approximately 8.32 s, exaggerated in-rigger drill | Torso image axis | +25° | 0° to +65° |
| Same frame | Extended-leg image axis | +55° | +40° to +70° |
| Same frame | Extended-leg bend proxy | 5° | 0° to 60° |
| Same frame | Bent-leg bend proxy | 100° | 20° to 165° |
| V08, approximately 9.72 s, basic parallel candidate | Torso image axis | −20° | −45° to 0° |
| Same frame | Pelvis-to-boots image axis | −35° | −60° to −20° |
| V08, approximately 10.55 s, same candidate later | Torso image axis | −10° | −40° to +15° |
| Same frame | Pelvis-to-boots image axis | −25° | −45° to −5° |

Sources: [V05 carving drill](https://www.youtube.com/watch?v=S4M9AVh4YPU&t=8s), [V08 assessment candidate](https://www.youtube.com/watch?v=M8BnlV-Pdcw&t=9s). These are isolated configurations, not sampled movement extrema, typical technique ranges or independent population observations. V05 is a deliberately exaggerated drill; V08 is an assessment candidate rather than an assumed ideal skier.

For sensitivity analysis each marker was shifted independently by −radius, 0 or +radius pixels on each axis, using 12 px for the close drill and 8 px for the smaller candidate. Those radii are analyst assumptions, not experimentally established annotation errors. All combinations were evaluated and the observed grid minimum/maximum rounded outward to 5°. This grid is neither a continuous worst-case bound nor a confidence interval; it excludes camera projection, lens distortion, slope and marker-model error. Central estimates are rounded to 5° to avoid implying pixel-derived biomechanical precision.

The wide bend-proxy sensitivity is a substantive finding: these frames cannot support useful precise knee-angle prescriptions. Image-axis comparisons also cannot be transferred to boot roll. More side-on footage with visible joint markers and independent review would improve the estimates, but anatomical validation still needs calibration.

## Timing values we can support now

Source playback speed in these public clips has not been verified. The player locations above identify frames; their difference is not reported as physical skiing time. Transition duration, phase fractions and event offsets remain unset.

The [Martínez field study](https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2019.00018/full), Figure 3, gives two illustrative sequences: nine carving turns with duration **2.97 ± 0.43 s**, and sixteen short turns with **1.04 ± 0.41 s**, where the reported spread is SD. These are source examples, not population reference intervals or desired durations. They are kept separately in the parameter JSON. The same paper supplies an offline replication starting point: 0.5 Hz coarse filtering, 3 Hz refinement, a 0.6 neighbour-search factor, and a strictly 0.3–5 s coarse interval gate. Its full method and task limitations still apply.

## Experimental settings to give OpenSki

The [parameter JSON](../integration/provisional-parameters.json) contains 17 entries: eight engineering choices, four published replication parameters and five unresolved values. Each includes its basis, applicability, limitations and required validation.

| Engineering choice | Initial value | Purpose |
|---|---:|---|
| Sensor acquisition/logging | 100 Hz | 10 ms nominal spacing; compare with 200 Hz later |
| MPU6050 gyro full scale | ±1000°/s | Initial clipping headroom; not a predicted movement range |
| MPU6050 accelerometer full scale | ±16 g | Initial impact headroom; not typical skiing acceleration |
| Requested gyro hardware bandwidth | Nominal 20 Hz | Initial filtering choice; verify module/library behaviour and aliasing |
| BLE batch maximum age | 50 ms | Transport budget while preserving sample timestamps |
| Stationary calibration capture | 10 s | Initial bias/noise sample; stillness needs independent checking |
| Timestamp-gap diagnostic | >20 ms | Initial review flag at nominal 100 Hz; also check sequence loss |
| Dual-boot relative clock-error budget | 10 ms | Design target only; event uncertainty also matters |

These are engineering starting choices, not values inferred from videos. Supported MPU6050 full-scale options and a nominal 20 Hz filter option were checked against the [primary Linux driver](https://raw.githubusercontent.com/torvalds/linux/master/drivers/iio/imu/inv_mpu6050/inv_mpu_core.c). Legacy manufacturer register-map requests redirected or failed. Exact register values, coupled accelerometer filtering, delays and achieved sample rate therefore remain bench checks rather than supplied firmware commands. Wider ranges trade resolution for headroom; change them based on measured noise/clipping, preserving metadata.

The published filter profile is **offline**. Zero-phase processing uses future samples. A causal Android/ESP32 implementation has different delay and timing behaviour and needs its own evaluation. The detector's amplitude/decision rules are not filled in here, so the four numerical replication entries do not constitute a complete working detector. A single-boot variant is a separate hypothesis from the published bilateral method.

Keep amplitude thresholds, orientation-fusion gains, ideal body/ski angles, loading targets and bilateral fault thresholds unset. In particular, do not turn the 0.3–5 s search gate into an assessment of good rhythm or the 10 ms clock budget into a skier-symmetry tolerance.

## Use and revision

Import the JSON and schema as research configuration/documentation. Select settings explicitly after inspecting the current OpenSki firmware and sensor library; this file does not apply itself to hardware. Keep coaching disabled while logging descriptive experimental features. Record the profile ID, chosen settings and method version with every recording.

Before skiing, bench-check sample rate, scale/sign, mounting transform, stillness/noise, clipping flags, loss handling and clocks. Then compare sensor events with independent same-definition annotations in synchronised, permitted, original-speed video. Evaluate task and participant differences without tuning and evaluating on the same recordings. Replace each provisional setting only with a documented rationale and validation result.

Recalculate the image examples using `.venv/Scripts/python.exe -m tools.derive_provisional_angles`. Run `.venv/Scripts/python.exe tools/build_handoff.py` to validate and rebuild the revision-2 handoff. These checks establish reproducibility and package integrity, not skiing accuracy.
