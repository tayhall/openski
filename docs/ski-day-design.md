# Ski day: whole-day tracking, run and lift splitting, resort data (design)

Status: design, 19 October 2026. Step 1 (the pure engine) is built and JVM-tested on synthetic days only; see "Engine" at the end. Everything is experimental and unvalidated on a real day on snow.

## Why

Today the app records phone GPS only inside the "ready at the top" run flow, and shows distance, top speed and vertical drop for that run. The track is not exported. A skier wants a whole day: which runs, which lifts, how long on each, with names and grades, and with the boot data (turns, cadence, left-right agreement, torso metrics) attached to each run. The boots add what a GPS-only app cannot.

## Goals

1. Track a whole day from the phone's location (and barometer where present) and split it into descents, lifts and rests.
2. Name each descent and lift by snapping to public piste and lift geometry from OpenStreetMap, cached on the phone.
3. Let the user tag a day and its runs (conditions, notes, free text), and attach each run's boot-derived numbers when boot data exists for that time.
4. Import GPX tracks (the user's own data) and export the day as GPX plus JSON.

## Non-goals and anti-goals

- No turn classification, edge angle or technique scoring. GPS numbers are GPS numbers; the boot metrics stay experimental.
- No scraping or bulk use of any commercial app's data. Only the user's own exported files and the open map data.
- No redistribution of OpenStreetMap extracts by us: the app fetches to the user's phone only.
- No naming of commercial ski apps in code or docs.
- Raw GPS fixes are stored and exported untouched; the splitting is derived and always reproducible from the raw fixes.

## Resort data (decision: download once, cache on the phone)

- The user picks a resort by name search or by current position. The app fetches pistes, lifts and lift stations for that area over Wi-Fi from the public Overpass service in one query, and stores them. It never queries live while skiing.
- Query keys: `piste:type=downhill` (ways and areas, direction of the way is downhill), `aerialway=*` ways and `aerialway=station` nodes. Keep only name, `piste:difficulty`, `ref`, `aerialway` type, `operator`, geometry and the OSM id and version.
- Refresh only on request or when the cached copy is older than about a season.
- Licence: OpenStreetMap data is under the Open Database Licence (attribution, share-alike on derived databases). Show "(c) OpenStreetMap contributors" in the resort screen and in exports that contain names or geometry from it. Do not host extracts. The public service has fair-use limits: one bounded query per resort, a clear User-Agent, a timeout, and no retry storms.
- Storage: new SQLite tables (database version 12): `resorts(id, name, osm_area, bbox, fetched_at_ms, source_note)` and `resort_ways(resort_id, osm_id, kind, name, ref, difficulty, aerialway, geometry)` with geometry as an encoded polyline of lat/lon. Needs a migration and an instrumentation check; deleting a resort deletes its ways.

## Splitting a day (pure, JVM-tested)

Input: GPS fixes (existing `GpsFix`, filtered with `RunTrack.usable`) and optional barometric altitude. Output: ordered segments of kind DESCENT, LIFT or REST, each with start, end, distance, vertical, mean and top speed.

- Smooth altitude (barometric when present, GPS altitude otherwise) and compute vertical speed over a window.
- DESCENT: sustained negative vertical speed with ski-like ground speed. LIFT: sustained positive or flat travel at lift speed, ideally along a known lift line. REST: near-stationary. Use hysteresis and minimum durations so a short traverse or a pole push does not split a run, and merge adjacent segments of the same kind.
- When resort geometry is available, a candidate lift segment is confirmed by snapping to an aerialway line, and a descent by snapping to a piste line.
- Thresholds are starting values to be tuned on real days; keep them as named constants in one config class with a doc comment each.
- Uncertainty is explicit: segments carry a confidence note (for example "altitude from GPS only", "no resort map").

## Naming by map-matching (pure, JVM-tested)

- Project fixes onto candidate piste or lift polylines within a distance tolerance (relative to reported accuracy), use direction (piste ways run downhill) and continuity to choose between nearby candidates, and return the best-matching named way plus a match score. A descent may cover several pistes in sequence: return the ordered list with covered fraction.
- Return "unmatched" rather than guess when the score is low.

## Tags

- Day tags and run tags in the database: free text note, a small set of condition tags the user picks (snow, visibility, crowd), and a "drill" tag. Tags never alter raw data.
- Boot link: a run's time range selects the boot metrics for that range from the existing turn-metrics, boot-agreement and torso-metrics outputs (counts, cadence, mean agreement). Blank when there is no boot data for that time.

## Import and export

- GPX import of the user's own tracks (tracks and segments, time, elevation) into a "day" with `source = gpx`; no boots. Parse defensively: reject files with no usable times.
- Export: `ski-day.gpx` (raw fixes) and `ski-day.json` (segments, names with match scores, tags, attribution, thresholds used, all labelled experimental). The existing session ZIP is unchanged; the day export is its own file.

## Build order

1. Pure engine: day splitting, map-matching, GPX read/write (no network, database or UI).
2. Resort data: query builder, response parser, database v12 and repository, fetch client behind an interface.
3. Integration: whole-day tracking mode in the service (foreground location, battery-aware sampling, barometer), a day screen and a resort picker, tags, export, docs, acceptance checks.

Steps 1 and 2 are independent and can run in parallel. Step 3 follows both.

## Open points

- The Overpass query for a "resort" is area-based and resort boundaries are inconsistent in OSM; the first version uses a bounding box around the chosen point (radius the user can widen), not a boundary relation.
- Barometer availability and calibration vary by phone; fall back to GPS altitude.
- Whole-day battery use of location tracking is unmeasured.
- None of the thresholds have seen real ski data.

## Engine (step 1, built)

Pure Kotlin in `android/app/src/main/java/com/openski/android/`, no Android types, network, database or UI:

- `SkiDaySplitter` takes `GpsFix` lists (filtered with `RunTrack.usable`) and an optional `BaroSample` series and returns ordered DESCENT, LIFT and REST segments that tile the day, except across a signal gap over 2 minutes, which is a hard boundary and counts nothing (start, end, distance, signed vertical, mean speed over moving time, held top speed, map confirmation, notes such as "altitude from GPS only" or "no resort map"). Per fix it median-filters altitude, takes ground speed from a 10 s chord on smoothed positions and vertical speed from a least-squares fit, labels with hysteresis, and gives flat travel a kind: on a mapped lift line it is lift (checked first, and with a map this includes stretches held as descent or lift only by the hysteresis band), next to a descent it is descent, next to a lift it is lift, otherwise rest. It then absorbs too-short runs into their neighbours, bridges short rests between two of the same kind and merges. Without usable altitude (or with GPS altitude on fewer than 80 % of fixes), moving time is a descent unless it lies on a mapped lift line, and every segment says so.
- `PisteMatcher` snaps a segment's fixes to `ResortWay` lines of the right kind (`ResortMap.kt`: `LatLon`, `WayKind`, `ResortWay`, `ResortMap`) with a Viterbi pass: closeness within a tolerance that grows with reported accuracy, a cost for running against the way's direction, and a cost for switching way. It returns the ways in order with covered fraction and score, or UNMATCHED below the score threshold. Each `ResortMap` builds a `ResortIndex` once (ways projected into a frame centred on the map, plus a 100 m grid of their line segments) and every split and match on that map reuses it: 30 000 fixes against 600 ways take well under a second on a desktop JVM (unit test bound: 10 s).
- `GpxCodec` reads GPX tracks with the JDK/Android DOM parser (namespaces, prefixes, missing elevation, unknown extensions, zone offsets; times with no zone are taken as UTC and counted; negative accuracy or speed is treated as missing) and rejects files with no usable times. Hardening: input over 20 MB is refused before reading, out-of-memory becomes a clean rejection, and any text containing a document type or entity declaration is refused before the parser sees it; the parser's DTD and external-access switches are also set where the platform accepts them (all of them on the JDK, unit-tested; Android's parser rejects unknown switches, so there the pre-parse refusal is the protection, checked on a phone per `docs/android-acceptance.md`). It writes raw fixes as GPX 1.1 with accuracy, vertical accuracy and speed in an `openski` extension (plain decimals, control characters stripped from the name), so a round trip returns identical fixes. Imported points without accuracy get 10 m so the usual filter keeps them.
- `SkiDayExport` builds `ski-day.json` (segments, match status, ways with scores, thresholds, experimental note, and "(c) OpenStreetMap contributors" whenever a way was matched). Non-finite numbers become null.
- Flat frames (`LocalFrame`) are centred on the bounding box of the track or map. East-west scale is off by about tan(latitude) times the north-south distance from the centre in radians: about 0.1 % at 7 km and 0.2 % at 15 km at 46 degrees. Areas crossing the 180 degree meridian are not supported.

