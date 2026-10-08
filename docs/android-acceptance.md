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
