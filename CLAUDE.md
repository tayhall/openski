# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OpenSki is a ski-boot motion sensor proof of concept with two halves in one repo:

- **Firmware** (repo root, PlatformIO/Arduino, C++17) for an ESP32 DevKit V1 (default) or ESP32-S3 with an MPU-6050 IMU.
- **Android app** (`android/`, native Kotlin, no Compose/AndroidX deps beyond the platform) that connects to two boot sensors over BLE, records, recovers flash recordings, and analyses sessions.

Ski turn classification is **not** implemented/validated. Turn candidates and boot roll are experimental; keep that framing in UI text, docs and exports.

The project was originally built with Codex; there is no AGENTS.md or Cursor/Copilot rule file. `docs/` holds the authoritative specs (see below).

## Firmware commands

```
pio run                                   # build default env (esp32-devkit-v1)
pio run -e esp32-s3                       # build ESP32-S3
pio run -e esp32-devkit-v1 -t upload      # USB flash
pio run -e esp32-devkit-v1-ota -t upload  # OTA flash (needs wifi_config.h)
pio device monitor -b 115200
pio test -e native                        # host Unity tests (test/test_imu_monitor)
pio test -e native -f test_imu_monitor    # single suite
```

On the Windows dev machine PlatformIO is installed in the git-ignored `.venv` (`.venvScriptspio.exe`, Python 3.13 from `C:Python313`), so `pio` is not on PATH. The DevKit is on COM11; if upload reports "Wrong boot mode detected", hold the BOOT button while it says "Connecting" (tap EN if needed). The first USB flash of the recorder partition layout has been done on the current board, so later updates can go over the air without BOOT: `PLATFORMIO_UPLOAD_FLAGS=$'--auth=<OTA password from wifi_config.h>\n--host_ip=<this PC's LAN IP>\n--host_port=45123' .venv/Scripts/pio.exe run -e esp32-devkit-v1-ota -t upload --upload-port ski.local` (redact the password in any output). **Separate the options in `PLATFORMIO_UPLOAD_FLAGS` with newlines, not spaces**: PlatformIO passes a space-separated string to `espota` as one `--auth` value, so extra flags get glued onto the password and the upload fails with "Authentication Failed". `--host_ip` is needed on this PC because the default listen address fails with "Listen Failed" (use the address of the LAN adapter, e.g. 192.168.43.x). If `ski.local` or `ski-s3.local` does not resolve, use the board's IP as `--upload-port`. The S3 Super Mini uses `-e esp32-s3-supermini-ota` and answers as `ski-s3.local`. A board that stops answering after a long run was fixed by a power cycle (ESP32 boards are 2.4 GHz only, so a router that steers clients to 5 GHz can drop them). The board is reachable at `http://ski.local/api/v1/imu`. A host compiler (WinLibs GCC, installed with winget on 7 October 2026) lets `pio test -e native` run; its `mingw64/bin` directory under `%LOCALAPPDATA%\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_*` must be on PATH, which shells opened before the install do not have. The standalone tests also build directly: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_tilt_tracker.cpp` (and `test_gesture_tracker.cpp`).

Setup: copy `include/wifi_config.example.h` to `include/wifi_config.h` (git-ignored; Wi-Fi SSID/password, OTA password). `AppConfig.h` falls back to empty strings if absent. Pins, hostname (`ski`) and BLE name (`OpenSki-ski`) live in `include/AppConfig.h`.

The DevKit V1 env uses a custom 4 MB partition table (`partitions/esp32dev-4mb.csv`) with a SPIFFS partition for recordings. A board previously running the ESPHome layout needs a **one-time USB flash** before OTA works.

## Android commands (run from `android/`)

Windows (primary dev machine). Gradle needs a JDK; the wrapper falls back to Android Studio's bundled JBR if `JAVA_HOME` is unset. SDK path is in git-ignored `local.properties`.

```
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest --tests "com.openski.android.SessionAnalysisTest"   # single class
.\gradlew.bat :app:assembleDebugAndroidTest
adb shell am instrument -w com.openski.android.test/com.openski.android.StorageInstrumentation
```

