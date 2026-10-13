# Run GPS: where, how fast, how far (design)

Status: implemented on `feat/run-gps`; hardware checks owed.

## What this is for

When you ski a run with the app, the phone also records where it is. After the run the app shows what the
boots cannot: how far you went, how fast, and how much height you dropped. It sits next to the turn data
the boots already give (half-turns and coaching verdicts).

Success: after a run outdoors, the Done screen and the logbook "Run" tab show distance, top speed, average
speed and vertical drop, and a run without a GPS fix says so plainly instead of showing zeros.

## Decisions already made

- The app uses GPS for runs, on by default, with a switch on the Ready screen.
- No GPS file in the export for now. The track stays in the app. It can be added later.
- Only runs use GPS. Test recordings, drills and plain sessions never ask for location.

## What the skier sees

1. **First run only:** one permission prompt, "Allow OpenSki to use location while the app is in use",
   with a one-line reason. If refused, runs still work and the card says "No GPS for this run".
2. **Ready screen:** a small "GPS" row under the boot status: *Waiting for signal / Ready (accuracy 6 m) /
   Off*, with a switch. It never blocks starting a run; a run can start with no fix yet.
3. **During the run:** nothing new on screen. The notification does not show location.
4. **Done screen and logbook Run tab:** a card with distance, top speed, average speed (moving time only),
   vertical drop, and the number of usable fixes. Wording: "Phone GPS readings, not a measure of technique."
5. **Demo boots runs:** no GPS, labelled as such.

## How it works

- **New piece `RunTrack`** (pure Kotlin, JVM tested): takes a list of fixes and returns the summary.
  - Drops fixes worse than 25 m horizontal accuracy and fixes that jump faster than 40 m/s.
  - Distance: sum of great-circle distances between kept fixes.
  - Speed: the phone's own speed value when it has one, else distance over time.
  - Top speed: the highest value held for at least 2 s, so one noisy fix cannot set it.
  - Vertical drop: smoothed altitude, start minus lowest point; reported only with 10 or more fixes that
    report vertical accuracy. Otherwise shown as "not available".
  - Moving time: seconds above 0.5 m/s, used for average speed.
- **New interface `RunLocation`** beside `RunBoots` and `RunSink`, so `RunController` can be tested with
  a fake and the Android location code stays in the service. `RunController` starts it with the run and
  stops it when the run ends, and ignores it if unavailable.
- **Service:** `SensorSessionService` already runs as a foreground service for the run. It adds the
  `location` foreground-service type and requests updates once a second while a run is active, using the
  platform `LocationManager` (no Google Play services, consistent with the app's no-extra-dependency rule).
  Updates stop in `stopRecording`, on pause for storage failure, and on service destroy.
- **Storage:** database version 10 adds `run_track(session_id, time_ms, lat, lon, alt, speed,
  accuracy_m, vertical_accuracy_m)` with an index on `(session_id, time_ms)`, plus a `gps_enabled`
  column on `run_info`. Deleting a session deletes its track. Migration check added to
  `StorageInstrumentation`.
- **Manifest:** `ACCESS_FINE_LOCATION` is already declared for BLE up to API 30; it is declared for all
  versions, and `FOREGROUND_SERVICE_LOCATION` is added.

## Privacy and honesty

- The track stays on the phone. Nothing is uploaded. Session deletion removes it.
- A track shows where someone skis; the settings text says so, and the ZIP export does not include it
  until that is decided separately.
- Speeds and drops are phone readings with stated accuracy limits and are never presented as scores.

## Failure cases

| Case | What happens |
|---|---|
| Permission refused | Run works; card says "No GPS for this run" |
| Location services off on the phone | Ready row says "Location is off"; run works |
| No fix for the whole run | Card says "No GPS fix during this run" |
| Fix lost mid-run | Gap kept as a gap; distance does not bridge it |
| Phone storage low | Existing guard stops recording; track so far is kept |
| Service killed | Track so far is kept; same as half-turns |

## Out of scope

Map view, route sharing, GPS in the ZIP export, positions per turn, GPS when not running a run,
background tracking, Google Play services, altitude from the barometer, and any claim about technique.

## Testing

- JVM: `RunTrackTest` (distance on known coordinates, outlier fix, 2 s top-speed rule, drop with and
  without vertical accuracy, moving time), `RunControllerTest` additions with a fake `RunLocation`
  (starts and stops with the run, absent or refused handled, demo run has none), `RunRecordsTest`
  for the row mapping.
- Emulator: migration to version 10 in `StorageInstrumentation`; a scripted route sent with
  `adb emu geo fix` to see the Done card and the Ready row states.
- Hardware, owed: a short outdoor walk with the boots, checking permission flow, the notification
  staying silent about location, and lock-screen behaviour. Added to `docs/android-acceptance.md`.

## Open question

Altitude from a phone GPS is noisy, often 10 to 20 m. If vertical drop proves unreliable on real
runs, the fallback is to show it only when fixes are good, or drop it and keep distance and speed.
