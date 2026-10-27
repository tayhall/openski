#include <unity.h>

#include <string.h>
#include <vector>

#include "Lsm6dsoxSensor.h"

using namespace openski::imu;

namespace {
class FakeBus : public RegisterBus {
 public:
  uint8_t regs[256]{};
  std::vector<std::pair<uint8_t, uint8_t>> writes;
  bool restartOk = true;
  bool failWrites = false;
  bool failReads = false;
  bool ignoreWrites = false;  // writes succeed but registers do not change
  bool resetStuck = false;    // SW_RESET never self-clears

  FakeBus() { regs[0x0F] = 0x6C; }
  bool restart() override { return restartOk; }
  bool writeRegister(uint8_t reg, uint8_t value) override {
    if (failWrites) return false;
    writes.emplace_back(reg, value);
    if (ignoreWrites) return true;
    regs[reg] = value;
    if (reg == 0x12 && (value & 0x01) && !resetStuck) regs[reg] = value & ~0x01;
    return true;
  }
  bool readRegisters(uint8_t first, uint8_t* data, size_t length) override {
    if (failReads) return false;
    for (size_t i = 0; i < length; ++i) data[i] = regs[(first + i) & 0xFF];
    return true;
  }
  void setWord(uint8_t lowReg, int16_t v) {
    regs[lowReg] = static_cast<uint8_t>(v & 0xFF);
    regs[lowReg + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
  }
  void setData(int16_t gx, int16_t gy, int16_t gz, int16_t ax, int16_t ay, int16_t az, int16_t t = 0) {
    setWord(0x20, t);
    setWord(0x22, gx);
    setWord(0x24, gy);
    setWord(0x26, gz);
    setWord(0x28, ax);
    setWord(0x2A, ay);
    setWord(0x2C, az);
    regs[0x1E] = 0x03;
  }
  int lastWriteTo(uint8_t reg) const {
    for (auto it = writes.rbegin(); it != writes.rend(); ++it)
      if (it->first == reg) return it->second;
    return -1;
  }
};

uint32_t fakeNow = 1000;
uint32_t fakeNowUs() { return fakeNow; }
void noDelay(uint32_t) {}
}  // namespace

void setUp() { fakeNow = 1000; }
void tearDown() {}

void test_init_writes_expected_configuration() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_TRUE(s.begin());
  TEST_ASSERT_EQUAL_INT(0x46, bus.lastWriteTo(0x10));  // 104 Hz, +-16 g, LPF2
  TEST_ASSERT_EQUAL_INT(0x4C, bus.lastWriteTo(0x11));  // 104 Hz, +-2000 dps
  TEST_ASSERT_EQUAL_INT(0x44, bus.lastWriteTo(0x12));  // BDU | IF_INC (after the reset write)
  TEST_ASSERT_EQUAL_INT(0x02, bus.lastWriteTo(0x13));  // LPF1_SEL_G
  TEST_ASSERT_EQUAL_INT(0x03, bus.lastWriteTo(0x15));  // FTYPE
  TEST_ASSERT_EQUAL_INT(0x00, bus.lastWriteTo(0x17));
  TEST_ASSERT_EQUAL_UINT8(0x01, bus.writes.front().second);  // reset first
  TEST_ASSERT_EQUAL_UINT8(0x12, bus.writes.front().first);
}

void test_who_am_i_mismatch_fails_cleanly_without_writes() {
  FakeBus bus;
  bus.regs[0x0F] = 0x68;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_FALSE(s.begin());
  TEST_ASSERT_EQUAL_size_t(0, bus.writes.size());
  Sample out{};
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kError), static_cast<int>(s.read(out)));
}

void test_bus_restart_failure_fails_begin() {
  FakeBus bus;
  bus.restartOk = false;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_FALSE(s.begin());
}

void test_init_fails_when_reset_never_clears() {
  FakeBus bus;
  bus.resetStuck = true;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_FALSE(s.begin());
}

void test_init_fails_when_registers_do_not_stick() {
  FakeBus bus;
  bus.ignoreWrites = true;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_FALSE(s.begin());
}

void test_init_write_failure_fails_begin() {
  FakeBus bus;
  bus.failWrites = true;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_FALSE(s.begin());
}

