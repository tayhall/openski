# Android recording and recovery acceptance

## Automated checks (6 October 2026)

- Debug APK builds with Gradle 9.2.1 and Android Studio's bundled Java on Windows.
- Eleven JVM unit tests pass: packet parsing, unsigned/signed values, malformed frames, contiguous transfer validation, failure/storage flags, overlapping sources, duplicate coverage, firmware clock rollover and experimental gyro lobes.
- Android lint passes with no errors. Remaining warnings primarily concern translation strings, deprecated UI APIs and existing manifest/resources.
- Isolated SQLite instrumentation passes on the Pixel 9a: v1 session/video preservation, new metadata, incomplete download rejection, validated-copy preservation during failed replacements, raw ZIP export, and deletion guards.
- The updated app launches behind the phone's lock screen without the previous background-service-start exception. Live-only telemetry uses a bound service; recording and recovery use a foreground service.
- Pixel USB debugging restored by installing Google's signed USB driver. The device changed from generic WinUSB to Android Composite ADB Interface and is visible through SDK ADB.

## Powered-sensor checks still needed

Use short recordings first. Retain an exported copy of important existing sessions.

1. Record both boot sensors for one minute. Lock the phone for at least 30 seconds. Stop and keep sensors powered until both backup states show `erased` in session detail. Expect approximately 50 live and 100 flash samples per second per sensor, subject to IMU sampling and recorder drops.
2. During recording, move one sensor out of range, then bring it back. Expect a live-stream warning/reconnect, gaps in live capture, and flash coverage for the interval when the sensor stayed powered and had recording capacity.
3. Disconnect a sensor before stopping the phone recording. Reconnect afterwards. Expect the sensor recorder to stop, recover its retained data and keep the other sensor's data independent.
4. Interrupt a flash download. Reconnect. Expect a new download from record zero, because recorder v2 has no unique session ID. Partial staging rows never replace a validated copy. Graphs must not count overlapping live/flash samples twice; export retains both raw sources.
5. Start with an older retained sensor recording. Expect a separate recovered session with approximate timing, then a new sensor capture for the active phone session. The app never assigns an unknown retained recording silently to the new run.
6. Power-cycle a sensor during recording. Expect reconnect and a recording event. A reboot ends its previous flash capture; any retained file is downloaded as a separate segment. Lost/unwritten samples cannot be recovered.
7. Let the sensor reach its safe flash limit. Expect the full indication while phone live capture continues. Flash capacity is about ten minutes; backup coverage does not extend beyond that limit until the capture is stopped/recovered.
8. Pause recovery from the notification, reopen the app and tap Recover sensor flash. Expect retained sensor data and recovery restarting safely. Stop from the recording notification and verify the phone session ends.
9. Open an existing v1 session, rename it, add notes, attach a video and set alignment offsets. Scrub graph/video together, export and inspect raw CSV/JSON. Confirm delete remains blocked for active sessions or pending recovery.
10. Compare experimental candidates with annotated ski footage using the appropriate mounting axis and sign. Stationary bench motions and walking may generate candidates. Do not treat these as validated turns or performance scores.

## Protocol and hardware constraints

Recorder v2 does not expose a persistent session identity, an independent checksum, synchronised clocks or battery level. Transfers validate frame shape, contiguous indices, declared count and the committed SQLite copy. Erasure follows only on the same connection after another status check; a disconnected or changed sensor requires a fresh download. An interrupted erase acknowledgement also triggers a fresh check/download instead of assuming the remote file still matches the phone copy.

Battery support reads the standard BLE Battery Service if firmware provides it. Current firmware displays Battery not reported. Battery hardware/circuit details and firmware support are needed to report actual charge.

Automatic alignment is approximate. Matching live samples estimates each boot's clock independently. Recovered recordings without matches use the phone's start/recovery time and are labelled approximate. Manual offsets are saved in metadata. No firmware image or partition table was changed during this Android work.

## Audio coaching checks (phone and earbuds needed)

The JVM tests cover the judgement, sounds, settings and demo feed. These cover what only a phone can show. Use Bluetooth earbuds or a helmet speaker unless a step says otherwise.

