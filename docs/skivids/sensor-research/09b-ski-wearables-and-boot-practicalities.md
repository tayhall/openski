# 09b - Ready-made ski wearables and boot practicalities (parts C and D)

Date of research: 2026-10-11. Scope: ready-made BLE/logging systems and practical issues for a ski-boot-cuff sensor. Chip-level and orientation-module choices are covered by another agent.

Labels: CONFIRMED = I read it in the cited source. REPORTED = secondary source / search-engine summary of a source I did not read in full. INFERRED = my reasoning. "not verified" = could not be established.

Fetching notes: movesense.com/product/movesense-sensor-md returned 404. Several vendor PDFs (Xsens DOT leaflet, Xsens Awinda leaflet, Movesense spec sheets, MbientLab MetaMotionS spec, Panasonic CR2025) were fetched as binary and read locally with pdftotext, so those are CONFIRMED. IEEE DataPort needs a subscription for the data itself. Polar/Garmin/Suunto/Catapult/Delsys/Notch were searched only superficially; see section 1.10.

---

## 0. Headline findings

1. Movesense is the only ready-made platform with published, on-snow boot-cuff precedent (two Salzburg-group papers plus the Atomic Connected Boot product). Those papers streamed over BLE at only 33 to 64 Hz and never reported cold, battery or saturation behaviour. The platform itself can log at up to 833 Hz, with +-16 g and +-2000 dps selectable.
2. Most research-grade "ready-made" IMUs are rated only 0 to 50 C (Xsens DOT and MTw Awinda trackers). That fails the -20 C requirement on paper. Movesense does not publish an operating temperature at all (it publishes only a CR2025 coin cell, which Panasonic rates to -30 C).
3. Rechargeable Li-ion/LiPo cannot be charged below 0 C and is only rated to discharge down to about -20 C by the usual references. Products that must work in the cold either use a primary lithium coin cell (Movesense), accept a narrow range (Xsens), or make an unquantified claim (Carv).
4. Two-boot sync: the only quantified field result with off-the-shelf Movesense hardware is the phone-side method (arrival time minus half the BLE round trip) with no stated accuracy. Vendor-claimed hardware-level sync exists only for Xsens Awinda (10 us, with a receiver station, 0 to 50 C). Post-hoc sync from a shared event (a jump, or a double tap/stomp) was used in the one ski paper that states how it aligned data to video. Plan for post-hoc alignment as the primary method and treat any radio sync as a bonus.
5. Accelerometer saturation on boots is a documented problem in ski research (edge-angle drift when the accelerometer saturates). The authors recommend mechanical damping, but nobody I found has tested it.

---

## 1. Ready-made systems (part C)

### 1.1 Movesense (Suunto-derived, Finland)

Variants: HR+/HR2/Active (called "Sport"), MD (medical), Flash (128 MB).

