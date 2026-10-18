# OpenSki BLE protocol, version 1

The ESP32 acts as a BLE GATT peripheral. An Android app scans for `OpenSki-ski`, connects, discovers the OpenSki service, reads status, and subscribes to live sample notifications.

## GATT identifiers

- Service: `b1e7a100-3c31-4d59-a2c8-1e9f2f810001`
- Live sample characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810002` (read, notify)
- Status characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810003` (read)
- Recorder control characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810004` (read, write, notify)
- Recorder data characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810005` (notify)
- Movement event characteristic: `b1e7a100-3c31-4d59-a2c8-1e9f2f810006` (read, notify)

## Live sample notification

Each 20-byte frame uses little-endian fields and fits the default BLE ATT payload size (23-byte MTU minus 3 bytes of ATT header = 20). Live frame versions 1 (19 bytes, no quality byte) and 2 (20 bytes) both exist; the Android app decodes both. Current firmware sends version 2.

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | protocol version | `2` (`1` for the older 19-byte frame) |
| 1 | 2 | sequence | unsigned, increments modulo 65536 |
| 3 | 4 | timestamp | unsigned milliseconds from the ESP32 monotonic clock |
| 7 | 2 | acceleration X | signed integer, value × 100 gives m/s² |
| 9 | 2 | acceleration Y | signed integer, value × 100 gives m/s² |
| 11 | 2 | acceleration Z | signed integer, value × 100 gives m/s² |
| 13 | 2 | gyro X | signed integer, value × 1000 gives rad/s |
| 15 | 2 | gyro Y | signed integer, value × 1000 gives rad/s |
| 17 | 2 | gyro Z | signed integer, value × 1000 gives rad/s |
| 19 | 1 | quality | OR of the quality flags of every IMU sample since the previous notification, including the samples skipped by decimation; bit 0 accelerometer near rail, bit 1 gyro near rail, bit 2 timestamp gap, bit 3 timestamp wrap; bits 4 to 7 `0` |

Notifications carry every second IMU sample, a steady 50 Hz (an earlier time-based limit measured only about 38 Hz; 52 Hz on LSM6DSOX builds, which sample at 104 Hz). The IMU continues sampling at its own rate (100 Hz, or 104 Hz on LSM6DSOX builds); the Android client can use timestamps and sequence numbers to detect stream gaps. Temperature is available in the status characteristic. The quality byte is cleared after each notification and while live notifications are off (production mode or no client), so it never describes samples from before the previous frame. The rail bits mean a raw axis lay within 8 counts of full scale; they are not proof that no clipping happened between reads (see [IMU data](imu-data.md)).

## Status characteristic

The 12-byte value is refreshed by the firmware:

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | protocol version | `1` |
| 1 | 1 | flags | bit 0 IMU ready, bit 1 sample available, bit 2 BLE client connected |
| 2 | 4 | sample count | unsigned successful IMU samples |
| 6 | 4 | read failures | unsigned I2C/read failures |
| 10 | 2 | temperature | signed integer, value × 100 gives °C |

The GATT identifiers and live frame version are shared between firmware and the Android client. The status characteristic stays at version `1`. The recorder protocol has its own version field (now `3`) and the live frame has its own (now `2`).

## Movement event notification (experimental)

The firmware runs `tilt_v1` (see [bench motion recognition](bench-motion-recognition.md)) and sends one 15-byte frame when a tilt excursion completes: the sensor tilted at least 20° away from its neutral pose and came back inside 10°. Fields are little-endian. The angles are sensor-frame estimates, not validated boot or ski angles.

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | event version | `1` |
| 1 | 1 | axis | sensor axis the tilt rotated about: 0 = x, 1 = y, 2 = z |
| 2 | 2 | sequence | unsigned, the excursion count modulo 65536 (starts at 1 after boot) |
| 4 | 4 | start time | unsigned milliseconds, the same ESP32 clock as the live frame |
| 8 | 2 | duration | unsigned milliseconds, saturating at 65535 |
| 10 | 2 | peak tilt | signed, value / 100 gives degrees away from neutral |
| 12 | 2 | rotation about axis | signed, value / 100 gives degrees; positive is the sensor rotating right-handed about the axis, as for the gyro |
| 14 | 1 | axis fraction | unsigned, value / 100 gives the share of the peak rotation about that axis |

One notification is sent per excursion, at most one per firmware loop. A client that connects later is not sent earlier events, and the sequence number shows a gap if notifications were lost. The characteristic can be read for the latest event. The neutral pose is captured automatically after boot and can be reset over Wi-Fi with `POST /api/v1/motion/zero`, or from the app with recorder-control opcode `07` (below).

### Frame versions on this characteristic

The first byte selects the frame. Version 1 is the `tilt_v1` event above, version 2 the `ski_v0` half-turn, version 3 the `ski_v0` state, version 4 the heading turn and version 5 the magnetometer (left boot only, experimental). Clients must ignore versions they do not know, so a client built before version 4 skips it. One frame is sent per firmware loop at most, and the last-sent sequence is tracked per type, so one type never hides another.

