# Bluetooth diagnostics over Wi-Fi

The firmware exposes `GET /api/v1/status` (also available as `/api/v1/ble`).
For the S3, use `http://ski-s3.local/api/v1/status` or its current LAN IP.
The existing `/api/v1/imu` sensor endpoint is unchanged.

The status includes Bluetooth connection and advertising state, successful
connection and disconnection counts, the last connection/disconnection times,
and the raw NimBLE disconnect reason. Timestamps are milliseconds since boot;
counters reset on reboot. A zero disconnect reason before the first disconnect
means no disconnect has been observed. The reason is the NimBLE host error
code, which can include a Bluetooth HCI error base; do not interpret it directly
as an Android GATT status code.

Device uptime, ESP reset reason, free heap, Wi-Fi RSSI and IMU sample/failure
counts help distinguish a BLE drop from a reboot, Wi-Fi loss or sensor fault.
Connection state describes the radio link, not whether Android has finished
subscribing to live telemetry. Counts come from Bluetooth GAP events and survive
between HTTP polls, so brief reconnects are still visible.

Record diagnostics without connecting the phone over USB:

```powershell
.\.venv\Scripts\python.exe tools/wifi_ble_log.py .pio/ble-status.jsonl --host 192.168.43.28 --duration 300
```

The logger saves one JSON record per poll, including UTC host time and network
errors, and prints connection-state changes. It polls approximately once per
second. HTTP access uses the existing unauthenticated LAN diagnostic server.
