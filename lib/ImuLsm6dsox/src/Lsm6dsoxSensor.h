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

  // Register map (ST lsm6dsox_reg.h).
  static constexpr uint8_t kRegWhoAmI = 0x0F;
  static constexpr uint8_t kRegCtrl1Xl = 0x10;
  static constexpr uint8_t kRegCtrl2G = 0x11;
  static constexpr uint8_t kRegCtrl3C = 0x12;
  static constexpr uint8_t kRegCtrl4C = 0x13;
  static constexpr uint8_t kRegCtrl6C = 0x15;
  static constexpr uint8_t kRegCtrl8Xl = 0x17;
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
