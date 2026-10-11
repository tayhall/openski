# 09a. Premium inertial chips and orientation modules for boot-cuff ski sensing

Date: 2026-10-11. Scope: chips and modules only. Cost is secondary (up to a few hundred EUR/USD per boot).

Label key: CONFIRMED = read in a primary or datasheet-mirror document during this research. REPORTED = secondary source or a search-engine summary of a manufacturer page that I could not open in full. INFERRED = my reasoning. "not verified" = could not find a figure.

## 0. Method and limits (read first)

- Direct downloads from st.com and analog.com timed out from this environment (WebFetch and curl, repeated). So for ST ASM330LHH, ST LSM6DSV16X/DSV320X and the ADIS165xx family I could not read the electrical tables. Where I quote them they come from search summaries of the manufacturer pages and are labelled REPORTED.
- Datasheets I did read as text: ST ISM330DHCX Rev 7 (https://cdn.sparkfun.com/assets/d/4/6/d/f/ism330dhcx_Datasheet.pdf), Bosch BMI270 and BMI323 (bosch-sensortec.com datasheets), Bosch BMI088 Rev 1.3 (https://cdn.sparkfun.com/assets/c/c/e/4/7/BMI088_Datasheet.pdf), TDK ICM-42688-P DS-000347 rev 1.6, CEVA/Bosch BNO08X datasheet, MicroStrain 3DM-CX5-IMU Rev F (https://static.chipdip.ru/lib/231/DOC027231576.pdf), VectorNav VN-100 product brief (https://vectornav.com/docs/default-source/product-brief/vn-100-product-brief.pdf), Xsens MTi-3 and MTi-630 leaflets (https://mm.digikey.com/Volume0/opasdata/d220001/medias/docus/3707/MTi-3.pdf, https://www.xsens.com/hubfs/MTi%20product%20leaflet_07022025_MTi-630.pdf), SBG Ellipse-N/D brochure (https://www.sbg-systems.com/wp-content/uploads/SBG-Ellipse-N-D-MK068.pdf).
- The pdftotext layout of several multi-column tables is garbled; I only quote values where the row meaning was unambiguous or corroborated by a second source.
- Nothing here has been bench tested. No source I found tests any of these parts below 0 C in a strapped-on, vibrating, boot-like environment (see section 5).

## 1. Key framing (INFERRED, but drives the ranking)

1. With VQF running its own gyro-bias estimation, a gyro zero-rate offset of 1 to 3 dps is not what limits "roll within a few degrees". VQF's lab ski result (0.7 to 3.7 deg median, from the established facts) was obtained with ordinary-grade sensors. Paying for 0.3 deg/h bias instability (Murata SCH16T-K01) or 8 deg/h versus 3 deg/h buys almost nothing for this use. INFERRED.
2. What does limit quality here: (a) accelerometer clipping and range (established: spikes to 6 to 18 g, a +-16 g part clipped on 13 to 35 percent of ski samples), (b) vibration-induced error and g-sensitivity of the gyro, (c) behaviour during cold transients (cold start, then warming from a body or boot), (d) mounting stress and strap flex, (e) power and enclosure/battery in the cold. So the premium that is worth paying for is accelerometer range plus a calibrated, rugged, temperature-characterised unit, not bias instability. INFERRED.
3. Accelerometer range is the clearest discriminator. Parts with a single +-8 g or +-16 g accelerometer (BNO08x, ADIS16505, VN-100, MTi-630, Epson M-G370PDG, SCH16T) will clip on the observed spikes unless their headroom is bigger than the nominal range. Parts offering >= +-32 g: TDK ICM-45686, ST LSM6DSV320X (high-g channel to 320 g), ADIS16470/16507 (+-40 g), MicroStrain CX5 (+-40 g option), x-IMU3 (+-24 g plus +-200 g channel). INFERRED from the figures below.

## 2. (A) Chip-level and evaluation-board parts

### 2.1 Analog Devices ADIS16470 / 16465 / 16500 / 16505 / 16507 (iSensor, factory-calibrated, BGA module, SPI)

| Part | Gyro in-run bias | Gyro noise density | Accel range | Operating T | Calibrated T | Source / label |
|---|---|---|---|---|---|---|
| ADIS16470 | 8 deg/h | 0.008 dps/rtHz | +-40 g, 13 ug in-run bias | -25 to +85 C | -10 to +75 C | CONFIRMED on a datasheet mirror: https://www.radiolocman.com/datasheet/pdf.html?di=130625 |
| ADIS16500 | 8.1 deg/h | not verified | +-392 m/s2 (about 40 g) | -25 to +85 C | -10 to +75 C | REPORTED via search summary of ADI pages: https://analog.com/ADIS16500 |
| ADIS16507 (-1/-2/-3) | -1 variant 2.3 deg/h; -3 variant not verified | not verified | +-392 m/s2 (about 40 g) | -40 to +105 C | -40 to +85 C | REPORTED: https://www.analog.com/en/products/adis16507.html |
| ADIS16465 (-1/-2/-3) | -1 variant 2 deg/h | not verified | stated +-8 g in the (preliminary Rev A) mirror | -40 to +105 C (third-party listing) | -40 to +85 C | REPORTED: https://www.radiolocman.com/datasheet/pdf.html?di=130629 |
| ADIS16505 (-1/-2/-3) | -1: 2.3 deg/h; -3: 7.5 deg/h typ | not verified | +-78.4 m/s2 (about +-8 g) | -40 to +105 C | not verified | REPORTED: https://www.analog.com/en/products/adis16505.html , https://sk.farnell.com/analog-devices/adis16505-3bmlz/mems-imu-module-3-6v-tri-axis/dp/4030176 |

Other facts:
- ADIS16507 gyro linear-acceleration (g) effect on bias: Rev A table lists 0.572e-3, 1.02e-3, 0.408e-3 (deg/s)/(m/s2) for X/Y/Z, which I convert to about 0.006, 0.010, 0.004 dps/g. Rev 0 listed 867e-6 (deg/s)/(m/s2) any direction, about 0.0085 dps/g. REPORTED (search summary of https://www.analog.com/media/en/technical-documentation/data-sheets/ADIS16507.pdf and https://www.mouser.com/pdfDocs/ADIS16507.pdf; conversions are mine). For comparison the Bosch parts list 0.1 dps/g max (BMI088, BMI270). This is the one parameter where ADI is clearly better: roughly 10x less g-sensitivity than the Bosch parts.
- Bias temperature coefficient for ADIS16507-3: not verified. Interface SPI; supply current, size, bandwidth: not verified (ADI pages unreachable).
- Price: ADI lists ADIS16507 from $597.94 at 1ku and ADIS16505 from $687.63 at 1ku (REPORTED, search summary of analog.com pages). DigiKey lists the ADIS16507-2/PCBZ breakout/eval board at $916.64 single (REPORTED, https://www.digikey.com/en/products/detail/analog-devices-inc/ADIS16507-2-PCBZ/11642787). LCSC reference price for the bare ADIS16470AMLZ about $217 to $241 (REPORTED, https://www.lcsc.com/product-image/C459878.html). ADIS16470/PCBZ breakout exists (https://wiki.analog.com/resources/eval/user-guides/inertial-mems/imu/adis1647x-pcb), price not verified.
- Cold-use note (CONFIRMED for ADIS16470/16500): operating limit is only -25 C and calibration only -10 C. -20 C use is inside the operating limit but outside the calibrated range, so the factory calibration advantage is lost exactly where you need it. ADIS16507 and 16465 are calibrated -40 to +85 C (REPORTED).
- Judgement (INFERRED): the ADIS parts are the only ones here with truly characterised, factory-calibrated bias/scale/alignment on a chip-like package, and ADIS16507 gets +-40 g and cold calibration. But at $600 to $900 per boot, with the unit sized for instruments rather than a strap, and roughly 10x the cost of a competent chip, the accuracy gain is not the one the application needs (section 1).
- Single biggest cold boot-mounted risk: ADIS16470/16500: operating specs void below -25 C and uncalibrated below -10 C. ADIS16507: price and an unproven size/power fit on a boot cuff; the part itself is the strongest candidate for -20 C. ADIS16505: +-8 g clipping.

### 2.2 Bosch BMI088, BMI270, BMI323

| Part | Gyro noise | Gyro zero-rate offset | Gyro TCO | g-sensitivity | Accel range | Accel TCO / zero-g | Operating T | Source / label |
|---|---|---|---|---|---|---|---|---|
| BMI088 | 0.014 dps/rtHz | +-1 dps typ (over life) | +-0.015 dps/K | 0.1 dps/g max | +-3/6/12/24 g | <0.2 mg/K, zero-g +-20 mg | -40 to +85 C | CONFIRMED, BMI088 datasheet Rev 1.3; offset corroborated at https://www.bosch-sensortec.com/products/motion-sensors/imus/bmi088/ (REPORTED via search) |
| BMI270 | 0.007 dps/rtHz | not extracted cleanly | 0.02 dps/K typ (TCSG row) | 0.1 dps/g max | +-16 g | zero-g offset row present, value not extracted cleanly | -40 to +85 C (REPORTED by the BMI323 datasheet; BMI270 value not independently extracted) | CONFIRMED (partly), BMI270 datasheet BST-BMI270-DS000-08 |
| BMI323 | 0.007 dps/rtHz | +-1 dps over life, soldered | 0.04 dps/K (best-fit) | not extracted | +-16 g | zero-g +-0.5 percent, TCO value not cleanly extracted | -40 to +85 C | CONFIRMED, BMI323 datasheet BST-BMI323-DS000-13 (Apr 2026) |

- BMI088 is Bosch's vibration-hardened part ("designed to effectively suppress vibrations above a few hundred Hz", mechanical shock rating 2,000 g, accelerometer bandwidth 5 to 280 Hz) and is the only +-24 g Bosch IMU. CONFIRMED, datasheet text. Accel and gyro are in separate dies with separate interfaces (INFERRED from its structure; datasheet describes it as two sensors).
- Accelerometer noise for BMI088 is inconsistent between sources (160 to 230 ug/rtHz). CONFIRMED in datasheet Rev 1.3: 160 ug/rtHz (X,Y), 190 ug/rtHz (Z).
- BMI088 maximum +-24 g is below the +-32 g target but above the observed 18 g spikes. INFERRED.
- Breakouts for BMI088 exist from hobbyist vendors (the datasheet is hosted at SparkFun), price class about $10 to $30. not verified.
- Single biggest risk: BMI088 zero-rate offset +-1 dps typ with 0.1 dps/g g-sensitivity max (CONFIRMED) means a worst case 0.1 dps/g x 2 g = 0.2 dps in a turn: negligible. The real risk is no factory-quoted data below -40 C and no mention of cold behaviour; also +-24 g ceiling for hard landings. BMI270/BMI323: +-16 g clipping.

### 2.3 ST ASM330LHH, ISM330DHCX, LSM6DSV16X, LSM6DSV320X

ISM330DHCX (CONFIRMED, DS13012 Rev 7, https://cdn.sparkfun.com/assets/d/4/6/d/f/ism330dhcx_Datasheet.pdf):
- Gyro zero-rate level typ +-1 dps, max +-3 dps (characterised on a limited sample, not production-tested).
- Gyro zero-rate change vs temperature +-0.005 dps/C typ, +-0.015 dps/C max.
- Rate noise density 5 mdps/rtHz typ, 8 max; ARW 0.21 deg/rt-h typ; bias instability 3 deg/h typ.
- Accel zero-g offset +-10 mg typ, +-65 mg max; zero-g change vs temperature +-0.1 mg/C typ, +-0.5 max; accel noise 60 ug/rtHz typ.
- Accel full scale +-2/4/8/16 g; gyro +-125 to +-4000 dps; ODR to 6667 Hz; operating temperature -40 to +105 C.
- Current about 1.2 mA typical (normal mode row); Machine Learning Core and FIFO on chip.
- Gyro g-sensitivity: not specified in the table I read.
- Industrial-grade, 10-year longevity programme (marketing; not verified).

ASM330LHH (automotive grade, AEC-Q100): -40 to +105 C, +-16 g, gyro +-125 to +-4000 dps, "embedded compensation for high stability over temperature", LGA-14 about 2.5 x 3 x 0.86 mm. REPORTED: https://www.st.com/en/mems-and-sensors/asm330lhh.html , https://www.radiolocman.com/datasheet/data.html?di=517251. Zero-rate and noise numbers: not verified. INFERRED to be close to ISM330DHCX (same family), but that is not confirmed.

LSM6DSV16X: same family as the earlier hobby recommendation; electrical numbers not re-fetched here (not verified in this report).

LSM6DSV320X (new, important for this application):
- Contains three sensors: low-g accel (+-2/4/8/16 g), high-g accel (+-32/64/128/256/320 g), gyro (+-250 to +-4000 dps). REPORTED: https://www.cnx-software.com/2025/05/28/st-lsm6dsv320x-ai-enabled-imu-with-two-mems-accelerometers-for-activity-and-shock-detection/ ; datasheet DS14623 Rev 3 per https://www.st.com/resource/en/datasheet/lsm6dsv320x.pdf (not readable here).
- Noise: gyro 3.8 mdps/rtHz, low-g accel 60 ug/rtHz, high-g accel 1000 ug/rtHz (REPORTED, same cnx-software article).
- Current: 0.67 mA (gyro + low-g), 0.80 mA with 9-axis combo (REPORTED). Operating -40 to +85 C (REPORTED). Package 3.0 x 2.5 x 0.83 mm. Price $9.31 single, $5.03 at 500 (REPORTED).
- Zero-rate offset and temperature coefficient: not verified.
- Relevance (INFERRED): the high-g channel removes the clipping problem entirely for falls and landings while the low-g channel keeps full noise performance for inclination. This is the only chip that solves the clipping failure mode cleanly on one die.
- Availability as breakout/eval board: not verified (new part; ST ecosystem likely offers an eval kit, not confirmed).
- Single biggest risk: dual-accelerometer sensor fusion complexity (two scale factors, two clocks) and the lack of any independently measured temperature drift data on this brand-new part. ISM330DHCX/ASM330LHH: 3 dps worst-case zero-rate level, not production-tested limits.

### 2.4 TDK ICM-45686, ICM-42688-P, ICM-42688-V

ICM-45686 (TDK product page via search/fetch, https://invensense.tdk.com/products/smartmotion/icm-45686/ , REPORTED because spec tabs were collapsed):
- Gyro noise 3.8 mdps/rtHz; accel noise 70 ug/rtHz; gyro up to +-4000 dps; accel up to +-32 g.
- Gyro temperature stability +-0.005 dps/C; accel temperature stability +-0.15 mg/C.
- Current (6-axis) 0.42 mA low-noise mode, 0.22 mA low-power mode; package 2.5 x 3.0 x 0.81 mm.
- Operating temperature -40 to +85 C (REPORTED, https://my.element14.com/tdk/icm-45686/mems-module-6-axis-1-71v-3-6v/dp/4572492).
- "BalancedGyro" architecture, designed to be less sensitive to vibration and board stress (marketing claim on the TDK page; independent verification: not verified).
- Evaluation board DK-45686 exists (https://azcus.digikey.com/en/products/detail/tdk-invensense/DK-45686/24883423). Chip price class: about $5 to $15 (not verified).
- Not stated: zero-rate offset at 25 C, g-sensitivity.

ICM-42688-P (CONFIRMED, DS-000347 rev 1.6): gyro noise 2.8 mdps/rtHz, accel noise 65 to 70 ug/rtHz, +-16 g, gyro +-2000 dps, specified temperature range -40 to +85 C, board-level 25 C zero-rate output tolerance +-0.5 dps, ZRO variation 0 to +70 C +-0.005 dps/C (rows parsed from a garbled table, but consistent with the "2.8 mdps/rtHz" and "5 mdps/C offset temp stability" in the datasheet highlights), 6-axis current 0.88 mA low-noise mode, accel zero-g change vs temperature +-0.15 mg/C. This matches fact (3) in the brief (datasheet noise confirmed on a ski).
ICM-42688-V: described as the automotive/vehicle variant. I could not verify its temperature range or offset figures. not verified.

- Single biggest risk: only +-16 g (42688) or +-32 g (45686) with no factory per-unit calibration; offset shifts from solder and board stress (a known property of these small LGA parts; the ICM-45686 vendor page itself emphasises board-stress offset stability). INFERRED. The ICM-45686's +-32 g covers the observed 18 g spikes with 1.8x margin.

### 2.5 Murata SCH16T family (replacement for the older SCHA634 / SCC2130 class)

SCH16T (CONFIRMED on Murata page, https://www.murata.com/en-global/products/sensor/gyro/overview/lineup/sch16t):

| Variant | Gyro range | Gyro bias instability | Gyro noise | Accel range |
|---|---|---|---|---|
| K01 | +-300 dps | 0.3 deg/h | 0.4 mdps/rtHz | +-8 g (aux channel to 26 g) |
| K10 | +-2000 dps | 2 deg/h | 6 mdps/rtHz | +-16 g (aux channel to 26 g headroom) |
| K20 | +-300 dps | 0.3 deg/h | 0.4 mdps/rtHz | +-8 g |

- Operating temperature -40 to +110 C, validated against AEC-Q100 Grade 1 tests; SafeSPI v2, 3.0 to 3.6 V; package 12 x 14 x 3 mm SOIC-24 (inverted). CONFIRMED.
- Bias over temperature: no numeric value on the page. Supply current: not stated there. Both: not verified.
- Evaluation board SCH16T-K10-PCB at $85.69 single (REPORTED, https://www.mouser.in/new/murata/murata-sch16t-k10-pcb-sensor-board). Sensor chip about $46 to $78 (REPORTED, SCH16T-K01-1 findchips/DigiKey snippet).
- SCHA634 and SCC2130: I could not find a usable datasheet. SCHA63T-K03 is +-6 g and +-300 dps, 1.68 deg/h bias instability, -40 to +110 C, and is listed NRND on Murata's gyro page (https://www.murata.com/en-global/products/sensor/gyro). REPORTED. Treat as superseded by SCH16T.
- Single biggest risk: +-16 g (K10) / +-8 g (K01) with only 26 g of electrical headroom. A fall or hard landing above 26 g will saturate. K01 gives the best bias but +-300 dps might clip during falls (roll or tumble rates). Also a SOIC, SafeSPI, automotive-oriented part with a small developer ecosystem: expect custom firmware effort. INFERRED.

### 2.6 Epson M-G370PDG, M-G552PR

- M-G370PDG0: gyro bias instability 0.8 deg/h, ARW 0.06 deg/rt-h (the -PDS/-PDT siblings list 0.03), gyro +-450 dps, accel +-8 g or +-16 g selectable, -40 to +85 C, 24 x 24 x 10 mm, 10 g, 3.3 V, about 16 mA. REPORTED via search summary of Epson briefsheets (https://global-origin.epson.com/products_and_drivers/sensing_system/assets/pdf/m-g370pdt0_briefsheet_e.pdf and https://geo-matching.com/products/m-g370pdg0; both redirected or blocked from my fetch).
- M-G552PR7: bias instability 0.8 deg/h, accel +-10 g, initial bias error 360 deg/h (1 sigma) / 3 mg, operating -30 to +80 C. REPORTED (https://global-origin.epson.com/products_and_drivers/sensing_system/assets/pdf/m-g552pr7_briefsheet_e_rev20230915.pdf).
- Price: not verified. g-sensitivity: not verified.
- Verdict (INFERRED): excellent gyro, but +-16 g at best, 16 mA (about 50 mW; fine) but a 24 x 24 x 10 mm block and no cold margin on the M-G552PR (-30 C). Not better than SCH16T-K10 for this application.
- Single biggest risk: +-8/16 g clipping and no known cold characterisation below -40 C.

### 2.7 Parts considered and dropped
- Bosch BHI360 smart IMU (6-axis plus on-chip processor): accel +-2 to +-16 g, gyro +-125 to +-2000 dps, -40 to +85 C (REPORTED, https://www.bosch-sensortec.com/products/smart-sensor-systems/bhi360/). Gyro noise and zero-rate: not verified. Its value is on-chip fusion, which conflicts with the wish to run VQF on raw data. Not recommended as primary.
- Bosch BMI330 (extended-temperature IMU, mentioned on https://www.mouser.in/en/new/bosch/bosch-bmi330-imu/): page timed out; not verified.

## 3. (B) Complete factory-calibrated modules

| Module | Gyro in-run bias | Gyro noise | Accel range | Accel in-run bias | Operating T | Size, weight | Power | Raw outputs | Price (qty 1) |
|---|---|---|---|---|---|---|---|---|---|
| VectorNav VN-100 (Rugged / SMD) | <10 (typ 5) deg/h | 0.0035 dps/rtHz | +-16 g | <0.04 mg | -40 to +85 C | Rugged 36x33x9 mm, 15 g; SMD 24x22x3 mm, 3.5 g | Rugged 220 mW (40 mA @ 5 V); SMD 185 mW (45 mA @ 3.3 V) | Yes, IMU data to 800 Hz plus dtheta/dv | $950 std cal; $1,300 thermal cal (REPORTED) |
| VectorNav VN-110 | 0.6 deg/h typ (<1) | not verified | +-15 g | <10 ug | -40 to +85 C | <160 g (Rugged), VN-110E 12 g | <2.5 W | Yes (INFERRED, same protocol family) | not verified; tactical-grade, expect several $k |
| Xsens MTi-3 | 10 deg/h | 0.003 dps/rtHz | +-16 g | 30 ug | -40 to +85 C | 12.1x12.1x2.55 mm, 0.6 g, IP00 | 44 mW @ 3 V | Inertial data output yes (at up to 1 kHz) | not verified |
| Xsens MTi-630 | 8 deg/h | 0.007 dps/rtHz | +-10 g (leaflet layout somewhat ambiguous) | 10 ug (x,y), 15 ug (z) | -40 to +85 C | 28x31.5x13 mm, 8.9 g, IP51 | <0.5 W, 4.5 to 24 V | Yes | MTi-630 dev kit about $1,062 to $1,619 (REPORTED) |
| Xsens MTi-300 | 10 deg/h | not verified | +-20 g | 15 ug | not verified | not verified | not verified | yes | not verified |
| MicroStrain 3DM-CX5-IMU | 8 deg/h | 0.005 dps/rtHz (300 dps range) | +-8 g std; +-2/4/20/40 g options | +-0.04 mg | -40 to +85 C | 38x24x9.7 mm, 8 g (HBK page also says 12 g/10.7 mm for one language variant) | 300 mW typ, 3.2 to 5.2 V | Scaled, temperature-compensated accel and gyro, plus dtheta/dv, 1 to 1000 Hz | not verified (volume pricing, quote) |
| SBG Ellipse-N / -D | 7 deg/h | not verified | +-8 g marine, +-20 g land/air, +-40 g high dynamics | 14 ug | -40 to +85 C | 46x45x24 mm 47 g (N box); OEM 29.5x25.5x16 mm 17 g | <600 mW (N), <900 mW (D), 5 to 36 V | 1 kHz IMU output | not verified |
| x-io x-IMU3 | not listed | not listed | +-24 g, plus separate +-200 g channel | not listed | not listed | not listed on page | 13 h logging on internal battery | Yes, raw streaming and CSV logs | GBP 300 ex VAT (CONFIRMED) |
| Bosch BNO08x | not listed (fused) | not listed | +-8 g, 12-bit | n/a | -40 to +85 C | 3.8 x 5.2 x 1.1 mm | not extracted | Yes, uncalibrated and raw ADC outputs available | chip class about $10 to $20 (not verified) |
| Bosch BHI360 | not verified | not verified | +-16 g (REPORTED) | not verified | -40 to +85 C | not verified | not verified | Yes (programmable) | not verified |

Sources:
- VN-100: CONFIRMED, https://vectornav.com/docs/default-source/product-brief/vn-100-product-brief.pdf and https://www.vectornav.com/products/VN-100 (fetched; page lists no IP rating or price). Individual calibration for bias, scale factor, misalignment and temperature; "standard (25 C) or full temperature compensation (-40 to +85 C)". CONFIRMED. Price REPORTED from the VectorNav purchasing guide copy (https://metromatics.com.au/wp-content/uploads/2025/12/VN-100-Purchasing-Guide.pdf) and store (https://vectornav.com/store/products/imu-ahrs/p/vn-100-rugged-development-kit): dev kit $1,200 (std) / $1,525 (thermal).
- VN-110: REPORTED via search summary (https://www.vectornav.com/products/vn-110, https://metromatics.com.au/wp-content/uploads/2025/12/VN110-Datasheet-v3.0-DS110-30-R1.pdf).
- MTi-3 and MTi-630: CONFIRMED from leaflets listed in section 0. MTi-300 REPORTED (https://www.directindustry.com/prod/xsens/product-159399-2601797.html). Prices REPORTED (https://il.farnell.com/xsens/mti-630-dk/development-kit-inertial-sensor/dp/3225728, https://www.cytechsystems.com/de/product/mti-630-dk).
- 3DM-CX5-IMU: CONFIRMED, Rev F datasheet (Parker/LORD 2020; may be outdated, re-check). States "fully temperature-compensated and calibrated over the operating temperature range", 500 g/1 ms shock survivability, "offset error over temperature" and "gain error over temperature" specified, vibration rectification error listed (0.001 dps/s per g2 RMS for the gyro).
- SBG: CONFIRMED brochure above. These are GNSS-aided INS boxes; the IMU part is a stock industrial MEMS. IP68 for the box version. Not a fit for power or form factor.
- x-IMU3: CONFIRMED, https://x-io.co.uk/x-imu3/. Gyro +-2000 dps, accel 24 g, high-g 200 g, 400 Hz; USB, UART, Wi-Fi, classic Bluetooth SPP (not BLE); 8 GB microSD logging (about 485 h); battery runtimes 13 h logging, 10 h Bluetooth; IP67; non-removable battery; sub-0.5 deg static AHRS. The page lists no operating temperature, weight, dimensions or gyro noise.
- BNO08x: CONFIRMED, BNO08X datasheet: accel +-8 g, 12-bit; gyro +-2000 dps, 16-bit; -40 to +85 C; 3.8 x 5.2 x 1.1 mm. Rotation-vector static error 2.0 deg, dynamic 3.5 deg; gaming rotation vector 1.5 / 2.5 deg. Raw gyro and uncalibrated gyro reports exist. The CEVA fusion is closed, runs a proprietary calibration state machine that cannot be replaced by VQF on the same output, and 12-bit +-8 g will clip on ski spikes. Rejected.
- BHI360: REPORTED, as in 2.7.

Per-module single biggest cold, boot-mounted risk:
- VN-100: +-16 g range clips in the same way that the +-16 g part in the established facts did (13 to 35 percent of ski samples); also the Rugged enclosure is not rated IP (not listed).
- VN-110: size/weight (up to 160 g box), price, and the same +-15 g limit.
- MTi-3: bare 0.6 g PCB, IP00, so all waterproofing, strain relief and connectors are on you; +-16 g.
- MTi-630: +-10 g and IP51 only.
- CX5-IMU: 300 mW (INFERRED about 80 to 90 mA at 3.3 V, so a larger cell), standard gyro range +-300 dps which falls and tumbles will exceed (order the +-900 dps option); the datasheet I read is Rev F 2020.
- SBG Ellipse: power (600+ mW) and size.
- x-IMU3: internal non-removable Li-ion cell with no stated cold limit (typical Li-ion charge/discharge cold limits are the real concern; INFERRED) and no listed operating temperature. Classic Bluetooth, not BLE.
- BNO08x: +-8 g and a closed fusion that is not VQF-compatible.

## 4. What the results mean for the stated needs

- Roll within a few degrees through a 6+ hour day: any part above is adequate on bias once VQF estimates bias online (INFERRED). The single biggest lever is not clipping the accelerometer.
- Turn timing to a few tenths of a second: needs gyro/accel at 100 to 200+ Hz and aligned timestamps; all candidates satisfy this (ODR >= 400 Hz on every option; ICM-45686 and LSM6DSV320X reach kHz ODR). INFERRED.
- 1 to 2 g sustained plus several-g impacts: accelerometer range of +-16 g is enough for sustained loads; +-32 g and above is what avoids clipping on spikes up to 18 g (established fact 4).
- Cold: every candidate except ADIS16470/16500 (-25 C) is rated to -40 C at chip or module level. But no source gives measured performance at -20 C in a strapped-on setting. Gyro bias temperature slope of the best-known units is 0.005 to 0.015 dps/C per datasheet; applied over 40 C that is 0.2 to 0.6 dps, which VQF will absorb. A bad-luck MPU6050-style slope (0.11 dps/C) would not be absorbable, which is why per-unit measurement still matters.
- Vibration: ADI quotes about 0.004 to 0.010 dps/g; Bosch quotes <=0.1 dps/g; VQF cannot separate this from gyro bias in sustained oscillating accelerations, but the magnitude at 1 to 2 g is under 0.2 dps for the Bosch parts: not a problem. INFERRED.

## 5. Decision table

| Option | Bias and temperature quality | Accel range | Cold rating | Power | Price class (single unit) | Raw access | Main risk |
|---|---|---|---|---|---|---|---|
| TDK ICM-45686 (chip, DK-45686 eval) | Gyro temp stability +-0.005 dps/C; ZRO not verified | +-32 g | -40 to +85 C (REPORTED) | 0.42 mA | chip about $5-15 (not verified); eval board tens of $ | Full raw, I3C/SPI/I2C | No per-unit factory calibration; board-stress offset; no cold data |
| ST LSM6DSV320X (chip) | Not verified (family ISM330DHCX: +-1 dps, 0.005 dps/C typ) | +-16 g low-g and +-320 g high-g | -40 to +85 C (REPORTED) | 0.67 to 0.80 mA | chip $5 to $9 (REPORTED) | Full raw, both accel channels | Dual-accel fusion complexity; new part with little third-party data |
| Murata SCH16T-K10 (chip, or K10-PCB eval $86) | 2 deg/h, 6 mdps/rtHz; bias over T not verified | +-16 g, 26 g headroom | -40 to +110 C, AEC-Q100 Gr 1 | not verified | chip $46-78, eval $86 | Full raw via SafeSPI | +-26 g ceiling; custom firmware effort |
| MicroStrain 3DM-CX5-IMU | 8 deg/h; fully temperature compensated | +-8 g std, +-40 g option | -40 to +85 C | 300 mW | not verified (likely high hundreds $; not verified) | Scaled compensated IMU data plus deltas, 1 kHz | Standard +-300 dps gyro; 300 mW; datasheet Rev F 2020 |
| VectorNav VN-100 thermal cal | 5 deg/h typ; fully temperature compensated | +-16 g | -40 to +85 C | 185 to 220 mW | $950 std / $1,300 thermal | Yes, 800 Hz | +-16 g clipping; no IP rating listed |
| ADI ADIS16507-3 | Calibrated -40 to +85 C; BI -1 variant 2.3 deg/h (-3 not verified) | +-40 g | -40 to +105 C | not verified | $600 chip, $917 eval | Yes, SPI | Cost, size and power unknown; ADIS16470/16500 only -25 C |
| Bosch BMI088 | +-1 dps, 0.015 dps/K, 0.1 dps/g | +-24 g | -40 to +85 C | not extracted | breakout $10-30 (not verified) | Full raw | +-24 g ceiling; no factory per-unit cal |
| x-IMU3 (turnkey logger) | not listed | +-24 g plus +-200 g | not listed | 13 h logging | GBP 300 ex VAT | Raw CSV logs | Battery cold limit; classic BT not BLE; temperature range unstated |
| Epson M-G370PDG | 0.8 deg/h BI | +-8/16 g | -40 to +85 C | about 16 mA (REPORTED) | not verified | yes | Clipping; 24x24x10 mm |
| Bosch BNO08x | closed fusion | +-8 g 12-bit | -40 to +85 C | not extracted | $10-20 (not verified) | raw available | +-8 g clipping; closed filter |

## 6. Ranked recommendation

1. Primary: TDK ICM-45686 on a purpose-built board (first on the DK-45686 eval board). Reasons: the +-32 g range removes the 13 to 35 percent clipping seen with +-16 g parts; gyro noise 3.8 mdps/rtHz and 0.005 dps/C temperature stability (REPORTED) are in the same class as the best chips in this survey; 0.42 mA supports a full-day battery on a small cell; raw access; cheap enough to fit two or three per boot for redundancy (INFERRED). It beats the ADI/VectorNav class for this task because the premium parts spend money on bias instability that VQF largely recovers, and most of them have worse accelerometer range (INFERRED).
2. Alternate chip: ST LSM6DSV320X. Pick this if bench data show impacts above 32 g matter or if a fall-detection or impact log is wanted; the high-g channel to 320 g plus a clean low-g channel is unique among surveyed parts. Costs: two-accelerometer handling and less field history.
3. Premium option if quality over cost really dominates: Murata SCH16T-K10 (or K01 if the +-300 dps range proves enough from the logged data). Best-documented bias instability and a -40 to +110 C automotive-grade qualification; limited by +-16 g (26 g headroom). Consider if the ICM-45686 shows temperature drift worse than quoted.
4. Reference/validation module: MicroStrain 3DM-CX5-IMU configured +-40 g and +-900 dps (gyro option), mounted next to the candidate chips as a calibrated truth sensor. Fully temperature-compensated, 500 g shock survivability, 8 g, scaled raw-equivalent outputs feed VQF directly. It is not the production sensor (300 mW, price unverified). The VN-100 with thermal calibration is the same role at $1,300 but caps at +-16 g, so choose CX5 for ski work.

Honourable mention: x-io x-IMU3 (GBP 300, IP67, 13 h logging, 24 g + 200 g accelerometers, raw logs) as a quick turnkey logger for early field trials, subject to a cold-battery check. Not recommended as the final sensor because of the non-BLE radio and unlisted temperature range.

Not recommended: ADIS16470/16500 (cold calibration ends at -10 C, operating limit -25 C), ADIS16505 (+-8 g), BNO08x (+-8 g, closed fusion), MTi-630 (+-10 g, IP51), SBG Ellipse (GNSS box, 600+ mW), Epson (+-16 g, bulky).

## 7. What must be verified on the bench before committing

1. Cold soak and warm-up: put sensors (mounted on the strap/boot, not on a PCB bench) in a freezer at -20 C for hours, then take them to room temperature with the logging on. Record gyro bias vs time and vs the on-chip temperature for each candidate; compare with datasheet slope (ICM-45686 0.005 dps/C; ISM330DHCX 0.005 to 0.015 dps/C) and with VQF's bias estimate. Do this with at least 5 units per type to see unit-to-unit spread (the MPU6050 spread was large).
2. Cold battery: whichever cell is used must be tested at -20 C under the real load for 6 h. Datasheet cold limits for the cell, not the IMU, are likely the binding constraint (INFERRED).
3. Accelerometer clipping on real skiing: log at the highest range on a boot cuff (not a ski) and look at the amplitude distribution during turns, edge changes and a deliberate jump. Decide whether +-32 g is enough or whether the +-320 g channel is needed.
4. g-sensitivity and vibration rectification: shake table or a hand-held oscillation test at 1 to 2 g sustained; verify that gyro bias shifts match the 0.1 dps/g (Bosch) and 0.004 to 0.01 dps/g (ADI) class figures. Not measured for ICM-45686, LSM6DSV320X or SCH16T.
5. Board-stress and strap effects: mount on the actual strap, flex and torque it, and check offset change (BalancedGyro claim of ICM-45686 is not independently verified).
6. Moisture and ingress on the real enclosure, and condensation on repeated cold-to-warm transitions.
7. Orientation estimation end to end: run VQF on logged raw data from candidate and CX5 reference simultaneously; report roll error against the reference through a full cold session, and include gyro-clipping behaviour for +-300 dps parts.
8. Items not verified in this report that must be pulled from the real datasheets: ADIS16507-3 bias temperature coefficient, size, current; LSM6DSV320X and ASM330LHH zero-rate level and temperature coefficient; ICM-45686 zero-rate output and g-sensitivity; ICM-42688-V cold rating; SCH16T bias over temperature and current; CX5-IMU current price and revision; x-IMU3 operating temperature, weight and gyro part; Epson prices; VN-110 price.