1. Open Boots > Coaching, tap Play test sounds. Expect a tick, a higher accent tick, a rising chirp, then a falling chirp, at a comfortable volume. Try the Volume setting at 30% and 90%.
2. Tap Try with demo boots. Expect ticks at the target beat and, after about eight turns, a rising chirp, then later a falling chirp as the demo drifts off target.
3. Lock the phone and put it in a pocket. Expect the ticks to carry on for at least two minutes and the notification to stay.
4. Switch the earbuds off mid-run. Expect the ticks to stop, no sound from the phone speaker, and the notification "Coaching paused: earbuds disconnected". Switch them back on and expect coaching to resume.
5. With no earbuds connected and Allow phone speaker off, tap Start coaching. Expect a refusal with a message. Turn Allow phone speaker on and expect the ticks from the speaker.
6. Play music in another app, then start coaching. Expect the ticks and chirps to mix over the music, and the music not to pause.
7. Start coaching with no boot connected. Expect the message that no boot is connected and the metronome still playing. Connect a boot and expect chirps once it sends turns.
8. With a real boot in the garden (training mode) and then in on-snow mode, roll the boot from side to side at the target beat. Expect chirps after each window. Record how often a clearly good window gets no sound, to tune the thresholds.
9. Leave the Coaching screen and reopen it while coaching runs. Expect coaching to continue and the screen to show the current state.
10. Disconnect the boot during a run. Expect the metronome to keep ticking and no crash.
11. Switch the earbuds off at the moment a chirp would play, several times. Expect silence from the phone speaker every time: no stray tick or chirp. Check Play test sounds separately: it deliberately plays on whatever route is active so the volume can be set.
12. While coaching, pull down the notification and tap Disconnect sensors, then lock the phone. Expect coaching to keep playing until you tap Stop coaching, after which the notification and the foreground service go away (unless a recording or recovery needs them).

## Run flow checks (phone, both boots and earbuds needed)

The JVM tests cover the run state machine, the zero confirmation, the chimes and the run records. Already observed on the Pixel 9a emulator with demo boots (8 October 2026): Today card, Ready screen with the disabled Start and its reason, a demo run through Running to the Done summary, the run stored in the database at version 8 (kind `run`, half-turns, verdicts and run settings), the run listed in the logbook as "DEMO · Run" and opening without a crash, and the version 8 migration check passing. The rest needs real hardware.

1. On the Ready screen with both boots on and earbuds in, the status rows show both boots and the earbuds as connected and Start run is enabled. Switch one boot off: Start run becomes disabled and the line above it names that boot.
2. Tap the boot-mode button. Both boots change between Training and On snow (check `http://ski-s3.local/api/v1/status` goes silent in On snow and returns in Training).
3. Tap Start run. Expect the boots to switch to On snow, "Stand upright. Hold still.", both marks turning solid after about a second of stillness, then the start chime and the metronome. Repeat while deliberately moving one boot: expect it to time out after 15 s naming that boot, and Try again to work.
4. Ski or roll the boots for a minute with the phone locked in a pocket. Expect the notification "Run in progress" with the elapsed time and turn count, the coaching chirps, and the Stop action working with the screen off.
5. Stop moving for 20 s. Expect the end chime, the run ending by itself, and the notification changing to "Saving run data".
6. Switch one boot off for 30 s during a run, then back on. Expect a banner naming it, the run continuing, and a "lost its link" note in the summary.
7. Walk out of range for a minute with both boots, then return. Expect the run to carry on or end sensibly, no crash, and the gap shown.
8. After the end chime, watch the saving progress while riding a lift. Record how long the download takes for a full 10-minute recording. Expect the boots to return to the mode they were in once saving finishes, and the Done summary.
9. Open a real run in the logbook straight after it ends, again while saving, and again after saving. Expect no crash and the half-turns visible; export it and check the zip contains `half-turns.csv`, `coach-verdicts.csv` and a `run` entry in `session.json`.
10. Record for longer than 10 minutes. Expect the summary to say the raw data covers the first 10 minutes only.
11. Start a run while a boot still holds an earlier recording. Expect Start to name the boot and say it is still saving.
12. Rotate the phone, press Home and reopen the app during each phase. Expect the screen to show the current phase and taps on Stop and Cancel to work every time.
13. After any storage change, rerun the migration check on an emulator: `adb shell am instrument -w com.openski.android.test/com.openski.android.StorageInstrumentation`. Expect a line starting `PASS:`.

## Run comparison checks

