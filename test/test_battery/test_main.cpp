#include <unity.h>

#include "BatteryMath.h"

using openski::battery::cellVolts;
using openski::battery::sampleDue;

void setUp() {}
void tearDown() {}

void test_equal_divider_doubles_the_pin_voltage() {
  TEST_ASSERT_FLOAT_WITHIN(0.0005f, 4.2f, cellVolts(2100.0f, 2.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.0005f, 3.0f, cellVolts(1500.0f, 2.0f));
}

void test_other_ratios_scale_linearly() {
  TEST_ASSERT_FLOAT_WITHIN(0.0005f, 3.9f, cellVolts(1300.0f, 3.0f));
  TEST_ASSERT_FLOAT_WITHIN(0.0005f, 0.0f, cellVolts(0.0f, 2.0f));
}

void test_first_sample_is_due_immediately() {
  TEST_ASSERT_TRUE(sampleDue(false, 0, 0, 60000));
  TEST_ASSERT_TRUE(sampleDue(false, 123, 0, 60000));
}

void test_next_sample_waits_for_the_interval() {
  TEST_ASSERT_FALSE(sampleDue(true, 59999, 0, 60000));
  TEST_ASSERT_TRUE(sampleDue(true, 60000, 0, 60000));
  TEST_ASSERT_FALSE(sampleDue(true, 69999, 10000, 60000));
  TEST_ASSERT_TRUE(sampleDue(true, 70000, 10000, 60000));
}

void test_interval_is_correct_across_millis_rollover() {
  const uint32_t last = 0xFFFFFFFFu - 10000u;  // 10 s before rollover
  // 2^32 - last = 10001 ms to rollover, so elapsed is now + 10001.
  TEST_ASSERT_FALSE(sampleDue(true, 49998u, last, 60000));
  TEST_ASSERT_TRUE(sampleDue(true, 49999u, last, 60000));
}

void test_hold_starts_below_threshold_and_releases_with_hysteresis() {
  using openski::battery::lowBatteryHold;
  TEST_ASSERT_FALSE(lowBatteryHold(3.9f, false));
  TEST_ASSERT_FALSE(lowBatteryHold(3.6f, false));
  TEST_ASSERT_TRUE(lowBatteryHold(3.55f, false));
  TEST_ASSERT_TRUE(lowBatteryHold(3.62f, true));   // still holding inside the band
  TEST_ASSERT_FALSE(lowBatteryHold(3.65f, true));  // released
}

void test_implausible_readings_never_hold_wifi() {
  using openski::battery::lowBatteryHold;
  TEST_ASSERT_FALSE(lowBatteryHold(0.133f, false));  // grounded or floating sense pin
  TEST_ASSERT_FALSE(lowBatteryHold(0.0f, true));
  TEST_ASSERT_TRUE(lowBatteryHold(3.0f, false));     // a genuinely flat cell does hold
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_hold_starts_below_threshold_and_releases_with_hysteresis);
  RUN_TEST(test_implausible_readings_never_hold_wifi);
  RUN_TEST(test_equal_divider_doubles_the_pin_voltage);
  RUN_TEST(test_other_ratios_scale_linearly);
  RUN_TEST(test_first_sample_is_due_immediately);
  RUN_TEST(test_next_sample_waits_for_the_interval);
  RUN_TEST(test_interval_is_correct_across_millis_rollover);
  return UNITY_END();
}
