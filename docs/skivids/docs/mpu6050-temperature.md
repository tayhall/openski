# MPU-6050 bias versus temperature (Cranfield dataset) and a noise check on a modern IMU

Date: 2026-10-11. Code: `tools/analyse_cranfield.py`, `tools/allan_static.py`.

## Data

Maton, Economou, Galvao Wall, Khan, Cooper (2024), *Low-cost IMU Sensor Temperature Humidity Zero Bias Data*, Cranfield Online Research Data, DOI 10.17862/cranfield.rd.24291112, CC BY 4.0. Four spreadsheets (`thermal_sweep`, `20_deg_cycles`, `20_deg_cycles_dwells`, `humidity_data`) from three MPU-6050 units in an environmental chamber; each sample has accelerometer, gyro and the chip's own temperature. **There is no time column**, so ramp rates are unknown. The data is stored locally in `data/cranfield-mpu6050/` (not for redistribution beyond the licence terms). The Cranfield pages sit behind a Cloudflare challenge, so it was fetched with a real browser.

Coverage: **MPU-6050 only.** No other IMU model is in this record. The same group's talk (*Temperature-Bias Compensation of Low-Cost Inertial Sensors, Possible or Pipe Dream?*, DOI 10.17862/cranfield.rd.25029125.v2) reports that temperature-bias responses differ between units, are nonlinear and can show a "temperature shock" effect; it was not read in full here.

## Results (three units, thermal sweep -18 to +72 C, plus 2 to 25 C cycles)

Gyro zero-rate offset at room temperature, degrees per second:

| Unit | x | y | z |
|---|---|---|---|
| IMU 1 | -4.5 | +2.9 | +0.2 |
| IMU 2 | -0.7 | -1.4 | -2.5 |
| IMU 3 | +7.4 | -0.5 | +1.0 |

Offset change with temperature (linear slope, degrees per second per degree C):

| Unit | x | y | z |
|---|---|---|---|
| IMU 1 | +0.008 | -0.015 | +0.001 |
| IMU 2 | +0.018 | +0.006 | +0.021 |
| IMU 3 | **+0.111** | +0.006 | +0.042 |

- Over the full 90 C sweep, the worst axis (unit 3, x) moves by about **10 degrees per second**; in the 2 to 25 C range that unit moves about 1 to 2.3 degrees per second.
- **Units differ a lot**: the same chip model has temperature slopes from 0.001 to 0.11 degrees per second per degree C and offsets of different sign. A single generic correction cannot work; calibration (or online estimation) must be per unit.
- **Hysteresis is modest** (warming versus cooling differ by up to 0.46 degrees per second on the worst case, usually below 0.1).
- **Accelerometer**: z-axis slope -0.8 to -4.8 mg per C depending on unit, and the mean magnitude at rest ranges from 0.89 to 1.015 g across units. A fixed "within 15 % of 1 g" gate is off-centre for a unit that reads 0.89 g: **accelerometer scale must be calibrated**.
- One unit shows a data glitch (temperature up to 90 C, large residual) in `20_deg_cycles`; treat that file's IMU 2 with caution.

## What it means for a boot sensor

A boot that warms from about -10 to +10 C would move the typical unit's gyro offset by 0.2 to 0.5 degrees per second and the worst unit by 1 to 2 degrees per second, before any filter correction. Left uncorrected, 1 degree per second is 60 degrees per minute of integrated roll. That is why rest-driven bias estimation (VQF) and per-unit temperature calibration both matter, and why the datasheet figure of +-20 dps is not conservative nonsense but a real possibility. A better chip with a small, stable tempco removes most of this.

## Noise check on a modern IMU (Beuken ski set, ICM-42688-class, 714 Hz)

The file `Lab Validation/static_data/data_5.txt` is called static but is the quasi-static orientation test with the ski being moved: gyro sd 5 to 7 deg/s over the whole file, so **it cannot be used for Allan variance**. Its longest still stretch is 13.6 s, too short for any bias-instability estimate. From its still windows (2 s, below 0.5 deg/s):

| Quantity | Value | Datasheet |
|---|---|---|
| Gyro noise density, x and y axes | about 0.001 to 0.004 deg/s per root Hz | 0.0028 deg/s per root Hz (TDK ICM-42688-P typical, from the hardware report) |
| Accelerometer noise (2 s sd) | 2 to 6 mg | consistent with the datasheet density at this bandwidth |
| Gyro z in still windows | exactly constant (sd 0) | suspicious; probably a held or repeated value; not investigated |

So the measured noise matches the part's datasheet: the modern chip's noise is fine; the quality gap versus MPU-6050 is mainly offset and temperature stability, not noise.

## Gaps

- No cold-temperature data for any chip other than these three MPU-6050 units (their sweep reaches -18 C). Nothing public was found for ICM-42688, LSM6DSV16X, BMI088 or BMI270 over -20 to +10 C; the ICM-42688-P datasheet specifies tempco only for 0 to 70 C.
- No time base, so warm-up rate and "temperature shock" cannot be quantified from this dataset.
- A long static log of a candidate IMU, with temperature, still has to be recorded on our own bench.
