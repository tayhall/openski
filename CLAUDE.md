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

`main.cpp` is a cooperative loop: each service exposes `begin()` and `tick()` in namespace `openski::<service>` and `loop()` calls them in order (imu, recorder, wifi, ota, telemetry, bluetooth, diagnostics) with a 10 ms delay. Don't block inside a `tick()`.

- IMU is chip-independent: `lib/Imu` defines the interface and `ImuMonitor` (host-testable, normalised samples); `lib/ImuMpu6050` is the only driver. A new chip (e.g. LSM6DSOX) should plug in behind the interface without touching consumers.
- IMU samples at 100 Hz. BLE live notifications are throttled to 50 Hz; the **flash recorder** (`RecorderService`) stores the full 100 Hz stream (about 10 minutes, one retained session) in SPIFFS until the app downloads it and issues erase.
- HTTP telemetry (`/api/v1/imu`) and Arduino OTA run over Wi-Fi alongside BLE (NimBLE-Arduino).

### Wire protocol (shared contract)

`docs/ble-protocol.md` is the single source of truth for GATT UUIDs, the 19-byte little-endian live frame (v1), the 12-byte status characteristic, and the recorder control/data protocol (v2: start/stop/info/erase/download-at-offset/cancel, 16-byte responses). Firmware (`BluetoothService`, `RecorderService`) and Android (`BleSensorClient`, `RecorderProtocol`, `SensorSample`) must change together; update the doc and `RecorderProtocolTest` too. Protocol v2 has no recording ID or checksum, which drives the Android recovery design below.

### Android app (`android/app/src/main/java/com/openski/android/`)

- **Training layer (launcher).** `HomeActivity` is the launcher: onboarding (goal, level, boots), then Today / Path / Progress / Boots tabs. `Programme.kt` holds the goals, pistes (blue Foundations, red Carving, black Short turns), the nine drills, `DrillScoring` and the pure `Progress` rules (unlocks, streak, vert); it is unit tested in `ProgrammeTest`. `DrillActivity` runs a drill on either demo boots or a live boot. Attempts, per-boot toe-axis mounting (`MountingPicker`) and the ghost trace live in `ProgressStore` (SharedPreferences), not SQLite.
  - Live path: `DrillActivity` binds `SensorSessionService` as its `Listener`, waits for a streaming boot, starts a *test* recording, calls `calibrateTest`, then feeds `onBootOrientation` rolls into `LiveDrillRecorder` (same `DrySkiAnalysis.Tracker` as the service and replay). It brackets the set with `START_TEST`/`PAUSE` markers and names the session "Drill · <title>" so it shows in the logbook. It follows one boot per drill. Only the pure parts (`LiveDrillRecorder`, scoring) are unit tested; the connect, calibrate and run flow has not been exercised on real BLE hardware.
  - Demo path: `DemoDrill` simulates a roll trace and runs it through the same detector. Demo attempts are flagged `demo`, labelled "Demo boots", and count toward the path.
  - Tempo and steadiness are scored on the beat (time between roll *starts*), not detected movement length, because the detector only times the excursion outside neutral.
  - The Boots tab shows pairing and mounting and leads to **Geek mode**, which is `MainActivity` (the original Record / Sessions / Test lab screens with raw telemetry; no longer the launcher).
- UI is hand-built native Views with two looks: `Snow.kt` (light, Barlow fonts in `res/font`, piste-marker shapes, one orange primary action per screen, `CarvedLineView`) for the training screens, and the older dark `SkiUi.kt` for recording and review. New training screens use `Snow`; do not mix them. Scores measure dry-ski boot roll only, never on-snow carving.
- `SensorSessionService` is a connected-device foreground service that owns both BLE links (via `BleSensorClient`), recording, auto-reconnect with capped backoff, and flash recovery/erase. Recording must survive the screen off and the app being backgrounded; the UI activities (`MainActivity`: Record / Sessions / Test lab; `SessionDetailActivity`: Replay / Analysis / Details) only observe and command it.
- `LocalSessionStore` is a hand-rolled SQLite layer (schema currently at version 6 with migrations; add a migration plus an instrumentation check when changing it). Live and flash samples are stored as **separate sources**; exports keep both, and graphs prefer flash inside its covered intervals.
- Flash recovery rules: download into a staging copy, validate (frame shape, contiguous indices, committed count), commit, validate the committed SQLite copy, and only then erase sensor flash on the same uninterrupted connection. Interrupted downloads restart from record 0. Never replace validated data with an unvalidated staging copy.
- Timing: each boot has an independent clock. `LiveSensorClock` preserves sensor-clock intervals across BLE bursts and rollover; alignment is estimated from live samples and phone receipt times, with user-adjustable per-boot and video offsets.
- Analysis (`SessionAnalysis`, `BootOrientation`, `BootComparison`, `DrySkiAnalysis`, `GuidedTrial`): the live orientation estimator is the same incremental code as replay (a unit test checks streaming/replay equivalence); keep them in sync. Definitions and limits are in `docs/ski-analysis.md`.
- `DemoSession` creates a clearly labelled synthetic session that must stay marked as synthetic through rename and export.
- `SessionExport` writes ZIPs (raw CSV, events, orientation, labels, health snapshots, `detected-movements.csv`, `analysis-summary.json`, metadata JSON).
- Videos are referenced by document URI, never copied.
- Recording guards: needs a live sensor and at least 20 MB free, stops below 10 MB; sensor replacement and session deletion are blocked while recovery is pending.

## Docs

`docs/`: `ble-protocol.md`, `bring-up.md` (OTA and expected serial output), `imu-data.md`, `android-design.md` (UI), `android-acceptance.md`, `bench-validation.md`, `ski-analysis.md`, `feature-roadmap.md`, `poc-plan.md` (current hardware, test phases and pass criteria). POC decision: sensors mount at the top of the boot cuff near the calf, so the "toward the toe" axis is the board axis facing forward, not its length; see that doc. Update the relevant doc when behaviour changes; the READMEs state current scope and verification status.

## Housekeeping

- Git-ignored build/local artefacts: `.pio/`, `include/wifi_config.h`, `android/local.properties`, `*.bin`.
- Branch work happens on feature branches (current: `feat/android-indoor-poc-design`); `main` is the PR target.
