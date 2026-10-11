# 01 - IMU hardware and sensor-set options for boot-mounted ski feedback (OpenSki)

Date of research: 2026-10-11. Scope: compare candidate IMUs and sensor sets, recommend 1 to 3 options.

## 0. How to read this report

Claim labels:

- **[C] CONFIRMED** - I read the figure in the cited primary source (datasheet / vendor page / paper) during this session. Datasheet PDFs were downloaded and text-extracted with `pdftotext`; where a table's column layout was garbled I re-extracted in raw mode and only kept values whose row labels were unambiguous.
- **[R] REPORTED** - the figure comes from a secondary source (distributor page, forum, retailer, search-result summary).
- **[I] INFERRED** - my reasoning from confirmed facts; not stated by any source.

Known gaps (stated up front, nothing was invented to fill them):

- st.com itself was unreachable from this environment (timeouts / 403). ST figures below come from ST datasheets mirrored on SparkFun, Arduino, Adafruit/chipdip and similar hosts. LSM6DSOX gyro noise / ZRO / tempco figures could **not** be read from a primary copy and are marked "not verified".
- MPU-6500 datasheet could not be retrieved; its entry is [I] only.
- ICM-45686 / ICM-45605: only TDK product-page figures were read, not the datasheet tables.
- Market prices are snapshots from search results/vendor pages and will drift. Treat as order of magnitude.
- No Allan-variance "bias instability" figure is published for most of these parts (only ISM330DHCX states one). Zero-rate offset, its tempco and noise density are the comparable proxies.

---

## 1. Executive summary

1. **Gyro bias, not gyro noise, decides roll accuracy on long runs.** Every modern candidate has gyro noise of 2.8 to 15 mdps/rtHz. At 50 Hz bandwidth that is roughly 0.02 to 0.1 dps rms [I], negligible against bias terms. What differs by 10x to 100x between parts is zero-rate offset (ZRO) and its temperature coefficient: MPU-6050 has +/-20 dps ZRO over temperature [C]; ICM-42670-P and LSM6DSV16X have about 0.006 to 0.015 dps/degC [C]. A boot that warms from -20 degC to +10 degC through a run sees 30 degC of swing [I]. At 0.015 dps/degC that is 0.45 dps of bias walk before any filter correction; at 0.05 dps/degC (BMI160, ICM-20948, BNO055 max) it is 1.5 dps [I].
2. **The MPU-6050 is a poor choice to carry forward.** Gyro ZRO +/-20 dps (25 degC) and +/-20 dps over -40..+85 degC [C], 3.9 mA with DMP [C], and the chip is discontinued/not recommended for new designs, with module-market clones [R]. It is fine as a proof-of-concept only, and its bias will need continuous estimation.
3. **Best fit for this use is a modern 6-axis IMU with 2.8 to 7 mdps/rtHz noise, <=0.015 dps/degC tempco, +/-2000 dps, +/-16 g, FIFO >= 2 kB, 0.5 to 1 mA, specified -40..+85 degC.** Candidates that meet that: LSM6DSV16X, ICM-42688-P, ICM-42670-P, BMI270/BMI323 (slightly worse), ISM330DHCX (best-documented stability, wider temp).
4. **Do not use a magnetometer** for roll relative to gravity. It adds nothing to roll, and boot buckles, binding and the other boot are ferromagnetic [I]. CEVA's own datasheet recommends the non-magnetic Game Rotation Vector in unstable magnetic fields [C].
5. **Accelerometer range must be >= +/-16 g.** A 2023 Science and Skiing congress abstract states that when the accelerometer saturates (icy/bumpy slopes) edging angles drift [C]. +/-8 g parts (BNO085 family, and the +/-8 g setting used by prior art) are at risk [I].
6. **BLE MCU:** nRF52840 (or its XIAO/Feather modules) is the pragmatic choice: -40..+85 degC rated, 4.8 mA peak TX at 0 dBm [C], 1.5 uA System ON sleep [C]. ESP32-C3 is cheaper but its BLE/active current is much higher (see section 7).

### Ranked recommendation (details in section 9)

| Rank | Option | One-line reason |
|---|---|---|
| 1 | **LSM6DSV16X + nRF52840** (SparkFun Qwiic breakout for the prototype; bare chip for a custom board) | Best combination of low noise (2.8 mdps), low tempco (0.006 dps/degC), 4.5 kB FIFO, 0.65 mA, +/-4000 dps and 16 g, hobbyist breakouts exist, built-in gyro-bias-aware fusion block as a cross-check |
| 2 | **ICM-42688-P (or ICM-42670-P) + nRF52840** on a small custom PCB | Equal noise, lowest tempco on paper (0.005 dps/degC but only specified 0..70 degC), programmable AAF/notch for vibration; poor hobbyist breakout supply (~$88 for the one Qwiic board found) |
| 3 | **XIAO nRF52840 Sense (LSM6DS3TR-C onboard)** as zero-solder pilot | Peer-reviewed prior art got turn detection with this IMU family at a boot cuff; accept worse bias (0.05 dps/degC class) and expect to do more bias estimation in software |

---

## 2. Requirements table derived from ski use

