# Production board bring-up (boot-v1)

Checklist for each prototype board of the production design in
[the spec](superpowers/specs/2026-11-15-production-board-design.md). These are bench measurements of a prototype run. Nothing here validates turn classification, and the magnetometer and boot-roll outputs stay experimental.

Build: `pio run -e boot-v1` (USB) and `boot-v1-ota` (over the air). Flags that matter, from `platformio.ini`: `OPENSKI_IMU_LSM6DSOX`, `OPENSKI_MAG_LIS3MDL`, I2C SDA 5 / SCL 6, `OPENSKI_BATTERY_ADC_PIN=2`, `OPENSKI_BATTERY_DIVIDER_ENABLE_PIN=1`, `OPENSKI_IMU_INT_PIN=4` with `OPENSKI_IMU_INT_USES_INT2`, `OPENSKI_HOSTNAME_MAC_SUFFIX`. Record results in [bench results](bench-results.md), one dated entry per session. Do the steps in order; stop at the first failure.

| # | Step | How | Pass criterion | Result |
|---|---|---|---|---|
| 1 | Continuity | Unpowered board, multimeter | No short between VBAT, 3V3 and GND | |
| 2 | 3V3 rail | USB only, no cell | 3.3 V within 5 % | |
| 3 | Charger and cold inhibit | Attach cell; chill the cell NTC | Charging starts at room temperature; stops with the NTC below 0 degC | |
| 4 | USB flash and bus scan | `pio run -e boot-v1 -t upload`, serial monitor | Build ID printed; I2C devices at 0x6A and 0x1C | |
| 5 | IMU stream | `http://ski-boot-xxxx.local/api/v1/imu` | `ready` true, about 104 Hz, gravity near 1 g at rest | |
| 6 | Magnetometer | `/api/v1/motion`; rotate the board | Mag present with changing counts. On a board built without the mag fitted, boot still completes and mag reports `present:false` | |
| 7 | Battery reading | Compare with a multimeter at 3.6 V and 4.0 V | Within 2 % | |
| 8 | Divider enable | Measure sleep current with GPIO1 floating, then with the bottom resistor tied to GND on a test unit | Normal unit draws less in sleep by about the divider current | |
| 9 | Motion wake | Put the board into deep sleep, tap it | Wakes via IMU INT2 on GPIO4 | |
| 10 | Deep-sleep current | Series meter on the cell lead | Record the figure. No limit yet: this is the missing baseline | |
| 11 | OTA | `pio run -e boot-v1-ota -t upload --upload-port ski-boot-xxxx.local` | Upload succeeds | |
| 12 | Two units on one network | Power two boards together | They resolve as different `ski-boot-xxxx.local` names | |
| 13 | BLE range | Real case, on a boot, phone in a pocket | Record the distance. No limit yet | |
| 14 | Cold soak | Follow the cold test in [bench results](bench-results.md) | Record only; no new limits are set here | |

Notes
- The hostname suffix is the last two bytes of the Wi-Fi MAC; the BLE name stays `OpenSki-ski` for every unit and the app tells boots apart by address.
- GPIO7 (mag INT), GPIO48 (LED) and GPIO0 (button) are wired on the board but unused by firmware, so there is nothing to check for them yet.
