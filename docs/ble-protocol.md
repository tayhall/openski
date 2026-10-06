# OpenSki BLE protocol, version 1

The ESP32 acts as a BLE GATT peripheral. An Android app scans for `OpenSki-ski`, connects, discovers the OpenSki service, reads status, and subscribes to live sample notifications.

## GATT identifiers

- Service: `b1e7a100-3c31-4d59-a2c8-1e9f2f810001`
- Live sample characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810002` (read, notify)
- Status characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810003` (read)
- Recorder control characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810004` (read, write, notify)
- Recorder data characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810005` (notify)

## Live sample notification

Each 19-byte frame uses little-endian fields and fits the default BLE ATT payload size:

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | protocol version | `1` |
| 1 | 2 | sequence | unsigned, increments modulo 65536 |
| 3 | 4 | timestamp | unsigned milliseconds from the ESP32 monotonic clock |
| 7 | 2 | acceleration X | signed integer, value × 100 gives m/s² |
| 9 | 2 | acceleration Y | signed integer, value × 100 gives m/s² |
| 11 | 2 | acceleration Z | signed integer, value × 100 gives m/s² |
| 13 | 2 | gyro X | signed integer, value × 1000 gives rad/s |
| 15 | 2 | gyro Y | signed integer, value × 1000 gives rad/s |
| 17 | 2 | gyro Z | signed integer, value × 1000 gives rad/s |

Notifications carry every second IMU sample, a steady 50 Hz (an earlier time-based limit measured only about 38 Hz). The IMU continues sampling at 100 Hz; the Android client can use timestamps and sequence numbers to detect stream gaps. Temperature is available in the status characteristic.

## Status characteristic

The 12-byte value is refreshed by the firmware:

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | protocol version | `1` |
| 1 | 1 | flags | bit 0 IMU ready, bit 1 sample available, bit 2 BLE client connected |
| 2 | 4 | sample count | unsigned successful IMU samples |
| 6 | 4 | read failures | unsigned I2C/read failures |
| 10 | 2 | temperature | signed integer, value × 100 gives °C |

The GATT identifiers and live frame version are shared between firmware and the Android client. The recorder protocol has its own version field and does not change the live sample frame.

## Recording and synchronization

The Android app starts recording on each boot sensor separately. Each sensor stores full-rate (100 Hz) samples in its internal SPI flash until the app stops it or the safe storage limit is reached. A single session is retained until the app downloads it and sends the erase command. The current 4 MB DevKit V1 layout reserves 70% of its SPIFFS partition for samples, giving about 10 minutes at 100 Hz. The app remains the long-term store for a full day.

Subscribe to recorder-control notifications before sending commands. Before requesting a download, also subscribe to the recorder-data characteristic. Commands are little-endian:

| Command | Bytes | Meaning |
| --- | --- | --- |
| Start | `01` | Begin a new session; rejected while an older session remains |
| Stop | `02` | Stop and flush queued samples to flash |
| Info | `03` | Return recorder state and counters |
| Erase | `04` | Delete the stopped session after the app has safely stored it |
| Download | `05 offset[4]` | Send sample records beginning at a zero-based record offset |
| Cancel download | `06` | Stop the current transfer |

Every control response is 16 bytes: protocol version `2`, response opcode, result code, flags, sample count (`uint32`), dropped sample count (`uint32`), and maximum sample count (`uint32`). All multibyte fields are little-endian. Response opcode matches the command; `85` signals download completion or failure. Result codes are `0` success, `1` storage unavailable, `2` busy, `3` no saved session, `4` invalid offset, `5` flash read/write error, and `6` invalid command. Flag bits are storage ready (0), recording (1), session present (2), capacity reached (3), storage error (4), and download active (5).

Each saved sample is 16 bytes: timestamp in microseconds since boot (`uint32`), acceleration X/Y/Z (`int16`, each divided by 100 gives m/s²), then gyro X/Y/Z (`int16`, each divided by 1000 gives rad/s). Download notifications begin with marker `D0` and a 24-bit little-endian first-record index, followed by one or more contiguous records. The firmware sizes each notification to the negotiated ATT MTU; default MTU carries one record. The Android app should verify each index, resume from the first missing record after a disconnect, and erase only after the complete session has been saved and validated.

The two sensors keep independent sessions and counters. The Android app should store the left and right streams separately with their own timestamps; their clocks are relative to each sensor's boot and are not synchronized by this protocol.
