# OpenSki sensor integration specification

Version 0.1.0. Proposed research interface, not a validated production algorithm. The app consumes sensor-derived numerical records; third-party footage stays outside the production system.

## Acquisition contract

Preserve raw samples before feature calculation. Each stream needs session ID, device ID, boot side, firmware and sensor model, sensor-to-boot mounting transform, accelerometer/gyro scale, units, nominal sampling rate and calibration reference. Each sample needs device monotonic time, sequence counter, accelerometer XYZ, gyroscope XYZ and clipping/dropout flags. Use SI units: seconds, m/s² and rad/s. Record whether acceleration includes gravity. Never silently combine sensor-frame and boot-frame vectors.

Define a right-handed boot frame: +X forward along the boot, +Y left, +Z upward through the sole. Use the same physical convention for both boots; anatomical-side reflection for comparison is a separate documented operation. Specify mounting transforms as sensor-to-boot rotations. Validate axis signs with bench rotations before skiing. A boot-cuff mount can move relative to the sole, so mounting location belongs in metadata.

BLE arrival times are diagnostic transport information, not acquisition timestamps. Fit offset and drift from device clocks into a session clock using repeated synchronisation observations. Preserve residual synchronisation error and sequence gaps. Do not interpolate across sensor saturation or long dropouts. Clock wrap and reconnection start new explicit clock segments.

## Proposed processing chain

1. Check calibration, units, sample ordering, saturation, sampling jitter and packet loss. Retain raw streams and exclusion masks.
2. Rotate sensor vectors into the defined boot frame. Bias estimates must be supported by a stationary calibration interval; do not interpret dynamic turn accelerations as gravity alone.
3. Generate angular-rate and optional fused-orientation trajectories. Label orientation reference and drift limitations. Six-axis roll/pitch estimates under sustained acceleration require independent validation. No absolute heading or snow-relative ski angle is implied.
4. Detect candidate turns using a replication of the published boot-gyro approach only after reviewing its exact implementation. Keep the one-boot configuration as a separate experiment. Attach an operational event definition to each candidate; do not name every zero crossing a true edge change.
5. Match candidate events to an independent reference, then extract per-turn duration, angular-rate trajectories, extrema timing and within-task variability. Do not discard failed turns just because they reduce agreement.
6. For dual boots, compare synchronised events and signed/reflected waveforms. Report bilateral differences with uncertainty and task context. A one-boot left-versus-right comparison changes that boot’s inside/outside role and is not a between-foot symmetry measurement.

The primary methodological starting point is [Martínez et al., field turn detection](https://doi.org/10.3389/fspor.2019.00018). Its results are motivation for replication, not OpenSki acceptance evidence.

## Capability boundaries

| Concept | Single boot | Dual boots | Bilateral insoles | Required validation |
|---|---|---|---|---|
| Boot angular rate | Sensor measurement after calibration | Both boots | Unavailable | Scale, bias, alignment and clipping |
| Boot orientation | Conditional estimate | Conditional estimates | Unavailable | Dynamic orientation reference |
| Turn timing | Candidate estimate | Bilateral candidates | Distinct load events | Independent same-definition video/reference events |
| Reversal lag between boots | Unavailable | Candidate estimate | Different loading-lag feature | Clock alignment and event agreement |
| Knee/hip/torso angles | Unavailable | Unavailable | Unavailable | Additional body sensing or calibrated imaging |
| True ski edge angle | Unavailable directly | Unavailable directly | Unavailable directly | Local ski-base/snow geometry reference |
| Foot loading/distribution | Unavailable | Unavailable | Conditional measurement | Sensor calibration, temperature and boot force-path effects |
| Carving quality | Not established | Not established | Not established | Independent ski-motion and outcome labels |

## Candidate research outputs

Every output includes metric ID, value/unit, sensor configuration, time interval, method version, calibration reference, context, quality flags and validation status. Separate tracking/clock diagnostics from uncertainty and from performance. The candidate set is `turn_duration_s`, `boot_roll_rate_rad_s`, `boot_reversal_lag_s`, `peak_rate_phase_fraction` and `turn_duration_variability`. Their definitions belong in a metric registry before implementation.

Do not map a video knee angle onto a boot-roll field. Instead link independently measured video and sensor records by session and time, test relationships within context, and retain null when a relationship is unobservable. The production interface should accept only metrics whose applicable hardware and validation domain match the current session.
