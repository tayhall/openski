# HW-747 battery sensing investigation

The opt-in `esp32-s3-supermini-adc-diagnostic` build adds `GET /api/v1/diagnostics/adc`. It preserves the normal Super Mini acquisition profile and reads GPIO1, 2, 4, 5, 6, 7 and 10 on demand. It excludes IMU GPIO8/9, GPIO3 (strap), USB, flash/PSRAM and ADC2. Requests are rejected while flash recording or a BLE connection is active. Use with the current known wiring only; attach no peripherals to the scanned pins during this investigation.

Each scan reports the mean of 16 raw conversions and a separate set of 16 calibrated millivolt conversions with minimum/maximum, at 12-bit resolution and 11 dB attenuation. Raw and millivolt samples are separate conversions, not pairs. Pins are never driven or given software pull-ups/down. The diagnostic does configure the selected pads for analogue input. It runs only when requested and can disturb acquisition timing during that request.

Battery voltage and charge percentage remain null. Stable pin voltage is only a candidate connection: floating pins, regulated rails and unrelated signals can look plausible. A verified divider ratio and comparison with measured battery voltage are needed before converting any ADC reading into battery voltage. No battery-sense connection on omitted pins is ruled out. No charger-status pin is identified by this scan.

References: [Arduino ADC API](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/adc.html), [board product photos](https://esp32s.com/product/esp32-s3-supermini-development-board/).

## 2026-10-11 battery-powered board check

Flashed the diagnostic build to `ski-s3` at 192.168.43.61 over authenticated OTA after confirming no active or retained flash recording. Build: `953ea50-dirty 2026-10-11`. The user reports battery power. Repeated scans are retained in [raw results](../data/bench/adc-battery-2026-10-11.json).

No stable battery-sense candidate appeared on the seven scanned pins. Initial conversions on GPIO1 and GPIO6 included large excursions followed by predominantly near-zero readings on subsequent scans; this is consistent with floating or unsettled inputs, not evidence of battery voltage. Other scanned pins also read predominantly near zero with occasional excursions. This does not rule out a sensing connection on excluded pins and does not identify charging status. Battery voltage and charge remain unavailable.

## Battery sensing and low-power profile (implemented, unverified on a cell)

The ADC investigation above found no battery connection on the board's own pins, so the S3 Super Mini builds now read the cell through an added divider: two 1 kohm resistors in series across the cell, midpoint on **GPIO10** (ADC1_CH9) with 100 nF to GND. `OPENSKI_BATTERY_DIVIDER_RATIO` (2.0 for 1k + 1k) converts the pin voltage to the cell voltage. Builds that do not define `OPENSKI_BATTERY_ADC_PIN` report the sensor as unavailable and skip all of this. GPIO10 is excluded from the diagnostic ADC scan.

**Reading.** `BatteryService` takes one reading in `begin()` (before the radio starts, so it is not under Wi-Fi load), then one per minute: the mean of 16 conversions at 11 dB, after discarding the first. A read takes tens of microseconds and does not disturb the 100 Hz loop. The result is served at `GET /api/v1/battery` (`available`, `battery_voltage_v`, `pin_mv`, `divider_ratio`, `age_ms`; `charge_percent` stays null) and as `battery_voltage_v` and `power_profile` (`normal` or `safe_after_brownout`) in `/api/v1/status`. The voltage is null until the first reading. Charge percentage is not derived: a lithium cell's voltage is a poor state-of-charge signal under load and needs a measured discharge curve first.

**Power profile (`OPENSKI_LOW_POWER_RADIO`, set for the S3 Super Mini).**

- CPU at 80 MHz.
- BLE starts first. Wi-Fi starts 5 s later at 8.5 dBm with modem sleep. If it has not connected after 20 s, transmit power is raised to 15 dBm.
- Wi-Fi is held off while the cell is below 3.6 V and released at 3.65 V (hysteresis). A reading under 2.5 V is treated as "not a cell" (no battery, floating or mis-wired pin) and never holds Wi-Fi, so OTA stays reachable. The hold is only evaluated before Wi-Fi starts; it does not turn a running link off.
- **Safe boot after a brownout.** If the reset reason is a brownout, BLE is not started and Wi-Fi comes up at 5 dBm (11 dBm if it does not connect), with the low-cell hold bypassed, so the board stays reachable for OTA on a cell that cannot carry the full radio start-up. A normal power-on or software reset returns to the usual profile. The reset reason is printed at boot.

Host tests: `pio test -e native -f test_battery` (divider maths, sample interval across `millis()` rollover, hold hysteresis, implausible readings).

**Not yet verified on hardware:** the divider ratio against a multimeter, the 3.6 V and 3.65 V thresholds (they are placeholders until a discharge curve is logged), the 80 MHz CPU with the 100 Hz loop and I2C under load, the brownout safe boot, and run time on a real cell. See [bench results](bench-results.md) when measurements exist.
