# Production Board Firmware and Bring-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the repo ready for the production boot board: one identical firmware build for left and right units (`boot-v1`), unique per-unit network names, a corrected design doc, and a bring-up checklist with pass criteria.

**Architecture:** The new env reuses the existing XIAO build flags and swaps only the board definition and pin flags for the ESP32-S3-MINI-1 board. Because both boots now run the same image, the Wi-Fi/OTA hostname gets a MAC-derived suffix, built by a small pure function that is unit-tested on the host. No protocol, Android or recogniser change.

**Tech Stack:** PlatformIO (espressif32, Arduino, C++17), Unity host tests (`pio test -e native`), Markdown docs.

**Spec:** `docs/superpowers/specs/2026-11-15-production-board-design.md`

**Out of scope (needs EDA/CAD, not this repo yet):** schematic, PCB layout, enclosure CAD, BOM sourcing, certification testing. The spec's "What is left to the builder" covers them; this plan only produces what the firmware and the bring-up need.

## Global Constraints

- I2C: SDA GPIO5, SCL GPIO6; LSM6DSOX at 0x6A; LIS3MDL at 0x1C (existing driver defaults, do not change).
- IMU wake: `OPENSKI_IMU_INT_PIN=4` with `OPENSKI_IMU_INT_USES_INT2`.
- Battery: divider ratio 2.0 (100k/100k, 100 nF), ADC on GPIO2, divider enable on GPIO1. In firmware the enable pin **is the bottom resistor's ground leg** (driven low = divider complete, input = divider open), so no FET is fitted.
- Module: ESP32-S3-MINI-1-N8 (8 MB quad flash, no PSRAM); partition table `partitions/xiao-esp32s3-8mb.csv`.
- Every `boot-v1` build defines `OPENSKI_MAG_LIS3MDL` and `OPENSKI_LOW_POWER_RADIO` (via `xiao_common`).
- GPIO7 (mag INT), GPIO48 (LED) and GPIO0 (button) are reserved on the board but unused by firmware in this plan. Do not add code for them.
- Keep the experimental framing: nothing here validates turn classification.
- Existing envs (`xiao-left-mag`, `xiao-right`, `esp32-devkit-v1`, ...) must keep building with unchanged behaviour.

## Review Focus

1. Two identical boots on one network must not collide on hostname (`ski-boot-xxxx.local`): covered by Task 2 tests (distinct MACs give distinct names; same MAC gives the same name every boot).
2. A MAC whose last bytes are `00 00` or `0f ff` still yields a valid DNS label (lowercase hex, zero-padded, no trailing dash): Task 2 test.
3. A prefix that would overflow the output buffer is truncated safely, never overruns or leaves an unterminated string: Task 2 test.
4. Existing envs must not change hostname: Task 3 verification greps the built flags and checks `xiao-left-mag` still reports `ski-xiao-l`.
5. A board with no magnetometer fitted (wrong assembly) must not crash boot: Task 4 bring-up item, checked by reading `/api/v1/motion` for `present:false` (existing behaviour; confirm, do not change).

---

### Task 1: Reconcile the design doc with the firmware

**Files:**
- Modify: `docs/superpowers/specs/2026-11-15-production-board-design.md`

**Interfaces:**
- Consumes: `include/AppConfig.h` (`kBatteryDividerEnablePin` semantics), `platformio.ini`.
- Produces: a spec whose pin and battery-sense statements match the firmware.