// begin() then read and discard the filter-settling samples, so a test sees its own data first.
void beginSettled(Lsm6dsoxSensor& s, FakeBus& bus) {
  TEST_ASSERT_TRUE(s.begin());
  bus.setData(0, 0, 0, 0, 0, 0);
  Sample ignored{};
  for (int i = 0; i < Lsm6dsoxSensor::kSettleSamples; ++i) {
    TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kNoData), static_cast<int>(s.read(ignored)));
  }
}

void test_scaling_at_several_values() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  beginSettled(s, bus);
  // 2049 LSB at 0.488 mg/LSB is about 1 g; 1000 LSB * 70 mdps = 70 dps.
  bus.setData(1000, -1000, 0, 2049, -4098, 0, 256);
  fakeNow = 123456;
  Sample out{};
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kSample), static_cast<int>(s.read(out)));
  TEST_ASSERT_EQUAL_UINT32(123456, out.timestampUs);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 2049 * 0.000488f * 9.80665f, out.accelMps2.x);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, -4098 * 0.000488f * 9.80665f, out.accelMps2.y);
  TEST_ASSERT_EQUAL_FLOAT(0.0f, out.accelMps2.z);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, 70.0f * 0.01745329252f, out.gyroRadps.x);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, -70.0f * 0.01745329252f, out.gyroRadps.y);
  TEST_ASSERT_FLOAT_WITHIN(1e-6f, 26.0f, out.temperatureC);
  TEST_ASSERT_EQUAL_UINT8(0, out.qualityFlags);
  bus.setData(10, 20, 30, 40, 50, 60, -512);
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kSample), static_cast<int>(s.read(out)));
  TEST_ASSERT_FLOAT_WITHIN(1e-5f, 60 * 0.000488f * 9.80665f, out.accelMps2.z);
  TEST_ASSERT_FLOAT_WITHIN(1e-5f, 30 * 0.07f * 0.01745329252f, out.gyroRadps.z);
  TEST_ASSERT_FLOAT_WITHIN(1e-5f, 23.0f, out.temperatureC);
}

void test_near_rail_flags() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  beginSettled(s, bus);
  Sample out{};
  bus.setData(0, 0, 0, 32760, 0, 0);
  s.read(out);
  TEST_ASSERT_EQUAL_UINT8(kAccelNearRail, out.qualityFlags);
  bus.setData(0, -32768, 0, 0, 0, 0);
  s.read(out);
  TEST_ASSERT_EQUAL_UINT8(kGyroNearRail, out.qualityFlags);
  bus.setData(0, 0, 32767, 0, -32760, 0);
  s.read(out);
  TEST_ASSERT_EQUAL_UINT8(kAccelNearRail | kGyroNearRail, out.qualityFlags);
  bus.setData(20000, -20000, 0, 32759, -32759, 0);  // accel one count inside the rail; gyro well inside the encodable limit
  s.read(out);
  TEST_ASSERT_EQUAL_UINT8(0, out.qualityFlags);
}

void test_no_data_until_both_sensors_ready() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  beginSettled(s, bus);
  Sample out{};
  out.timestampUs = 77;
  bus.regs[0x1E] = 0x01;  // only accelerometer
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kNoData), static_cast<int>(s.read(out)));
  bus.regs[0x1E] = 0x00;
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kNoData), static_cast<int>(s.read(out)));
  TEST_ASSERT_EQUAL_UINT32(77, out.timestampUs);
}

void test_read_error_leaves_output_unchanged() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_TRUE(s.begin());
  bus.setData(1, 2, 3, 4, 5, 6);
  bus.failReads = true;
  Sample out{};
  out.timestampUs = 99;
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kError), static_cast<int>(s.read(out)));
  TEST_ASSERT_EQUAL_UINT32(99, out.timestampUs);
}

void test_first_samples_after_start_are_discarded() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_TRUE(s.begin());
  bus.setData(1, 2, 3, 4, 5, 6);
  Sample out{};
  for (int i = 0; i < Lsm6dsoxSensor::kSettleSamples; ++i) {
    TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kNoData), static_cast<int>(s.read(out)));
  }
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kSample), static_cast<int>(s.read(out)));
}

void test_gyro_flagged_above_encodable_limit() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  beginSettled(s, bus);
  Sample out{};
  // 70 mdps/LSB: 26000 LSB is 1820 dps = 31.8 rad/s (encodable); 27000 LSB is 1890 dps = 33.0 rad/s, which
  // the int16 rad/s x 1000 wire format clips even though the raw value is not within 8 counts of full scale.
  bus.setData(26000, 0, 0, 0, 0, 0);
  s.read(out);
  TEST_ASSERT_EQUAL_UINT8(0, out.qualityFlags);
  bus.setData(0, 27000, 0, 0, 0, 0);
  s.read(out);
  TEST_ASSERT_EQUAL_UINT8(kGyroNearRail, out.qualityFlags);
  bus.setData(0, 0, -27000, 0, 0, 0);
  s.read(out);
  TEST_ASSERT_EQUAL_UINT8(kGyroNearRail, out.qualityFlags);
}

