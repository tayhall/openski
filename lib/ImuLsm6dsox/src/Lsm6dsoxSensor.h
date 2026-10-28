#pragma once

#include "Imu.h"
#include "RegisterBus.h"

namespace openski::imu {

// LSM6DSOX driver (I2C address 0x6A, or 0x6B with SDO high). Bus, clock and delay are injected so the
// driver can be tested on the host. Samples are normalised to SI units, same as Mpu6050Sensor.
class Lsm6dsoxSensor final : public ImuSensor {
 public:
  using NowUsFn = uint32_t (*)();
  using DelayMsFn = void (*)(uint32_t);

  Lsm6dsoxSensor(RegisterBus& bus, NowUsFn nowUs, DelayMsFn delayMs)
      : bus_(bus), nowUs_(nowUs), delayMs_(delayMs) {}

  bool begin() override;
  ReadResult read(Sample& out) override;
  const char* name() const override { return "LSM6DSOX"; }
  bool setSleepMode(bool on) override;
  bool powerDown() override;
  bool armWakeOnMotion(float thresholdMps2, bool useInt2) override;
  bool clearWakeEvent() override;

  // Wake-up threshold register value (1 to 63) for a threshold in m/s^2. In wake mode the range is +-2 g and
  // WAKE_THS_W selects full scale / 256, so one step is 7.8125 mg; the result rounds up and is never zero.
  static uint8_t wakeThresholdSteps(float thresholdMps2);

  // Register map (ST lsm6dsox_reg.h).
  static constexpr uint8_t kRegWhoAmI = 0x0F;
  static constexpr uint8_t kRegCtrl1Xl = 0x10;
  static constexpr uint8_t kRegCtrl2G = 0x11;
  static constexpr uint8_t kRegCtrl3C = 0x12;
  static constexpr uint8_t kRegCtrl4C = 0x13;
  static constexpr uint8_t kRegCtrl6C = 0x15;
  static constexpr uint8_t kRegCtrl8Xl = 0x17;
  static constexpr uint8_t kRegWakeUpSrc = 0x1B;
  static constexpr uint8_t kRegTapCfg0 = 0x56;
  static constexpr uint8_t kRegTapCfg2 = 0x58;
  static constexpr uint8_t kRegWakeUpThs = 0x5B;
  static constexpr uint8_t kRegWakeUpDur = 0x5C;
  static constexpr uint8_t kRegMd1Cfg = 0x5E;
  static constexpr uint8_t kRegMd2Cfg = 0x5F;
  static constexpr uint8_t kRegStatus = 0x1E;
  static constexpr uint8_t kRegOutXlA = 0x28;
  static constexpr uint8_t kRegOutTempL = 0x20;  // then gyro X/Y/Z, then accel X/Y/Z (14 bytes)
  static constexpr uint8_t kWhoAmIValue = 0x6C;
  // Values written at init.
  static constexpr uint8_t kCtrl3Value = 0x44;  // BDU | IF_INC
  static constexpr uint8_t kCtrl1Value = 0x46;  // ODR 104 Hz | +-16 g | LPF2 on
  static constexpr uint8_t kCtrl2Value = 0x4C;  // ODR 104 Hz | +-2000 dps
  static constexpr uint8_t kCtrl4Value = 0x02;  // LPF1_SEL_G
  static constexpr uint8_t kCtrl6Value = 0x03;  // FTYPE = 011
  static constexpr uint8_t kCtrl8Value = 0x00;  // HPCF_XL = ODR/4, slope/HP path off
  // Sleep mode: accelerometer 26 Hz in low-power mode (XL_HM_MODE) with the same +-16 g range and LPF2, gyro
  // powered down. A few tens of microamps in place of about half a milliamp at 104 Hz with the gyro on.
  static constexpr uint8_t kCtrl1SleepValue = 0x26;  // ODR 26 Hz | +-16 g | LPF2 on
  static constexpr uint8_t kCtrl2SleepValue = 0x00;  // gyro power-down
  static constexpr uint8_t kCtrl6SleepValue = 0x13;  // XL_HM_MODE | FTYPE = 011
  // Wake-on-motion mode (ST lsm6dsox_reg.h bit layouts): accelerometer 12.5 Hz low power at +-2 g with LPF2, gyro
  // off, wake-up through the slope filter (TAP_CFG0 SLOPE_FDS = 0) with a latched interrupt (LIR), threshold
  // weight full scale / 256 (WAKE_UP_DUR WAKE_THS_W), no extra duration, routed to INT1 or INT2 (WU = bit 5).
  static constexpr uint8_t kCtrl1WakeValue = 0x12;      // ODR 12.5 Hz | +-2 g | LPF2 on
  static constexpr uint8_t kWakeUpDurValue = 0x10;      // WAKE_THS_W = 1, WAKE_DUR = 0, SLEEP_DUR = 0
  static constexpr uint8_t kTapCfg0Value = 0x01;        // LIR; SLOPE_FDS = 0 (slope filter)
  static constexpr uint8_t kInterruptsEnable = 0x80;    // TAP_CFG2 INTERRUPTS_ENABLE
  static constexpr uint8_t kRouteWakeUp = 0x20;         // MD1_CFG INT1_WU / MD2_CFG INT2_WU
  static constexpr uint32_t kWakeFilterSettleMs = 250;  // a few samples at 12.5 Hz before the interrupt is enabled
  static constexpr uint8_t kSleepSettleSamples = 4;  // LPF2 at ODR/4 settles in about 0.15 s at 26 Hz
  // The first outputs after the ODR is enabled include the digital filters settling; they are read (which
  // clears data-ready) and discarded. About 100 ms at 104 Hz, a conservative starting value to tune.
  static constexpr uint8_t kSettleSamples = 10;
  // The live frame and flash record store gyro as int16 of rad/s x 1000, which saturates at 32.767 rad/s
  // (about 1877 dps), below the chip's +-2000 dps range. Flag from this limit, not only from the raw rail.
  static constexpr float kGyroEncodableRadps = 32.7f;
  static constexpr uint8_t kStatusAccelReady = 0x01;
  static constexpr uint8_t kStatusGyroReady = 0x02;

 private:
  RegisterBus& bus_;
  NowUsFn nowUs_;
  DelayMsFn delayMs_;
  bool initialized_ = false;
  uint8_t settleRemaining_ = 0;
  bool sleepMode_ = false;
};

}  // namespace openski::imu