Observed on the Pixel 9a emulator with a demo run (8 October 2026): the card on the Done screen (sentence, number lines, window tiles opening on the first off-target window, solid recorded line against the pale target line, Play) and the logbook "Run" tab first and selected with the same card and no crash. The rest needs real runs.

1. After a real run, the sentence and numbers are believable against how it felt: depth and beat averages close to what the coaching reported, and the first off-target window is one you remember being off.
2. Tap each window tile on a long run (more than 20 windows): the tiles row scrolls, the chart changes, and Play animates the selected window.
3. Do a run that leans to the skier's left first on each boot (swap the boots between legs if practical): the recorded line should start on the same side on both boots, since the right boot is mirrored.
4. Open a run in the logbook straight after it ends, before the boots' data is saved, and again after saving. Expect the Run tab and no crash. Open an ordinary recording: expect the old three tabs only.
5. Check a run where a boot dropped its link for 30 s: the window around the gap shows flat stretches at zero, and the sentence still reads sensibly.
6. Tune the sentence thresholds (2°, 0.15 s, 20%) if "close" or "uneven" feels wrong on real runs.

## Run GPS checks

Observed on the Pixel 9a emulator (13 October 2026): the Ready screen's GPS row reads "GPS needs location permission" with the permission revoked and "GPS ready · accuracy 5 m" after granting it and sending `adb emu geo fix`; the switch toggles the row to "GPS off"; a demo run's Done card reads "No GPS in demo runs"; no crash. A real GPS run needs a phone outdoors and real boots.

1. Fresh install, tap Start run: the location prompt appears once; refusing it still starts the run and the Done card says "No GPS for this run".
2. With permission, outdoors: the Ready row goes from "waiting for signal" to "GPS ready".
3. Lock the phone and pocket it during zeroing and the run: the Done card still shows distance and top speed (this exercises the foreground location type started while visible).
4. The notification text never mentions location.
5. Turn location services off, start a run: it starts and the Done card says so; no crash.
6. Walk a known 200 m route: distance within about 10 %; vertical drop reads "not available" or a small number.

## Stomp sync checks (two boots and a phone needed)

- [ ] Prompt text: the run Ready, Running and Done screens and the Geek mode Record screen tell the skier to stomp both boots together at the start and again at the end.
- [ ] Surface: stomp on a hard floor or packed snow, both boots landing at the same instant, about a second apart from any other bump. Try a soft surface (deep snow, carpet) and note whether the aligner still accepts it.
- [ ] Record about a minute with a stomp in the first 20 s and one as you stop (up to 45 s before the recording ends; for an auto-ended run that is about 20 s before the end). The Details tab line under Recording health shows the stomp offset, the ping offset and their difference; `gesture-sync.json` in the export agrees, and `second_boot_windows_from_pings` is true when the connect bursts were stored.
- [ ] Record the same with the boots stomped one after the other instead of together: the result should not be trusted (the offset should show the delay); note what it reports.
- [ ] Refusals read sensibly and are not guesses: no end stomp (no clear stomp), a double stomp in one window (more than one comparable stomp), a stomp on one boot only, a recording under 45 s (too short), one boot missing.
- [ ] Compare stomp and ping offsets over several sessions and note the typical difference and its sign; this measures the ping bias described in `docs/clock-sync-design.md` ('Known bias').
- [ ] A session with only live samples (flash not recovered) is accepted but rougher (20 ms samples); note the difference from a recovered session.
- [ ] End ping burst (`EndBurstPlanner`, JVM-tested only): stop a normal recording and let a run end by itself, both boots connected. The Record screen says "syncing boot clocks" for about 3 s, then the save and flash recovery start; `clock-sync.csv` has about 8 pairs per boot in the last few seconds of the session. Repeat with one boot switched off just before the stop: the save still starts after about 3 s, with pairs from the other boot only. A test recording or drill saves at once with no end pairs.

## Owed on hardware (research round)

Nothing below has been run on a real boot. The JVM and host tests cover the parsing, the detector parity fixture and the exports. The storage-migration instrumentation check for database version 9 has been compiled but not run (no emulator was attached); run it on an emulator as in the run flow checks above. These items need powered boots.