### `ski_v0` half-turn (version 2, 18 bytes, experimental)

Sent when a half-turn completes: roll leaves a ±8° band around the zeroed pose and later crosses zero to the other side. The values are cuff lean in a sensor-derived frame, not ski edge angle, and nothing here classifies a turn.

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | version | `2` |
| 1 | 1 | flags | bit 0 side `+` (roll > 0), bit 1 outside the expected envelope, bit 2 pitch outside its envelope range, bit 3 samples were skipped (impact gate) during the half-turn |
| 2 | 2 | sequence | unsigned, `ski_v0` event count modulo 65536 (starts at 1 after boot) |
| 4 | 4 | start time | unsigned milliseconds, the same clock as the live frame |
| 8 | 2 | duration | unsigned milliseconds, saturating at 65535 |
| 10 | 2 | peak roll | signed, value / 100 gives degrees; positive means the leg leaned toward the sensor's lateral axis (the boot's outer side when mounted as described in the motion recognition notes; the app mirrors the right boot so positive reads as leaning left on both legs) |
| 12 | 2 | peak roll rate | unsigned, value / 10 gives degrees per second |
| 14 | 2 | pitch at peak | signed, value / 100 gives degrees forward of the zeroed pose |
| 16 | 2 | reserved | `0` |

The envelope flag means peak roll above 60°, rate above 300 °/s, duration outside 0.3 to 4 s, or pitch outside −15° to +45°. It is a plausibility check, not a quality score.

### `ski_v0` heading turn (version 4, 16 bytes, experimental)

Sent when a lobe of the causal heading rate (rotation about the world vertical) completes. It is a heading-rate lobe, not a turn classification: nothing here says the skier made a ski turn.

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | version | `4` |
| 1 | 1 | flags | bit 0 side `+` (positive heading rate), bit 1 samples were skipped (impact gate) during the lobe, bit 2 the rate came near the gyro range limit, bit 3 reserved `0` (a sample gap abandons the lobe, so there is no gap bit) |
| 2 | 2 | sequence | unsigned, heading-turn count modulo 65536 |
| 4 | 4 | start time | unsigned milliseconds, the same clock as the live frame |
| 8 | 2 | duration | unsigned milliseconds, saturating at 65535 |
| 10 | 2 | peak rate | signed, value / 10 gives degrees per second, saturating at ±32767 |
| 12 | 2 | heading change | signed, value / 100 gives degrees, saturating at ±32767 |
| 14 | 2 | reserved | `0` |

Positive means a right-handed rotation about world-up, which is counter-clockwise seen from above (a left turn). The detector smooths the rate causally, so start and end are shifted back by the 0.25 s smoothing delay to line up with the motion; the frame itself is still sent only after the lobe ends. Heading change is the signed integral of the de-trended rate over the lobe.

Heading-turn frames are sent in production mode as well as diagnostics, like the half-turn and state frames.

### `ski_v0` state (version 3, 16 bytes, experimental)

Sent once a second while a client is connected, in both modes.

| Offset | Size | Field | Encoding |
| --- | ---: | --- | --- |
| 0 | 1 | version | `3` |
| 1 | 1 | flags | bit 0 zeroed, bit 1 production mode, bit 2 sample gap in the last second |
| 2 | 2 | sequence | unsigned, heartbeat count modulo 65536 |
| 4 | 4 | time | unsigned milliseconds, the same clock as the live frame |
| 8 | 2 | roll now | signed, value / 100 gives degrees |
| 10 | 2 | pitch now | signed, value / 100 gives degrees |
| 12 | 2 | vibration | unsigned, value / 100 gives m/s², RMS of high-passed acceleration over the second |
| 14 | 2 | gyro activity | unsigned, value / 10 gives degrees per second, RMS over the second |

### Magnetometer (version 5, 16 bytes, experimental)

Sent only by builds with a LIS3MDL magnetometer (the left boot, `OPENSKI_MAG_LIS3MDL`), only in diagnostics mode (never in production), only while a client is connected, at 10 Hz (the latest sample each 100 ms; no frame if there is no new sample). Live only: magnetometer data is not written to flash. Values are raw sensor counts at +-4 gauss, 6842 counts per gauss, with no calibration, hard-iron or soft-iron correction and no heading; treat them as uncalibrated and unvalidated on a boot. Clients that do not know version 5 ignore it.

| Offset | Size | Field | Notes |
| --- | ---: | --- | --- |
| 0 | 1 | version | `5` |
| 1 | 1 | flags | bit 0 a raw axis is within 100 counts of the +-4 gauss limit (27368 counts), bit 1 reads failed since the previous magnetometer frame; other bits 0 |
| 2 | 2 | sequence | `uint16`, own counter, wraps |
| 4 | 4 | time | `uint32` milliseconds, the same clock as the live frame (`esp_timer_get_time() / 1000`) |
| 8 | 2 | raw X | `int16` counts |
| 10 | 2 | raw Y | `int16` counts |
| 12 | 2 | raw Z | `int16` counts |
| 14 | 2 | reserved | `0` |

