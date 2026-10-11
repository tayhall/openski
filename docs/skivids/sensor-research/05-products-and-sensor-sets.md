# 05 - Products, research systems and sensor sets for ski technique feedback

Research date: 2026-10-11. Scope: what commercial and academic systems sense, where, how well it is validated, and what that implies for OpenSki's sensor set (currently one MPU6050 IMU on the boot cuff strap).

Label key: **CONFIRMED** = I read it in the cited source. **REPORTED** = secondary source, marketing, review or forum. **INFERRED** = my reasoning, not stated by a source.

Search-tool caveat: much of the web content below was read through a summarising fetch tool, not as raw pages. Numbers marked CONFIRMED were returned from the cited page, but treat exact figures as "verify against the PDF before quoting in a paper".

---

## 0. Executive summary

1. **The most important finding: Carv itself dropped insole pressure sensing.** Carv 1 (2016 onward) had a 36-sensor capacitive insert plus a motion sensor. Carv 2 is a 6-axis IMU (accelerometer + gyro, no magnetometer listed) clipped to the boot strap, with no insole, and claims to be "just as accurate" (testimonial only). So the best-funded commercial player moved from "insole plus IMU" to "single strap IMU plus large ML model". That is close to OpenSki's PoC hardware. (CONFIRMED hardware: https://getcarv.com/device , https://www.nordicsemi.com/Nordic-news/2024/11/Motion-Metrics-Carv-2-employs-Nordic-nRF52832-SoC-and-nPM1100-PMIC?lang=en ; accuracy claim REPORTED/marketing.)
2. **A single boot-cuff IMU is academically validated for turn timing.** Martinez et al. 2019 report turn-switch precision of roughly 0.03 to 0.06 s against video using the gyroscope roll axis from one cuff IMU (lab ergometer, one skier, plus one 28-turn on-snow pilot). In the 2021 treadmill comparison of about 25 published methods, the cuff-IMU "Martinez CTD" method was among the best IMU methods (accuracy within 0.01 s). (CONFIRMED: https://pmc.ncbi.nlm.nih.gov/articles/PMC6413051 , https://pmc.ncbi.nlm.nih.gov/articles/PMC8038779)
3. **Edge angle from a boot IMU is plausible but not rigorously validated on snow, and is a boot/shank angle, not a ski edge angle.** The literature shows ~2 to 4 degree precision in lab and field for shank inclination with multi-sensor body suits; single-IMU Madgwick approaches have drift problems under accelerometer saturation, and the group that wrote them had no reference results yet. (CONFIRMED: https://lida.sport-iat.de/svd/Record/4081463 , https://sponet.de/sponet/Record/4097543?lng=en , https://lida.sport-iat.de/svd/Record/4064053?lng=en)
4. **Insole pressure adds genuinely new information (load, weight transfer, inside/outside foot, fore/aft) but commercial-grade insoles still underestimate total force vs force plates, and DIY Velostat/FSR insoles are poor at absolute force.** Use them for relative timing and distribution, not newtons. (CONFIRMED: https://www.jssm.org/jssm-10-754.xml-abst ; Velostat paper https://spiral.imperial.ac.uk/entities/publication/ebba7f4d-5a0f-417d-bc07-8d211cfb11ea)
5. **Recommended order:** (a) finish and validate the single-IMU-per-boot system, two boots rather than one (left/right consistency needs both); (b) add phone GNSS plus barometer logging for ground truth, speed, turn radius and slope; (c) only then a pressure insole experiment (cheap FSR array, relative signals), treating it as research, not a product feature; (d) ski-mounted IMU only for validation sessions, not a user feature.

---

## 1. Commercial products

### 1.1 Carv (Motion Metrics Ltd, UK)

| Item | Carv 1 (insole generation) | Carv 2 (current) |
|---|---|---|
| Sensing | 36 capacitive pressure sensors per insert (started at 48/foot, trimmed after finding unused midfoot sensors) plus a motion sensor; battery/BLE pack on outside of boot | 6-axis IMU (3-axis accel + 3-axis gyro); clip on boot strap; "sensor fusion"; calibrated each time you ski |
| Where | Insert ~3 mm thick under the liner on the boot board; cable to external pack (~7 mm) | One clip per boot on the strap, "one size fits all", rental/touring/race boots |
| Sample rate | 20 Hz (stated for the metric stream) | Not stated |
| Hardware | Not specified | Nordic nRF52832 SoC, nPM1100 PMIC, 300 mAh Li-ion, up to 5 days |
| Metrics | 12 (blog says 12; another post says 13): Balance (Early Forward Movement, Mid-Turn Balance); Edging (Edge Angle, Edge Similarity, Early Edging, Progressive Edge Build); Rotary (Parallel Skis, Turn Shape); Pressure (Outside Ski Pressure, Pressure Smoothness, Early Weight Transfer, G-Force); combined into "Ski:IQ" | Reviews list ~10 including edge angle, early edging, progressive edge build, turn shape, edge similarity, G-force; plus snow-type detection |
| Turn detection | "Highly-tuned AI model", automatic start/end; analysis per turn in under 130 ms | Same style; "Large Motion Model" trained on >500 million turns (company claim) |
| Accuracy | No figures. Ted Ligety testimonial: Carv 2 "just as accurate as the previous Carv with footbeds" | No figures; "highly accurate" |
| Independent validation | None found | None found |

Sources:
- CONFIRMED (company blog): https://getcarv.com/blog/how-carv-turns-your-skiing-into-data (36 pressure sensors, 20 Hz, 12 metrics, turn detection, Ski:IQ bands, no accuracy figures)
- CONFIRMED (company blog): https://getcarv.com/blog/how-we-designed-carv-to-fit-in-your-boots (capacitive principle: copper pad vs grounded plane across closed-cell silicone foam; 48 to 36 sensors; 3 mm insert; 3 to 5 day battery)
- CONFIRMED (company page): https://getcarv.com/device and https://getcarv.com/sensors (Carv 2: 6-axis, clip, 5 days, USB-C, no numeric accuracy)
- CONFIRMED (vendor): https://www.nordicsemi.com/Nordic-news/2024/11/Motion-Metrics-Carv-2-employs-Nordic-nRF52832-SoC-and-nPM1100-PMIC?lang=en (300 mAh, nRF52832, 6-axis IMU, ">500 million turns")
- REPORTED (reviews/forum): https://www.skitalk.com/threads/carv-2-for-every-boot-in-every-terrain.35359/page-4 (forum: edge angle may not be true snow-referenced angle; unverified opinions about insole vs no insole)

**Patents.** US 11,328,620 B2, "System and method for physical activity performance analysis", assignee Motion Metrics Ltd, inventors James Bruce Alexander Grant and Pruthvikar Reddy Mosali; provisional 62/162,342 filed 15 May 2015; application filed 13 May 2016; granted 10 May 2022. CONFIRMED (Google Patents, page text partly read, claims not read): https://patents.google.com/patent/US11328620B2/en . The description covers capacitive or resistive force sensors in insoles or bands, IMU with sensor fusion, **barometer, GPS and microphone for surface identification**, and ski metrics such as transition point of centre of mass between feet (percentage through turn), forward/back CoM time fraction, left/right bias, ski yaw difference, max ski roll per turn, and yaw peaks/troughs for turn detection. A second family member exists (https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12451026) and a "Methods for calibrating a motion and ground reaction force analysis system" document (https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/12366496); I did not read either (scanned PDFs). Useful for OpenSki: the patent text is a free catalogue of which derived metrics Carv thought worth computing.

**Price.** REPORTED, conflicting by outlet and date, and I did not verify against Carv's current store. US: annual plan around $249 including sensors (https://www.tomsguide.com/wellness/fitness/carv-review); alternatives of $129 sensors plus $119/yr for 6 days, or a $99 six-day pass with $19 extra days (same source, https://sendy.io/blog/carv-personal-ski-coaching-system); Stuff.tv says sensors alone were $199 (https://www.stuff.tv/review/carv-2-review). UK: GBP 199/yr season pass including sensors, or GBP 99 six-day pass plus one-off GBP 129 sensors (https://sheclicks.net/reviews/carv-review). Treat as "roughly 200 to 250 per year, subscription model". I found no "Carv Pro" tier.

**Honest caveats.**
- "Ski:IQ" and the "400 to 500 million turns" are proprietary, unvalidated in peer-reviewed literature as far as I could find.
- Carv 1 and Carv 2 sensor counts are inconsistent across sources (48, 36, 72 "pressure and motion sensors"); the company blog is the most specific.
- Carv 2 has no pressure sensor, so its "Pressure" family of metrics (if still shown) must be inferred from motion and priors. That is INFERRED, not stated by Carv.
- The boot is a poor proxy for ski edge angle (see section 3.2).

### 1.2 Atomic Connected / Connected Boot (Atomic, Salzburg Research, Univ. Salzburg, Movesense)

- Hardware: two IMUs in a strap on the upper posterior cuff, Movesense boards, accelerometer and gyro at 833 Hz internally, streamed over BLE at 54 Hz (iOS) or 33 Hz (Android). CONFIRMED: https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1495176/full
- Metrics: speed, edge angle, turn count and size, edge speed, G-forces, a 1-to-10 "ski quality / carving score" built by PCA. CONFIRMED: same Frontiers article; https://www.salzburgresearch.at/en/tools-methods/connected-boot-reference-system-for-alpine-skiing/ ; https://apps.apple.com/us/app/atomic-connected/id1611557296
- Availability: the app listing says the strap hardware is "Not available for sale", "For Research purposes only". CONFIRMED (App Store listing).
- Validation: 62 recreational skiers, Spearman 0.52 between self-rated carving ability and the score, self-rating 1.71 points above the algorithmic score; authors call it a pilot, many users only 2 days, no expert validation (instructor comparison cancelled). CONFIRMED (Frontiers article). So the score is validated only weakly, and only for style on a snowplow-to-carving axis.
- The 2019 CES concept "Hawx Connected" added force sensors in the liner plus a Movesense unit (REPORTED: https://wt-obk.wearable-technologies.com/2019/01/ces-2019-wearable-atomic-unveils-smart-connected-ski-boot/). The research product today appears to be IMU only.
- A 2025 conference paper defines "Edging Velocity" (how fast the ski tips onto the edge at turn initiation) on Connected Boot data: race carving > moderate carving > parallel steering, one expert skier only. CONFIRMED abstract: https://www.scitepress.org/PublishedPapers/2025/136651

This is the closest academic analogue to OpenSki: IMU in a cuff strap, both boots, phone app, turn-level metrics.

### 1.3 Snowcookie (Switzerland)

- Three "Oreo-sized" devices: one on the chest, two on the skis; "105 parameters"; iPhone app; $449 per set; developed with Intel and Red Bull Media House; used by racing teams. CONFIRMED (VentureBeat): https://venturebeat.com/2019/01/08/snowcookie-unveils-sensors-to-track-105-things-about-your-skiing
- No accuracy figures stated. Battery not stated.
- A PitchBook entry lists the company as out of business (status change 26 Apr 2026); I could not corroborate this. REPORTED/unverified: https://pitchbook.com/profiles/company/109062-46
- Relevance: the only commercial example of ski-mounted plus body-mounted IMUs. Its apparent failure to persist is weak evidence (INFERRED) that multi-device setup friction hurts recreational adoption.

### 1.4 Rossignol / PIQ boot sensor (2015 to 2016)

- 13-axis sensor on a boot strap; edge-to-edge speed, G-force, carving angle, air time, rotation; pre-order US$149 plus strap; stores data when no phone. REPORTED: https://newatlas.com/rossignol-piq-ski-sensor-review/42518 , https://www.iphoneincanada.ca/news/track-skiing-with-your-iphone/
- Reviewer said it seemed aimed at racers and repeat courses. Current availability not confirmed. No accuracy data found.

### 1.5 Xensr, XON SNOW-1, Phiz

- **Xensr**: I found nothing on it in search. Cannot report specs. A 2014 Santa Clara student thesis describes a similar ski/board-mounted GPS + flex + accel prototype but is not Xensr. (https://scholarcommons.scu.edu/mech_senior/42)
- **XON SNOW-1** (Cerevo): smart snowboard bindings with 5 sensors, BLE. REPORTED: https://xon.cerevo.com/en/snow-1/soen-1-2/ . Not verified further.
- **Phiz**: no results found; unverified.

### 1.6 Watches and phones (Garmin, Apple, Suunto, Slopes-style apps)

- Garmin fenix manuals: auto-run for downhill skiing/snowboarding; per-run time, distance, max/avg speed, total descent. CONFIRMED: https://www8.garmin.com/manuals-apac/webhelp/fenix8series/EN-SG/GUID-BBA99BEC-D9D8-431C-9360-D08C9FE11902-3621.html . User forum reports of inflated run counts and spurious top speeds on Descent Mk1 (REPORTED: https://forums.garmin.com/outdoor-recreation/outdoor-recreation/f/descent-mk1/157306/ski-app-problems).
- Apple Watch: watchOS 4.2 (2018) added workout APIs for ski/snowboard; third-party apps show vertical descent, run count, speeds, using altimeter. REPORTED: https://venturebeat.com/technology/apple-watch-series-3-now-tracks-skiing-and-snowboarding-stats-using-altimeter . I did not retrieve current watchOS built-in behaviour.
- Suunto: no direct source retrieved. (Suunto/Movesense hardware underlies Atomic's strap; REPORTED only.)
- None of these report turn-level technique metrics. Wrist placement and 1 Hz GNSS cannot see boot edging. INFERRED (supported by the above: no edge/turn metrics listed).

### 1.7 Resort tracking (EpicMix, Skiline etc.)

RFID lift-gate scans give vertical feet and lift counts, nothing about technique. REPORTED: https://www.rfidjournal.com/?p=83672 . Not relevant to sensor choice except as a possible context data source.

### 1.8 Research-grade insoles (Moticon, Novel pedar)

- **Moticon OpenGo (Insole3)**: 16 pressure sensors, 0.25 N/cm2 resolution, 65% area coverage, 6-axis IMU (+/-16 g, +/-2000 dps), 10 to 100 Hz, BLE, coin cell, total force error under 5% after individual calibration (vendor claim), 9 double sizes. Price not published (quote only). CONFIRMED (vendor page): https://www.moticon.de/insole3-specs/ . Ski-specific specs not given.
- **Novel pedar-x**: 99 capacitive sensors per insole, 50 Hz in the cited validation study; data logger. CONFIRMED only via a validation paper description: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4578601/ is a Moticon study; pedar details came via search summary, so REPORTED. No price found. Pedar is the system used in much ski biomechanics literature (INFERRED from the Nakazato/Spörri references, not verified for pedar specifically).
- Both are tens of thousands of currency units class in my recollection, but I found no source; **price UNKNOWN**.

---

## 2. Academic systems

| Group / paper | Sensors and placement | Metrics | Validation | Limits |
|---|---|---|---|---|
| Martinez, Jahnel, Buchecker, Snyder, Stoggl, Brunauer 2019, Sensors 19(4):902 | One LSM6DS3 IMU on posterior upper cuff of each boot (Atomic Hawx 130); 833 Hz raw, 64 Hz output | Turn-switch timing from gyro roll axis (3 Hz LPF) or gyro resultant | Ergometer, 1 expert skier, 3 raters on video; adjusted precision about 6 to 12 ms (abstract: 0.056 s and 0.063 s); on-snow pilot: 28 turns, count matched GNSS | One skier, constrained plane, on-snow = single run. Figures in text not fully consistent. https://pmc.ncbi.nlm.nih.gov/articles/PMC6413051 |
| Martinez, Snyder, Moore, Stoggl 2021, Sensors 21(7):2573 | Mocap, 7 IMUs, boot IMUs, pressure insoles (100 Hz), force plates, EMG; indoor ski treadmill | Compares ~25 published turn-switch methods | All within 0.25 s; ten within 0.05 s; best IMU methods Martinez CTD and Yamagiwa; best kinetic Sporri and Nakazato insole methods (accuracy within 0.01 and 0.03 s); video best overall | Treadmill not snow; 14 skiers, mostly juniors; reference itself not independently validated; GNSS methods tested with mocap position. https://pmc.ncbi.nlm.nih.gov/articles/PMC8038779 |
| Martinez, Nakazato, Scheiber, Snyder, Stoggl 2020, Frontiers | Force plates vs pressure insoles, 20 skiers | Turn-switch timing from both | Methods compared; sensors had been used interchangeably without direct comparison | https://www.frontiersin.org/articles/10.3389/fspor.2020.00002/abstract |
| Nakazato, Scheiber, Muller 2011, JSSM 10:754 | Pressure insoles vs portable force plates, 20 skiers (10 expert, 10 intermediate), steep and flat | Vertical GRF | Insoles underestimate outside-phase mean and max vGRF; sometimes overestimate during edge change; depends on phase, skier level, mode, pitch | https://www.jssm.org/jssm-10-754.xml-abst |
| Neuwirth, Snyder, Kremser, Brunauer, Holzer, Stoggl 2020, Sensors 20(15):4232 | Two Movesense IMUs per skier (one per boot cuff), phone GNSS 1 Hz; 2,063 turns, 20 advanced/expert skiers | Classify snowplow, snowplow-steering, drifting, carving; features include edge angle estimate, roll symmetry L/R, GNSS speed | Parallel vs non-parallel 95.9%; drifting vs carving: gradient boosted trees acc 0.953, random forest 0.948; snowplow vs snowplow-steering sensitivity only 0.69 | Experts only, 3 resorts, up to 10 cm fresh snow, one ski model, no powder/moguls. https://pmc.ncbi.nlm.nih.gov/articles/PMC7435691 |
| Kranzinger & Kranzinger 2024, Frontiers | Connected Boot (above) | 1 to 10 skiing quality score | 62 recreational skiers, r 0.52 vs self-rating | See 1.2. https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1495176/full |
| Fasel, Sporri, Gilgien, Kroll, Muller, Aminian 2016 (ISBS), 2017 (PLOS ONE, Frontiers Physiol.), 2018 (Science and Skiing VII turn switch) | 11 body IMUs (limbs, trunk, head), plus GNSS and ski-fixed IMU variants | Joint angles, CoM kinematics, turn switch | Knee flexion accuracy/precision 1.7/4.3 deg; joint centre position error <110 mm accuracy, <30 mm precision; simplest GNSS + one IMU turn switch method comparable to differential GNSS + multi-IMU. 2016 conclusion: calibration dominates; skis/arms orientation can be estimated but each parameter needs separate validity checking. https://infoscience.epfl.ch/record/247213?ln=en ; https://ojs.ub.uni-konstanz.de/cpa/article/view/6459 | Elite racers, body suit, calibration procedures |
| Snyder, Martinez, Brunauer, Stoggl 2020 (Science and Skiing VIII) edge angle estimation | Wearable system, simulated skiing | Edge angle from lower-leg IMU | Cited prior: Fasel 2018 shank inclination field accuracy about 1.1 deg (inside), -1.8 deg (outside) with precision about 2.6 to 2.9 deg. https://lida.sport-iat.de/svd/Record/4064053?lng=en | Snyder paper's own numbers not on the page I read |
| Hummel, Huber, Waibel, Menhorn, Spitzenpfeil 2023 and 2024/2025 | Two IMUs on boot backs; second setup IMU on ski and IMU on boot; Madgwick filter; 1000 Hz | Edge angle over full run; ski-vs-boot orientation deviation | 2023: no reference yet, drift when accelerometer saturates on ice/bumps. Earlier simulated study +/-1.96 deg limits of agreement (second-hand). 2025: compared with AI pose estimation, results pending. https://lida.sport-iat.de/svd/Record/4081463 ; https://sponet.de/sponet/Record/4097543?lng=en | Preliminary |
| Supej 2010, J Sports Sci | Full IMU suit + RTK GNSS | 3D kinematics | Lab-grade reference, not a field product (I did not read the paper) | https://uet.vnu.edu.vn/~ducnh/RefWorks/GEOSensor/geo30.pdf |
| Angle of attack (carving vs skidding) work, Salzburg Research | IMU for ski orientation + RTK-GNSS for velocity vector | Angle between ski heading and velocity | Validated on centrifugal rig then field; no numbers on page | https://www.digital-motion.at/?p=3006 |

Spoerri pressure insoles: I could not retrieve a Sporri-authored insole paper itself; it appears only as a citation in the 2020/2021 Salzburg papers, where the Sporri PI turn-switch method performs among the best kinetic methods. Treat Sporri-specific claims as REPORTED.

Gaps: "Martinez/Mueller" as a pair did not return a joint paper; the nearest are Martinez (Salzburg) and Muller (Nakazato/Scheiber/Muller 2011; Fasel/Sporri/Kroll/Muller/Aminian).

---

## 3. What each extra sensor adds beyond one boot IMU

### 3.1 Baseline: one boot-cuff IMU (OpenSki PoC)

Supported by evidence:
- Turn switches and turn duration: very good (ms to tens of ms precision on gyro roll axis). CONFIRMED (sources above).
- Turn count, left/right turn duration asymmetry, rhythm: derivable (INFERRED, direct consequence).
- Skiing style class (snowplow / drifting / carving) with phone GNSS: ~95% parallel vs non-parallel on experts. CONFIRMED (Neuwirth).
- Roll angular velocity, G-load proxies, edging velocity: yes.
- Static/dynamic shank (cuff) tilt: yes, with drift and calibration caveats.

Not supported or weak:
- Absolute ski edge angle (boot-to-ski offset from boot flex/lateral cant, binding, ski deflection): not measured; a boot-cuff angle is a proxy. INFERRED; Hummel 2025 is literally testing boot vs ski IMU orientation deviation to quantify this.
- Load, weight transfer, outside-ski pressure: not observable directly.
- Speed, turn radius: need GNSS.

Hardware note on MPU6050 (INFERRED from general knowledge, not from a researched source here): it is a dated 6-axis part, +/-2000 dps gyro and +/-16 g accel, no magnetometer. For the turn-switch task the cited work used a 3 Hz filtered gyro, so bandwidth is not the constraint. Gyro saturation and accelerometer clipping on impact are the realistic concerns. A newer low-noise IMU (the papers used LSM6DS3 and Movesense) is a cheap swap; I did not research part-level noise comparisons.

### 3.2 Second IMU on the ski (or one on the boot plus one on ski)

- Adds: true ski orientation, boot-minus-ski angle (shows how much the boot "lies" about edge angle), ski vibration/chatter (a skidding proxy), ski-referenced roll. Fasel's ski-fixed IMU work exists for cross-country and ski-mountaineering (https://infoscience.epfl.ch/record/247213 related; Journal of Biomechanics 2016 uphill, slope accuracy 0.40 deg, REPORTED via search). I found no validated alpine edge-angle accuracy for ski-mounted IMUs in the sources retrieved.
- Cost: low in parts, high in practicality (mount on ski top, impact, cold, snow, binding interference, pairing, skis differ). Snowcookie put sensors on skis; commercial persistence unclear.
- Verdict (INFERRED): excellent for validation sessions to quantify the error of boot-based edging, not for a user feature.

### 3.3 Insole pressure (FSR, capacitive, Velostat)

- Adds things IMU fundamentally cannot: load timing (when does weight transfer to the new outside ski), outside/inside ski loading, fore/aft centre of pressure (balance), pressure smoothness, left/right load symmetry. These are exactly what Carv 1 listed as its Pressure and Balance categories and what the Carv patent describes (transition point, forward/back CoM, left/right bias).
- Turn timing from insoles is as good as IMU (Sporri PI and Nakazato PI within 0.01 to 0.03 s on treadmill). So insoles do not improve turn detection much. CONFIRMED (Martinez 2021).
- Absolute force is unreliable even for commercial insoles: Nakazato 2011 found insoles underestimate vGRF vs force plates and the error varies by phase, skier level and pitch. CONFIRMED. A forum claim that Carv 2 works better without insoles is anecdotal (https://www.skitalk.com/threads/carv-2-for-every-boot-in-every-terrain.35359/page-4) and should be disregarded as evidence.
- Boot environment issues (INFERRED from Carv's own design blog): thin profile needed so as not to ruin boot fit, sensor foam compression space, cable routing, external battery/BLE because under-foot wireless is poor, damp and cold. https://getcarv.com/blog/how-we-designed-carv-to-fit-in-your-boots
- Boot-liner-level shear and ski-boot rigidity: insole sees plantar load only, not forces via cuff/shin (Martinez 2021 limitations: "Insoles measure only plantar pressure, not all force transmitted to the ski").

### 3.4 Barometer

- Adds: vertical speed and descent/slope estimate, run segmentation (lift vs descent), terrain steepness when fused with GNSS speed. Carv's patent lists barometer for surface identification (https://patents.google.com/patent/US11328620B2/en). Garmin/Apple use altimeters for run descent stats. REPORTED/CONFIRMED as above.
- Does not add technique information directly. Very cheap (BMP280/BMP390 class) and trivial to wire. Note boot cuff placement is fine; pressure ports must not be blocked. INFERRED.

### 3.5 GNSS

- Adds: speed, turn radius, slope orientation, run location, ground truth for turn count and style. Neuwirth used a 1 Hz phone GNSS; Fasel found a single IMU + GNSS performed about as well as differential GNSS + multi-IMU for turn switch. CONFIRMED/REPORTED as above. Consumer GNSS validation: AdMos receiver 3% median turn detection error vs differential GNSS (REPORTED: https://nih.brage.unit.no/nih-xmlui/handle/11250/2986927).
- Phone GNSS is free (the phone is already required). A dedicated multi-constellation chip in the boot adds little unless it logs above 5 to 10 Hz. INFERRED.
- Valuable mainly for: normalising edge angle vs speed and radius ("what good looks like" depends on conditions), and for generating training labels.

### 3.6 Magnetometer / full 9-axis

- Carv 1 had a magnetometer (REPORTED, e.g. https://www.electronicspecifier.com/products/sensors/carv-up-the-snow-with-the-winter-wearable-digital-ski-coach/ ). Carv 2 lists none. Boot environments with steel buckles and binding make magnetometer heading unreliable (INFERRED). Skip.

### 3.7 Body-mounted IMUs (chest/pelvis, Fasel-style full body)

- Adds: upper-body angulation, CoM, separation hips/shoulders, true edge angle through full chain. Best science, worst usability. The cited Snowcookie single chest unit was an attempt at a compromise. Fasel 2016: calibration dominates error and each parameter must be validity-checked. Out of scope for a boot-sensor project except for a single chest IMU as a later option (INFERRED).

---

## 4. DIY feasibility (insole)

Evidence from the literature and community:
- Velostat bench study: mean accuracy error 110 kPa, cyclic drift, thermal drift up to 67% change in voltage range; still gave plausible standing/walking patterns. CONFIRMED: https://spiral.imperial.ac.uk/entities/publication/ebba7f4d-5a0f-417d-bc07-8d211cfb11ea
- FSR repeatability concerns, hysteresis, drift and temperature sensitivity are documented; Arduino forum cites 15% to 25% part-to-part repeatability for FSRs. REPORTED: https://forum.arduino.cc/t/accurate-pressure-sensors/328795 , https://oandplibrary.org/poi/1997_03_179.asp . A DIY smart insole write-up uses an FSR array with ESP32: https://jpralves.net/post/2020/08/27/a-diy-smart-insole-to-check-your-pressure-distribution.html (REPORTED, not read in full).
- Cold: no source retrieved on FSR/Velostat at -10 C. Carv explicitly "optimised battery chemistry for cold". I could not verify DIY cold performance; assume bad until tested. INFERRED.
- Prior art: no published accuracy for a full DIY ESP32 insole build was found.

Practical build (INFERRED engineering judgement, not sourced):
- 4 to 8 FSRs per foot at heel, midfoot lateral/medial, forefoot lateral/medial (what matters for edging is medial vs lateral and fore vs aft, not a full map; Carv itself cut from 48 to 36 and then dropped the insole).
- Under the liner on the boot board like Carv, or a very thin insole sheet; wiring exits at the top of the cuff to an ESP32 on the cuff along with the IMU; keep it all on one clock (important for timing).
- Calibrate per skier with a stand-on-one-foot and weight-shift routine; use normalised signals (fraction of total, percentage of body weight on foot) not absolute force.
- Expect: usable for relative timing and outside/inside ratio; not usable for kN force. Dominant risks: mechanical durability under shell pressure, drift, boot fit, sweat.

Alternatives: Moticon OpenGo as a one-off research instrument to validate a DIY insole (price unknown, quote only), or a loan from a university.

Time/cost estimate (INFERRED, order of magnitude): IMU-only second boot: parts about tens of currency units, 1 to 2 weekends. Barometer: negligible. FSR insole prototype: about 20 to 60 currency units per foot parts, 2 to 6 weeks of iteration to get something durable. Do not treat these as researched numbers.

---

## 5. What does "what good skiing looks like" require?

Evidence that the community focuses on a small set of measurable constructs:
- Edge angle and its build progression, early edging (Carv metrics; Atomic score; Edging Velocity paper). Need IMU orientation, ideally ski-referenced, validated.
- Weight transfer timing and outside-ski loading (Carv Pressure/Balance; patent transition point). Need pressure/force or a model-based inference.
- Turn shape/symmetry left vs right (Neuwirth roll symmetry feature; Carv Edge Similarity). Need two boots (the 2019 Martinez paper found one boot vs two boots give similar timing precision, but left/right comparison needs both, INFERRED).
- Parallel skis, rotation (Carv Rotary): needs both boots (yaw differential), as in the patent's ski-yaw-difference metric.
- Carving vs skidding: best from angle of attack (ski orientation + velocity vector from GNSS) or from ML on IMU+GNSS (Neuwirth 95% on experts).

Consequences for OpenSki goals:
1. Turn detection and timing: single cuff IMU is sufficient and validated. Nothing else needed.
2. Edging per turn: a boot IMU gives a reasonable relative signal (per-skier consistency, left vs right, build rate). Absolute angles need caution and ideally a ski-referenced validation. GNSS helps contextualise.
3. Left/right consistency: needs both boots with synchronised clocks, otherwise not possible.
4. Load/pressure distribution: needs insoles (or force) and is the least mature and most costly dimension; Carv 2's exit from insoles suggests the commercial value does not justify the hardware pain for recreational users (INFERRED; Carv has not said why).

---

## 6. Decision table

Cost and difficulty are relative to a hobbyist with ESP32 skills; INFERRED unless a source is attached.

| # | Sensor set | Turn timing | Edging per turn | L/R consistency | Load/pressure | Speed/radius/slope | Cost | Build difficulty | Evidence base | Build order |
|---|---|---|---|---|---|---|---|---|---|---|
| A | 1 IMU on 1 boot (current PoC) | Excellent (ms-level, Martinez 2019) | Proxy only, one side | No | No | Needs phone GNSS | Lowest | Lowest | Strong for timing; edging weak | Done |
| B | 1 IMU per boot, synchronised | Excellent | Proxy, both sides | Yes (turn asymmetry, roll symmetry per Neuwirth) | No | Needs phone GNSS | Low | Low-medium (time sync, BLE/ESP-NOW) | Strong (Connected Boot, Carv 2 architecture) | **1** |
| C | B + phone GNSS + barometer logging | Excellent | Proxy plus context-normalised | Yes | No | Yes | Low (barometer cents-to-few-currency units) | Low-medium (log alignment) | Strong (Neuwirth, Fasel GNSS+IMU) | **2** |
| D | C + FSR/Velostat insole, 4 to 8 zones per foot, relative signals | Excellent | Proxy | Yes | Relative load transfer timing, in/out, fore/aft; not absolute | Yes | Low-medium | High (durability, drift, boot fit, cold) | Weak for DIY; medium for commercial (Nakazato underestimation) | **3** |
| E | C + ski-mounted IMU (validation use) | Excellent | Better: ski-referenced, boot-ski offset | Yes | No | Yes | Low-medium | Medium-high (mount, shock, cold) | Limited alpine validation found | **4 (validation only)** |
| F | Commercial insoles (Moticon, pedar) for ground truth | Excellent | n/a | Yes | Yes (better but still underestimates force) | n/a | High / unknown, quote only | Low technically, high financially | Strong (research standard) | Borrow, don't buy |
| G | Full body IMUs (Fasel-style) | Excellent | True edge via chain | Yes | No | Yes with GNSS | High | Very high | Strong in racing research | Out of scope |

Bottom line: the cheapest path to deliver three of the four stated goals (turn detection, edging per turn, left/right consistency) is **B then C**. Load/pressure is the only goal that truly needs a new sensor type (D), and it should be pursued as an experiment with per-skier calibration and relative metrics, not as a promised feature. Ski-mounted IMU (E) is worth one or two sessions to measure how far boot-derived edging deviates from ski-derived edging.

---

## 7. Known gaps and unverified items

- Carv 1 accuracy and Carv 2 accuracy: no independent test found.
- Carv patent claims not read (text truncated); description details only.
- Carv current retail prices unverified; sources conflict.
- Xensr, Phiz: no information found. Suunto ski metrics: not retrieved.
- Sporri insole paper itself and Supej 2010 paper not read; referenced via citations.
- Moticon and pedar prices: unknown.
- No source retrieved for FSR/Velostat performance at cold temperatures.
- Snowcookie company status unconfirmed.
- MPU6050 statements here are from background knowledge, not a sourced review.
