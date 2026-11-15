# Production boot sensor: board and case sketch

Status: **sketch for review, nothing designed in CAD or EDA yet, nothing built.** Numbers marked *target* are goals, not measurements. Parts named below are my picks from memory of the datasheets; verify each against the current datasheet before the schematic is drawn.

## Intent

One boot-mounted sensor unit that is identical for left and right, small enough to sit on the top of the boot cuff, robust enough for a ski day, and certifiable for sale. It replaces the hand-wired XIAO ESP32S3 + LSM6DSOX (+ LIS3MDL on the left) prototypes. Firmware stays the one codebase; the left/right role is assigned in the app (Boot setup), not by hardware or build.

## Decisions from the conversation

- Production-oriented, not another prototype spin.
- Every board carries **both** the LSM6DSOX and the LIS3MDL. No left/right hardware variants.
- **No bare ESP32 chip.** Use a pre-certified module so radio certification is inherited.

## Goals and non-goals

Goals
- One PCB, one case, one firmware env (`boot-v1`, to be added; a merge of today's `xiao-left-mag` flags with the pin map below).
- Same I2C addresses and driver code as today (LSM6DSOX 0x6A, LIS3MDL 0x1C) so no driver rewrite.
- Motion-wake deep sleep as already built (IMU INT2 to an RTC GPIO), and the low-battery sleep.
- Safe charging in the cold: no charging below 0 degC.
- Splash, snow and drop proof case; strap or clip that survives boot flex.

Non-goals (this round)
- Turn classification, new firmware features, a new BLE protocol.
- Magnetometer-based heading in the app (the part is fitted, use is separate work).
- Wireless charging, a display, haptics, GPS on the boot.

Anti-goals
- A design that passes on the bench but cannot ship because of certification, cell transport rules or cold charging.
- Placing the magnetometer where the board's own currents, magnets or the cell disturb it.

## Approaches considered

| | A. Pre-certified module on a custom board (**recommended**) | B. Keep the XIAO module as a daughter board | C. Bare ESP32-S3 chip, own RF |
|---|---|---|---|
| What | ESP32-S3-MINI-1 module soldered to our board with charger, buck, sensors | XIAO soldered or socketed on a carrier with the sensors | Chip-down with own antenna and matching |
| Certification | Module's radio approvals carried over; end product still needs its own tests | Same, but XIAO's modular status for resale is murkier | Full intentional-radiator testing, ruled out |
| Size and height | Smallest, about 28 x 42 mm board | Taller and larger (stacked boards) | Smallest |
| Cost at volume | Good | Poor (paying for a charger and USB twice) | Best |
| Risk | Low, same S3 silicon the firmware runs on | Lowest effort, not a production answer | High |

Pick: **A**. It keeps the S3, the flash size (8 MB for the existing `xiao-esp32s3-8mb.csv` layout) and the Arduino/NimBLE firmware unchanged, and drops only the XIAO carrier.

## Block diagram

```
 USB-C or pogo (VBUS, GND, D+/D-)
        |
   [ESD/TVS] --> [1S charger + power-path, NTC input] <--- cell NTC
        |                     |
        |                  VBAT (protected 1S LiPo, 3.0-4.2 V)
        |                     |
        |        +---- divider (100k/100k, bottom leg on GPIO1) --> ADC (GPIO2)
        |        |
        |        +--> [low-Iq buck, 3.3 V] ---+--> ESP32-S3-MINI-1 (8 MB, onboard antenna)
        |                                     |      |  I2C GPIO5/6
        |                                     |      +--> LSM6DSOX  0x6A  (INT2 -> GPIO4, wake)
        |                                     |      +--> LIS3MDL   0x1C  (INT/DRDY -> GPIO7, optional)
        |                                     +--> status LED (GPIO48 via resistor)
   USB D+/D- --> GPIO19/20 (native USB: flash, recovery)      button -> GPIO0 (boot/wake)
```

## Proposed parts (verify before schematic)

| Block | Pick | Why / note |
|---|---|---|
| MCU module | Espressif ESP32-S3-MINI-1-N8 (8 MB flash, no PSRAM) | Pre-certified, 15.4 x 20.5 mm, onboard antenna, matches today's flash layout. Needs the module's antenna keep-out respected exactly or the inherited certification no longer applies. |
| IMU | ST LSM6DSOX | Already the driver in `lib/ImuLsm6dsox`; INT2 wake in use. |
| Magnetometer | ST LIS3MDL | Already supported (`lib/MagLis3mdl`). Place at the end of the board furthest from the cell and charge current paths. |
| Charger | TI BQ25185 class (1S, power path, NTC pin, low quiescent) or equivalent | NTC pin gives the 0 degC charge inhibit the POC plan requires. |
| 3.3 V rail | Nano-quiescent buck (TPS62840 class) | Needs headroom for Wi-Fi/BLE peaks that an LDO of small rating cannot give; very low quiescent matters for the motion-wake sleep. Dropout behaviour near 3.0 V cell is handled by the existing low-battery deep sleep. |
| Cell | 1S LiPo pouch, about 500 mAh target (for example 502540, 5 x 25 x 40 mm), **with protection circuit and a 10k NTC** | Runtime is unmeasured; cold derating is real. Cell must come with UN38.3 test report. |
| Battery sense | 100k/100k divider plus 100 nF, bottom resistor returned to GPIO1 instead of GND | The firmware drives that pin low to read and leaves it floating in sleep (`OPENSKI_BATTERY_DIVIDER_ENABLE_PIN`), so no FET is needed and the divider draws nothing in deep sleep. A fuel gauge IC is a later option, not needed now. |
| Connector | Sealed USB-C **or** 3-4 pad non-magnetic spring-pin cradle | See "Charging and the magnetometer" below. |
| UI | One status LED, one button (GPIO0) | Button doubles as download-mode. OTA already exists, USB is for factory and recovery. |
| Protection | ESD diode array on USB, TVS on VBUS, reverse-polarity protection on any exposed charge pads | Boots get dropped, wet and charged from random chargers. |

## Pin map (proposed, firmware-compatible)

| Function | ESP32-S3 GPIO | Matches today? |
|---|---|---|
| I2C SDA | 5 | yes (XIAO D4) |
| I2C SCL | 6 | yes (XIAO D5) |
| IMU INT2 (wake) | 4 | yes (`OPENSKI_IMU_INT_PIN=4`, `INT_USES_INT2`) |
| Battery ADC | 2 | yes (left build) |
| Divider bottom leg (enable) | 1 | new, flag `OPENSKI_BATTERY_DIVIDER_ENABLE_PIN` exists |
| Mag INT/DRDY | 7 | new, optional |
| Status LED | 48 | new |
| Button | 0 | boot strap |
| USB D-/D+ | 19 / 20 | native USB |

GPIO7 (mag INT), GPIO48 (LED) and the button are reserved on the board but not used by firmware yet.

Build and identity: the build uses PlatformIO board `esp32-s3-devkitc-1` (as `env:esp32-s3` does) with 8 MB quad flash, not `seeed_xiao_esp32s3`. Both boots run the same image, so the Wi-Fi/OTA hostname carries the last two MAC bytes (`ski-boot-xxxx`); the BLE name stays `OpenSki-ski` and the app tells boots apart by address, as now.

Strapping pins (0, 3, 45, 46) are kept off the sensor and sense nets except GPIO0 for the button. I2C pull-ups 4.7k on the board. IMU and magnetometer share the one bus, as they do now.

## Layout rules that matter

- **Antenna:** module antenna at the board edge with the full keep-out clear of copper, cell and case metal. Orient the unit so the antenna end faces away from the leg and outward. A phone in a pocket on the other side of a calf and a boot shell is the hard case, so BLE range through the body needs a bench check with the final enclosure.
- **IMU:** near the board centre, rigid, mounted flat so the case does not flex it. Keep it away from the buck inductor and the charger thermally, because gyro bias drifts with temperature (see `docs/poc-plan.md`).
- **Magnetometer:** far from the buck inductor, the cell and the charge input, with the cell's current return not looping under it. No ferrous parts (screws, springs) within a few centimetres. Boot buckles are steel, so a magnetometer on a boot will see disturbance regardless; treat its output as experimental.
- **Axes:** silkscreen the board axes and mark the "up the leg" end. The mounting rules in `docs/poc-plan.md` (long axis up the leg, forward axis across the width) become the board's physical convention: make the board long and narrow (about 28 x 42 mm).
- **Test pads:** UART, USB D+/D-, SWD-less boot pad, battery voltage, and I2C, on the underside for a pogo-pin jig in production.

## Charging and the magnetometer

Magnetic charging connectors would put a permanent magnet next to the LIS3MDL and upset its calibration. So: no magnets in the connector. Two options:

1. **Sealed USB-C** with a captive silicone plug. Simple, universal, but the weak point of ingress sealing and wear.
2. **Spring-pin cradle** (2 power pads plus 2 data pads) with a non-magnetic clip-on charge cradle. Better sealing and no port to flood, costs a custom cradle.

My pick: start with option 1 for the first production-intent run (fewer custom parts), keep the pads from option 2 on the underside so the jig and a later cradle both work.

## Case and mounting

Sketch, not CAD:

```
  top view (about 55 x 32 x 13 mm)           side section
 +-------------------------------+         ___________________
 | [LED]                  [btn]  |        |  lid (PC/ABS)     |
 |   +-----------------------+   |        |-------------------|  gasket
 |   | MCU/antenna end       |   |        | PCB  (sensors mid)|
 |   | sensors in the middle |   |        | LiPo under PCB    |
 |   | USB-C plug end        |   |        |___________________|
 |   +-----------------------+   |             base with strap slots and curved back
 +-------------------------------+
        strap slots at both long ends
```

- Two-part shell (base and lid), perimeter gasket, ultrasonic weld or four screws (brass screws are non-ferrous; stainless 300-series is acceptable if far from the mag).
- Curved back to sit on the cuff, with a replaceable foam or TPU pad.
- Strap: hook-and-loop through two slots, or a clip plate bonded to the boot. The strap must hold the unit from rotating, because roll is the measurement.
- Target IP67; prove with a water and cold-soak test, and a drop test onto a hard floor from about 1.5 m.
- Light-pipe for the LED, a flexible button boss, no vents (a gore membrane vent is an option if cold cycling pumps moisture in).

## Compliance (what to plan for)

- The ESP32-S3-MINI-1 radio approvals do **not** certify the end product. Plan for end-product EMC and radio testing in each market (CE RED, FCC Part 15 unintentional emissions, UKCA), keeping the module's antenna as specified.
- Lithium cell: UN38.3 transport test report and IEC 62133 for the cell, plus the board's charger approval path.
- Include RoHS/REACH declarations on the BOM and the usual labelling.
- Take advice from a test house before the first PCB is released, not after.

## Risks and open items

1. **Cold:** cell capacity and charging at low temperature; NTC inhibit is in the design, performance is unmeasured (cold test is already deferred in `docs/bench-results.md`).
2. **BLE through body and boot:** needs a hardware range check with the real case.
3. **Sleep current:** target is low tens of uA in motion-wake sleep; unmeasured on the current XIAO boots (see sleep notes), so the production budget should be measured on the prototype first.
4. **Mag disturbance on a boot:** the magnetometer may be unusable there. It is fitted for optionality, not as a promise.
5. **Charge connector choice** (USB-C vs spring pins) is a call I made, shown above.
6. **Cell size:** 500 mAh is a placeholder until runtime is measured.

## What is left to the builder

Schematic, layout, enclosure CAD, firmware `boot-v1` env, first prototype run (about 5 boards), bring-up checklist (power rails, I2C scan for 0x6A and 0x1C, INT2 wake, ADC calibration, USB flash, OTA), then the cold and range tests. A prototype round should keep the pin map above frozen so the existing `xiao-left-mag` firmware only needs a build-flag change.