| # | Requirement | Target | Why / basis | Label |
|---|---|---|---|---|
| R1 | Operating temp, spec'd | -20 to +5 degC use; part specified to at least -40..+85 degC | All candidates in section 3 are specified -40..+85 degC (ISM330DHCX to +105) | [C] per datasheets, section 3 |
| R2 | Gyro full scale | +/-2000 dps minimum (+/-1000 might suffice, +/-4000 not required) | Prior boot-cuff work used +/-500 dps and did not report saturation (not discussed either way); impacts and falls produce short spikes, so headroom is cheap | [C] for prior art range; [I] for the rest |
| R3 | Accel full scale | >= +/-16 g | Saturation causes edge-angle drift per Hummel et al. 2023; sustained cornering is 1-2 g but impacts are several g | [C] abstract; [I] sizing |
| R4 | Output rate to phone | 100-200 Hz fused/raw stream; sample internally at >= 400 Hz with on-chip low-pass | Prior art sampled the IMU at 833 Hz and forwarded 64 Hz over BLE and still reached 95.3% turn-detection accuracy; BROAD baseline here is 286 Hz | [C] prior art; [I] sizing |
| R5 | Gyro noise density | <= 10 mdps/rtHz | At that level noise is not the roll-error driver | [I] |
| R6 | Gyro ZRO tempco | <= 0.015 dps/degC over the real range (-20..+10 degC) | Limits uncorrected bias walk to < 0.5 dps over a 30 degC warm-up | [I] |
| R7 | Initial ZRO | <= +/-1 dps (calibratable at standstill) | Large initial offsets (MPU-6050 +/-20 dps) make the first seconds unusable without calibration | [I] |
| R8 | Anti-aliasing / vibration immunity | Programmable AAF and/or notch, or high internal rate with filtering | Boot cuff sees vibration and impacts; undersampled vibration aliases into the band | [I]; AAF/notch confirmed for ICM-42688-P [C] |
| R9 | FIFO | >= 2 kB (batch over BLE connection intervals) | Lets MCU sleep between connection events | [I] |
| R10 | Sensor current | <= 1 mA for 6-axis at 100-200 Hz | 6+ h in cold on a small cell | [I] |
| R11 | Whole-node average current | <= about 3-4 mA | 6 h x 3.5 mA = 21 mAh before cold derating; Li-ion is at roughly half capacity at -20 degC per Battery University's general statement | [I]; derating [R] https://batteryuniversity.com/article/bu-502-discharging-at-high-and-low-temperatures |
| R12 | Supply | 1.71-3.6 V, 1.8 V I/O friendly | Direct from a single Li-ion/LiPo cell via LDO | [C] for parts |
| R13 | Mount-orientation independence | Orientation-agnostic algorithm (gravity-vector calibration at standstill), 3-axis gyro + 3-axis accel only | Mounting varies every fit; no IMU part choice fixes this | [I] |
| R14 | Availability | Breakout or module in stock; production (not NRND) | Hobbyist reproducibility | [R] per section 3 |
| R15 | Magnetometer | Not needed | Section 5 | [I] |

---

## 3. Candidate comparison

### 3.1 Gyroscope

"ZRO" = zero-rate output offset. "Tempco" = ZRO variation vs temperature. Typical values unless stated. Test conditions differ between vendors (component vs board level, soldered, over life), so compare orders of magnitude.