- [ ] **Step 1: Fix the battery-sense row** in "Proposed parts": replace "bottom leg switched by an N-FET on GPIO1" with "bottom resistor returned to GPIO1 instead of GND (the firmware drives it low to read and floats it in sleep), so no FET is needed".
- [ ] **Step 2: Fix the block diagram label** "divider (100k/100k) via enable FET" to "divider (100k/100k), bottom leg on GPIO1".
- [ ] **Step 3: Fix the pin map** row "Divider enable | 1 | new, flag already supported" to "Divider bottom leg (enable) | 1 | new, flag `OPENSKI_BATTERY_DIVIDER_ENABLE_PIN` exists" and add a note under the table: GPIO7 and GPIO48 and the button are not used by firmware yet.
- [ ] **Step 4: Fix the board setting**: in the intent section state that the build uses PlatformIO board `esp32-s3-devkitc-1` (as `env:esp32-s3` does), not `seeed_xiao_esp32s3`, with the 8 MB flash settings from Task 3.
- [ ] **Step 5: Add a "Per-unit identity" paragraph**: both boots run the same image, so the Wi-Fi/OTA hostname carries the last two MAC bytes (`ski-boot-xxxx`); the BLE name stays `OpenSki-ski` and the app tells boots apart by address, as now.
- [ ] **Step 6: Verify** by grepping the doc: `grep -n "FET" docs/superpowers/specs/2026-11-15-production-board-design.md` returns nothing about the divider.
- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/specs/2026-11-15-production-board-design.md
git commit -m "docs: align the production board spec with the firmware pin handling"
```

---

### Task 2: Unique per-unit hostname

**Files:**
- Create: `lib/Identity/src/UnitName.h`
- Create: `test/test_unit_name/test_main.cpp` (mirror the layout of `test/test_imu_monitor`)
- Modify: `include/AppConfig.h` (`kHostname` use), `src/WifiService.cpp:31`, `src/OtaService.cpp:26,35`, `src/TelemetryService.cpp` (the `config::kHostname` uses at lines 128, 250, 276, 300, 360, 383, 391, 398)

**Interfaces:**
- Produces, in `lib/Identity/src/UnitName.h`, namespace `openski::identity`:
  - `size_t formatHostname(const char* prefix, const uint8_t mac[6], bool withMacSuffix, char* out, size_t outSize)`: writes `<prefix>` or `<prefix>-<hex of mac[4]><hex of mac[5]>` (lowercase, four hex digits), always NUL-terminates when `outSize > 0`, truncates the prefix if needed so the suffix still fits, and returns the length written (excluding the NUL).
  - `const char* hostname()`: in `src/` (new `src/UnitIdentity.cpp` plus `include/UnitIdentity.h`), returns a process-lifetime buffer built once from `config::kHostname`, the device MAC (`esp_read_mac(mac, ESP_MAC_WIFI_STA)`) and `OPENSKI_HOSTNAME_MAC_SUFFIX` (suffix on when that macro is defined).
- Consumes: `config::kHostname` as the prefix.

- [ ] **Step 1: Write the failing tests** in `test/test_unit_name/test_main.cpp`:
  - `test_suffix_uses_last_two_mac_bytes`: prefix `"ski-boot"`, mac `{0x24,0x6f,0x28,0xa1,0xb2,0xc3}`, suffix on → `"ski-boot-b2c3"`, return 13.
  - `test_distinct_macs_give_distinct_names`: two MACs differing only in byte 5 give different strings.
  - `test_same_mac_is_stable`: calling twice gives identical output.
  - `test_zero_padding_and_lowercase`: mac ending `{0x00,0x00}` → `"...-0000"`; ending `{0x0f,0xff}` → `"...-0fff"`.
  - `test_suffix_off_returns_prefix_only`: suffix off → exactly the prefix.
  - `test_truncates_without_overrun`: `outSize = 8` with a long prefix → result length 7, byte 7 is NUL, and the buffer after index 7 is untouched (fill with a sentinel).
  - `test_zero_size_buffer_writes_nothing`: `outSize = 0` returns 0 and does not write.
- [ ] **Step 2: Run to verify it fails.** Run: `pio test -e native -f test_unit_name`. Expected: FAIL to compile (`UnitName.h` missing). On this machine the WinLibs `mingw64/bin` directory must be on PATH (see CLAUDE.md).
- [ ] **Step 3: Implement `formatHostname`** in `lib/Identity/src/UnitName.h` as a header-only, no-Arduino function (so the native env can build it). Truncate the prefix to `outSize - 1 - 5` when the suffix is on.
- [ ] **Step 4: Run to verify it passes.** Run: `pio test -e native -f test_unit_name`. Expected: 7 passed.
- [ ] **Step 5: Add `hostname()`** in `src/UnitIdentity.cpp` (declaration in `include/UnitIdentity.h`), and replace every `config::kHostname` in the files listed above with `identity::hostname()`. Keep `config::kHostname` as the prefix; do not rename the macro.
- [ ] **Step 6: Verify no behaviour change on existing envs.** Run: `pio run -e xiao-left-mag` and `pio run -e esp32-devkit-v1`. Expected: both SUCCESS. (Without `OPENSKI_HOSTNAME_MAC_SUFFIX` the hostname is exactly the old one.)
- [ ] **Step 7: Run the full host suite.** Run: `pio test -e native`. Expected: all suites pass, including the existing ones.
- [ ] **Step 8: Commit**

```bash
git add lib/Identity test/test_unit_name src/UnitIdentity.cpp include/UnitIdentity.h include/AppConfig.h src/WifiService.cpp src/OtaService.cpp src/TelemetryService.cpp
git commit -m "feat: derive the Wi-Fi and OTA hostname from the MAC when requested"
```

Note: `src/TelemetryService.cpp` has uncommitted changes in the working tree today. Stage only your hunks (`git add -p`) or finish that work first.

---

### Task 3: The `boot-v1` environments

**Files:**
- Modify: `platformio.ini` (new `[env:boot-v1]` and `[env:boot-v1-ota]` after `xiao-right-ota`)
- Modify: `CLAUDE.md` (one line in the firmware command/architecture notes naming the new env)

**Interfaces:**
- Consumes: `xiao_common.build_flags` (adds `OPENSKI_IMU_LSM6DSOX`, I2C pins 5/6, `OPENSKI_LOW_POWER_RADIO`, divider ratio 2.0) and `hostname()` from Task 2.
- Produces: env names `boot-v1` and `boot-v1-ota` for bring-up and OTA.

- [ ] **Step 1: Add `[env:boot-v1]`** extending `env:esp32-s3` (board `esp32-s3-devkitc-1`) with: `board_upload.flash_size = 8MB`, `board_upload.maximum_size = 8388608`, `board_build.partitions = partitions/xiao-esp32s3-8mb.csv`, `board_build.arduino.memory_type = qio_qspi` (quad flash, no PSRAM), and `build_flags` of `${xiao_common.build_flags}` plus `-DOPENSKI_HOSTNAME=\"ski-boot\"`, `-DOPENSKI_HOSTNAME_MAC_SUFFIX`, `-DOPENSKI_MAG_LIS3MDL`, `-DOPENSKI_BATTERY_ADC_PIN=2`, `-DOPENSKI_BATTERY_DIVIDER_ENABLE_PIN=1`, `-DOPENSKI_IMU_INT_PIN=4`, `-DOPENSKI_IMU_INT_USES_INT2`. Add a comment that this is the production board and every unit is identical.
- [ ] **Step 2: Add `[env:boot-v1-ota]`** extending `env:boot-v1` with `upload_protocol = espota`.
- [ ] **Step 3: Build.** Run: `pio run -e boot-v1`. Expected: SUCCESS, with the flash image fitting the 2 MB app slots.
- [ ] **Step 4: Check the flags are what the spec says.** Run: `pio project config -e boot-v1`. Expected: the output contains `OPENSKI_MAG_LIS3MDL`, `OPENSKI_BATTERY_ADC_PIN=2`, `OPENSKI_BATTERY_DIVIDER_ENABLE_PIN=1`, `OPENSKI_IMU_INT_PIN=4`, `OPENSKI_IMU_INT_USES_INT2`, `OPENSKI_HOSTNAME_MAC_SUFFIX`.
- [ ] **Step 5: Confirm the neighbours are untouched.** Run: `pio run -e xiao-left-mag -e xiao-right`. Expected: both SUCCESS.
- [ ] **Step 6: Run the fixtures check** (CLAUDE.md requires it after changes under `lib/`, and Task 2 added a lib). Run: `tools/check-fixtures.sh`. Expected: passes with no fixture re-emit needed.
- [ ] **Step 7: Add the env to CLAUDE.md** next to the other build commands: `pio run -e boot-v1` (production board, MAC-suffixed hostname `ski-boot-xxxx`).
- [ ] **Step 8: Commit**

