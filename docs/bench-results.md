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

## 2026-10-06 (later): complete six-face set and a clean still capture

Same board and setup, 19:37 to 19:41.

### Six faces, all six (19:41 report)

| Face | Total m/s² | Off axis (raw) | x | y | z |
|---|---|---|---|---|---|
| +X up | 10.316 | 2.4° | 10.307 | −0.228 | −0.370 |
| −X up | 9.285 | 1.4° | −9.282 | −0.227 | 0.021 |
| −Y up | 10.054 | 2.7° | 0.467 | −10.043 | 0.092 |
| +Y up | 9.695 | 2.5° | 0.209 | 9.686 | −0.360 |
| +Z up | 10.152 | 4.1° | 0.583 | −0.433 | 10.126 |
| −Z up | 10.212 | 7.3° | 1.285 | 0.192 | −10.129 |

| Axis | Offset | Scale |
|---|---|---|
| X | +0.512 m/s² | −0.12% |
| Y | −0.178 m/s² | +0.59% |
| Z | −0.002 m/s² | +3.28% |

Three sessions now agree: offsets within about 0.01 m/s² and scales within about 0.1%. These are properties of the board, not placement noise. The raw off-axis angles are inflated by the offsets: after removing them the −Z face is about 5.0° (not 7.3°) and +Z about 1.5° (not 4.1°).

### Still noise, 60 s

| Time | Gyro bias x, y, z (°/s) | Gyro noise | Accel noise | Rate | Note |
|---|---|---|---|---|---|
| 18:46 | −1.93, +0.06, +0.38 | 0.05 °/s | 0.030 m/s² | 37.9 Hz | warm baseline |
| 19:37 | −1.88, +0.09, +0.36 | 0.10 °/s | 0.215 m/s² | 37.5 Hz | **moved, disregard** |
| 19:41 | −1.94, +0.06, +0.37 | 0.05 °/s | 0.034 m/s² | 37.6 Hz | clean |

**Warm bias is stable over about an hour.** Between the two clean captures (55 minutes apart, both with the board warm at room temperature) the bias moved by 0.01 °/s on X and Z and 0.00 on Y, about 0.6° per minute, well inside the 0.05 °/s target. This says nothing yet about a cold start; that comparison is still to do. The live rate is a steady 37.5 to 37.9 Hz.

### Predicted tilt readings (before the tilt test)
From the six-face estimate, relative tilt from a flat reference should read about 0.4 to 0.8° low at 10° to 20° and about 1.8° low at 45° when tilting toward +X; tilting toward +Y stays within about 0.6°. The correction should bring these to about zero. Compare against the real tilt test.

## 2026-10-06 (20:10): first cold attempt, compromised

The board was taken out of a −25 °C freezer in a sealed bag, plugged in through the bag, and measured by hand while it warmed. The measurements were difficult through the bag and the board was handled, so **treat this attempt as unusable for conclusions**.

- The +X face reading is identical to the 19:41 capture, so it was not retaken. The X offset and scale from that report mix warm and cold.
- The other five faces were retaken with larger raw off-axis angles (4° to 8°).
- The 60 s still capture at 20:10 gave gyro bias x −2.09, y +0.27, z +0.51 °/s, gyro noise 0.14 °/s and accelerometer noise 0.125 m/s². The accelerometer noise is too large to be temperature drift, which points to handling or vibration. No board temperature was recorded, because the app does not read it and only the serial log carries `temp_c`.
- Accelerometer offsets and scales changed by no more than about 0.04 m/s² and 0.2% on the retaken faces, but given the handling this is only a weak hint.

**Repeat with the board untouched and the serial log capturing temperature**, so bias against temperature can be fitted without any hand contact. Do not attempt the six faces cold.

## Cold test: deferred

The freezer test was not completed on 6 October. The first attempt (20:10) was handled through a bag and is unusable, and a second attempt planned around a USB serial log fell through because the board was powered from a network-side supply, not the PC; by the time that was clear the board had warmed to room temperature (25.2 °C). The cold-start bias comparison, and any bias-against-temperature fit, are therefore still open.

For next time, no USB link is needed. `tools/wifi_imu_log.py` polls the board's Wi-Fi endpoint (`http://ski.local/api/v1/imu`, which includes `temperature_c`) twice a second and records gyro, accelerometer and temperature, so it works with the board powered from any charger. Start it before the board is powered; a drop in the uptime column marks the power-up. Freeze for 30 to 45 minutes in a sealed bag, power the board through the taped neck, lay it flat chip side up, and leave it untouched for about 25 minutes.
