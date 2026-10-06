# Bench results log

Measured evidence for the [POC plan](poc-plan.md). Add a dated entry for each session; do not edit old entries.

## 2026-10-06: first bench session, loose board

**Setup.** ESP32 DevKit V1 with a GY-521 (MPU-6050) on four jumper wires, loose on a desk. Sensor 58:BF:25:9E:A6:0E, assigned as the left boot in the app. Firmware built from the repo at that point (BLE name `OpenSki-ski`) and flashed over USB on COM11 with the recorder partition layout. It reports `Recorder ready: 57662 sample slots (~576 seconds at 100 Hz)`. Phone link about −41 dBm. App with Bench tools. The board had been powered about an hour before the still capture, so that capture is a warm baseline at room temperature (sensor reading about 27 °C on the serial log).

### Resting readings
Serial log at 0.5 Hz, board resting with Y roughly pointing down, 27.0 °C:

- Acceleration about 0.62, −9.93, 1.48 m/s², total about 10.06
- Gyro about −0.034, +0.002, +0.007 rad/s (about −1.95, +0.1, +0.4 °/s)

### Six faces

Run 1 (18:38):

| Face | Total m/s² | Off axis | x | y | z |
|---|---|---|---|---|---|
| +X up | 10.319 | 1.5° | 10.315 | −0.247 | −0.110 |
| −X up | 9.281 | 1.4° | −9.278 | −0.224 | 0.042 |
| −Y up | 10.057 | 2.6° | 0.448 | −10.047 | −0.021 |
| +Y up | 9.691 | **8.1°** | 0.010 | 9.593 | −1.371 |
| +Z up | 10.109 | 2.0° | 0.228 | −0.269 | 10.103 |
| −Z up | 10.157 | 3.8° | 0.477 | 0.469 | −10.135 |

Run 2 (18:42), repeats of three faces:

| Face | Total m/s² | Off axis | x | y | z |
|---|---|---|---|---|---|
| +X up | 10.313 | 2.0° | 10.307 | −0.262 | −0.256 |
| +Y up | 9.703 | 4.1° | 0.340 | 9.678 | 0.605 |
| −Y up | 10.063 | 3.6° | 0.637 | −10.043 | 0.033 |

**Repeatability.** The same face read twice agrees to under 1 mg: +X 10.315 then 10.307 m/s²; −Y −10.047 then −10.043. Bench tool and placement are consistent.

**Best estimate** (the +Y capture in run 1 was 8° off, so Y uses run 2):

| Axis | Offset | Scale | Note |
|---|---|---|---|
| X | +0.52 m/s² (about +53 mg) | −0.10% | repeated |
| Y | −0.18 m/s² (about −18 mg) | +0.55% | placement-limited, roughly +0.1% to +0.6% |
| Z | −0.02 m/s² | **+3.2%** | one pair only, worth a repeat |

These are about the limits I recall for the MPU-6050 (offset around ±50 mg on X and Y, sensitivity ±3%), so the board looks normal. That is from memory and should be checked against the datasheet. The X offset looks like about 3° of tilt when X is horizontal, which is the size of the ±3° roll target.

### Still noise, 60 s (18:46, warm)

| Measure | Value |
|---|---|
| Gyro bias | x **−1.93**, y +0.06, z +0.38 °/s |
| Gyro noise | 0.05 °/s |
| Accelerometer noise | 0.030 m/s² |
| Effective rate | **37.9 Hz** |
| Gaps over 100 ms | 0 |

### Observations

- **Noise is not the concern.** At 0.05 °/s rms, integrating for 60 s adds under 0.05°. Bias is what matters, as the plan says.
- **Short-term bias is stable.** The serial log (about −1.95 °/s) and the 60-second BLE capture (−1.93 °/s) agree to about 0.02 °/s, inside the 0.05 °/s target. This is one temperature. The cold-start comparison is still to do.
- **The live rate is 37.9 Hz, not the documented 50 Hz.** The firmware enforces a 20 ms minimum between sends (`kLiveRateLimitMs` in `BluetoothService.cpp`) from a loop that ticks every 10 ms plus work, so sends land 20 or 30 ms apart. The flash recorder is unaffected (100 Hz). Drill timing tolerates it; a decimated true 50 Hz stream, or batching, would be better. To fix in the next firmware change.

### Not yet done

- Tilt accuracy against a reference angle (before and after applying the accelerometer correction).
- Z face repeat, and the second half of the Z pair.
- Gyro bias after a cold start and after re-warming, and the freezer test.
- Streaming health over an hour, Wi-Fi against BLE comparison, flash recording and recovery, battery run time.