```bash
git add platformio.ini CLAUDE.md
git commit -m "feat: add the boot-v1 build for the production board"
```

---

### Task 4: Bring-up checklist with pass criteria

**Files:**
- Create: `docs/production-board-bring-up.md`
- Modify: `docs/bench-results.md` (add an entry when the first board is run, not now)

**Interfaces:**
- Consumes: `boot-v1` from Task 3, the HTTP endpoints `/api/v1/imu` and `/api/v1/motion`, the existing OTA flow in `docs/bring-up.md`.
- Produces: the pass/fail list a person runs on each prototype board, in order.

- [ ] **Step 1: Write the checklist** as a numbered table with columns Step, How, Pass criterion, Result. Items, in order:
  1. Visual and continuity: no short between VBAT/3V3/GND on the unpowered board.
  2. 3V3 rail with USB only (no cell): 3.3 V ±5 %.
  3. Cell attach: charger reports charging; NTC inhibit confirmed by chilling the NTC (charging stops below 0 °C).
  4. USB flash: `pio run -e boot-v1 -t upload`; serial prints the build ID and the I2C scan shows 0x6A and 0x1C.
  5. IMU stream: `/api/v1/imu` ready true, about 104 Hz sample rate, gravity near 1 g at rest.
  6. Magnetometer: `/api/v1/motion` reports the mag present with changing raw counts when rotated; also check a unit with the mag not fitted still boots and reports `present:false` (Review Focus 5).
  7. Battery reading: ADC value within 2 % of a multimeter reading at 3.6 V and 4.0 V.
  8. Divider enable: current in deep sleep does not include the divider (compare with a tie-to-GND test unit).
  9. Motion wake: board in deep sleep wakes on a tap via IMU INT2 (GPIO4).
  10. Deep-sleep current, measured and recorded (no pass limit; this is the baseline the spec says is missing).
  11. OTA: `pio run -e boot-v1-ota -t upload --upload-port ski-boot-xxxx.local` succeeds.
  12. Two units on one network resolve as different `ski-boot-xxxx.local` names.
  13. BLE range through the body and boot with the real case: record the distance, no pass limit yet.
  14. Cold soak: reference the cold test in `docs/bench-results.md`, no new limits invented here.
