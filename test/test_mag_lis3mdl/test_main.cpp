#include <unity.h>

#include "Lis3mdlMagnetometer.h"

using namespace openski::mag;
using openski::imu::RegisterBus;

namespace {
class FakeBus : public RegisterBus {
 public:
  uint8_t regs[256]{};
  bool failReads = false;
  bool failWrites = false;
  uint8_t lastBurstStart = 0;
  FakeBus() { regs[0x0F] = 0x3D; }
  bool writeRegister(uint8_t reg, uint8_t value) override {
    if (failWrites) return false;
    regs[reg] = value;
    return true;
  }
  bool readRegisters(uint8_t first, uint8_t* data, size_t length) override {
    if (failReads) return false;
    if (length > 1) lastBurstStart = first;
    const uint8_t base = first & 0x7F;  // MSB is the auto-increment flag, not part of the address
    for (size_t i = 0; i < length; ++i) data[i] = regs[(base + i) & 0x7F];
    return true;
  }
  void setField(int16_t x, int16_t y, int16_t z) {
    regs[0x28] = x & 0xFF; regs[0x29] = (x >> 8) & 0xFF;
    regs[0x2A] = y & 0xFF; regs[0x2B] = (y >> 8) & 0xFF;
    regs[0x2C] = z & 0xFF; regs[0x2D] = (z >> 8) & 0xFF;
    regs[0x27] = 0x0F;
  }
};
}  // namespace

void setUp() {}
void tearDown() {}

void test_init_configures_uhp_80hz_4gauss_continuous() {
  FakeBus bus;
  Lis3mdlMagnetometer m(bus);
  TEST_ASSERT_TRUE(m.begin());
  TEST_ASSERT_EQUAL_UINT8(0x7C, bus.regs[0x20]);
  TEST_ASSERT_EQUAL_UINT8(0x00, bus.regs[0x21]);
  TEST_ASSERT_EQUAL_UINT8(0x00, bus.regs[0x22]);
  TEST_ASSERT_EQUAL_UINT8(0x0C, bus.regs[0x23]);
}

void test_who_am_i_mismatch_fails() {
  FakeBus bus;
  bus.regs[0x0F] = 0x6C;
  Lis3mdlMagnetometer m(bus);
  TEST_ASSERT_FALSE(m.begin());
  MagSample s{};
  TEST_ASSERT_EQUAL(static_cast<int>(MagResult::kError), static_cast<int>(m.read(s)));
}

void test_write_failure_fails_begin() {
  FakeBus bus;
  bus.failWrites = true;
  Lis3mdlMagnetometer m(bus);
  TEST_ASSERT_FALSE(m.begin());
}

void test_raw_and_gauss_values() {
  FakeBus bus;
  Lis3mdlMagnetometer m(bus);
  TEST_ASSERT_TRUE(m.begin());
  bus.setField(6842, -3421, 100);
  MagSample s{};
  TEST_ASSERT_EQUAL(static_cast<int>(MagResult::kSample), static_cast<int>(m.read(s)));
  TEST_ASSERT_EQUAL_INT16(6842, s.rawX);
  TEST_ASSERT_EQUAL_INT16(-3421, s.rawY);
  TEST_ASSERT_EQUAL_INT16(100, s.rawZ);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, 1.0f, s.gaussX);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, -0.5f, s.gaussY);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, 100.0f / 6842.0f, s.gaussZ);
  TEST_ASSERT_EQUAL_UINT8(0xA8, bus.lastBurstStart);  // 0x28 with the auto-increment bit
}

void test_no_data_and_errors() {
  FakeBus bus;
  Lis3mdlMagnetometer m(bus);
  TEST_ASSERT_TRUE(m.begin());
  MagSample s{};
  bus.regs[0x27] = 0x07;  // ZYXDA clear
  TEST_ASSERT_EQUAL(static_cast<int>(MagResult::kNoData), static_cast<int>(m.read(s)));
  bus.setField(1, 2, 3);
  bus.failReads = true;
  TEST_ASSERT_EQUAL(static_cast<int>(MagResult::kError), static_cast<int>(m.read(s)));
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_init_configures_uhp_80hz_4gauss_continuous);
  RUN_TEST(test_who_am_i_mismatch_fails);
  RUN_TEST(test_write_failure_fails_begin);
  RUN_TEST(test_raw_and_gauss_values);
  RUN_TEST(test_no_data_and_errors);
  return UNITY_END();
}