| Part | Gyro full scale (dps) | Noise density | Initial ZRO | ZRO tempco / variation | Max gyro ODR | Source |
|---|---|---|---|---|---|---|
| MPU-6050 (rev 3.2 copy) | 250/500/1000/2000 | 0.005 dps/rtHz @10 Hz | +/-20 dps (25 degC) | +/-20 dps over -40..+85 degC | 8 kHz | [C] https://alliance.seas.upenn.edu/~medesign/wiki/uploads/Guides/MPU6050_datasheet.pdf |
| MPU-9250 | 250..2000 | 0.01 dps/rtHz | +/-5 dps | +/-30 dps over -40..+85 degC | 8 kHz | [C] https://cdn.sparkfun.com/assets/learn_tutorials/5/5/0/MPU9250REV1.0.pdf |
| MPU-6500 | not retrieved | not retrieved | - | - | - | [I] same 6-axis family as MPU-9250 without mag; unverified |
| ICM-20948 | 250..2000 | 0.015 dps/rtHz | +/-5 dps (component) | +/-0.05 dps/degC (-40..+85) | ~9 kHz class (not extracted) | [C] https://cdn.sparkfun.com/assets/7/f/e/c/d/DS-000189-ICM-20948-v1.3.pdf |
| ICM-42688-P (rev 1.6) | 15.6..2000 | 2.8 mdps/rtHz | +/-0.5 dps (board, 25 degC) | +/-0.005 dps/degC, **0..70 degC only** | 32 kHz | [C] https://pcbsync.com/wp-content/uploads/2026/07/ICM-42688-P-Datasheet-TDK.pdf (TDK rev 1.6; TDK page lists v1.8 as newest: https://invensense.tdk.com/?p=9240) |
| ICM-42670-P (rev 1.2) | 250..2000 | 0.007 dps/rtHz | +/-1 dps (25 degC) | +/-0.015 dps/degC (-40..+85, board-level) | 1.6 kHz | [C] https://media.tindie.com/datasheets/42072/2026-05-06-00-49-25/ds-000451-icm-42670-p-datasheet.pdf |
| ICM-45686 | up to +/-4000 | 3.8 mdps/rtHz | not stated on product page | +/-0.005 dps/degC | not stated | [C] product page https://invensense.tdk.com/en-us/products/6-axis/icm-45686 |
| ICM-45605 | not stated | 3.8 mdps/rtHz | not stated | +/-0.01 dps/degC | not stated | [C] https://invensense.tdk.com/en-us/products/6-axis/icm-45605 |
| BMI160 (rev 1.0 Nov 2020) | 125..2000 | 0.007 dps/rtHz @10 Hz | +/-3 dps | +/-3 dps over -40..+85; TCO 0.05 dps/K | 3.2 kHz | [C] https://www.bosch-sensortec.com/media/boschsensortec/downloads/datasheets/bst-bmi160-ds000.pdf |
| BMI270 | 125..2000 | 0.007 (performance) / 0.010 (normal) dps/rtHz | +/-0.5 dps (soldered, over life) | +/-0.015 dps/K | 6.4 kHz | [C] https://www.bosch-sensortec.com/media/boschsensortec/downloads/datasheets/bst-bmi270-ds000.pdf |
| BMI323 | 125..2000 | 0.007 dps/rtHz (high perf.) | +/-1 dps (soldered, over life) | +/-0.04 dps/K | 6.4 kHz | [C] https://www.bosch-sensortec.com/media/boschsensortec/downloads/datasheets/bst-bmi323-ds000.pdf |
| LSM6DS3 (DocID026899 rev 10) | 125..2000 | 7 mdps/rtHz | +/-10 dps typ | +/-0.05 dps/degC | 1.66 kHz | [C] https://content.arduino.cc/assets/st_imu_lsm6ds3_datasheet.pdf |
| LSM6DS3TR-C | to 2000 | 5 mdps/rtHz | +/-3 dps | not read | - | [R] search summary of ST rev 3 datasheet https://www.st.com/resource/en/datasheet/lsm6ds3tr-c.pdf (not opened) |
| LSM6DSOX | +/-125..2000 | not verified | not verified | not verified | 6.7 kHz | [C] range/ODR from Adafruit https://www.adafruit.com/product/4438 and guide https://static.chipdip.ru/lib/618/DOC011618898.pdf; datasheet figures not read |
| ISM330DHCX (DS13012 rev 7) | 125..4000 | 5 typ / 8 max mdps/rtHz; ARW 0.21 deg/rt-h; **bias instability 3 deg/h (25 degC)** | +/-3 dps max (+/-1 typ) | +/-0.005 typ / +/-0.015 max dps/degC | 6.67 kHz | [C] https://cdn.sparkfun.com/assets/d/4/6/d/f/ism330dhcx_Datasheet.pdf |
| LSM6DSV16X (DS13510 rev 2) | 125..4000 | 2.8 mdps/rtHz | +/-1 dps | +/-0.006 dps/degC | 7.68 kHz | [C] https://docs.sparkfun.com/SparkFun_6DoF_LSM6DSV16X/assets/BoardFiles/lsm6dsv16x.pdf |
| BNO055 (rev 1.2 2014) | 125..2000 | 0.014 dps/rtHz | +/-1 typ, +/-3 max dps | +/-0.015 typ, +/-0.03 max dps/K | fusion out 100 Hz | [C] https://cdn-shop.adafruit.com/datasheets/BST_BNO055_DS000_12.pdf |
| BNO085/BNO086 | +/-2000 (raw gyro); calibrated gyro 400 Hz max, gyro rotation vector 1 kHz | not given (fusion part) | not given | gyro dynamic accuracy 3.1 dps (nominal, whole fusion output) | rotation vector 400 Hz | [C] https://www.ceva-ip.com/wp-content/uploads/BNO080_085-Datasheet.pdf (rev 1.17) |

Notes on gyro table:

- The ICM-42688-P ZRO tempco is only specified for 0 to +70 degC [C]. Skiing at -20 degC is outside that window, so the 0.005 dps/degC figure is an extrapolation there [I]. ICM-42670-P and LSM6DSV16X state their tempco over the full -40..+85 degC range [C].
- Only ISM330DHCX publishes bias instability (3 deg/h, i.e. 0.0008 dps) [C]. That is a short-term in-run figure at 25 degC; real-world drift is dominated by temperature and mounting stress [I]. TDK warns that PCB package stress changes gyro output offset over temperature [C] (MPU-6050 datasheet section 11.4.1: https://alliance.seas.upenn.edu/~medesign/wiki/uploads/Guides/MPU6050_datasheet.pdf).
- BNO08x does not publish raw gyro bias numbers; CEVA states the sensor hub "dynamically calibrates sensor data for temperature and aging" [C] same datasheet. That makes the bias-handling a black box.

### 3.2 Accelerometer, range, shock

| Part | Accel full scale (g) | Noise density | Zero-g offset | Zero-g tempco | Source |
|---|---|---|---|---|---|
| MPU-6050 (rev 3.2) | 2/4/8/16 | 400 ug/rtHz | +/-50 mg (X,Y) / +/-80 mg (Z) initial (column layout in extraction is ambiguous) | tens of mg over 0..70 degC (+/-35 / +/-60 mg, same ambiguity) | [C] upenn datasheet above |
| MPU-9250 | 2..16 | 300 ug/rtHz | +/-60 mg (X,Y component) | +/-1.5 mg/degC | [C] |
| ICM-20948 | 2..16 | 230 ug/rtHz | +/-25 mg component, +/-50 mg board | +/-0.8 mg/degC | [C] |
| ICM-42688-P | 2..16 | 65 (X,Y) / 70 (Z) ug/rtHz | +/-20 mg board | +/-0.15 mg/degC (-40..+85) | [C] |
| ICM-42670-P | 2..16 | 100 ug/rtHz | +/-25 mg | +/-0.15 mg/degC | [C] |
| ICM-45686 | up to +/-32 | 70 ug/rtHz | not read | +/-0.15 mg/degC | [C] product page |
| BMI160 | 2..16 | 180 typ / 300 max ug/rtHz | +/-25 mg component, +/-40 mg board, +/-150 mg life | +/-1.0 mg/K | [C] |
| BMI270 | 2..16 | 160 ug/rtHz | +/-20 mg | +/-0.25 mg/K | [C] |
| BMI323 | 2..16 | 180 ug/rtHz | +/-35 mg soldered, +/-50 mg life | +/-0.3 mg/K | [C] |
| LSM6DS3 | 2..16 | 90-180 ug/rtHz (by FS) | +/-40 mg | +/-0.5 mg/degC | [C] |
| ISM330DHCX | 2..16 | 60 typ / 100 max ug/rtHz | +/-65 mg max (+/-10 typ) | +/-0.1 typ / +/-0.5 max mg/degC | [C] |
| LSM6DSV16X | 2..16 | 60 ug/rtHz (HP) | +/-12 mg | +/-0.07 mg/degC | [C] |
| BNO055 | 2..16 | not extracted | +/-80 typ / +/-150 max mg | +/-1 typ / +/-3.5 max mg/K | [C] |
| BNO085/086 | **+/-8 g (12-bit)** | - | - | - | [C] CEVA datasheet: "triaxial 12-bit accelerometer with a range of +/-8g" |

Shock: ICM-42670-P datasheet advertises stability against "shock (up to 20,000g)" [C] (same datasheet as section 3.1). ISM330DHCX lists "high shock survivability" [C]. No candidate's in-boot shock performance was verified in the field [I]; the real risk is mechanical (mounting resonance), not the chip.

Is +/-2000 dps / +/-16 g enough? [I] Yes for roll/turn work on a boot cuff, with the caveat that +/-16 g can still clip hard impacts (falls, landing). Clipped samples must be flagged and bridged with gyro only. Parts offering more: ICM-45686 +/-32 g [C]; LSM6DSV16X and ISM330DHCX +/-4000 dps (not needed) [C].

### 3.3 System properties

| Part | Current, 6-axis active | FIFO | Interfaces | Temp range | On-chip fusion / features | Package | Lifecycle / known issues |
|---|---|---|---|---|---|---|---|
| MPU-6050 | 3.9 mA (incl. DMP) [C] | 1024 B [C] | I2C (6050), SPI on 6000 [C] | -40..+85 [C] | DMP | QFN 4x4x0.9 [R] | Discontinued, not recommended for new designs [R] https://www.rapidonline.com/news/mpu6050-alternatives-equivalents ; counterfeits/odd WHO_AM_I reported [R] https://eevblog.com/forum/projects/counterfeit-mpu6050-chips-from-china |
| MPU-9250 | gyro-only 3.2 mA; 3.5 mA 9-axis+DMP [C] | 512 B [C] | I2C/SPI | -40..+85 | DMP, AK8963 mag | QFN 3x3 | End of life, ICM-20948 recommended [R] https://discuss.px4.io/t/mpu-9250-end-of-life-migrate-to-icm-20948/9262 |
| ICM-20948 | gyro 1.23 mA; 9-axis 3.11 mA [C] | 512 B [C] | I2C/SPI | -40..+85 | DMP, AK09916 mag | QFN 3x3 | Not firmware compatible with MPU-9250; 1.8 V I/O [R] https://discuss.px4.io/t/mpu-9250-end-of-life-migrate-to-icm-20948/9262 |
| ICM-42688-P | 0.88 mA LN [C] | 2 kB [C] | I3C/I2C/SPI [C] | -40..+85 [C] | AAF, notch, ext. clock input; no fusion | LGA-14 2.5x3 [R] https://fat.lcsc.com/product-detail/C1850418.html | Newer TDK parts (ICM-456xx) exist; no counterfeit reports found in this research |
| ICM-42670-P | 0.55 mA LN; sleep 3.5 uA [C] | up to 2.25 kB [C] | I3C/I2C/SPI [C] | -40..+85 [C] | APEX (pedometer, tilt...) [C] | LGA-14 2.5x3 | - |
| ICM-45686 | 0.42 mA LN / 0.22 mA LP [C] | not stated | I3C/I2C/SPI, OIS | -40..+85 [R] element14 | BalancedGyro, APEX [C] | 2.5x3x0.81 | Eval board only found (~$99) [R] https://pcbsync.com/icm-45686/ |
| BMI160 | 0.99 mA [C] | 1024 B [C] | I2C/SPI | -40..+85 [C] | step/motion features | LGA 2.5x3 | "Discontinued / not recommended for new designs" per Bosch community replies [R] https://community.bosch-sensortec.com/t5/MEMS-sensors-forum/CMRT-for-BMI160/m-p/95660 |
| BMI270 | 0.685 mA normal / 0.97 mA perf. [C] | 2 kB [C] | I2C/SPI [C] | -40..+85 [C] | feature engine; needs ~8 kB config upload each power-up [R] https://community.bosch-sensortec.com/t5/MEMS-sensors-forum/What-is-the-reasoning-behind-the-BMI270-requiring-an-8KB-upload/m-p/19913 | LGA 2.5x3 | In production; extra boot step |
| BMI323 | 0.79 mA HP / 0.69 normal [C] | 2 kB [C] | I2C/SPI/I3C (12.5 MHz) [C] | -40..+85 [C] | feature engine | LGA | Few hobbyist breakouts found [R] |
| LSM6DS3 | 1.25 mA HP; 0.9 mA normal [C] | up to 8 kB [C] | I2C/SPI | -40..+85 [C] | pedometer, tilt | LGA 2.5x3x0.83 | Older generation; onboard XIAO Sense as LSM6DS3TR-C [R] https://wiki.seeedstudio.com/XIAO_BLE/ |
| LSM6DSOX | not verified | not verified (family 9 kB per ISM330 sibling) | I2C/SPI | -40..+85 [C] https://static.chipdip.ru/lib/618/DOC011618898.pdf | MLC, FSM | LGA | $11.95 Adafruit breakout [C] https://www.adafruit.com/product/4438 |
| ISM330DHCX | 1.2 typ / 1.5 max mA HP [C] | up to 9 kB [C] | I2C/SPI [C] | **-40..+105** [C] | MLC, FSM | LGA-14L 2.5x3x0.83 | "Industry 4.0" part; longevity-oriented [R] |
| LSM6DSV16X | 0.65 mA combo HP [C] | up to 4.5 kB [C] | I2C/I3C/SPI [C] | -40..+85 [C] | **SFLP** sensor fusion (game rotation vector, gravity, gyro bias), MLC, FSM, Qvar [C] | LGA 2.5x3x0.83 [C] | SparkFun Qwiic boards exist [R] |
| BNO055 | up to 12.3 mA (9DOF @ 100 Hz, max) [C] | none (fusion registers) | I2C/UART | -40..+85 [C] | full 9-DOF fusion at 100 Hz [C] | LGA 28 | Long I2C clock stretching, trouble with some masters [R] https://community.bosch-sensortec.com/mems-sensors-forum-jrmujtaw/post/bno055-long-i2c-clock-stretching-vh3nGpxZKy0YOAQ ; 2014 datasheet |
| BNO085/086 | ~3.5 mA (VDDIO) + 7.5 mA (VDD) at 100 Hz fusion, i.e. ~35 mW [C] | host reports via SHTP | I2C/SPI/UART [C] | -40..+85 [C] | SH-2 fusion, 9-axis, gyro rotation vector 1 kHz [C] | 28-pin LGA 3.8x5.2x1.1 [C] | Accel only +/-8 g; heading drift spec 0.5 deg/min [C]; ~$35 breakout [C] https://www.sparkfun.com/sparkfun-vr-imu-breakout-bno086-qwiic.html |

BNO08x accuracy as published [C] (CEVA rev 1.17): rotation vector dynamic 3.5 deg / static 2.0 deg, game rotation vector non-heading dynamic 2.5 deg / static 1.5 deg, gravity nominal static angle error 1.5 deg, and "in practice the rotation vector is typically accurate to 5 deg". These are simulation-derived nominal figures [C]. They are not competitive with the 1.6 deg median roll already achieved on BROAD with a plain complementary filter, and they offer no way to inspect or tune the filter [I].

### 3.4 Price and availability (snapshot)

| Part | Breakout / module and price found | Label / URL |
|---|---|---|
| MPU-6050 GY-521 | "Cheap modules still sold in volume"; price not captured | [R] https://www.rapidonline.com/news/mpu6050-alternatives-equivalents |
| ICM-20948 | Adafruit STEMMA QT $19.95 (1-9) | [R] https://www.adafruit.com/product/4554 |
| ICM-42688-P | Chip about $2.12 at volume (LCSC); only one Qwiic breakout found, $88 (Elecrow) | [R] https://fat.lcsc.com/product-detail/C1850418.html ; https://www.elecrow.com/icm-42688-p-ultra-low-power-imu-breakout.html |
| ICM-42670-P | Tindie breakout exists; price not captured | [R] https://www.tindie.com/products/spelectronics/icm42670-breakout-board-mpu-6500-upgrade/ |
| BMI270 | SparkFun Micro Qwiic $17.58, standard $18.50 | [R] https://www.sparkfun.com/sparkfun-micro-6dof-imu-breakout-bmi270-qwiic.html |
| BMI323 | no breakout found | [R] search |
| LSM6DSOX | Adafruit STEMMA QT $11.95 | [C] https://www.adafruit.com/product/4438 |
| ISM330DHCX | Same Adafruit family guide; price not captured | [R] |
| LSM6DSV16X | SparkFun Qwiic $24.54; Micro $25.50 | [R] https://www.sparkfun.com/sparkfun-6dof-imu-breakout-lsm6dsv16x-qwiic.html |
| BNO086 | SparkFun Qwiic $34.95 | [C] https://www.sparkfun.com/sparkfun-vr-imu-breakout-bno086-qwiic.html |
| XIAO nRF52840 Sense | board with LSM6DS3TR-C, BQ25101 charger; price not captured | [R] https://wiki.seeedstudio.com/XIAO_BLE/ |

---

## 4. Relevant evidence from skiing literature

- A boot-cuff turn-detection algorithm (gyroscope only) achieved 95.3% accuracy for parallel turns in the field. Hardware: LSM6DS3 at +/-8 g and +/-500 dps, sampled at 833 Hz and forwarded at 64 Hz over Bluetooth, placed at the back of the upper cuff of each boot [C] https://pmc.ncbi.nlm.nih.gov/articles/PMC7739568/. Peak rates/accelerations and saturation were not reported [C] same page. This validates the sensor position and a low-grade IMU for **timing**; it does not validate roll accuracy.
- A ski-ergometer study of single-IMU cuff mounting found turn-switch precision of +/-0.03 s with one or two IMUs on the cuff, and that the roll axis of the gyroscope was the best signal [R] https://pmc.ncbi.nlm.nih.gov/articles/PMC6413051/ (the numbers quoted come from the search summary; the page itself reported timing errors in milliseconds and the same LSM6DS3 +/-8 g / +/-500 dps hardware [C]).
- Edge-angle work with Madgwick filtering on boot IMUs (10 Hz Butterworth, ICSS 2023) states: "if the accelerometer saturates (e.g. on icy/bumpy slopes), edging angles drift"; filter gain tuning helps and mechanical damping may help [C] https://lida.sport-iat.de/svd/Record/4081463.
- A magnetometer-aware ski IMU system exists (ski jumping) but no source in this research addresses buckle/binding magnetic interference in ski boots directly [R] searches; see section 5.

---

## 5. Magnetometer: useful or harmful?

Verdict: **harmful or at best useless for this goal; leave it out** [I].

Reasons:

1. **Roll relative to gravity needs no heading.** Gravity-referenced roll/pitch come from accelerometer + gyro; a magnetometer only corrects yaw [I]. CEVA says as much: the Game Rotation Vector (no magnetometer) has roll and pitch referenced against gravity, "long term the output will likely drift in yaw", and that is "preferable" for smoothness [C] https://www.ceva-ip.com/wp-content/uploads/BNO080_085-Datasheet.pdf (section 2.2.2).
2. **Disturbed field.** A boot has steel buckles, bindings, and a second boot nearby [I]. CEVA recommends the Game Rotation Vector "if the application requires an orientation estimate in an unstable magnetic field" and notes that dynamic magnetometer calibration can cause jumps [C] same datasheet section 3.1. BNO055's own datasheet says heading accuracy depends on hardware/software and tilt compensation [C].
3. **Extra cost.** A magnetometer adds current (ICM-20948 9-axis 3.11 mA vs gyro-only 1.23 mA [C]), calibration burden and a failure mode with no benefit to the target outputs [I].

When it could help (not recommended now) [I]: a stable absolute heading to separate left/right turns from the fall line. The turn-switch methods used in the literature (gyroscope roll axis) do not need it [C] https://pmc.ncbi.nlm.nih.gov/articles/PMC7739568/. If a 9-axis part is bought anyway (ICM-20948, BNO08x), run it in 6-axis mode and use the magnetometer only for an optional consistency check, as in the magnetic-anomaly approach of comparing orientation with and without magnetometer data [R] https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10072956.

---

## 6. Barometer and pressure insoles

### 6.1 Barometer

- Relevance to the stated outputs (turn timing, roll angle, L/R consistency): **low** [I]. It gives altitude/vertical speed, which is useful for run segmentation and descent rate, not for technique feedback.
- Modern parts: BMP581 relative accuracy +/-6 Pa typ (700-1100 hPa, 15-55 degC) per Bosch [R]; rated -40..+85 degC [R] https://www.bosch-sensortec.com/products/environmental-sensors/pressure-sensors/Bmp581 . +/-6 Pa is roughly +/-0.5 m of altitude [I].
- Practical problem: an open pressure port at a boot cuff in snow/water and wind gusts [I]; no source found stating water resistance of the part [R]. Not worth the risk for v1.

### 6.2 Pressure insoles / instrumented boots (possible "load/pressure" phase)

- On-snow validation (Pedar-type insoles vs force plates, 10 expert and 10 intermediate skiers) found insoles generally underestimate total vertical ground reaction force, with the gap depending on turn phase, level and pitch [R] https://pmc.ncbi.nlm.nih.gov/articles/PMC3761512 (via summary). A reliability study reported insoles reproduce about 70% of load-cell measurements and that results "should be analyzed with caution" [R] https://sponet.de/sponet/Record/4097541.
- A 2021 instrumented-boot study placed piezoresistive sensors in a ski boot: toecap sensors gave insufficient data; insole (heel/forefoot) and spoiler/shaft sensors were more reliable [R] https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8077231/.
- Moticon OpenGo (not validated in alpine) impulses were 13-34% lower than AMTI force plate with r = 0.8-1.0 in most situations [R] https://www.jssm.org/jssm-10-754.xml-Fulltext (summary).
- Verdict [I]: pressure insoles add **load/weight-distribution and fore-aft balance** information the IMU cannot provide, and the evidence supports shape/timing of loading rather than absolute force. They cost more than the whole IMU node, need in-boot calibration, and are a second mechanical system in a boot. Keep for phase 2, and consider cheap strain/FSR sensing on the spoiler as an experiment before buying insoles.

---

## 7. BLE MCU modules and power

| MCU | BLE radio current | Sleep | Temp range | Notes | Source |
|---|---|---|---|---|---|
| **nRF52840** | TX 0 dBm: 4.8 mA peak spec; 6.40 mA incl. DC/DC and CPU in the electrical table; RX 1 Mbps 4.6 mA (DC/DC) / 6.26 mA in the module-level radio entry; TX +8 dBm 14.8 mA | System OFF 0.4 uA; System ON, no RAM retention, wake on RTC 1.5 uA at 3 V | -40..+85 degC [C] | Cortex-M4F, 1 MB flash, 256 kB RAM, BLE 5, DC/DC; Arduino/Zephyr/nRF Connect ecosystems; XIAO and Feather modules widely available | [C] https://cdn.sparkfun.com/assets/d/2/6/2/6/nRF52840_PS_v1.1.pdf |
| nRF5340 | not read | not read | not read | Dual-core; higher price/complexity than needed for 100-200 Hz IMU streaming [I] | not verified |
| nRF54L15 | RX 3.4 mA, TX 4.8 mA at 0 dBm (3 V) per Nordic page; sleep 0.7-2.9 uA | - | not found | Newer, lower power; XIAO board exists; ecosystem younger [R] | [R] https://www.nordicsemi.com/Products/nRF54L15/Modules |
| ESP32-C3 | BLE current not tabulated in the datasheet pages read; modem-sleep CPU running 17-23 mA (80/160 MHz), CPU idle 13-18 mA | Light-sleep 130 uA, Deep-sleep 5 uA | Ambient -40..+105 degC (FH4X/FH8X), some variants -40..+85 | RISC-V, Wi-Fi + BLE 5 | [C] https://www.espressif.com/sites/default/files/documentation/esp32-c3_datasheet_en.pdf |
| ESP32-S3 | datasheet downloaded, not parsed for BLE current | - | - | Dual-core, overkill; not analysed | [R] |

Power considerations [I unless stated]:

- Radio is not the dominant load at 100-200 Hz streaming with 20-30 ms connection intervals; the IMU (0.5-1 mA) plus MCU wake/sensor-read overhead and the radio duty cycle land in the 1-3 mA range. A community measurement for nRF54L15 gives 15-20 uA average at a 1 s interval and ~300 uA at 30 ms [R] https://hubble.com/community/guides/getting-the-most-battery-life-out-of-the-nrf54l15/ ; nRF52840 is the same order [I].
- Use IMU FIFO + watermark interrupt and batch several samples per BLE notification; let the MCU sleep between events.
- ESP32-C3's sleep is 5 uA deep but its active/modem-sleep floor is 13-23 mA [C], about 10x nRF52840's active class. Not suitable for a coin-sized 6 h node.
- Battery: Li-ion at -20 degC is "about 50 percent performance" per Battery University's general statement [R]. Primary lithium AA/AAA (Energizer L91 is rated -40..+60 degC) is a cold-proof alternative [R] https://data.energizer.com/pdfs/L91.pdf at the cost of size. Charging a Li-ion cell below 0 degC is generally not permitted by cell makers; I did not verify a specific source, so keep the charger/cell out of the cold [I].
- Keep the cell and electronics inside the boot cuff liner or under a layer of foam; internal heating from the node itself is small [I].

---

## 8. Known problems and risks by part

| Part | Problem | Label / source |
|---|---|---|
| MPU-6050 | EOL / NFND; many boards "not manufactured for years, likely counterfeit, rejects or recycled"; WHO_AM_I 0x72 and 0x98 seen on GY-521 boards | [R] https://community.sparkfun.com/t/mpu6050-obsolete/63218 ; https://eevblog.com/forum/projects/counterfeit-mpu6050-chips-from-china (the EEVblog thread never identified the die; another poster blamed I2C rise times) |
| MPU-9250 | EOL; magnetometer sub-die | [R] |
| BMI160 | Bosch says discontinued/not recommended for new designs | [R] |
| BMI270 | 8 kB firmware blob upload at every power-up (library burden, boot time) | [R] |
| BNO055 | Very long I2C clock stretching breaks some masters; old 2014 datasheet; high current | [R] / [C] |
| BNO08x | +/-8 g accel; black-box auto-calibration; ~35 mW at 100 Hz fusion; simulation-derived accuracy; heading drift 0.5 deg/min | [C] |
| ICM-42688-P | Tempco only specified 0..70 degC; poor breakout supply; documented ODR-dependent filter behaviours require care | [C]/[R] |
| ICM-20948 | Not firmware compatible with MPU-9250; mag/DMP complexity; 1.8 V I/O | [R] |
| All | Mounting stress and temperature alter gyro offset after soldering/strapping; calibration at the actual temperature required | [C] MPU-6050 datasheet sec. 11.4.1 |
| All | Vibration aliasing and clipping at +/-16 g not characterised in a ski boot | [I] |

---

## 9. Ranked recommendation

### Option 1 (recommended): LSM6DSV16X + nRF52840

- Why: matches or beats every requirement in section 2: 2.8 mdps/rtHz, 60 ug/rtHz, +/-1 dps ZRO, **0.006 dps/degC across -40..+85 degC**, +/-16 g, +/-4000 dps, 4.5 kB FIFO, 0.65 mA, I3C/I2C/SPI [C]. Available as SparkFun Qwiic boards (about $25) [R].
- Bonus: SFLP fusion block outputs game rotation vector, gravity vector and **gyroscope bias** into the FIFO [R] https://community.st.com/mems-and-sensors-62/how-lsm6dsv16x-enables-sensor-fusion-low-power-sflp-algorithm-120280 . Use as an independent cross-check against your host-side filter; keep raw data as ground truth [I].
- Risks: datasheet read was Rev 2 (current is Rev 4 per search summary; reported same values) [R]; ZRO test conditions "after calibration" footnote [R]; moderate ecosystem maturity versus LSM6DSOX/ISM330.
- Cost: about $25 breakout + about $10-25 nRF52840 module [R/I].

### Option 2: ICM-42688-P (or ICM-42670-P if power/availability matter) + nRF52840 on a small custom board

- Why: lowest tempco on paper (0.005 dps/degC) and ZRO +/-0.5 dps; 2.8 mdps/rtHz; 65/70 ug/rtHz accel; programmable anti-alias and notch filters aimed at vibration; external clock input; 32 kHz ODR [C]. ICM-42670-P: 0.55 mA, +/-0.015 dps/degC over the full -40..+85 degC [C].
- Risks: tempco only specified 0..70 degC for ICM-42688-P (cold use is extrapolation) [C]; limited hobbyist breakouts ($88 Qwiic board found) so likely needs a PCB; ICM-45686 (+/-32 g, 0.42 mA) exists but only a $99 eval board was found [R].
- Chip cost about $2 [R], PCB/assembly effort is the real cost [I].

### Option 3 (pilot, lowest effort): XIAO nRF52840 Sense (LSM6DS3TR-C onboard)

- Why: IMU + BLE MCU + LiPo charger in 21 x 17.5 mm [R]; same IMU family used in published boot-cuff turn detection at +/-8 g, +/-500 dps [C].
- Risks: ZRO +/-3 dps (TR-C [R]) and tempco 0.05 dps/degC class (LSM6DS3 [C]); worse bias, so roll accuracy over long runs will lean on software bias estimation. Good enough to prove timing/rhythm; do not use to prove final roll accuracy.

### What not to choose

- **MPU-6050 / MPU-9250 for new work** (EOL, ZRO tens of dps) [C]/[R]. Keep the existing MPU-6050 only to exercise the pipeline.
- **BNO055 / BNO085 as the primary sensor** (+/-8 g on BNO08x, opaque fusion, 35 mW, accuracy figures worse than your 1.6 deg baseline) [C]/[I]. They remain reasonable if you ever want a turn-key orientation without writing a filter.
- **A magnetometer, barometer or insole in v1** (section 5-6).

### Practical settings to carry forward [I]

1. Gyro +/-2000 dps, accel +/-16 g; internal ODR >= 400 Hz with on-chip low-pass, stream 100-200 Hz.
2. Log the IMU's own temperature sensor with every packet (all candidates have one) so ZRO can be modelled against temperature offline.
3. Do a still-pose calibration at each fit (gravity vector gives mounting orientation) and again during standstill at lifts; re-estimate gyro bias whenever |omega| and ||a|| - 1 g are small.
4. Flag clipped accel samples (>= 15.9 g) and hold the gyro-only prediction through them.
5. Keep raw data logging on during development so any on-chip fusion (SFLP, BNO08x) can be compared to the host filter.

---

## 10. Source index (primary and secondary)

Primary (datasheets / vendor pages / papers):

- InvenSense MPU-6000/6050 product specification rev 3.2: https://alliance.seas.upenn.edu/~medesign/wiki/uploads/Guides/MPU6050_datasheet.pdf
- InvenSense MPU-9250 product specification: https://cdn.sparkfun.com/assets/learn_tutorials/5/5/0/MPU9250REV1.0.pdf
- TDK ICM-20948 DS-000189 v1.3: https://cdn.sparkfun.com/assets/7/f/e/c/d/DS-000189-ICM-20948-v1.3.pdf
- TDK ICM-42688-P DS-000347 rev 1.6: https://pcbsync.com/wp-content/uploads/2026/07/ICM-42688-P-Datasheet-TDK.pdf (TDK page: https://invensense.tdk.com/?p=9240)
- TDK ICM-42670-P DS-000451 rev 1.2: https://media.tindie.com/datasheets/42072/2026-05-06-00-49-25/ds-000451-icm-42670-p-datasheet.pdf
- TDK ICM-45686 / ICM-45605 product pages: https://invensense.tdk.com/en-us/products/6-axis/icm-45686 , https://invensense.tdk.com/en-us/products/6-axis/icm-45605
- Bosch BMI160 DS rev 1.0: https://www.bosch-sensortec.com/media/boschsensortec/downloads/datasheets/bst-bmi160-ds000.pdf
- Bosch BMI270: https://www.bosch-sensortec.com/media/boschsensortec/downloads/datasheets/bst-bmi270-ds000.pdf
- Bosch BMI323: https://www.bosch-sensortec.com/media/boschsensortec/downloads/datasheets/bst-bmi323-ds000.pdf
- Bosch BNO055 rev 1.2: https://cdn-shop.adafruit.com/datasheets/BST_BNO055_DS000_12.pdf
- CEVA BNO08X rev 1.17: https://www.ceva-ip.com/wp-content/uploads/BNO080_085-Datasheet.pdf
- ST LSM6DS3 rev 10: https://content.arduino.cc/assets/st_imu_lsm6ds3_datasheet.pdf
- ST ISM330DHCX rev 7: https://cdn.sparkfun.com/assets/d/4/6/d/f/ism330dhcx_Datasheet.pdf
- ST LSM6DSV16X rev 2: https://docs.sparkfun.com/SparkFun_6DoF_LSM6DSV16X/assets/BoardFiles/lsm6dsv16x.pdf
- Nordic nRF52840 PS v1.1: https://cdn.sparkfun.com/assets/d/2/6/2/6/nRF52840_PS_v1.1.pdf
- Espressif ESP32-C3 datasheet v2.4: https://www.espressif.com/sites/default/files/documentation/esp32-c3_datasheet_en.pdf
- Martinez et al. 2019, gyroscope-based turn detection: https://pmc.ncbi.nlm.nih.gov/articles/PMC7739568/
- Hummel et al. 2023, edge angle IMU challenges (abstract): https://lida.sport-iat.de/svd/Record/4081463
- Adafruit LSM6DSOX/ISM330DHCX guide: https://static.chipdip.ru/lib/618/DOC011618898.pdf ; product page https://www.adafruit.com/product/4438
- SparkFun BNO086 page: https://www.sparkfun.com/sparkfun-vr-imu-breakout-bno086-qwiic.html

Secondary (reported):

- MPU-6050 EOL: https://www.rapidonline.com/news/mpu6050-alternatives-equivalents ; https://community.sparkfun.com/t/mpu6050-obsolete/63218
- MPU-9250 EOL: https://discuss.px4.io/t/mpu-9250-end-of-life-migrate-to-icm-20948/9262
- Counterfeit symptoms: https://eevblog.com/forum/projects/counterfeit-mpu6050-chips-from-china
- BMI160 status, BMI270 upload: Bosch community links in section 3.3
- BNO055 clock stretching: https://community.bosch-sensortec.com/mems-sensors-forum-jrmujtaw/post/bno055-long-i2c-clock-stretching-vh3nGpxZKy0YOAQ
- LSM6DSV16X SFLP: https://community.st.com/mems-and-sensors-62/how-lsm6dsv16x-enables-sensor-fusion-low-power-sflp-algorithm-120280
- Insole/boot force literature: https://pmc.ncbi.nlm.nih.gov/articles/PMC3761512 , https://sponet.de/sponet/Record/4097541 , https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8077231/
- Battery University BU-502: https://batteryuniversity.com/article/bu-502-discharging-at-high-and-low-temperatures