Instrumentation tests (`StorageInstrumentation`, a custom runner, not AndroidJUnitRunner) cover SQLite migrations v1–6, staged transfers, ZIP export and protected deletion in isolated databases; they run on an emulator. Real BLE/boot-angle behaviour needs hardware: see `docs/android-acceptance.md` and `docs/bench-validation.md`.

Toolchain: AGP 9.0.1 with built-in Kotlin, compile/target SDK 36, minSdk 26, Java 17 source compat with a Java 21 Gradle daemon (`gradle/gradle-daemon-jvm.properties`).

## Architecture

### Firmware (`src/`, `include/`, `lib/`)

`main.cpp` is a cooperative loop: each service exposes `begin()` and `tick()` in namespace `openski::<service>` and `loop()` calls them in order (imu, recorder, wifi, ota, telemetry, bluetooth, diagnostics) with a 2 ms delay, which must stay short enough to catch every 100 Hz IMU sample (a 10 ms delay caught only about 83 per second). Don't block inside a `tick()`. `tools/build_id.py` stamps `OPENSKI_BUILD_ID` (git commit, date) into the build; it is printed at boot and served as `build` in the Wi-Fi JSON. The IMU start-up is retried every 2 s with I2C bus recovery.

- IMU is chip-independent: `lib/Imu` defines the interface and `ImuMonitor` (host-testable, normalised samples); `lib/ImuMpu6050` is the only driver. A new chip (e.g. LSM6DSOX) should plug in behind the interface without touching consumers.
- IMU samples at 100 Hz. BLE live notifications are throttled to 50 Hz; the **flash recorder** (`RecorderService`) stores the full 100 Hz stream (about 10 minutes, one retained session) in SPIFFS until the app downloads it and issues erase.
- HTTP telemetry (`/api/v1/imu`, `/api/v1/motion`, `POST /api/v1/motion/zero`) and Arduino OTA run over Wi-Fi alongside BLE (NimBLE-Arduino).
- Motion recognition (`MotionService`): `lib/Motion` has the portable `GestureTracker` (gyro-burst rules) and `TiltTracker` (`tilt_v1`: gravity+gyro tilt against an auto-captured neutral pose; see `docs/bench-motion-recognition.md`). `include/SensorCalibration.h` holds fixed per-unit accel offset/scale and gyro bias, applied only to recogniser inputs and enabled for the S3 supermini envs. Recogniser output is experimental and in the sensor frame, not ski axes.
- `ski_v0` (`lib/Motion/src/SkiTracker.h`; frames and command parsing in `SkiFrames.h`) detects half-turns on the boot from roll and pitch against a zeroed pose and sends 18-byte events and a 1 Hz state frame on the movement characteristic. The firmware has two runtime modes (`ModeService`): **diagnostics** (default every boot; Wi-Fi, HTTP, OTA and the raw 50 Hz stream on) and **production** (Wi-Fi and raw stream off, events and state only, for battery life), switched by recorder-control opcode `08 mode`; zero is opcode `07`. Host tests: `g++ -std=gnu++17 -Ilib/Motion/src tools/test_ski_tracker.cpp` (and `test_ski_frames.cpp`).

### Wire protocol (shared contract)

`docs/ble-protocol.md` is the single source of truth for GATT UUIDs, the 19-byte little-endian live frame (v1), the 12-byte status characteristic, and the recorder control/data protocol (v2: start/stop/info/erase/download-at-offset/cancel, 16-byte responses). Firmware (`BluetoothService`, `RecorderService`) and Android (`BleSensorClient`, `RecorderProtocol`, `SensorSample`) must change together; update the doc and `RecorderProtocolTest` too. Protocol v2 has no recording ID or checksum, which drives the Android recovery design below. The experimental movement-event characteristic (15-byte frame per `tilt_v1` excursion) is decoded by `MovementEventProtocol.kt`; `BleSensorClient` subscribes when the firmware has it and dispatches frames on that characteristic by version byte (1 `tilt_v1` excursion, 2 `ski_v0` half-turn, 3 `ski_v0` state) to `SensorSessionService.Listener`; the Test lab's "Boot sensors" card shows the latest half-turn and state and sends zero and mode commands.

