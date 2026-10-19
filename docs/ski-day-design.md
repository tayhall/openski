# Ski day: whole-day tracking, run and lift splitting, resort data (design)

Status: design, 19 October 2026. Step 2 (resort data) is built; see "Resort data (built, step 2)" at the end. Everything is experimental and unvalidated on a real day on snow.

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

## Resort data (built, step 2)

Status: built 19 October 2026 on branch `feat/ski-day-resorts`. No UI yet. JVM-tested against hand-written fixtures in the real Overpass JSON shape; never run against the live service. Experimental.

- `ResortModel.kt`: `LatLon`, `WayKind` (PISTE, LIFT) and `ResortWay` (shared with the step 1 engine).
- `OverpassQuery.kt`: `area(centre, radiusM)` turns the centre and radius (clamped to 500 m to 25 km) into a bounding box (`GeoBox.around`) and `box(...)` builds one query for `piste:type=downhill` ways and relations, `aerialway` ways (except `station` and `pylon`), and `aerialway=station` nodes and ways (stations mapped as outlines), `[out:json][timeout:60]`, `out meta geom` (geometry plus each element's version). `search(name, within?)` finds `landuse=winter_sports` or `site=piste` objects by a case-insensitive name pattern (`[timeout:25]`, `out body bb qt 20`, so ways and relations come with `bounds`); when the server returns the full 20 elements the result says `maybeMore`. The typed name is reduced to letters, digits, spaces, hyphens and apostrophes (anything else becomes `.`), so it cannot break out of the query string.
- `OverpassParser.kt`: Overpass JSON to `ResortData` (`ResortWayInfo` per way: the `ResortWay` plus raw `piste:difficulty`, `operator`, OSM version, an `area` mark and the relation id it came from; `ResortStation` per station node, and per station way as the mean of its outline's vertices with `osm_type` 'way'). A null or unusable geometry point splits a way into numbered parts rather than being bridged by a straight line; parts with fewer than two points are dropped, and a way with no usable part is skipped and counted. `name` falls back to `piste:name` and `ref` to `piste:ref` for pistes only. Difficulty is normalised to easy (novice, easy), intermediate, advanced, expert (expert, extreme), freeride or other; no colours are implied, since they differ by country. Only a closed ring is marked `area` (a closed piste way unless `area=no`, a closed `area=yes` way, or a closed outer way of a `multipolygon`); `inner` members (holes) are left out. A piste relation (for example `type=route`) fills only the tags its member ways lack and never changes a lift. Known limits: a member way not also returned on its own takes the relation's tags, and member ways are kept in the order and direction OSM stores them (not joined into rings, not turned to run downhill). A server `remark` (timeout or memory error) marks the result partial. Malformed, truncated, empty or non-JSON input returns `OverpassParse.Failed` with a message; the parser never throws.
- `Polyline.kt`: the encoded polyline format at 1e-6 degree precision (round trip within 0.5e-6 degrees).
- Database version 12 (`LocalSessionStore`, `ResortStore.kt`): `resorts(id, name, centre_lat, centre_lon, radius_m, fetched_at_ms, way_count, source_note, truncated)`, `resort_ways(resort_id, osm_id, part, kind, name, ref, difficulty, difficulty_raw, aerialway, operator, osm_version, area, relation_id, geometry, PRIMARY KEY(resort_id, osm_id, part))` with geometry as an encoded polyline, and `resort_stations(resort_id, osm_type, osm_id, name, lat, lon, operator, osm_version, PRIMARY KEY(resort_id, osm_type, osm_id))`. This replaces the `osm_area` and `bbox` columns sketched above: the stored centre and radius define the box, and `truncated` is set when the radius asked for was cut to 25 km. `ResortStore` saves a resort with its ways and stations in one transaction (a failure leaves the old copy), lists, loads ways and stations, deletes a resort with its rows, and reports age. Deleting a session never touches these tables. `StorageInstrumentation` checks the v11 to v12 migration and a fresh-install round trip (compiled, not yet run on an emulator).
- Locking: `ResortStore` locks on the `LocalSessionStore` instance like the store's own `@Synchronized` methods. `save` builds all rows (including polyline encoding) before taking the lock, then holds it, and SQLite's write lock, for one transaction; fetching and parsing hold no lock. Without WAL, SQLite would make other writers wait for that transaction anyway, so the protection is a guard instead: `ResortRepository` takes a `downloadBlocker` that the resort screen should wire to "a recording is active", and a blocked download returns `Blocked` without sending anything.
- `ResortRepository.kt`: validate, fetch, parse, store. A centre outside +-85 degrees latitude, a non-finite position or a non-positive radius returns `InvalidRequest` before anything is sent; exceptions from query building or the fetcher become typed `FetchFailed` errors. Only a complete, non-empty, parsed answer replaces a cached map: fetch errors, parse errors, partial answers (server remark), empty answers and failed saves all leave the cache as it was and return the kept copy. `Saved.truncated` reports a radius cut to the maximum. `isStale` is true when the copy is older than `STALE_AFTER_MS` (150 days, about one season) or missing. `ATTRIBUTION` is "(c) OpenStreetMap contributors (ODbL)" and is also written into each resort's `source_note` with the OSM data timestamp. Tests use a fake `ResortFetcher`; nothing in the tests touches the network.
- `HttpResortFetcher.kt`: one HTTPS POST to `https://overpass-api.de/api/interpreter` with User-Agent `OpenSki/0.1 (experimental ski-boot sensor app for Android; <contact>)`, where the contact is a placeholder constant (`CONTACT`) to set before any public release; `ReleaseCheckTest` fails on the placeholder when the environment variable `OPENSKI_RELEASE_CHECK` is set (it is skipped otherwise; run it with `--tests "com.openski.android.ReleaseCheckTest" --rerun` so Gradle does not reuse an earlier result). Connect timeout 15 s, read timeout 90 s (the query's own 60 s plus a margin), responses over 16 MB refused, and the body is decoded from the read buffer without an extra copy. On HTTP 429 it waits once (Retry-After as seconds or as an HTTP date, clamped to 30 to 120 s; 30 s when absent) and retries exactly once; nothing else is retried, including 504 (a busy server or a heavy query; a repeat would add load). Called on the main thread it returns `MAIN_THREAD` without sending; an interrupt returns `INTERRUPTED` and restores the interrupt flag; unexpected runtime errors return `FAILED`; it never throws. The app now declares `android.permission.INTERNET` (it had no network permission before); nothing calls the fetcher yet.

### What leaves the device

A resort download sends the Overpass host one query containing a bounding box (south, west, north, east to six decimals), and every request carries the User-Agent above; a name search sends the typed name (reduced to letters, digits, spaces, hyphens and apostrophes) and, when searching near the phone, a box. The host also sees the phone's IP address, as with any HTTPS request. When a box is built from the phone's own position (`ResortRequest.nearPosition`, `ResortRepository.searchNear`), the position is first rounded to `LOCATION_GRID_DEG` (0.01 degrees, about 1.1 km north-south), so the exact fix never leaves the device. Nothing else is sent: no boot data, no session data, no account. Boxes built from a search result use that result's public map centre.

### Overpass usage notes relied on

Checked on 19 October 2026 on the OpenStreetMap wiki page "Overpass API" (wiki.openstreetmap.org/wiki/Overpass_API), not against the live service: as a guide for the main instance, under 10,000 queries and under 1 GB per day is not expected to disturb others, divided by 100 for regular use, and app usage counts the total of all its users; apps should send a User-Agent or Referer that identifies them; no parallel queries; cache and rate-limit calls; after HTTP 429 or 406 pause 30 seconds before a new request; commercial use should use self-hosted or paid servers; the server is described as often overloaded. The page does not mention 504, which is not retried here. I did not verify the live service's current limits, its actual timeout and size defaults, or how it answers this exact query. One download per resort, on request, with the cache used afterwards, keeps a user far below these figures.