| Item | Value | Label / source |
|---|---|---|
| IMU | 9-axis: accelerometer +-2/4/8/16 g, gyroscope +-125/245/500/1000/2000 dps, magnetometer. Sample rates 12.5/26/52/104/208/416/833 Hz | CONFIRMED, spec sheet https://www.movesense.com/wp-content/uploads/2020/12/Movesense-Spec-Sheet-12-2020.pdf and Flash sheet https://www.movesense.com/wp-content/uploads/2023/10/Movesense-Flash-Sensor-Spec-Sheet-2023-10.pdf |
| IMU chip part number | Not stated by Movesense. A paper using Movesense-supplied boards in an ice-skiing study names LSM6DS3 (+-8 g, +-500 dps in that configuration) | Chip: not verified for current hardware. REPORTED/CONFIRMED in paper: https://pmc.ncbi.nlm.nih.gov/articles/PMC7739568/ (paper names "LSM6DS3, STMicroelectronics"; I read it, but it does not say the board is Movesense; the Neuwirth paper below does say "by Movesense" and uses the same ranges) |
| MCU | Nordic nRF52832, 64 kB RAM, 512 kB flash (shared with OS and app) | CONFIRMED, same spec sheet |
| Battery | CR2025 user-replaceable coin cell. "Operating time up to months, depending on application." Flash sheet examples: 3-axis acc at 26 Hz about 3 weeks, ECG@125 Hz about 300 h. No figure for 6-axis at 208+ Hz | CONFIRMED, Flash sheet |
| Ingress | "Water resistant to 30 m/100 ft". No IP code stated | CONFIRMED, https://www.movesense.com/product/movesense-sensor-hr2/ |
| Operating temperature | Not stated for the sensor. Only temperature-sensor accuracy "<0.5 C, 0 C to +65 C" (a measurement spec, not an operating limit) | CONFIRMED absence; spec sheet above |
| Size and mass | 36.6 mm dia x 10.6 mm; 9.4 g with battery (HR2 page says 10 g) | CONFIRMED |
| On-board logging | Standard variants: 3 Mbit EEPROM (spec sheet); older docs list 384 kB. Flash: 128 MB, usable roughly 100 to 125 MB depending on data rate | CONFIRMED (spec sheets, https://www.movesense.com/product/movesense-flash/) |
| Price (ex VAT) | HR2 EUR 99 (1-49 pcs); Flash EUR 249 (min order 5 per the product page text I read; one search summary says packs of five) | CONFIRMED https://www.movesense.com/product/movesense-sensor-hr2/ and https://www.movesense.com/product/movesense-flash/ |
| SDK and raw access | C++ firmware SDK (nRF52 GNU toolchain), iOS and Android libraries, wireless firmware update. Raw acc and gyro streaming and logging | CONFIRMED, spec sheet |
| Multi-unit time sync | No vendor sync spec found. Neuwirth used phone-side compensation (below) | not verified for on-device sync |
| Cold-specific warning | The Flash variant "is more sensitive to low voltage situation during write operations than EEPROM"; flash uses more power when in operation and when not in use | CONFIRMED https://movesense.com/docs/system/hw_variants/ |

INFERRED storage arithmetic (raw 6-axis, 2 bytes per axis, no overhead):
- Standard 3 Mbit EEPROM (about 375 kB) holds about 5 minutes at 104 Hz. Standard variants cannot log a ski day on-board; they need BLE streaming.
- Flash at 208 Hz is about 54 MB per 6 h (fits). At 416 Hz about 108 MB per 6 h (marginal against the 100 to 125 MB usable). At 833 Hz about 216 MB per 6 h (does not fit).
- Movesense says Flash can hold "7 days of raw movement data" (REPORTED by search summary of https://www.movesense.com/news/2023/10/movesense-flash-is-an-ultralight-ecg-and-movement-data-recorder-for-standalone-operation/; the data rate behind that claim is not stated).

Ski research using Movesense:
- Neuwirth et al. 2020, Sensors 20(15):4232, "Classification of Alpine Skiing Styles Using GNSS and IMUs": two IMUs "by Movesense", one per boot, on the upper posterior cuff in a custom housing with strap; +-8 g, +-500 dps; 833 Hz internal rate with 416.5 Hz (acc) and 245 Hz (gyro) digital low-pass filters; streamed at 54 Hz over BLE to an iPhone; no in-field calibration; phone replaced the sensor timestamp by arrival time minus half the BLE round trip, recalculated continuously; if one IMU dropped out, the other's signal was used for both boots; the app stops after 60 missed values (about 1.1 s). Outcome: 2,000 turns from 20 skiers; ensemble classifiers above 93 percent accuracy on parallel turns, 88 percent on non-parallel turns. Battery, cold and saturation were not discussed. CONFIRMED, https://pmc.ncbi.nlm.nih.gov/articles/PMC7435691
- Martinez, Snyder et al. (turn detection, Atomic Hawx 130 boots, IMU on rear upper cuff, +-8 g, +-500 dps, 833 Hz, sent at 64 Hz over BLE): gyroscope-based turn switch detection with reported precision +-0.03 s in the field (REPORTED via abstract in search summary) with alignment to video via a jump at the start and end of each trial; the paper does not state how the two boot sensors were synchronised with each other; no discussion of cold, battery or saturation. CONFIRMED (read) https://pmc.ncbi.nlm.nih.gov/articles/PMC7739568/. The 0.056 to 0.063 s ergometer figures for the earlier lab study are REPORTED (PMC6413051 via search summary).
- Atomic "Connected Boot" (Frontiers in Sports and Active Living 2024): board by Movesense, 833 Hz, filtered, streamed at 54 Hz (iOS) or 33 Hz (Android); 51 of 130 enrolled participants excluded; Android users had more technical problems; skiing quality score correlated only 0.52 (Spearman) with self-rated carving ability; battery, cold and saturation not mentioned. CONFIRMED, https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1495176/full

Take-away: Movesense has the strongest "it was strapped to a boot cuff on snow and gave turn timing within about 0.03 s" evidence, but all evidence is on 33 to 64 Hz streamed data, +-8 g/+-500 dps, with no published roll accuracy, no cold data, and a dropout rule that implies BLE loss is common.

### 1.2 Xsens DOT (Movella)

| Item | Value | Source |
|---|---|---|
| Full scale | +-2000 dps, +-16 g, +-8 gauss | CONFIRMED, leaflet https://www.xsens.com/hubfs/Downloads/Manuals/Xsens%20DOT%20Datasheet.pdf (read locally with pdftotext) |
| Output rates | Real-time 1 to 60 Hz; recording up to 120 Hz (REPORTED via search summary of the datasheet; the pdftotext output I read was truncated at the output-rate table) | REPORTED |
| Battery | 70 mAh, about 1 h charge; "up to 8 hours" continuous (distributor listing, REPORTED https://www.mouser.co.uk/new/xsens/movella-dot/) | CONFIRMED capacity, REPORTED life |
| Operating temperature | 0 to 50 C | CONFIRMED |
| Ingress | IP68 | CONFIRMED |
| Internal storage / recording | 64 MB; maximum recording time about 88 minutes | CONFIRMED storage; recording limit CONFIRMED from https://www.xsens.com/resources/blog/new-xsens-dot-release-is-here |
| Size, mass | 36.3 x 30.35 x 10.8 mm; 11.2 g | CONFIRMED |
| Sync | One sensor is the root; the others adjust against it; about 10 s sync before measurement; vendor claim "1 ppm error after 30 minutes (1.8 ms), about 3 ppm after 60 minutes (10 ms)" | CONFIRMED vendor claim (blog above). No independent measurement found. |
| Price | not verified | |
| Ski research | None found | not verified |

Assessment (INFERRED): the 0 to 50 C rating, the 88-minute recording cap and roughly 8 h life make DOT a poor fit for an all-day -20 C boot sensor. Orientation output is already fused, but raw acc and gyro are available in recording mode.

### 1.3 Xsens MTw Awinda

- Tracker: +-2000 dps, +-160 m/s2 (about 16 g), internal sampling 1000 Hz, wireless update 120 Hz for one MTw, operating temperature 0 to 50 C, battery 6 h continuous (older leaflet; newer Movella listings reportedly 12 h for orders after 1 Oct 2024), 47 x 30 x 13 mm, 16 g. Awinda station: time synchronisation to within 10 us across up to 20 trackers; 4 BNC sync in/out ports; station ambient -25 to 80 C but specified performance 0 to 65 C "non-condensing environments, avoid wet and humid conditions". CONFIRMED from leaflet https://www.xsens.com/hubfs/Downloads/Leaflets/mtw-awinda%20leaflet.pdf; the 12 h figure is REPORTED (search summary).
- Needs a receiver station or dongle within 10 to 50 m and a PC; no on-board logging stated in the leaflet. Ski research: none found by me (Xsens MVN suits were used in ski kinematics work, REPORTED in https://pmc.ncbi.nlm.nih.gov/articles/PMC5672088 search listing; not verified).
- Verdict (INFERRED): excellent as a bench/lab reference for sync tests, unsuitable for field use in cold and wet.

### 1.4 Shimmer3 IMU Unit

- Price EUR 430; 51 x 34 x 14 mm; 9-DoF plus pressure; two accelerometers (low-noise or wide-range); microSD logging or BLE streaming. CONFIRMED https://shimmersensing.com/product/shimmer3-imu-unit/. Ranges, rates, battery capacity, operating temperature, IP rating: the product page states none of them (not verified).
- Time: onboard RTC is synchronised to the PC time via Consensys when connected or docked; the RTC is volatile; Bluetooth latency "mode of approximately 25 ms and maximum 100 ms"; maximum sampling 2048 Hz via Consensys with packet loss at high rates. REPORTED (search summary of Shimmer IMU User Guide https://shimmersensing.com/wp-content/docs/support/documentation/IMU_User_Guide_rev1.4.pdf, not read).
- Ski research: none found (not verified).
- Verdict (INFERRED): a credible raw-data research logger to compare against; cold rating and ingress unknown, so a bench test and vendor query are needed.

### 1.5 MbientLab MetaMotionS (and MetaMotionR/RL)

- BMI270 accel/gyro (+-2/4/8/16 g; 125 to 2000 dps), BMM150 magnetometer, BMP280, nRF52840, 4 Gbit NAND log memory, 32.768 kHz 20 ppm RTC crystal, micro-USB LiPo charger, 26 x 17 x 2.5 mm module. CONFIRMED, spec v0.5 https://mbientlab.com/documents/MetaMotionS-PS5.pdf (read locally).
- Module operating temperature -40 to 85 C (CONFIRMED, Table 4 of that spec). This is the electronics; the LiPo cell is a separate limit (INFERRED). Product page reportedly gives a 70 to 100 mAh LiPo, 2 h charge, IP40 case (REPORTED, https://mbientlab.com/metamotions/ via search summary).
- 20 ppm crystal alone implies up to about 72 ms drift per hour per unit, and up to about 144 ms per hour between two units if they are at opposite extremes (INFERRED arithmetic; real crystals are usually better).
- Sync: MbientLab's own community thread shows two MMR units diverging by about 275 ms over about 5 minutes with up to about 0.5 s variation, with the vendor advising users to match epochs "intelligently" in software. CONFIRMED (read), https://mbientlab.com/community/discussion/comment/10027 (anecdotal user report on an older MMR on firmware 1.5.0).
- Price: not verified. Ski research: none found.
- Verdict (INFERRED): cold-rated electronics and a big log memory, but IP40, small cell, and poor documented inter-unit timing.

### 1.6 WITmotion BLE sensors (WT901BLE5.0, BWT901BLE5.0, BWT901CL, WT9011DCL)

- Used by Prochazka and Charvatova (IEEE DataPort "Skiing Data Analysis", DOI 10.21227/qebb-nf33): "WITmotion sensors" recorded at 100 Hz, GNSS from Garmin at 1 Hz, turn points from GoPro video; no model number, no placement stated. CONFIRMED https://ieee-dataport.org/documents/skiing-data-analysis
- Specs I could source only second-hand: WT901BLE5.0 uses an MPU9250 (REPORTED, Newegg listing via search summary), operating temperature listed as "40 to 80" with the minus sign apparently lost (inference only); BWT901BCL listed -40 to +85 C, 200 Hz, +-16 g (REPORTED, manual via search summary); WT9011DCL-BT50 130 mAh, about 40 h, 0.2 to 200 Hz (REPORTED, retailer). Mapping of these to the unit Prochazka used is not verified.
- Sync: no cross-unit sync documented in anything I found (not verified).
- Verdict (INFERRED): cheap (tens of USD or EUR, price not verified), consumer-grade, MPU-family parts, which the project has already found drift. Useful only as a low-cost extra logger, not as the primary candidate.

### 1.7 Commercial ski sensors

- Carv 2: 6-axis accel + gyro, clips onto the boot strap, 34 g per sensor (REPORTED, Advnture review via search), "about five days" battery and USB-C charging, "waterproof, snowproof, fallproof", "We optimized the battery chemistry to work in even the coldest winter"; no numeric temperature range, mass or price on the page. CONFIRMED (page) https://getcarv.com/device. Consumer app, no documented raw data access (not verified). The page says placement on the strap can vary because the system "recalibrates each session", which is the same orientation-varies problem we have.
- Snowcookie: three sensors (chest plus two skis, not boots), each with IMU (3D acc, gyro, magnetometer), app reports "105 metrics", launch price USD 449 (2019). REPORTED, https://venturebeat.com/2019/01/08/snowcookie-unveils-sensors-to-track-105-things-about-your-skiing. Chip not verified.
- Atomic Connected Boot: sensors built into the boot using a Movesense board (CONFIRMED, Frontiers paper above).
- Verdict (INFERRED): closed, not research platforms. Carv's strap-clip approach and "recalibrate each session" is worth studying but you cannot get the raw stream.

### 1.8 Physilog (Gait Up)

- physilog.com lists the "sixth-generation" P6 but gave no specifications on the page I fetched (CONFIRMED absence, http://physilog.com/). Range, temperature, IP rating, battery and sync are all not verified. Earlier Physilog generations are widely used in gait and a Gait Up-affiliated ski-mountaineering validation reports relative errors near 5 percent on stride parameters using a ski-mounted sensor (REPORTED, https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5876535/ via search summary). Worth a vendor enquiry (INFERRED).

### 1.9 ActiGraph GT9X Link

- Primary accelerometer +-8 g at 30 to 100 Hz, secondary +-16 g, IP27, 14 days best case, 4 GB, price not verified, operating temperature not found. REPORTED (distributor copy of the spec, https://timik.no/product/link-gt9x). Verdict (INFERRED): activity-counts design, IP27 and low rate; not suitable.

### 1.10 Not researched adequately

Notch (only a 2013 TechCrunch article and a tennis validation found; specs not verified: https://techcrunch.com/2013/11/24/notch), Delsys, Catapult, Garmin, Suunto, Polar. For Polar Verity Sense I found 45 mAh, up to 30 h, WR50 and an SDK with accelerometer rates; the gyroscope availability and the accelerometer rate for that device are not verified (REPORTED, https://the5krunner.com/specs/polar/verity-sense/). Catapult/Delsys are team-sports/lab systems; no ski boot evidence found (not verified).

---

## 2. Boot-specific practicalities (part D)

### 2.1 Lithium cell operation at -20 C

- Li-ion charging window is 0 to 45 C and "No charge permitted below freezing"; plating of metallic lithium on the anode during sub-freezing charge gives permanent degradation in performance and safety; Li-ion discharge limit listed as -20 C to 60 C; internal resistance rises in the cold. CONFIRMED https://batteryuniversity.com/article/bu-410-charging-at-high-and-low-temperatures
- Vendor guidance for LiFePO4 (REPORTED, vendor blogs found via search; treat as indicative): charge current reduced to 0.1C below 0 C and 0.05C below -10 C; 85 percent or more discharge efficiency at 0.2C and -20 C; academic note: LiFePO4 initial capacity at -20 C about half of room temperature (REPORTED, J. Alloys Compd. 2013 via search summary).
- Primary lithium coin cell (CR2025): Panasonic datasheet (Feb 2026) gives nominal 165 mAh, 3 V, continuous drain 0.2 mA, operating -30 to +85 C, "excellent low-temperature performance". CONFIRMED https://energy.panasonic.com/dam/master/pdf/en/datasheet/lithium/CR2025_Datasheet_EN.pdf. The graph of capacity versus temperature is not readable as text, so the capacity at -20 C is not verified.
- INFERRED: a CR2025 is rated for 0.2 mA continuous, but BLE streaming with an IMU at 208+ Hz draws mA-level currents in bursts. Coin cells have high internal resistance, worse in the cold. The sensor's brown-out or radio resets in cold are the likely failure mode, not simple lack of capacity. Bench test needed.
- Movesense documentation itself warns that flash writes are more sensitive to low supply voltage than EEPROM (CONFIRMED, hw_variants page above). That combination (cold coin cell sag plus flash writes) is a specific risk for the Flash variant.
- Beuken-type custom ski sensors: a 7.2 V, 2.25 Ah pack "has been shown to operate upwards of twelve hours during sub-freezing temperature field testing"; "Lithium-ion was chosen for its ability to deliver power at low temperatures"; no numeric temperature given. CONFIRMED https://pmc.ncbi.nlm.nih.gov/articles/PMC10974825/ (a ski-mounted, much larger system).
- How products cope: Movesense uses a replaceable primary coin cell (CONFIRMED); Carv claims "battery chemistry" optimisation with no figures (CONFIRMED claim, no numbers); Xsens DOT and Awinda simply specify 0 to 50 C (CONFIRMED). None of the commercial products I read describe heaters.
- INFERRED practical measures: charge indoors only; keep the sensor inside a jacket or the heated room until putting boots on; thermal mass and body heat from the leg help (boot cuff sits on the outside of the liner though, so this is weak); consider two cells per day or a daily coin-cell swap with Movesense; prefer a cell chemistry and fuel gauge with a cold-rated datasheet if building your own.

### 2.2 Ingress protection

- Xsens DOT: IP68 (CONFIRMED). ActiGraph GT9X: IP27 (REPORTED). MetaMotionS: IP40 (REPORTED). Movesense: "water resistant to 30 m" with no IP code (CONFIRMED); Carv: "waterproof, snowproof" with no code (CONFIRMED). Shimmer3 IMU and WITmotion: not verified.
- INFERRED: the main risks on a boot are not immersion but ice and snow packing against the strap, thermal cycling that draws moist air into the case, and charging ports/ battery doors. Movesense's snap-stud interface and coin-cell door, and USB ports on Xsens/Mbient/Carv, are the weak points. A sealed, gasketed housing as in the Beuken design (press-fit TPU dome with gasket sealant, CONFIRMED in PMC10974825) is an option if building your own.

### 2.3 Mounting and vibration

- Cuff position used in published skiing work: "posterior upper cuff of each boot", in a rigid housing held by a tight strap that clamps on the cuff. CONFIRMED in Neuwirth (PMC7435691: clamping pressure "ensured that the sensor was not dislocated") and PMC7739568 ("tight elastic strap and a customized rigid housing to avoid movement or misalignments").
- Other published locations: IMeasureU units on the "posterior longitudinal axis of the boots", +-16 g/+-2000 dps at 1125 Hz, plus a +-200 g variant at 1600 Hz (CONFIRMED, https://pmc.ncbi.nlm.nih.gov/articles/PMC10379626/); two 6-DOF 1000 Hz IMUs on the back of the boot (ICSS 2023 abstract, https://lida.sport-iat.de/svd/Record/4081463); a commercial IMU board on each ski behind the boot (low-drift pilot, REPORTED via search summary of https://pmc.ncbi.nlm.nih.gov/articles/PMC8038258/).
- Nothing I found compares cuff versus shell versus tongue, and nothing tests foam or elastomer isolation (not verified). The only guidance is the ICSS 2023 suggestion that "mechanical dampening" could further improve results (CONFIRMED statement, untested).
- INFERRED engineering view: the cuff pivots relative to the shell, so a cuff-mounted sensor sees the shank angle (good for roll and turn timing of the lower leg) not the ski angle; a shell-mounted sensor sees the ski more directly but receives harsher shock and is on the buckle side of the boot. Stiff, minimum-mass rigid mounting protects against relative rotation (which matters more for orientation than vibration). Low-pass filtering at 10 Hz for orientation, as done in the ICSS work, removes most vibration content before the filter if you choose that (INFERRED); isolation foam that is too soft adds its own resonance in the band you care about.

### 2.4 Two-boot clock synchronisation

- Vendor hardware/protocol sync: Xsens Awinda 10 us within one receiver (CONFIRMED vendor claim). Xsens DOT 1.8 ms after 30 min / 10 ms after 60 min (vendor claim). Others not documented.
- Field method used in skiing: phone-side timestamping with half the round trip subtracted (Neuwirth, CONFIRMED, no accuracy given); jump-based alignment to video (PMC7739568, CONFIRMED).
- Literature on BLE sync (REPORTED unless noted): a KU Leuven paper cites connection-event-based BLE sync as "not very accurate", about +-750 us at the application level, and reports 200 us at low power and under 1 us at higher energy with a dedicated design (CONFIRMED, read, https://arxiv.org/pdf/2309.14867); a 2024 offline sync for ECG+IMU reports under 1 ms average offset versus over 100 ms before (REPORTED via search summary, not opened).
- Anecdote: consumer BLE IMUs from MbientLab showed hundreds of ms of non-constant offset over minutes (CONFIRMED anecdote above).
- INFERRED recommendation: whichever hardware, add an explicit sync event per session (for example three hard heel stomps or a ski-pole/boot clap at the start and end of every run block, as in the jump-based method). With a 6 h day, a two-point sync (start and end of day) gives a linear drift model; add mid-day events because the MbientLab anecdote suggests drift is not always linear. Turn timing "to a few tenths of a second" is far looser than any of these figures, so even phone-side timestamping at tens of ms is adequate for turn timing; tighter sync matters mainly for left/right comparison of roll.

### 2.5 Saturation and impact (ski-specific reports)

- Edge-angle drift when the accelerometer saturates on icy or bumpy slopes; gain adjustment helps; damping suggested. CONFIRMED, https://lida.sport-iat.de/svd/Record/4081463
- One ski-mounted system reported its +-16 g IMUs "never saturate", citing prior work suggesting IMUs "may experience upwards of 150 G". CONFIRMED, https://pmc.ncbi.nlm.nih.gov/articles/PMC10974825/ (ski-mounted, not boot-mounted). Neuwirth/Martinez boot sensors were set to +-8 g, so clipping must have been possible but was not reported.
- Another study carried a +-200 g, 1600 Hz option beside the +-16 g one, which signals that researchers anticipated impacts beyond 16 g on boots (CONFIRMED that both were mounted, https://pmc.ncbi.nlm.nih.gov/articles/PMC10379626/; their reason is not stated, so the motive is INFERRED).
- INFERRED: with +-16 g maximum on Movesense/DOT and clipping to be expected, the filter must gate the accelerometer (VQF already has rest/motion detection, but a clip flag from raw samples at full scale should force the accelerometer weight to zero). That is easier if you log at 208 Hz or above.

---

## 3. Decision table

| # | Option | IMU chip | Range | Cold rating | Battery | Sync | Raw access | Price | Evidence in ski research | Main risk |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Movesense Flash (or HR2/Active for streaming) | Not named by vendor (LSM6DS3 reported in a related paper) | +-2 to 16 g, +-125 to 2000 dps, to 833 Hz | Not stated; CR2025 -30 to +85 C | CR2025 replaceable; 6-axis life not published | No vendor sync spec; phone-side or post-hoc | Yes, SDK | EUR 249 Flash, EUR 99 HR2 (ex VAT) | Strong: 3 papers/product on boot cuff | Unpublished cold behaviour; clipping at 16 g; flash and low-voltage; BLE streaming only 33 to 64 Hz in prior work |
| 2 | Custom board, own design (ICM-42688 or similar, nRF52 + SD + cold-rated cell) | ICM-42688 | +-16 g typical | By design | By design | Own design, shared clock only if wired | Yes, full | Dev cost, not verified | Beuken ski-mounted system, sub-freezing 12 h | Engineering time; ingress and cell safety are on you |
| 3 | MbientLab MetaMotionS | BMI270 | +-16 g, +-2000 dps | Module -40 to 85 C; LiPo and IP40 are limits | 70 to 100 mAh LiPo (REPORTED), charge indoors | Poor in user reports (275 ms over 5 min) | Yes, SDK | not verified | None found | Cell and IP40; sync |
| 4 | Shimmer3 IMU | Not named on page | Selectable | not verified | Li-ion, mAh not verified | RTC via Consensys; BT latency 25 to 100 ms | Yes, SD or BLE | EUR 430 | None found | Cold and IP unknown |
| 5 | Xsens DOT | Not stated | +-16 g, +-2000 dps | 0 to 50 C | 70 mAh, about 8 h | Root-node, 1.8 ms/30 min claim | Yes (recording mode), 120 Hz max | not verified | None found | Out of temperature spec; 88 min recording cap |
| 6 | Xsens MTw Awinda | Not stated | +-16 g, +-2000 dps | 0 to 50 C | 6 h (12 h newer) | 10 us with station | Yes, 120 Hz wireless | not verified | None found | Needs receiver station; not for field |
| 7 | WITmotion WT901BLE / BWT901BCL | MPU9250 (REPORTED) | +-16 g | -40 to +85 C claimed (REPORTED) | 130 mAh (a related model) | Not documented | Yes | Low, not verified | Prochazka dataset 100 Hz | MPU-family drift; unverifiable specs |
| 8 | Carv 2 | 6-axis, not named | Not stated | "coldest winter", no number | About 5 days | Not applicable | No raw access known | not verified | Consumer; no papers found | Closed |
| 9 | Snowcookie | IMU, not named | Not stated | not verified | not verified | Not applicable | No | USD 449 (2019, REPORTED) | Ski-mounted, no papers found | Closed; ski not boot |
| 10 | ActiGraph GT9X | Not stated | +-8 g primary | not verified | 14 days best case | Not applicable | Yes | not verified | None for alpine | 30 to 100 Hz, IP27 |

---

## 4. Ranked recommendation

1. **Movesense Flash, loaded with your own logging firmware, as the first field candidate.** Reasons: it is the only ready-made unit with published boot-cuff ski work and a turn-timing validation; raw 6-axis up to 833 Hz at +-16 g/+-2000 dps; on-device 128 MB log removes BLE dropouts (the two ski papers had to patch over disconnections); user-replaceable primary lithium cell avoids the charge-at-cold problem; small (9.4 g, 36.6 mm); price is within budget. Use 208 Hz or 416 Hz logging (INFERRED fits one day), sync to a phone at start/end for clock offset, and use stomp/jump events for post-hoc alignment. Caveats: cold rating is unpublished; no IP code; no vendor multi-unit sync; clipping at 16 g.
2. **Build your own logger around a cold-capable cell** (second, in parallel). Cleanest answer to saturation, sync (one MCU reading two IMUs on a cable is the only truly synchronous design) and cold, but costs engineering time. Beuken's design is the precedent for sub-freezing operation, though on the ski, not the boot.
3. **Shimmer3 IMU** as a research-grade reference logger on the same boot, mainly to benchmark Movesense, provided Shimmer confirms the temperature and ingress ratings (EUR 430).
4. **MbientLab MetaMotionS** only if you need a rechargeable unit with -40 C electronics and are prepared to replace the case and cell.
5. **WITmotion** as a cheap supplementary logger only.
6. **Do not use:** Xsens DOT and MTw Awinda in the field (0 to 50 C; keep Awinda for bench sync reference), ActiGraph, Carv 2 and Snowcookie (no raw access, ski-mounted for Snowcookie).

---

## 5. What must be bench-tested before committing

1. **Cold soak**: sensor (with the actual cell) at -20 C in a freezer for 2 h, then run at the intended sample rate and logging mode for 6 h in a cold chamber or in a cooler with dry ice sensors. Record brown-outs, resets, BLE disconnections, gyro bias versus temperature, and flash-write failures (Movesense Flash). Repeat with two cell brands (for example Panasonic and Energizer).
2. **Battery life at your rates**: 6-axis at 208 and 416 Hz, logging and BLE mode, at 20 C and -20 C. The vendor publishes only 3-axis at 26 Hz.
3. **Logging capacity**: confirm the actual bytes per sample including timestamps and file system overhead in the Flash variant; verify no drop-outs "at high memory fill rates" (Movesense's note).
4. **Two-unit sync**: tap-sync both units, then rest and shake in a cold box for 6 h; measure offset versus time with several stomp events to see if drift is linear. Compare to the Awinda station if you have access.
5. **Clipping**: drop and impact tests from a ladder onto a boot on hard snow or a plank; log at +-16 g and, if possible, alongside a +-200 g reference (as in PMC10379626), to see how often 16 g is exceeded on a real boot; verify the clip flag works in your filter.
6. **Mounting and vibration**: compare rigid cuff clamp, cuff clamp plus 2 to 3 mm closed-cell foam, and shell mount on the same turns using a reference (e.g. a second IMU on the ski); look at gyro noise density in the 20 to 100 Hz band and at roll error after VQF.
7. **Ingress**: spray/ice test, a day of cold-to-warm cycling (outside to lodge), check for condensation inside; check the coin-cell door and USB port seals.
8. **Orientation robustness**: attach in three different mounting orientations and check that your auto-alignment still yields roll within a few degrees against a reference.
9. **Chip identity**: ask Movesense (info@movesense.com is on the spec sheet) which IMU part is in the current hardware and what temperature range is guaranteed; ask Shimmer and Gait Up (Physilog 6) for temperature and ingress ratings.

---

## 6. Source list (all URLs cited above)

- https://www.movesense.com/product/movesense-sensor-hr2/
- https://www.movesense.com/product/movesense-flash/
- https://www.movesense.com/wp-content/uploads/2020/12/Movesense-Spec-Sheet-12-2020.pdf
- https://www.movesense.com/wp-content/uploads/2023/10/Movesense-Flash-Sensor-Spec-Sheet-2023-10.pdf
- https://movesense.com/docs/system/hw_variants/
- https://www.movesense.com/news/2023/10/movesense-flash-is-an-ultralight-ecg-and-movement-data-recorder-for-standalone-operation/
- https://pmc.ncbi.nlm.nih.gov/articles/PMC7435691 (Neuwirth et al. 2020)
- https://pmc.ncbi.nlm.nih.gov/articles/PMC7739568/ (turn detection in the field)
- https://pmc.ncbi.nlm.nih.gov/articles/PMC6413051 (ergometer turn detection, via search summary)
- https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1495176/full
- https://pmc.ncbi.nlm.nih.gov/articles/PMC10974825/
- https://pmc.ncbi.nlm.nih.gov/articles/PMC10379626/
- https://pmc.ncbi.nlm.nih.gov/articles/PMC8038258/ (via search summary)
- https://lida.sport-iat.de/svd/Record/4081463
- https://www.xsens.com/hubfs/Downloads/Manuals/Xsens%20DOT%20Datasheet.pdf
- https://www.xsens.com/resources/blog/new-xsens-dot-release-is-here
- https://www.mouser.co.uk/new/xsens/movella-dot/
- https://www.xsens.com/hubfs/Downloads/Leaflets/mtw-awinda%20leaflet.pdf
- https://shimmersensing.com/product/shimmer3-imu-unit/
- https://shimmersensing.com/wp-content/docs/support/documentation/IMU_User_Guide_rev1.4.pdf
- https://mbientlab.com/documents/MetaMotionS-PS5.pdf
- https://mbientlab.com/metamotions/
- https://mbientlab.com/community/discussion/comment/10027
- https://ieee-dataport.org/documents/skiing-data-analysis
- https://getcarv.com/device
- https://venturebeat.com/2019/01/08/snowcookie-unveils-sensors-to-track-105-things-about-your-skiing
- http://physilog.com/
- https://timik.no/product/link-gt9x
- https://the5krunner.com/specs/polar/verity-sense/
- https://techcrunch.com/2013/11/24/notch
- https://batteryuniversity.com/article/bu-410-charging-at-high-and-low-temperatures
- https://energy.panasonic.com/dam/master/pdf/en/datasheet/lithium/CR2025_Datasheet_EN.pdf
- https://arxiv.org/pdf/2309.14867