All thresholds live in `SkiDayConfig` (exported under `thresholds`). Starting values, chosen from typical lift and skiing speeds and phone GPS noise; none tuned on a real day:

| Threshold | Value | Meaning |
|---|---|---|
| `position_smooth_s` | 5 s | Moving average on positions for the moving-or-resting speed (GPS jitter). |
| `speed_half_window_s` | 5 s | Half-width of the chord giving that ground speed. |
| `distance_smooth_s` | 0 s (raw fixes) | Averaging for distance, mean and top speed. Any smoothing cuts turn corners (5 s read 89 %, 3 s 94 % of the synthetic path); raw fixes read 100 to 103 % (jitter). Steps count only while moving. |
| `altitude_median_s` | 5 s | Running median on altitude before anything else, removing single-fix spikes. |
| `gps_vertical_window_s` | 30 s | Least-squares window for vertical speed from GPS altitude (noisy, so wide). |
| `baro_vertical_window_s` | 15 s | Same with barometric altitude (quieter, sharper). |
| `endpoint_average_s` | 6 s | A segment's vertical reads each end off a straight-line fit of the 6 s inside that end, so a steady climb or fall is not pulled toward zero. |
| `max_signal_gap_s` | 120 s | A longer gap ends the segment; both sides are split separately and the gap adds no distance or time. Shorter gaps count as a straight line and are noted. |
| `rest_enter_mps / rest_exit_mps` | 0.8 / 1.5 m/s | Ground speed hysteresis: below 0.8 a skier rests, above 1.5 moves again. Queues shuffle at about 0.3, lifts run at 2 or more. |
| `descent_enter_mps / descent_stay_mps` | 0.4 / 0.1 m/s down | Vertical speed that starts a descent / below which it becomes flat travel. Pistes give 1 to 3 m/s down. |
| `lift_enter_mps / lift_stay_mps` | 0.4 / 0.1 m/s up | Same for lifts; chairlifts climb about 1 to 2 m/s. |
| `min_descent_s / min_lift_s / min_rest_s` | 30 / 60 / 60 s | Shorter runs are absorbed into their neighbours (a dip, a pole push or short climb, a brief stop). |
| `bridge_rest_s` | 180 s | A rest shorter than this between two descents (or two lifts) joins them: a stop mid-run, a lift that halts. |
| `max_vertical_accuracy_m` | 30 m | GPS altitude with worse reported vertical accuracy is not used (unknown accuracy is accepted, as in GPX). |
| `min_altitude_fixes` | 10 | Fewer usable altitudes means no altitude. |
| `min_gps_altitude_coverage` | 0.8 | GPS altitude is used only when 80 % of fixes carry a usable one; otherwise no altitude, with a note. |
| `min_baro_coverage / baro_max_gap_s` | 0.9 / 10 s | The barometer is used only if it covers 90 % of fixes (a reading within 10 s on both sides); otherwise GPS altitude, never a mix. |
| `top_speed_hold_s` | 2 s | A top speed must be held this long (reported speed preferred, else position chord). |
| `note_stopped_s` | 10 s | Stopped time inside a descent or lift above which the segment notes it. |
| `piste_base_tolerance_m / lift_base_tolerance_m` | 30 / 15 m | Matching tolerance before accuracy: pistes are centre lines of wide runs, lifts follow their line. |
| `accuracy_factor` | 1.5 | Times the fix's reported accuracy added to the tolerance. A fix with no usable accuracy is never matched. |
| `direction_min_speed_mps` | 1 m/s | Slower than this the travel direction is unknown and not compared. |
| `wrong_way_cos / wrong_way_cost / wrong_way_score_factor` | -0.2 / 2 / 0.5 | A fix whose travel runs against the way (pistes downhill, lifts up) costs more and scores half. |
| `switch_cost / unmatched_cost` | 4 / 1.5 | Cost of changing way (continuity) and of an unmatched fix (equal to a fix about 0.87 tolerances away). |
| `min_way_fraction` | 0.1 | A way covering less of the segment is dropped from the list. |
| `min_match_score` | 0.5 | Below this the segment (or a listed way) is unmatched rather than guessed. |
| `map_confirm_fraction` | 0.5 | Fraction of fixes on a way of the right kind for the map to confirm a lift or descent, or to claim flat travel as lift. |

