# OpenSki Android app

Native Kotlin Android app for the OpenSki ESP32 BLE sensors. It remembers two boot sensors, records the live 50 Hz streams, recovers full-rate sensor flash recordings and provides session review/export tools. The BLE UUIDs and 19-byte decoder follow [`../docs/ble-protocol.md`](../docs/ble-protocol.md).

## Open and run

Open `D:\projects\openski\android` in Android Studio. The project uses a Java 21 Gradle daemon, Java 17 source compatibility, Android SDK 36, Android Gradle Plugin 9.0.1, and built-in Kotlin. Sync Gradle, select the connected Android phone, and run the `app` configuration. Enable Bluetooth and grant nearby-device and notification permissions when prompted.

For a USB phone connection, enable Developer options and USB debugging, connect the phone, then accept its debugging prompt. The phone must be nearby with Bluetooth enabled for sensor discovery. The app uses the advertised OpenSki service UUID so unrelated BLE devices are filtered out.

## Current scope

- Live telemetry for left and right sensors.
- Record and retain raw sensor samples in a local SQLite database on the phone.
- Browse sessions grouped by ski day; open quality summaries, graphs and recording events.
- Attach a video from Android's file picker and open it from the session detail. The app stores a document URI, not a duplicate video file or cloud copy.
- Runtime Bluetooth scan/connect permissions for Android 12 and newer.
- Experimental gyro turn candidates with selectable mounting axis/sign; these are not validated ski-turn classifications.
- Recording and BLE links run in a connected-device foreground service, so recording can continue with the screen off and the app backgrounded. The notification shows recording state and sensor reconnect warnings.
- Sensor assignments are remembered on the phone and the app reconnects automatically with a capped retry delay.
- Start and stop sensor flash recording alongside phone recording. Download retained flash recordings after stopping or reconnecting, validate their record order/count and the committed SQLite copy, then erase flash only on the same uninterrupted connection.
- Remember boot assignments, show label codes from Bluetooth addresses, and forget/replace or swap sensors when recording and recovery have finished.
- Rename sessions, add notes, delete completed sessions and export ZIP files containing raw CSV samples, events and JSON metadata.
- Review acceleration/gyro graphs beside attached video; adjust left/right time offsets and a video offset in seconds. Original live and flash sources remain separate in storage and exports.
- Show phone free space, sensor flash capacity and standard BLE battery level when supported. The current firmware does not report battery level.

Sessions are stored in the app's private storage and remain on the phone. Removing the app clears them. Export important recordings first. Video files remain in their original location; if a file is moved or deleted, its session attachment may no longer open. Live samples are unavailable during BLE gaps; sensor flash can fill gaps while the sensor remains powered and has capacity. Sensor flash holds approximately ten minutes, rather than an entire ski day. Keep sensors powered and nearby after stopping until recovery finishes.

Firmware protocol v2 has no unique recording ID or checksum. Interrupted downloads therefore restart from record zero instead of reusing an offset whose identity cannot be established. A staging copy never replaces previously validated data until the whole download passes validation. Unknown retained recordings get separate history entries with approximate timing. Validation covers frame shape, contiguous indices and committed record count, not an independent firmware checksum. Graphs prefer flash within its covered intervals; exports retain both sources, including overlaps.

Boot clocks are independent. The app estimates alignment from matching live samples and phone receipt times, or from phone start/recovery time when no live match exists. Coverage and timing remain estimates; use video offsets for review. Experimental turn candidates identify gyro lobes, including motions other than skiing. Real ski runs, mounting calibration and labelled video are needed before treating them as turns or using them for performance metrics.

Recording/recovery uses a foreground notification. Stop recording or pause recovery from its notification. Pausing leaves sensor data intact. Recording requires a live sensor and at least 20 MB free; it stops below 10 MB. Replacing sensors or deleting sessions is blocked while relevant recovery is pending.

## Windows USB debugging

Google's signed USB driver must be installed for Pixel debugging, not just Microsoft's generic WinUSB driver. The Pixel should appear as **Android Composite ADB Interface** in Device Manager. If it appears as a generic Pixel/WinUSB device and ADB cannot see it, install **Google USB Driver** from Android Studio's SDK Manager, then bind the driver through Device Manager or an administrator `pnputil /add-driver <android_winusb.inf> /install` command. Reconnect once after installation and accept the phone's debugging prompt. See [Google USB driver](https://developer.android.com/studio/run/win-usb).

The SDK is configured through ignored `local.properties`. Command-line builds can use Android Studio's bundled Java:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Java source compatibility is 17; the Gradle daemon configuration requests Java 21.

## Verification

Unit tests exercise packet decoding, interrupted/invalid transfer rejection, overlapping sources, coverage, clock rollover and turn candidates. `:app:assembleDebugAndroidTest` builds a custom instrumentation runner that checks v1 SQLite migration, staged transfers, retained validated copies, ZIP export and protected deletion in an isolated test database:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.openski.android.test/com.openski.android.StorageInstrumentation
```

Hardware acceptance checks still require powered boot sensors: record with the screen off, interrupt each BLE link, reconnect after stopping, interrupt a download and verify recovery without duplicate graph samples, confirm flash is erased only after a validated copy, and compare time alignment/turn candidates with real video.

The sensor recorder's start/stop/download protocol is documented in the firmware protocol. Device-side recording requires the firmware image with the SPIFFS partition layout and a one-time USB flash if the board still has the earlier ESPHome partition table.