### Android app (`android/app/src/main/java/com/openski/android/`)

- **Training layer (launcher).** `HomeActivity` is the launcher: onboarding (goal, level, boots), then Today / Path / Progress / Boots tabs. `Programme.kt` holds the goals, pistes (blue Foundations, red Carving, black Short turns), the nine drills, `DrillScoring` and the pure `Progress` rules (unlocks, streak, vert); it is unit tested in `ProgrammeTest`. `DrillActivity` runs a drill on either demo boots or a live boot. Attempts, per-boot toe-axis mounting (`MountingPicker`) and the ghost trace live in `ProgressStore` (SharedPreferences), not SQLite.
  - Live path: `DrillActivity` binds `SensorSessionService` as its `Listener`, waits for a streaming boot, starts a *test* recording, calls `calibrateTest`, then feeds `onBootOrientation` rolls into `LiveDrillRecorder` (same `DrySkiAnalysis.Tracker` as the service and replay). It brackets the set with `START_TEST`/`PAUSE` markers and names the session "Drill · <title>" so it shows in the logbook. It follows one boot per drill. Only the pure parts (`LiveDrillRecorder`, scoring) are unit tested; the connect, calibrate and run flow has not been exercised on real BLE hardware.
  - Demo path: `DemoDrill` simulates a roll trace and runs it through the same detector. Demo attempts are flagged `demo`, labelled "Demo boots", and count toward the path.
  - Tempo and steadiness are scored on the beat (time between roll *starts*), not detected movement length, because the detector only times the excursion outside neutral.
  - `BenchActivity` (Boots tab, Bench tools) is a loose-board check on raw samples: six-face accelerometer check, tilt accuracy against a typed reference angle, and still gyro/accelerometer noise. A Landmarks step learns which signed axis points at the holes edge and the chip side (`Landmarks`, stored in `ProgressStore`) so `MountingPicker`, the calibrate screen and the Boots tab describe axes as "toward the holes edge". The six-face result can be saved as a per-sensor `AccelCorrection` (stored in `ProgressStore` by sensor address) that is applied only where orientation is computed: the service live path, calibration, session replay and the export's orientation columns. Recorded and exported raw samples stay uncorrected. Maths is in `BenchMath` (unit tested); the capture UI has not been run against a real streaming sensor yet.
  - **Coaching** (`Coach.kt`, `ToneSynth.kt`, `CoachSession.kt`, `CoachSettings.kt`, `DemoCoachFeed.kt`, `CoachAudio.kt`, `CoachingActivity.kt`): audio cues for a skier with the phone in a pocket. `Coach` judges non-overlapping windows of 3 to 5 `ski_v0` half-turns against a `CoachTarget` (a drill's beat and depth, or custom) and returns a rising chirp, a falling chirp or nothing; `MetronomeClock` ticks at the target beat. The judging, sounds, settings and demo feed are JVM-tested; `CoachAudio` and the service hook are only checked on a phone (see `docs/android-acceptance.md`). The service is declared `connectedDevice|mediaPlayback`. Entered from the Boots tab; it compares dry-ski boot roll with a training target, never on-snow technique.
  - **Runs** (`RunController.kt`, `RunActivity.kt`, `RunRecords.kt`, `ZeroTracker.kt`): the "ready at the top" flow. `RunController` is a pure state machine (Ready, Zeroing, Running, Saving, Done) reaching the boots, clock and database through `RunBoots` and `RunSink`; the service hosts one through adapters. Starting a run switches both boots to on-snow mode (remembering what it found), zeroes them, starts a `kind = 'run'` session with on-board recording and coaching, ends after 20 s without a half-turn (120 s before the first), saves the boots' flash through the existing recovery, then restores the mode only if the run changed it. Half-turns, coach verdicts and run settings are stored in `run_events`, `run_verdicts` and `run_info` (database version 8), as the boot sent them, and exported as `half-turns.csv` and `coach-verdicts.csv`. Runs are exempt from the production-mode recording guard; drills and test recordings are not. The logic and records are JVM-tested and the migration passes on the emulator; the service adapters with real boots, the audio and the lift download are only checked on hardware (see `docs/android-acceptance.md`).
  - **Run comparison** (`RunComparison.kt`, `RunComparisonCard.kt`): the target wave against the recorded wave for a run. `RunComparisons.build` turns a run's stored half-turns, verdicts and target into windows (each verdict takes the last `windowSize` half-turns before it), two waves per window, signed differences from the target, left versus right depth and a plain sentence. The recorded wave is reconstructed from the half-turns (a half-sine through each turn's measured peak, start and length), not the measured roll curve, and the card says so; the target is re-anchored at each window. The card reuses `CarvedLineView` and shows on the Done screen and in a logbook "Run" tab. The logic is JVM-tested; the shapes on real runs need the checks in `docs/android-acceptance.md`.
  - The Boots tab shows pairing and mounting and leads to **Geek mode**, which is `MainActivity` (the original Record / Sessions / Test lab screens with raw telemetry; no longer the launcher).