Tested (JVM, synthetic): a day of three descents, two lifts and rests with correlated GPS position and altitude noise, linked turns, a flat traverse, a 30 s stop mid-descent, a lift that stops for a minute, an uphill pole push, a flat run-out, a queue, and a descent straight into a lift with no queue. Over 20 noise seeds each, with GPS altitude and with the barometer, the worst inner boundary error is 9 s (asserted at 10 s or less). Verticals are within 3 m + 3 % with the barometer (worst seen 4.3 m); with GPS altitude a further 15 m is allowed for the drift of the 5 m altitude noise at each end (worst seen 19 m, about 5 %). Descent distance on raw fixes reads 100 to 103 % of the path. Also tested: a flat stretch on a mapped lift line right after a descent, a 90 s signal gap bridged inside a lift and a 5 minute gap splitting it, partial barometer coverage, sparse GPS altitude, no altitude with and without a map, NaN and missing fields, parallel pistes 25 m apart, direction deciding between equally close pistes, a descent over two pistes in order, unmatched tracks, zero tolerance and unusable accuracy, a large map, GPX round trips, malformed files, DTDs, oversize input and byte order marks.

Known limits:

- Nothing has seen real ski data. Real GPS under lift cables, in tunnels and gondola cabins, and real barometer drift with weather are not modelled.
- Distance on raw fixes includes GPS jitter while moving: +1 to +3 % with 3 m of drifting noise at 1 Hz, more if a phone's noise is jumpier.
- Boundaries lag or lead by up to about 10 s because of the smoothing windows; the vertical-speed filter alone lags by several seconds.
- Drag lifts, flat or downhill lift sections without a map, skinning or walking uphill, very slow skiers (under 1.5 m/s) and long flat cat-tracks between lifts are untested and likely mislabelled; a long walk on the flat is labelled rest.
- A rest under 3 minutes between two descents joins them, so a descent, a short wait and another descent without a lift count as one run.
- Pistes mapped as areas, or ways drawn against the OSM direction convention, score low and come back unmatched. With a map, a flat stretch of a descent that runs along a lift line in the lift's direction would be taken as lift.
- GPX is read whole into memory (DOM, capped at 20 MB). A file declaring a non-UTF encoding without a byte order mark is decoded as UTF-8.