- [ ] Heading-turn frames (movement characteristic, version 4) arrive on a real boot while turning in place on a chair or in a doorway, and `heading-turns.csv` from the replay of that recording lists lobes close to the turns made.
- [ ] A flash download of a recording that includes a deliberate knock (a sharp tap on the boot) shows the quality flag set in the stored samples and `D1` data markers in a protocol trace. Only near-rail (within 8 counts of full scale) and dropped-before flags are stored, so knock hard enough to clip or drop samples and expect the exported `samples.csv` `quality` column to be non-zero there; or accept that `quality` stays 0 for a gentle tap and record which it was.
- [ ] A session recorded before the protocol v3 change (flash record format 2 or live frame v1) still replays and exports unchanged after the app update and the version 9 migration.
- [ ] Rest-bias convergence after a cold start: `http://ski-s3.local/api/v1/motion` shows `bias_updates` advancing and `gyro_bias_radps` settling within its 0.15 rad/s clamp while the boot sits still, with `bias_refused` staying low.
- [ ] Before the first flash of recorder-v3 firmware onto a board that holds an older retained recording, download and erase it first; an older recording downloaded afterwards has its timestamps' low three bits read as quality flags (see `docs/ble-protocol.md` Compatibility).
- [ ] Mount check: the Analysis tab line "Mount check" and `mount_slip` in `analysis-summary.json` stay unflagged for a session recorded with the strap untouched, and flag a session where the sensor is deliberately rotated about 15 degrees around the leg part-way through a repeated leaning set (the replay is simulation-validated only; tilt slips under 15 degrees are expected to be missed).
- [ ] On the first snow day, a side-by-side of roll-lobe candidates against heading-rate candidates against video, following `docs/first-snow-protocol.md` in the research repo.
- [ ] Re-measure the accelerometer and gyro calibration under the research acquisition profile (16 g, 20 Hz); until then `/api/v1/acquisition` reports `recogniser_calibration_matches_profile: false` on research builds.

## Phone motion logging checks

Phone motion recording (gyroscope, accelerometer, game rotation vector at about 100 Hz, `phone-motion.csv`) is covered on the JVM only for row assembly, batching and the CSV, and the database round trip is written into the instrumentation check but has not been run. Delivery with the screen off and the sensor clock base are checked only on a phone; none of the items below has been done.

- [ ] Timestamp base on the target phone: record briefly and read the `phone_clock_check offset_ms=...` line in `events.csv` or the `phone-motion.csv` header. Near zero (a few ms) means sensor events share the `elapsedRealtimeNanos` base; a large value means they do not.
- [ ] Delivery with the screen off: record 5 minutes with the phone locked in a pocket; `phone-motion.csv` should cover the whole session at about 100 rows per second with no multi-second holes. Repeat with battery saver on, since some phones throttle background sensors.
- [ ] Battery drain over 30 minutes: compare a 30 minute recording with phone motion on against the same with the Geek mode switch off, and record the difference in `docs/bench-results.md`.
- [ ] A phone without a game rotation vector (or an emulator) still records the boots and notes `phone_motion unavailable`.
- [ ] Switch (Geek mode, Test lab, "Record phone motion (experimental)"; the rules are JVM-tested in `PhoneMotionTest`, the screen is not): it is on for a fresh install; turn it off, record, and the export has no `phone-motion.csv` rows and `events.csv` has `phone_motion off (setting)`; turning it on during a recording changes nothing until the next one, also across a service restart (the decision logged at the start, `phone_motion on/off (setting)`, is reused); the setting survives an app restart. A drill (Today or Path tab) with the switch on records `phone-motion.csv` (garden trials T3 and T4); a Test lab test recording does not.

## Garden trials checklist screen (check by hand)

Geek mode, Test lab, "Garden trials checklist (T0 to T6)" opens `TrialChecklistActivity` (dark Geek mode look). The content is the pure list in `GardenTrials.kt`, and `GardenTrialsTest` checks it covers T0 to T6 and that the titles and export file names match `docs/garden-trial-protocol.md`; the screen itself has not been opened on a phone.

- [ ] Seven cards, T0 to T6, each with the trial title, numbered steps, the export files and the `trial_report.py` command; text is readable at the largest font size and the screen scrolls.
- [ ] Ticking a box updates the "n of 7 done" line, and the ticks survive leaving the screen and restarting the app.

## Resort map download checks (phone and network needed)

