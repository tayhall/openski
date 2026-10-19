# Ski day: whole-day tracking, run and lift splitting, resort data (design)

Status: design, 19 October 2026. Nothing here is built yet. Everything is experimental and unvalidated on a real day on snow.

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