### Modes and motion commands

The boot has two runtime modes. **Diagnostics** is the default after every boot: Wi-Fi, the HTTP API, OTA and the 50 Hz live stream are on. **Production** turns Wi-Fi and the live stream off to save battery; half-turn events and the state frame still flow, and flash recording is unaffected. Mode is never stored: a power cycle returns to diagnostics.

Three commands are written to the recorder control characteristic. They work even when flash storage is unavailable, and the 16-byte response carries the usual flags plus bit 6 (set = production mode).

| Command | Bytes | Meaning |
| --- | --- | --- |
| Zero | `07` | Capture the neutral pose from the next still second (under 0.12 rad/s, within 5% of 1 g, roughly upright on the leg). Stand upright in the ski stance first. |
| Set mode | `08 mode` | `00` diagnostics, `01` production. Any other value or length returns result `6`. |
| Time sync | `09 token[2]` | Return the sensor clock; see below. Length must be 3, else result `6`. |

### Time sync (opcode `09`)

Lets the phone map each boot's clock to its own. Additive: the control version stays `3`, and a firmware without it answers result `6`. It works without flash storage and in both modes. Request: `09 token_lo token_hi`, the token a little-endian `uint16` chosen by the phone. The 16-byte response uses its own layout after the usual first four bytes:

| Offset | Size | Field |
| --- | ---: | --- |
| 0 | 1 | protocol version `3` |
| 1 | 1 | opcode `09` |
| 2 | 1 | result (`0` success, `6` wrong length) |
| 3 | 1 | flags, as in every response |
| 4 | 2 | token echoed from the request |
| 6 | 8 | sensor time in microseconds, `uint64`, little-endian (`esp_timer_get_time()`, the clock behind the 32-bit timestamps) |
| 14 | 2 | reserved `0` |

The sensor time is read in the BLE host task when the write arrives, not when the firmware later processes it (one queued request is handled per loop tick), so queueing delay does not enter the value. On a result `6` response the token and time fields are zero.

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

Every control response is 16 bytes: protocol version `3`, response opcode, result code, flags, sample count (`uint32`), dropped sample count (`uint32`), and maximum sample count (`uint32`). All multibyte fields are little-endian. Response opcode matches the command; `85` signals download completion or failure. Result codes are `0` success, `1` storage unavailable, `2` busy, `3` no saved session, `4` invalid offset, `5` flash read/write error, and `6` invalid command. Flag bits are storage ready (0), recording (1), session present (2), capacity reached (3), storage error (4), and download active (5).

Each saved sample is 16 bytes: timestamp in microseconds since boot (`uint32`, low 3 bits are flags, so the resolution is 8 us), acceleration X/Y/Z (`int16`, each divided by 100 gives m/s²), then gyro X/Y/Z (`int16`, each divided by 1000 gives rad/s). Download notifications begin with marker `D1` (records carry the flags below; `D0` was the format-2 marker, which version 3 firmware no longer sends) and a 24-bit little-endian first-record index, followed by one or more contiguous records. The timestamp field is the sample time in microseconds with the low 3 bits replaced by flags; mask them off (`timestamp & ~7`) before using the time. Bit 0: accelerometer near rail. Bit 1: gyro near rail. Bit 2: one or more earlier samples were dropped immediately before this one (the recorder queue was full; the bit is set on the next record that was stored, because a flag on a dropped record would be lost with it). The rail flags mean a raw axis lay within 8 counts of full scale on that read; absence of the flag is not proof that clipping did not happen between reads.

The firmware sizes each notification to the negotiated ATT MTU; default MTU carries one record. The Android app should verify each index, resume from the first missing record after a disconnect, and erase only after the complete session has been saved and validated.

The two sensors keep independent sessions and counters. The Android app should store the left and right streams separately with their own timestamps; their clocks are relative to each sensor's boot and are not synchronized by this protocol.

### Compatibility

This is a breaking change for live streaming in both directions, but only one pairing is unsupported. The new app still decodes old firmware: recorder status versions 2 and 3, `D0` download chunks and the 19-byte live frame v1 are all accepted. New firmware with an old app is not supported: an app built before live frame version 2 does not accept 20-byte frames or recorder protocol version 3, ignores frame versions it does not know, and cannot decode live frame v2 or marker `D1`. Update the app before or together with the sensors. Sessions already stored on the phone from version 2 firmware replay unchanged. The app fails closed on versions: a frame or response with a version it does not know is rejected rather than guessed at.

**Retained recordings.** Download and erase any retained recording before flashing recorder-v3 firmware; a recording made by older firmware that is downloaded afterwards has its timestamps' low three bits read as quality flags. Version 3 firmware always sends marker `D1`, and the stored flash file carries no format marker.

