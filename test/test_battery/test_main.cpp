#include <unity.h>

#include "BatteryMath.h"

using openski::battery::cellVolts;
using openski::battery::sampleDue;
using openski::battery::lowCellAtRest;

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

void test_one_reading_at_rest_decides_the_shutdown() {
  TEST_ASSERT_TRUE(lowCellAtRest(3.25f));
  TEST_ASSERT_FALSE(lowCellAtRest(3.3f));
  TEST_ASSERT_FALSE(lowCellAtRest(3.6f));
  TEST_ASSERT_FALSE(lowCellAtRest(0.4f));  // not a cell: never counts
}

void test_shutdown_needs_three_consecutive_low_readings() {
  using namespace openski::battery;
  int count = lowCellCount(3.25f, 0);
  TEST_ASSERT_FALSE(shutdownDue(count));
  count = lowCellCount(3.2f, count);
  TEST_ASSERT_FALSE(shutdownDue(count));
  count = lowCellCount(3.2f, count);
  TEST_ASSERT_TRUE(shutdownDue(count));
}

void test_a_recovered_reading_restarts_the_count() {
  using namespace openski::battery;
  int count = lowCellCount(3.2f, lowCellCount(3.2f, 0));
  count = lowCellCount(3.4f, count);  // a sag that recovered
  TEST_ASSERT_EQUAL_INT(0, count);
  TEST_ASSERT_FALSE(shutdownDue(lowCellCount(3.2f, count)));
}

void test_implausible_readings_never_count_towards_shutdown() {
  using namespace openski::battery;
  TEST_ASSERT_EQUAL_INT(0, lowCellCount(0.133f, 2));  // no cell fitted
  TEST_ASSERT_EQUAL_INT(1, lowCellCount(3.29f, 0));
  TEST_ASSERT_EQUAL_INT(0, lowCellCount(3.3f, 1));    // threshold itself is not low
}

void test_resume_needs_a_charged_cell_or_no_cell() {
  using namespace openski::battery;
  TEST_ASSERT_FALSE(resumeFromShutdown(3.3f));
  TEST_ASSERT_FALSE(resumeFromShutdown(3.79f));
  TEST_ASSERT_TRUE(resumeFromShutdown(3.8f));
  TEST_ASSERT_TRUE(resumeFromShutdown(0.1f));  // not a cell: never stay asleep
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_shutdown_needs_three_consecutive_low_readings);
  RUN_TEST(test_a_recovered_reading_restarts_the_count);
  RUN_TEST(test_implausible_readings_never_count_towards_shutdown);
  RUN_TEST(test_resume_needs_a_charged_cell_or_no_cell);
  RUN_TEST(test_hold_starts_below_threshold_and_releases_with_hysteresis);
  RUN_TEST(test_implausible_readings_never_hold_wifi);
  RUN_TEST(test_equal_divider_doubles_the_pin_voltage);
  RUN_TEST(test_other_ratios_scale_linearly);
  RUN_TEST(test_one_reading_at_rest_decides_the_shutdown);
  RUN_TEST(test_first_sample_is_due_immediately);
  RUN_TEST(test_next_sample_waits_for_the_interval);
  RUN_TEST(test_interval_is_correct_across_millis_rollover);
  return UNITY_END();
}
