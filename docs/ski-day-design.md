# Ski day: whole-day tracking, run and lift splitting, resort data (design)

Status: 19 October 2026. Step 1 (the pure engine) is built and JVM-tested on synthetic days only; see "Engine" at the end. Step 2 (resort data) is built and tested against hand-written fixtures only; see "Resort data (built, step 2)". Step 3 (integration: whole-day tracking, database version 13, the day report, screens, tags, import and export) is built on branch `feat/ski-day-app`, JVM-tested on synthetic days and compiled, but never run on a phone; see "Integration (built, step 3)" at the end. Everything is experimental and unvalidated on a real day on snow.

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

Steps 1 and 2 are independent and can run in parallel. Step 3 follows both. All three are built (see the sections at the end); none has been checked on a phone or on snow.

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

## Resort data (built, step 2)

Status: built 19 October 2026 on branch `feat/ski-day-resorts`. No UI yet. JVM-tested against hand-written fixtures in the real Overpass JSON shape; never run against the live service. Experimental.

- The model is the engine's `ResortMap.kt` (`LatLon`, `WayKind`, `ResortWay`); `ResortWay.difficulty` keeps the raw `piste:difficulty` as that file defines it, and the parser's `ResortWayInfo` wrapper adds the rest.
- `OverpassQuery.kt`: `area(centre, radiusM)` turns the centre and radius (clamped to 500 m to 25 km) into a bounding box (`GeoBox.around`) and `box(...)` builds one query for `piste:type=downhill` ways and relations, `aerialway` ways (except `station` and `pylon`), and `aerialway=station` nodes and ways (stations mapped as outlines), `[out:json][timeout:60]`, `out meta geom` (geometry plus each element's version). `search(name, within?)` finds `landuse=winter_sports` or `site=piste` objects by a case-insensitive name pattern (`[timeout:25]`, `out body bb qt 20`, so ways and relations come with `bounds`); when the server returns the full 20 elements the result says `maybeMore`. The typed name is reduced to letters, digits, spaces, hyphens and apostrophes (anything else becomes `.`), so it cannot break out of the query string.
- `OverpassParser.kt`: Overpass JSON to `ResortData` (`ResortWayInfo` per way: the `ResortWay` plus the normalised difficulty class, `operator`, OSM version, an `area` mark and the relation id it came from; `ResortStation` per station node, and per station way as the mean of its outline's vertices with `osm_type` 'way'). A null or unusable geometry point splits a way into numbered parts rather than being bridged by a straight line; parts with fewer than two points are dropped, and a way with no usable part is skipped and counted. `name` falls back to `piste:name` and `ref` to `piste:ref` for pistes only. Difficulty is normalised to easy (novice, easy), intermediate, advanced, expert (expert, extreme), freeride or other; no colours are implied, since they differ by country. Only a closed ring is marked `area` (a closed piste way unless `area=no`, a closed `area=yes` way, or a closed outer way of a `multipolygon`); `inner` members (holes) are left out. A piste relation (for example `type=route`) fills only the tags its member ways lack and never changes a lift. Known limits: a member way not also returned on its own takes the relation's tags, and member ways are kept in the order and direction OSM stores them (not joined into rings, not turned to run downhill). A server `remark` (timeout or memory error) marks the result partial. Malformed, truncated, empty or non-JSON input returns `OverpassParse.Failed` with a message; the parser never throws.
- `Polyline.kt`: the encoded polyline format at 1e-6 degree precision (round trip within 0.5e-6 degrees).
- Database version 12 (`LocalSessionStore`, `ResortStore.kt`): `resorts(id, name, centre_lat, centre_lon, radius_m, fetched_at_ms, way_count, source_note, truncated)`, `resort_ways(resort_id, osm_id, part, kind, name, ref, difficulty (normalised class), difficulty_raw, aerialway, operator, osm_version, area, relation_id, geometry, PRIMARY KEY(resort_id, osm_id, part))` with geometry as an encoded polyline, and `resort_stations(resort_id, osm_type, osm_id, name, lat, lon, operator, osm_version, PRIMARY KEY(resort_id, osm_type, osm_id))`. This replaces the `osm_area` and `bbox` columns sketched above: the stored centre and radius define the box, and `truncated` is set when the radius asked for was cut to 25 km. `ResortStore` saves a resort with its ways and stations in one transaction (a failure leaves the old copy), lists, loads ways and stations, deletes a resort with its rows, and reports age. Deleting a session never touches these tables. `StorageInstrumentation` checks the v11 to v12 migration and a fresh-install round trip (compiled, not yet run on an emulator).
- Locking: `ResortStore` locks on the `LocalSessionStore` instance like the store's own `@Synchronized` methods. `save` builds all rows (including polyline encoding) before taking the lock, then holds it, and SQLite's write lock, for one transaction; fetching and parsing hold no lock. Without WAL, SQLite would make other writers wait for that transaction anyway, so the protection is a guard instead: `ResortRepository` takes a `downloadBlocker` that the resort screen should wire to "a recording is active", and a blocked download returns `Blocked` without sending anything.
- `ResortRepository.kt`: validate, fetch, parse, store. A centre outside +-85 degrees latitude, a non-finite position or a non-positive radius returns `InvalidRequest` before anything is sent; exceptions from query building or the fetcher become typed `FetchFailed` errors. Only a complete, non-empty, parsed answer replaces a cached map: fetch errors, parse errors, partial answers (server remark), empty answers and failed saves all leave the cache as it was and return the kept copy. `Saved.truncated` reports a radius cut to the maximum. `isStale` is true when the copy is older than `STALE_AFTER_MS` (150 days, about one season) or missing. `ATTRIBUTION` is `ResortMap.ATTRIBUTION` plus " (ODbL)", "(c) OpenStreetMap contributors (ODbL)", and is also written into each resort's `source_note` with the OSM data timestamp. Tests use a fake `ResortFetcher`; nothing in the tests touches the network.
- `HttpResortFetcher.kt`: one HTTPS POST to `https://overpass-api.de/api/interpreter` with User-Agent `OpenSki/0.1 (experimental ski-boot sensor app for Android; <contact>)`, where the contact is a placeholder constant (`CONTACT`) to set before any public release; `ReleaseCheckTest` fails on the placeholder when the environment variable `OPENSKI_RELEASE_CHECK` is set (it is skipped otherwise; run it with `--tests "com.openski.android.ReleaseCheckTest" --rerun` so Gradle does not reuse an earlier result). Connect timeout 15 s, read timeout 90 s (the query's own 60 s plus a margin), responses over 16 MB refused, and the body is decoded from the read buffer without an extra copy. On HTTP 429 it waits once (Retry-After as seconds or as an HTTP date, clamped to 30 to 120 s; 30 s when absent) and retries exactly once; nothing else is retried, including 504 (a busy server or a heavy query; a repeat would add load). Called on the main thread it returns `MAIN_THREAD` without sending; an interrupt returns `INTERRUPTED` and restores the interrupt flag; unexpected runtime errors return `FAILED`; it never throws. The app now declares `android.permission.INTERNET` (it had no network permission before); nothing calls the fetcher yet.

### What leaves the device

A resort download sends the Overpass host one query containing a bounding box (south, west, north, east to six decimals), and every request carries the User-Agent above; a name search sends the typed name (reduced to letters, digits, spaces, hyphens and apostrophes) and, when searching near the phone, a box. The host also sees the phone's IP address, as with any HTTPS request. When a box is built from the phone's own position (`ResortRequest.nearPosition`, `ResortRepository.searchNear`), the position is first rounded to `LOCATION_GRID_DEG` (0.01 degrees, about 1.1 km north-south), so the exact fix never leaves the device. Nothing else is sent: no boot data, no session data, no account. Boxes built from a search result use that result's public map centre.

### Overpass usage notes relied on

Checked on 19 October 2026 on the OpenStreetMap wiki page "Overpass API" (wiki.openstreetmap.org/wiki/Overpass_API), not against the live service: as a guide for the main instance, under 10,000 queries and under 1 GB per day is not expected to disturb others, divided by 100 for regular use, and app usage counts the total of all its users; apps should send a User-Agent or Referer that identifies them; no parallel queries; cache and rate-limit calls; after HTTP 429 or 406 pause 30 seconds before a new request; commercial use should use self-hosted or paid servers; the server is described as often overloaded. The page does not mention 504, which is not retried here. I did not verify the live service's current limits, its actual timeout and size defaults, or how it answers this exact query. One download per resort, on request, with the cache used afterwards, keeps a user far below these figures.

## Integration (built, step 3)

Status: built 19 October 2026 on branch `feat/ski-day-app`. The pure parts are JVM-tested on synthetic days; the database migration check is compiled into `StorageInstrumentation` but has not run on an emulator; the service and screens have not run on a phone. Phone checks are listed in `docs/android-acceptance.md` ("Ski day").

### Storage (database version 13)

- `ski_days(id TEXT PRIMARY KEY, title, started_at_ms, ended_at_ms (null while tracking), source ('phone' or 'gpx'), resort_id (null for none), notes, tags_json, created_at_ms)`, `day_fixes(day_id, time_ms, lat, lon, alt, speed, accuracy, vertical_accuracy, baro_alt)` with an index on `(day_id, time_ms)`, and `day_run_tags(day_id, segment_start_ms, tags_json, note, PRIMARY KEY(day_id, segment_start_ms))`.
- `DayStore` follows `ResortStore`: every call locks on the `LocalSessionStore` instance. Fixes are written in transactions of at most 200 rows (`DayRules.FIX_BATCH_ROWS`), each under its own short lock, so an import or a backlog never holds the store for long. A fix is stored exactly as received; the only change is that a non-finite optional value (a NaN altitude, say) becomes null, and a row without a finite position is skipped and counted. Deleting a day removes its fixes and tags in one transaction; deleting a session or a resort never touches days (a day whose resort was deleted has no map).
- `StorageInstrumentation` checks the 12 to 13 upgrade (a session and a resort survive, the tables start empty), a day round trip (450 fixes over three transactions with and without optional values and barometer, one unstorable row skipped), updates, run tags (replace, and removal by an empty tag), finishing, a GPX import, and that deleting a session, a resort or another day leaves days alone. While adding it I found that the older 8 to 9, 9 to 10 and 10 to 11 checks could not have passed since version 11: they reopened a database that still held the newer tables, so the upgrade's `CREATE TABLE` would fail, and the 10 to 11 check expected version 11. They now drop every table newer than the version they set (`dropTablesAbove`) and expect `LocalSessionStore.VERSION`.

### Whole-day tracking (`DayTrackingService`, `DayTracking.kt`)

- A separate foreground service of type `location` (declared in the manifest next to the sensor service; the app already holds `ACCESS_FINE_LOCATION` and `FOREGROUND_SERVICE_LOCATION`). It needs no boots and runs whether or not `SensorSessionService` does. Start and Stop come from the day screen; the notification shows fixes and pace and has a Stop day action.
- Location comes from `GpsListener` (now with an interval argument) and `RunTrack.fixFrom`, as for runs. Each fix is stored with a barometric altitude: the mean of the pressure readings of the last 5 s, converted with the standard atmosphere formula 44330 * (1 - (p / 1013.25)^(1/5.255)) (the platform's `SensorManager.getAltitude` formula); null without a pressure sensor or a recent plausible reading (300 to 1100 hPa).
- Cadence (`CadencePlanner`, constants in `DayTrackingConfig`): one fix a second while moving; after 120 s still (under 0.7 m/s and inside 30 m of where stillness began) one fix every 5 s; back to one a second at 1.5 m/s or on leaving the 30 m radius. The speed is the phone's reported speed, or without it a chord over at least 5 s. Only fixes with accuracy of 25 m or better steer it. On the synthetic day only stops longer than two minutes slow down; every lift and descent fix stays at one a second. The battery cost of whole-day tracking, at either rate, is unmeasured.
- Fixes are buffered (`FixBatcher`: 30 rows or 30 s) and written on one background thread; a failed write goes back into the buffer, is retried with the next batch, and the error shows on the screen. At most about 30 s of fixes is lost if the process dies.
- A partial wake lock (renewed every 10 s, one-hour timeout) is held while tracking so the barometer keeps reading with the screen off.
- Resume: a day stays open until Stop. `START_STICKY` restarts the service after the process dies and it resumes the open phone day; the day screen also offers Resume or End for a day left open (for example after a reboot: there is no boot receiver).
- Refusals are visible: without location permission the service does not start, the day screen says why with a Settings link, and a notification is posted; location switched off or a refused request shows on the screen and in the notification and is retried every 10 s; a refused foreground start (Android 14 rules) is reported, not swallowed.

### Day analysis (`SkiDayReport`, `DayBootStats`, `SessionBootAnalysis`)

- `SkiDayReport.build(fixes, map?, bootStats?)` runs `SkiDaySplitter` (barometer altitude taken from the stored fixes) and, with a map, `PisteMatcher`, and returns numbered segments (Run 3, Lift 2, Rest 4). A matched descent or lift is named from its ways in order of travel (name, else "Piste <ref>" or "Lift <ref>", else "unnamed piste") with a grade (the normalised `piste:difficulty` of the way covering most of it, or the lift type). Day totals: vertical descended and climbed (null without altitude), runs, lifts, the longest run by distance, time skiing, on lifts and resting (segment durations; long signal gaps count nowhere), distance skied and top speed. The attribution is set whenever a map was given, since the map also shapes the split.
- Boot numbers per descent: `bootStats(startMs, endMs)` is asked once per descent and returns null when no sensor session overlaps it. `DayBootSource` wires it to `LocalSessionStore`: the real (never synthetic) sessions overlapping the day are loaded and analysed once with `SessionBootAnalysis`, the same code the session export now calls (orientation, movements, heading-rate candidates, turn-metric rows, clock alignment, agreement pairs, torso metrics), and only their event times are kept (`SessionBootEvents`). A descent gets, per boot, the dry-roll movements, heading-rate turn candidates and run half-turns (ski_v0 events, by phone receipt time) starting inside it, per minute of covered time when at least 20 s is covered; the mean derivative similarity, roll correlation and wedge fraction of the agreement pairs starting inside it; and the mean torso gyro RMS and horizontal acceleration SD of the torso rows with metrics. A boot without a saved calibration has no roll counts (missing, not zero); a refused torso analysis or a failed session analysis becomes a note. One difference from the session export, stated in the JSON: a day counts movements over the whole session, not only inside START_TEST..PAUSE marker windows. Boot times are phone-receipt referenced and GPS fix times are satellite time; on a phone with network time they should agree to well under a second, which is enough to assign turns to runs (not checked).
- Loading a session reads all its samples, so a day with many long sessions is slow to open (on a background thread; a session too large for memory becomes a note).

### Screens (Snow look)

- Home, Today tab: a "Ski day" card under "Ready to ski?" with a secondary button, so "Start a run" stays the screen's one primary action.
- `SkiDayActivity`: the current day (the one being tracked, else an open one, else the newest). Start day or Stop day (the primary), Resume or End for a day left open, a live tracking line (fixes, last fix age and accuracy, pace, barometer) and any error with a Settings link. Then the resort card (name, age, stale marker, choose or change the map), day totals, day tags and notes (snow, visibility, crowd, drill practised, free note, title), and the segments: title and start time, name and grade, duration, vertical, distance and top speed, a boot line where a session overlaps, and the run's tags; tapping a run tags it. "Piste and lift names and lines: (c) OpenStreetMap contributors (ODbL)" shows whenever a map was used. Export ski-day.gpx and ski-day.json, import a GPX file, delete a day, and a list of other days.
- `ResortPickerActivity`: search by name, or download the area around the phone (its last known position, rounded to 0.01 degrees before anything is built from it), with a spinner while busy and plain messages for offline, rate limited (HTTP 429), busy (504), timeout, too large, an empty area, truncated or unreadable answers and refused downloads, each saying whether the cached copy was kept. Cached maps show their size, age, a stale marker (older than 150 days) and a cut-area marker, with Use for this day, Refresh and Delete. Network calls run one at a time on a background thread. The download guard is "a sensor recording or run is in progress": the screen binds to `SensorSessionService` only if it is already running (flags 0) and also checks the database for an open session.
- GPX import: the system document picker (`ACTION_OPEN_DOCUMENT`), read with `GpxCodec` (20 MB cap, DTDs refused) on a background thread, stored as a day with `source = gpx`, with a message giving the points imported and skipped.

### Export

- `ski-day.gpx`: every stored fix, untouched, through `GpxCodec.write` (accuracy, vertical accuracy and speed in the `openski` extension). The barometer is not in the GPX.
- `ski-day.json` (`SkiDayExport.dayDocument`): the step 1 summary (segments, matches with scores, thresholds, altitude source, notes) plus each segment's title, number, name and grade, its run tags and boot numbers (descents with overlapping data only, else null); the day's title, source, times, notes and tags; run tags that no longer fall inside any descent (`unattached_run_tags`, never dropped); the day totals; the resort id and name; the attribution whenever a map was used; a boot-data note; and the stored barometric altitudes as `[time_ms, altitude_m]` pairs. Non-finite numbers are null.
- Both are written through the system "create document" picker, as session ZIPs are. The session ZIP is unchanged: its analysis code moved into `SessionBootAnalysis` without changing its output, and a JVM test exports the calibrated demo session.

### Tests (JVM, synthetic)

`DayTrackingTest` (standard atmosphere values, implausible pressures, the pressure window, the cadence rules including drift, poor fixes, chord speeds and a synthetic day, batching and retry), `SkiDayRecordsTest` (tag JSON and lenient parsing, chunking, storable fixes, which open day resumes, which descent a run tag belongs to, cleaning, GPX days), `DayBootStatsTest` (session selection, counts and means in a range, coverage, no invented numbers, the real analysis pipeline on the calibrated demo session, half-turns without calibration, the session export after the refactor), `SkiDayReportTest` (with and without a map, an unmatched map, boot overlap on one descent only, barometer from stored fixes, no altitude, name fallbacks, the day JSON) and `SkiDayTextTest` (formatting, download and search messages, boot lines).

### Not checked yet

Everything Android: the service on a phone (permission flow, screen-off survival, Doze, restart after the process dies, a location foreground service restarted from the background on Android 14), the barometer on a real phone, battery use, the screens, the document pickers, the resort download against the live Overpass service, export opening in a GPX viewer, and the migration on an emulator. The day and boot numbers have not seen a real ski day.
