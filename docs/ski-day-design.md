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

- `SkiDaySplitter` takes `GpsFix` lists (filtered with `RunTrack.usable`) and an optional `BaroSample` series and returns ordered DESCENT, LIFT and REST segments that tile the day (start, end, distance, signed vertical, mean speed over moving time, held top speed, map confirmation, notes such as "altitude from GPS only" or "no resort map"). Per fix it smooths positions, takes ground speed from a 10 s chord and vertical speed from a least-squares fit, labels with hysteresis, gives flat travel the kind of its context (descent next to a descent, lift on a mapped lift line or next to a lift, rest otherwise), absorbs too-short runs into their neighbours, bridges short rests between two of the same kind and merges. Without any altitude, moving time is a descent unless it lies on a mapped lift line, and every segment says so.
- `PisteMatcher` snaps a segment's fixes to `ResortWay` lines of the right kind (`ResortMap.kt`: `LatLon`, `WayKind`, `ResortWay`, `ResortMap`) with a Viterbi pass: closeness within a tolerance that grows with reported accuracy, a cost for running against the way's direction, and a cost for switching way. It returns the ways in order with covered fraction and score, or UNMATCHED below the score threshold.
- `GpxCodec` reads GPX tracks with the JDK/Android DOM parser (namespaces, prefixes, missing elevation, unknown extensions, zone offsets; times with no zone are taken as UTC and counted; document type declarations are refused) and rejects files with no usable times. It writes raw fixes as GPX 1.1 with accuracy, vertical accuracy and speed in an `openski` extension, so a round trip returns identical fixes. Imported points without accuracy get 10 m so the usual filter keeps them.
- `SkiDayExport` builds `ski-day.json` (segments, match status, ways with scores, thresholds, experimental note, and "(c) OpenStreetMap contributors" whenever a way was matched). Non-finite numbers become null.

All thresholds live in `SkiDayConfig` (exported under `thresholds`). Starting values, chosen from typical lift and skiing speeds and phone GPS noise; none tuned on a real day:

| Threshold | Value | Meaning |
|---|---|---|
| `position_smooth_s` | 5 s | Moving average on positions before distances and speeds (GPS jitter). |
| `speed_half_window_s` | 5 s | Half-width of the chord giving the ground speed for moving or resting. |
| `gps_vertical_window_s` | 30 s | Least-squares window for vertical speed from GPS altitude (noisy, so wide). |
| `baro_vertical_window_s` | 15 s | Same with barometric altitude (quieter, sharper). |
| `rest_enter_mps / rest_exit_mps` | 0.8 / 1.5 m/s | Ground speed hysteresis: below 0.8 a skier rests, above 1.5 moves again. Queues shuffle at about 0.3, lifts run at 2 or more. |
| `descent_enter_mps / descent_stay_mps` | 0.4 / 0.1 m/s down | Vertical speed that starts a descent / below which it becomes flat travel. Pistes give 1 to 3 m/s down. |
| `lift_enter_mps / lift_stay_mps` | 0.4 / 0.1 m/s up | Same for lifts; chairlifts climb about 1 to 2 m/s. |
| `min_descent_s / min_lift_s / min_rest_s` | 30 / 60 / 60 s | Shorter runs are absorbed into their neighbours (a dip, a pole push or short climb, a brief stop). |
| `bridge_rest_s` | 180 s | A rest shorter than this between two descents (or two lifts) joins them: a stop mid-run, a lift that halts. |
| `max_vertical_accuracy_m` | 30 m | GPS altitude with worse reported vertical accuracy is not used (unknown accuracy is accepted, as in GPX). |
| `min_altitude_fixes` | 10 | Fewer usable altitudes means no altitude. |
| `min_baro_coverage / baro_max_gap_s` | 0.9 / 10 s | The barometer is used only if it covers 90 % of fixes (a reading within 10 s on both sides); otherwise GPS altitude, never a mix. |
| `top_speed_hold_s` | 2 s | A top speed must be held this long (reported speed preferred, else position chord). |
| `note_stopped_s` | 10 s | Stopped time inside a descent or lift above which the segment notes it. |
| `piste_base_tolerance_m / lift_base_tolerance_m` | 30 / 15 m | Matching tolerance before accuracy: pistes are centre lines of wide runs, lifts follow their line. |
| `accuracy_factor` | 1.5 | Times the fix's reported accuracy added to the tolerance. |
| `direction_min_speed_mps` | 1 m/s | Slower than this the travel direction is unknown and not compared. |
| `wrong_way_cos / wrong_way_cost / wrong_way_score_factor` | -0.2 / 2 / 0.5 | A fix whose travel runs against the way (pistes downhill, lifts up) costs more and scores half. |
| `switch_cost / unmatched_cost` | 4 / 1.5 | Cost of changing way (continuity) and of an unmatched fix (equal to a fix about 0.87 tolerances away). |
| `min_way_fraction` | 0.1 | A way covering less of the segment is dropped from the list. |
| `min_match_score` | 0.5 | Below this the segment (or a listed way) is unmatched rather than guessed. |
| `map_confirm_fraction` | 0.5 | Fraction of fixes on a way of the right kind for the map to confirm a lift or descent. |

Tested (JVM, synthetic): a day of three descents, two lifts and rests with correlated GPS position and altitude noise, linked turns, a flat traverse, a 30 s stop mid-descent, a lift that stops for a minute, an uphill pole push, a flat run-out, a queue, across several noise seeds; inner boundaries land within 20 s with GPS altitude and 15 s with the barometer (worst seen about 9 s), verticals within 12 % (5 % with the barometer). Also a descent straight into a lift with no queue, a two-minute signal gap in a lift, partial barometer coverage, no altitude with and without a map, NaN and missing fields, parallel pistes 25 m apart, direction deciding between equally close pistes, a descent over two pistes in order, unmatched tracks, GPX round trips and malformed files.

Known limits:

- Nothing has seen real ski data. Real GPS under lift cables, in tunnels and gondola cabins, and real barometer drift with weather are not modelled.
- Distance is measured on 5 s smoothed positions, which cut the corners of linked turns: on the synthetic day descents read about 88 to 90 % of the true path length.
- Boundaries lag or lead by up to about 10 s because of the smoothing windows.
- Drag lifts, flat or downhill lift sections without a map, skinning or walking uphill, very slow skiers (under 1.5 m/s) and long flat cat-tracks between lifts are untested and likely mislabelled; a long walk on the flat is labelled rest.
- A rest under 3 minutes between two descents joins them, so a descent, a short wait and another descent without a lift count as one run.
- Pistes mapped as areas, or ways drawn against the OSM direction convention, score low and come back unmatched. Matching cost grows with fixes times nearby ways and has not been measured on a full resort.
- GPX is read whole into memory (DOM); a very large file has not been tried.
