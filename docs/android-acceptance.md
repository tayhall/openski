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
