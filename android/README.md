# OpenSki Android app

Native Kotlin Android app for the OpenSki ESP32 BLE sensors. This first app milestone scans for two sensors, assigns one to each boot, and displays the live 50 Hz IMU stream. The BLE UUIDs and 19-byte decoder follow [`../docs/ble-protocol.md`](../docs/ble-protocol.md).

## Open and run

Open `D:\projects\openski\android` in Android Studio. The project uses JDK 17, Android SDK 36, Android Gradle Plugin 9.0.1, and built-in Kotlin. Sync Gradle, select the connected Android phone, and run the `app` configuration. Enable Bluetooth and grant nearby-device and notification permissions when prompted.

For a USB phone connection, enable Developer options and USB debugging, connect the phone, then accept its debugging prompt. The phone must be nearby with Bluetooth enabled for sensor discovery. The app uses the advertised OpenSki service UUID so unrelated BLE devices are filtered out.

## Current scope

- Live telemetry for left and right sensors.
- Record and retain raw sensor samples in a local SQLite database on the phone.
- Browse session date, duration, and left/right sample counts; tap a session for its detail.
- Attach a video from Android's file picker and open it from the session detail. The app stores a document URI, not a duplicate video file or cloud copy.
- Runtime Bluetooth scan/connect permissions for Android 12 and newer.
- No turn classification yet.
- Recording and BLE links run in a connected-device foreground service, so recording can continue with the screen off and the app backgrounded. The notification shows recording state and sensor reconnect warnings.
- Sensor assignments are remembered on the phone and the app reconnects automatically with a capped retry delay.
- Automatic sensor-side flash download is a later milestone.

Sessions are stored in the app's private storage and remain on the phone. Removing the app clears them. Video files remain in their original location; if a file is moved or deleted, its session attachment may no longer open. Recording captures notifications received over BLE; the app retries sensor links after disconnects, but samples are unavailable during the gap.

The sensor recorder's start/stop/download protocol is documented in the firmware protocol. Device-side recording requires the firmware image with the SPIFFS partition layout and a one-time USB flash if the board still has the earlier ESPHome partition table.