- UI is hand-built native Views with two looks: `Snow.kt` (light, Barlow fonts in `res/font`, piste-marker shapes, one orange primary action per screen, `CarvedLineView`) for the training screens, and the older dark `SkiUi.kt` for recording and review. New training screens use `Snow`; do not mix them. Scores measure dry-ski boot roll only, never on-snow carving.
- `SensorSessionService` is a connected-device foreground service that owns both BLE links (via `BleSensorClient`), recording, auto-reconnect with capped backoff, and flash recovery/erase. Recording must survive the screen off and the app being backgrounded; the UI activities (`MainActivity`: Record / Sessions / Test lab; `SessionDetailActivity`: Replay / Analysis / Details) only observe and command it.
- `LocalSessionStore` is a hand-rolled SQLite layer (schema currently at version 8 with migrations; add a migration plus an instrumentation check when changing it). Live and flash samples are stored as **separate sources**; exports keep both, and graphs prefer flash inside its covered intervals.
- Flash recovery rules: download into a staging copy, validate (frame shape, contiguous indices, committed count), commit, validate the committed SQLite copy, and only then erase sensor flash on the same uninterrupted connection. Interrupted downloads restart from record 0. Never replace validated data with an unvalidated staging copy.
- Timing: each boot has an independent clock. `LiveSensorClock` preserves sensor-clock intervals across BLE bursts and rollover; alignment is estimated from live samples and phone receipt times, with user-adjustable per-boot and video offsets.
- Analysis (`SessionAnalysis`, `BootOrientation`, `BootComparison`, `DrySkiAnalysis`, `GuidedTrial`): the live orientation estimator is the same incremental code as replay (a unit test checks streaming/replay equivalence); keep them in sync. Definitions and limits are in `docs/ski-analysis.md`.
- `DemoSession` creates a clearly labelled synthetic session that must stay marked as synthetic through rename and export.
- `SessionExport` writes ZIPs (raw CSV, events, orientation, labels, health snapshots, `detected-movements.csv`, `analysis-summary.json`, metadata JSON).
- Videos are referenced by document URI, never copied.
- Recording guards: needs a live sensor and at least 20 MB free, stops below 10 MB; sensor replacement and session deletion are blocked while recovery is pending.

## Docs

`docs/`: `bench-results.md` (dated measurements; add an entry per session), `ble-protocol.md`, `bring-up.md` (OTA and expected serial output), `imu-data.md`, `android-design.md` (UI), `android-acceptance.md`, `bench-validation.md`, `ski-analysis.md`, `feature-roadmap.md`, `poc-plan.md` (current hardware, test phases and pass criteria). POC decision: sensors mount at the top of the boot cuff near the calf, so the "toward the toe" axis is the board axis facing forward, not its length; see that doc. Update the relevant doc when behaviour changes; the READMEs state current scope and verification status.

## Housekeeping

- Git-ignored build/local artefacts: `.pio/`, `include/wifi_config.h`, `android/local.properties`, `*.bin`.
- Branch work happens on feature branches (current: `feat/firmware-development`); `main` is the PR target.