The resort data layer (Overpass query, parser, database version 12, repository) is JVM-tested against hand-written fixtures and fake fetchers only; the version 12 migration check is compiled into `StorageInstrumentation` but has not been run. Nothing has been fetched from the live Overpass service. The resort picker (`ResortPickerActivity`, reached from the ski day screen) now exists; run these checks through it.

What leaves the phone (see `docs/ski-day-design.md`, "What leaves the device"): the Overpass host receives the query (a bounding box to six decimals, or a typed name and an optional box), the User-Agent `OpenSki/0.1 (...; <contact>)` and, as with any request, the phone's IP address. A box built from the phone's own position is centred on the position rounded to 0.01 degrees (about 1 km); no boot or session data is sent.

- [ ] Run `StorageInstrumentation` on an emulator and expect the PASS line to include "v11->v12 resort maps, fresh-install resort round trip".
- [ ] With a proxy or the network inspector, confirm a "near me" download or search sends a box whose centre is the position rounded to 0.01 degrees, and nothing besides the query, the User-Agent and the usual HTTP headers.
- [ ] Start a boot recording (or a run), then try a resort download in the picker: it is refused with "a boot recording or run is in progress"; no request is sent. A name search is still allowed.
- [ ] Over Wi-Fi, fetch one real resort by name search and by current position (radius 5 km): the download finishes in under a minute, `resorts.way_count` is plausible against the OSM map of that area, and lift and piste names and grades look right for a few known runs.
- [ ] Attribution: the stored `source_note` ends with "(c) OpenStreetMap contributors (ODbL)" and names the OSM data timestamp; the resort screen (step 3) shows the attribution wherever names or geometry appear.
- [ ] Size: note the response size and the database growth for a large resort at the default radius; it must stay well under the 16 MB cap. Try 25 km once and record what happens (refused as too large, a server timeout remark, or a full answer).
- [ ] Airplane mode after a download: the cached resort lists and loads its ways and stations, and a refresh attempt fails with a network error that keeps the cached copy (age unchanged).
- [ ] The User-Agent contact constant (`HttpResortFetcher.CONTACT`) is set to a real contact before any public build: `OPENSKI_RELEASE_CHECK=1` then `.\gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.ReleaseCheckTest" --rerun` must pass (it is skipped without the variable).
- [ ] If HTTP 429 occurs naturally: exactly one retry after at least 30 s, no further requests. An HTTP 504 is reported at once and not retried.

## GPX import checks (phone needed; Ski day screen, "Import a GPX file")

The parser hardening in `GpxCodec` is unit-tested on the JDK only. Android's DOM parser rejects most hardening switches, so on a phone the protection is the refusal of any document type or entity declaration before parsing.

1. Import a GPX whose prolog contains `<!DOCTYPE gpx [<!ENTITY e SYSTEM "file:///proc/self/status">]>` and uses `&e;` in the track name: the import is refused with "declares a document type" and no name or file content appears anywhere.
2. Import a GPX exported by the app (`ski-day.gpx`): the fixes come back unchanged (count, first and last time).
3. Import a file over 20 MB: refused as too large, no crash or long freeze.
4. Import a real GPX of your own from another app or watch: a day with source "imported GPX" appears with the file's track name, its first and last point times, and runs and lifts that look right against what you skied; the message gives the points imported and skipped.

## Ski day checks (phone needed; boots optional)

Whole-day tracking (`DayTrackingService`), the day report, the ski day and resort screens, run and day tags, and the GPX and JSON exports are JVM-tested on synthetic days only (pure parts) and compiled; none of it has run on a phone. The database version 13 check is compiled into `StorageInstrumentation` but has not run.

