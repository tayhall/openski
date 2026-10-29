# IMU wake-on-motion for the movement sleep (design draft)

Status: firmware built and host-tested 28 Oct 2026 on `feat/sleep-wake`; not run on a board (no boot is wired yet). Not reviewed by a separate builder check.

## Intent

A boot put to sleep on purpose (`SleepService::request()`) currently light-sleeps and wakes every 2 s to read the IMU, then restarts the board when `WakeDetector` sees movement. Light sleep on the ESP32-S3 costs roughly ten times a deep sleep (typical figures, unmeasured on these boards). The LSM6DSOX can watch for movement itself at a few microamps and raise an interrupt pin. Wire that pin to an RTC-capable GPIO and the ESP32 can deep-sleep until the IMU wakes it.

## Goals

- Movement sleep uses ESP32 deep sleep, woken by the IMU interrupt. Wake behaviour unchanged for the user: the board restarts and BLE and Wi-Fi come back through the normal boot.
- Builds without the interrupt wire keep today's light-sleep polling (flag `OPENSKI_IMU_INT_PIN` unset), so the left boot is unaffected until it is rewired.
- A dead or stuck IMU can never leave the board asleep with the radios off (today's 10 s silence check has to be replaced, see below).

## Non-goals

- The low-battery deep sleep (separate, timer or USB-sense wake).
- Changing the live, recorded or analysed data.
- Turn detection or any ski analysis.

## Hardware

- The fitted IMU is the Adafruit LSM6DSOX + LIS3MDL breakout (seen in photos of the left boot's proto board; same part on both boots). Its **INT2** pad is legible on the silkscreen in the photo (bottom row, right end); INT1's pad was not legible. The LSM6DSOX can route its wake-up event to either interrupt pin, so the firmware takes a flag choosing INT1 or INT2 (`OPENSKI_IMU_INT_USES_INT2`).
- Wire that pad to the proto board's hole printed **3** (= D3 = GPIO4, RTC-capable, free). D3 and D10 were the two candidates for the USB-sense divider; this takes D3, USB sense takes D10 (printed 10, GPIO9). Avoid D2 (GPIO3, strapping pin).
- Does not depend on removing the breakout's power LED, but the LED (about 1 to 2 mA, unmeasured) will swamp any saving until it is removed.

## Firmware sketch

1. **Driver** (`Lsm6dsoxSensor`, new `ImuSensor::armWakeOnMotion(thresholdMps2)`, default false): accelerometer only, gyro powered down, low-power mode at 12.5 or 26 Hz, **range changed to +-2 g** (the 6-bit wake threshold is a fraction of full scale: at +-16 g one step is far too coarse for today's 0.1 m/s^2 threshold; at +-2 g with the finer weight one step is about 7.8 mg), wake-up threshold and duration registers, wake-up routed to INT1. Verify every register and weight against the LSM6DSOX datasheet before writing the host test: the values here are from memory.
2. **SleepService:** with `OPENSKI_IMU_INT_PIN` set, after the 1 s settle stop BLE and Wi-Fi, arm the IMU, check the pin is low (if it is high the boot is still moving: clear the source register and retry for a few seconds, else give up the sleep), enable ext1 wake on that pin (any high), then `esp_deep_sleep_start()`. On the wake the existing `begin()` path reports `woke_from_sleep`; trigger size and slept time are no longer known (RTC timer only, so `slept_ms` comes from the RTC clock or is reported as unknown).
3. **Dead-man timer:** deep sleep also sets a long timer wake (proposed 30 minutes). On a timer wake the board checks the IMU answers and is still configured; if it does it re-arms and sleeps again without bringing the radios up; if not it does a normal boot. This replaces the 10 s silence check.
4. Light-sleep path stays as the fallback when the pin is not defined.

## Behavioural difference to accept or test

`WakeDetector` fires on any axis moving 0.1 m/s^2 from a baseline, so a slow tilt counts. The IMU's wake-up detector responds to a change between successive filtered samples, so a very slow pick-up may not cross it. The chip also has a 6D orientation-change interrupt that would catch a slow tilt; use it as a second source if bench trials show slow pick-ups missed. Decide from a bench check, not from the datasheet.

## Tests

- Host: register writes for arming (fake bus, like `test_imu_lsm6dsox`), range restore through `begin()`, retry logic when the pin starts high.
- Bench, owed (kept short): wake by picking the boot up, wake latency, no false wakes on a table for 10 minutes, sleep current with a µA-capable meter (a USB power meter cannot resolve it).

## Decided

Both boots get the wire (user, 28 Oct). Pad choice (INT1 or INT2) follows what the silkscreen shows on the board in hand.

## Built (28 Oct 2026)

- Register map checked against ST's `lsm6dsox_reg.h`: WAKE_UP_SRC 0x1B, TAP_CFG0 0x56 (LIR bit 0, SLOPE_FDS bit 4), TAP_CFG2 0x58 (INTERRUPTS_ENABLE bit 7), WAKE_UP_THS 0x5B, WAKE_UP_DUR 0x5C (WAKE_THS_W bit 4: full scale / 64 or / 256), MD1_CFG 0x5E and MD2_CFG 0x5F (wake-up routing bit 5). The weight names (`LSb_FS_DIV_64`, `LSb_FS_DIV_256`) come from the same header; the header carries no prose, so the step size (7.8125 mg at +-2 g with the /256 weight) is inferred from those names and still to be confirmed by bench.
- `ImuSensor::armWakeOnMotion(threshold, useInt2)` and `clearWakeEvent()` (LSM6DSOX only; default false elsewhere). Sequence: interrupts off, gyro off, low-power accelerometer 12.5 Hz at +-2 g with LPF2, threshold (0.1 m/s^2 rounds up to 2 steps, about 0.153 m/s^2), latched slope-filter wake-up, 250 ms filter settle, route to INT1 or INT2, interrupts on, read WAKE_UP_SRC.
- `SleepService`: with `OPENSKI_IMU_INT_PIN` defined, the sleep request deep-sleeps on ext1 (any high) plus a 30-minute timer wake; the pin is pulled down in the RTC domain and must read low before sleeping (a latched event is cleared up to 10 times, 500 ms apart; if it never goes low the board falls back to the old light-sleep loop after `imu::begin()`). `motionWakeCheck()` in `setup()` right after the IMU starts reports a wake (`woke_from_sleep`, trigger and slept time unknown, reported as 0) or, on the timer wake, re-arms and sleeps again without starting the radios, booting normally if the IMU does not answer.
- Build flags (not set in any environment yet, so every boot behaves as before): `-DOPENSKI_IMU_INT_PIN=4` (D3 = GPIO4) and, for the pad seen on the boards, `-DOPENSKI_IMU_INT_USES_INT2`. Add them to `xiao-left-mag` and `xiao-right` once the boot is wired. Both build with the flags set (`PLATFORMIO_BUILD_FLAGS`).
- Host tests in `test_imu_lsm6dsox`: threshold rounding and clamp, the register sequence (interrupts off first and on last), INT1 and INT2 routing, write failure, restoring normal acquisition through `begin()`, clearing the event.
- Not covered: the timer wake does not check the battery, so a cell that runs flat during a long movement sleep is only caught on the next real wake.

## Automatic idle sleep (28 Oct 2026)

Found on the bench: a boot woken for a test stayed awake for 4 hours, because sleep only ever started on a request (`POST /api/v1/sleep` or the app). With the IMU interrupt wired, `SleepService::tick()` now also requests sleep itself once the boot has been still for `OPENSKI_IDLE_SLEEP_MS` (default 10 minutes). Stillness is the `WakeDetector` test (0.1 m/s^2 or 0.1 rad/s from a baseline) in `StillnessTimer` (`lib/Motion/src`, host test `tools/test_stillness_timer.cpp`); any movement restarts the period. A recording or a flash download also restarts it. A connected phone does not prevent it: the phone reconnects after the wake. Builds without the interrupt pin never sleep on their own, so bench boards and the right boot (until wired) behave as before. The 3.3 V rail stays on in deep sleep because the IMU has to stay powered to watch for movement; only the breakout's power LED is avoidable waste (right-boot hardware idea).

The 30-minute dead-man wake also reads the cell (radios off, so one reading is enough, `lowCellAtRest`): below the low-cell shutdown voltage it goes into the low-battery deep sleep (10-minute timer wakes, resumes at 3.8 V on charge) instead of re-arming, so the earlier "timer wake does not check the battery" gap is closed.
