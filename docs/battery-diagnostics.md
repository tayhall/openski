# HW-747 battery sensing investigation

The opt-in `esp32-s3-supermini-adc-diagnostic` build adds `GET /api/v1/diagnostics/adc`. It preserves the normal Super Mini acquisition profile and reads GPIO1, 2, 4, 5, 6, 7 and 10 on demand. It excludes IMU GPIO8/9, GPIO3 (strap), USB, flash/PSRAM and ADC2. Requests are rejected while flash recording or a BLE connection is active. Use with the current known wiring only; attach no peripherals to the scanned pins during this investigation.

Each scan reports the mean of 16 raw conversions and a separate set of 16 calibrated millivolt conversions with minimum/maximum, at 12-bit resolution and 11 dB attenuation. Raw and millivolt samples are separate conversions, not pairs. Pins are never driven or given software pull-ups/down. The diagnostic does configure the selected pads for analogue input. It runs only when requested and can disturb acquisition timing during that request.

Battery voltage and charge percentage remain null. Stable pin voltage is only a candidate connection: floating pins, regulated rails and unrelated signals can look plausible. A verified divider ratio and comparison with measured battery voltage are needed before converting any ADC reading into battery voltage. No battery-sense connection on omitted pins is ruled out. No charger-status pin is identified by this scan.

References: [Arduino ADC API](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/adc.html), [board product photos](https://esp32s.com/product/esp32-s3-supermini-development-board/).

## 2026-10-11 battery-powered board check

Flashed the diagnostic build to `ski-s3` at 192.168.43.61 over authenticated OTA after confirming no active or retained flash recording. Build: `953ea50-dirty 2026-10-11`. The user reports battery power. Repeated scans are retained in [raw results](../data/bench/adc-battery-2026-10-11.json).

No stable battery-sense candidate appeared on the seven scanned pins. Initial conversions on GPIO1 and GPIO6 included large excursions followed by predominantly near-zero readings on subsequent scans; this is consistent with floating or unsettled inputs, not evidence of battery voltage. Other scanned pins also read predominantly near zero with occasional excursions. This does not rule out a sensing connection on excluded pins and does not identify charging status. Battery voltage and charge remain unavailable.