- [ ] Run `StorageInstrumentation` on an emulator and expect the PASS line to include "v12->v13 ski days" (the older 8->9, 9->10 and 10->11 checks were fixed in the same change and need this run too).
- [ ] Permission flow on a fresh install (Android 13 or later): Today, Ski day, Start day asks for precise location and notifications. Refuse location: nothing starts and the screen says precise location is needed, with "Allow location in Settings". Grant approximate only: same refusal. Grant precise but refuse notifications: tracking starts and the screen says the notification will not show.
- [ ] A short walk (15 to 20 minutes, including a few minutes standing still and some up and down): the tracking line counts fixes, shows the last fix age and accuracy, says "standing still" after about two minutes still and returns to one fix a second on walking off. Afterwards the day shows segments (a walk on the flat is expected to read as rest) and plausible totals.
- [ ] Screen off: lock the phone for 10 minutes while walking; fixes keep arriving at the expected rate (count roughly matches the time) and the notification stays with its Stop day action, which ends the day.
- [ ] Barometer: on a phone with one the line says "barometer on" and the JSON has barometer samples; on one without, "no barometer" and the day still splits on GPS altitude.
- [ ] Kill the app process (Settings, force stop, or `adb shell am kill com.openski.android` while backgrounded) during tracking: the service restarts and resumes the same day, or the screen offers Resume or End for the open day. Note whether Android 14 allows the restarted location service to receive fixes.
- [ ] Location switched off during tracking: the screen and notification say so and tracking resumes by itself when it is switched back on.
- [ ] Resort download over Wi-Fi (resort picker, search and "around me"), then "Use for this day": runs and lifts show names and grades, and "Piste and lift names and lines: (c) OpenStreetMap contributors (ODbL)" is visible on the day screen.
- [ ] Airplane mode: the day screen still names runs from the cached map; a download attempt says the phone is offline and keeps the cached copy.
- [ ] Tags: edit the day's tags and notes and tag one run; reopen the screen and the tags are there; the JSON carries them.
- [ ] Export ski-day.gpx and open it in a GPX viewer (another app or a desktop tool): the track and times match the day. Export ski-day.json: it opens as JSON, shows segments, totals, tags, the resort name and the attribution.
- [ ] With a boot recording during the walk: while it is still recording, the overlapping runs say "session still recording, boot stats not included" and the recording is not disturbed (no storage error, no disconnect) while the day screen is open; after it ends, those runs show a boot line and runs outside it show none. The boot numbers come from dry-ski boot roll and say nothing about technique.
- [ ] Resort picker "around me" with a last position older than 5 minutes (phone just switched on, location off for a while): it says how old the position is and asks for a fresh fix (Android 11 and later) rather than downloading the old area.
- [ ] Leave a day open overnight (or set the phone clock 13 h ahead): opening the day screen or resuming closes it at its last fix instead of continuing it.
- [ ] Rotate the phone while the export save picker is open: the export still writes the file.
- [ ] Battery: note the battery drop over one hour of tracking with the screen off (unmeasured so far).


## Practice records and source separation (v14)

- [x] Pixel 9a emulator, 19 October 2026 — StorageInstrumentation: fresh install and v13→v14 upgrade, failed-import rollback, identical-looking legacy rows, repeat import, immutable completion, target/session round trip and session deletion preserving practice history.
- [ ] Upgrade with both demo and measured preference attempts. Original rows/scores remain under their correct source; the UI explains why measured totals may now exclude old demos.
- [ ] Complete a demo: it appears in Demo, changes no My practice best/streak/unlock, and uses practice points rather than GPS vertical units. Demo drills remain freely explorable.
- [ ] Finish a measured set early: recent history says incomplete, with no stars/points/unlocks. A complete measured set retains normal version-1 scoring.
- [ ] Open a locked measured drill through Demo and try My boots: measured progression is still enforced.
- [ ] Failure paths: malformed legacy JSON leaves source preferences untouched; a storage failure shows an error rather than empty successful history; Retry save uses the same attempt identity.
- [ ] Path/Progress source choice survives rotation. No device-BLE validation is claimed for this increment; drill lifecycle preservation is scheduled separately.


## Service-owned drill lifecycle (increment 2)

- [ ] Real boots: rotate during connection, mounting, hold, calibration, running and saving; phase/count/session identity survive and the result is stored once.
- [ ] Real boots: leave the screen and lock the phone during capture, then return through the notification. Samples continue; Finish stops only that drill's recording.
- [ ] Start another drill/run/manual recording/coaching session while a drill is active. The app returns to the active drill or refuses the competing command; capture/calibration is not stolen.
- [ ] Disconnect during hold/calibration and reconnect. Calibration does not accept an old callback or stale stream; data gaps are not invented movements.
- [ ] Process/service death during a live set produces an interrupted attempt with no completion reward, and the retained raw recording is recoverable on reconnect.
- [ ] Save failure remains visible and retry uses the same identity; no second reward or replacement of validated raw data.
- [x] Pixel 9a emulator, 19 October 2026 — mounting chooser opens on demand, repeated updates/clicks do not stack it, and cancel/dismiss retains practice state.
- [x] Pixel 9a emulator, 19 October 2026 — demo rotation/background/return produces one committed result. Force-stop during a demo and reopen Home: interrupted, zero stars/points, no duplicate attempt.
- [x] StorageInstrumentation — ACTIVE→terminal updates preserve identity; failed orphan raw-session closure rolls back interruption, retry closes only the linked raw session, and repeated recovery is idempotent.