- [ ] **Step 2: State the framing** at the top: measurements are bench measurements of one prototype run, nothing validates turn classification.
- [ ] **Step 3: Verify the doc** renders and every pin and flag it quotes matches `platformio.ini`: `grep -n "OPENSKI_" docs/production-board-bring-up.md` and compare by eye against the `boot-v1` block.
- [ ] **Step 4: Commit**

```bash
git add docs/production-board-bring-up.md
git commit -m "docs: add the production board bring-up checklist"
```

---

## Self-Review

- **Spec coverage:** pin map and battery sense (Tasks 1, 3); both sensors on every board and identical firmware (Tasks 2, 3); wake and sleep (Task 3 flags, Task 4 items 9 and 10); cold and range checks (Task 4); schematic, layout, enclosure, certification are explicitly out of scope and listed in the header.
- **Types:** `formatHostname` and `hostname()` are defined in Task 2 and used by Task 3 only through the `OPENSKI_HOSTNAME_MAC_SUFFIX` macro.
- **Proportion:** the plan is a few short tasks around one new pure function and one new env.
- **Known gaps:** GPIO7, GPIO48 and the button have no firmware; the board's BLE name is shared by all units (unchanged); flash-mode flags for the MINI-1 (`qio_qspi`) are untested on hardware until bring-up step 4.