void test_sleep_mode_is_accelerometer_only_and_low_power() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_FALSE(s.setSleepMode(true));  // not initialised yet
  TEST_ASSERT_TRUE(s.begin());
  bus.regs[0x1E] = 0x03;
  Sample out{};
  for (int i = 0; i < 12; ++i) s.read(out);  // clear the start-up discards
  bus.writes.clear();
  TEST_ASSERT_TRUE(s.setSleepMode(true));
  TEST_ASSERT_EQUAL_INT(0x00, bus.lastWriteTo(0x11));  // gyro power-down
  TEST_ASSERT_EQUAL_INT(0x13, bus.lastWriteTo(0x15));  // low-power accelerometer
  TEST_ASSERT_EQUAL_INT(0x26, bus.lastWriteTo(0x10));  // 26 Hz, +-16 g, LPF2
  TEST_ASSERT_EQUAL_UINT8(0x11, bus.writes.front().first);  // gyro stopped first
  // Only the accelerometer needs to be ready; the gyro never is. The first outputs are discarded.
  bus.setData(1000, 2000, 3000, 2048, 0, -2048);
  bus.regs[0x1E] = 0x01;
  for (int i = 0; i < 4; ++i) TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kNoData), static_cast<int>(s.read(out)));
  TEST_ASSERT_EQUAL(static_cast<int>(ReadResult::kSample), static_cast<int>(s.read(out)));
  TEST_ASSERT_FLOAT_WITHIN(0.01f, 2048 * 0.488f / 1000.0f * 9.80665f, out.accelMps2.x);
  TEST_ASSERT_FLOAT_WITHIN(0.01f, -2048 * 0.488f / 1000.0f * 9.80665f, out.accelMps2.z);
  TEST_ASSERT_EQUAL_FLOAT(0.0f, out.gyroRadps.x);  // gyro is off: reads as zero, not the stale register
  // Back to full rate: the gyro starts last and the filters settle again.
  bus.writes.clear();
  TEST_ASSERT_TRUE(s.setSleepMode(false));
  TEST_ASSERT_EQUAL_INT(0x46, bus.lastWriteTo(0x10));
  TEST_ASSERT_EQUAL_INT(0x4C, bus.lastWriteTo(0x11));
  TEST_ASSERT_EQUAL_INT(0x03, bus.lastWriteTo(0x15));
  TEST_ASSERT_EQUAL_UINT8(0x11, bus.writes.back().first);
}

void test_begin_leaves_sleep_mode() {
  FakeBus bus;
  Lsm6dsoxSensor s(bus, fakeNowUs, noDelay);
  TEST_ASSERT_TRUE(s.begin());
  TEST_ASSERT_TRUE(s.setSleepMode(true));
  TEST_ASSERT_TRUE(s.begin());
  bus.setData(1000, 2000, 3000, 100, 200, 300);
  Sample out{};
  for (int i = 0; i < 11; ++i) s.read(out);
  TEST_ASSERT_TRUE(out.gyroRadps.x != 0.0f);  // normal acquisition again, gyro read
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_init_writes_expected_configuration);
  RUN_TEST(test_who_am_i_mismatch_fails_cleanly_without_writes);
  RUN_TEST(test_bus_restart_failure_fails_begin);
  RUN_TEST(test_init_fails_when_reset_never_clears);
  RUN_TEST(test_init_fails_when_registers_do_not_stick);
  RUN_TEST(test_init_write_failure_fails_begin);
  RUN_TEST(test_scaling_at_several_values);
  RUN_TEST(test_near_rail_flags);
  RUN_TEST(test_no_data_until_both_sensors_ready);
  RUN_TEST(test_first_samples_after_start_are_discarded);
  RUN_TEST(test_gyro_flagged_above_encodable_limit);
  RUN_TEST(test_read_error_leaves_output_unchanged);
  RUN_TEST(test_sleep_mode_is_accelerometer_only_and_low_power);
  RUN_TEST(test_begin_leaves_sleep_mode);
  return UNITY_END();
}