## Accessible practice and exact result links (increment 3)

- [x] Pixel 9a emulator, 19 October 2026 — `StorageInstrumentation` includes `PracticeUiRegression`: native button role and selected navigation nodes, chart summaries with no target, coaching focus/scroll stability across verdicts, exact run/session route, exact practice identity after reopen, and missing-record fallbacks. The UI subset also runs with `-e practice_ui_only true`.
- [ ] TalkBack on a physical phone: headings, enabled/disabled actions, selected tab/window and onboarding checked choices are understandable; verdict updates keep accessibility focus and avoid repeated tick announcements.
- [ ] Font/display size: 130% and largest supported setting, portrait/landscape. Buttons wrap without clipping and charts retain readable labels. Outdoor/glove checks remain device-only.
- [x] Emulator portrait at 130% font: completed demo chart and saved attempt summary reviewed visually; Open saved result remained reachable by scrolling. Largest font/display size and landscape remain open.
- [ ] Open a measured result, demo result and imported historical result; confirm source, saved score and target/unknown-target labels. Delete a linked raw session and confirm the practice result remains readable.
- [ ] Run saving: Continue saving in background does not stop recovery or imply restored Training mode. Open saved run goes directly to that session, even when newer sessions exist.

## Guided preparation and shared recovery (increment 4)

- [x] JVM, 19 October 2026: all readiness blockers include actions; guidance does not block runs, conflicts block demos, queued mode restoration waits for confirmation, and reconnect requires a fresh zero report. Recovery facts distinguish download/validation/checked-copy/erase and retained-error states. Final focused checks also cover duplicate saved assignments and protected same-owner reconnection.
- [x] Pixel 9a emulator StorageInstrumentation, 19 October 2026: pending records survive store reopen; global recovery includes older pending work and the latest saved session; exact-session queries stay scoped. Guided setup reopens pending/paused recovery and protects assignment, forgetting and mode changes. Existing staged-recovery/export protections and UI regressions pass.
- [ ] Real boots: discover and assign Left/Right from guided setup, reject duplicate assignment, reconnect saved sensors, set mounting and neutral zero. Confirm a saved address alone is never described as connected.
- [ ] Real boots: retain a recording, reconnect and recover from setup without Geek mode. Today, Run and saved results agree on waiting/downloading/validating/checked-copy/erase state. Leave/reopen each screen during recovery.
- [ ] Pause recovery, recreate the service/process, and reopen setup: paused status remains; connect/resume or retry resumes explicitly. Failed download/erase preserves the checked phone copy and sensor protection as applicable.
- [ ] Mode/zero: lost command acknowledgement, disconnect/reconnect and failed validation never show false restoration. Training is unavailable until protected recovery is complete. Mode commands do not interrupt active run/drill/manual capture.
- [ ] Audio route loss, denied Bluetooth permission, Bluetooth off, low phone storage and missing sensor copy have usable recovery paths. GPS refusal still permits a run without GPS.
- [ ] Field checks: phone screen off, bright light, cold/gloves, battery and real flash-download reliability remain unverified by emulator/JVM checks.

## Compact layouts and persistent controls (increment 5)

- [ ] `PhaseLayoutRegression`: compact portrait/landscape at 130%/200% fonts, no footer/content overlap, retained focused action and current callback across warnings, and keyboard inset geometry. Run through the instrumentation runner after integration.
- [ ] Emulator: Run Ready, Zeroing/retry, Running, Saving and Drill intro/active/save failure keep their action reachable after scrolling and long warnings. Verify the current blocker remains understandable and its resolution action is reachable.
- [ ] Emulator: expand Run preparation, drill instructions and setup options, then rotate/reopen. Check expansion and phase, reading order, TalkBack roles/focus and exact-result links.
- [ ] Real phone: bright sunlight, cold/gloves and physical-button gestures. Touch targets retain at least the previous 64dp phase action height; field ergonomics remain unverified.
