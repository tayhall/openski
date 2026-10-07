#include <unity.h>

#include "ImuMonitor.h"

using openski::imu::ImuMonitor;
using openski::imu::ImuSensor;
using openski::imu::ReadResult;
using openski::imu::Sample;

namespace {
class FakeImu : public ImuSensor {
 public:
  bool beginOk = true;
  ReadResult nextResult = ReadResult::kSample;
  Sample nextSample{};

  bool begin() override { return beginOk; }
  ReadResult read(Sample& out) override {
    if (nextResult == ReadResult::kSample) out = nextSample;
    return nextResult;
  }
  const char* name() const override { return "fake"; }
};
}  // namespace

void setUp() {}
void tearDown() {}

void test_no_sensor_is_not_ready() {
  ImuMonitor monitor(nullptr);
  TEST_ASSERT_FALSE(monitor.begin());
  TEST_ASSERT_FALSE(monitor.stats().ready);
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT32(0, monitor.stats().samples);
}

void test_begin_failure_is_not_ready_and_poll_is_ignored() {
  FakeImu imu;
  imu.beginOk = false;
  ImuMonitor monitor(&imu);
  TEST_ASSERT_FALSE(monitor.begin());
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT32(0, monitor.stats().samples);
  TEST_ASSERT_FALSE(monitor.hasSample());
}

void test_sample_is_counted_and_kept_as_latest() {
  FakeImu imu;
  imu.nextSample.timestampUs = 1234;
  imu.nextSample.accelMps2 = {0.0f, 0.0f, 9.81f};
  imu.nextSample.gyroRadps = {0.1f, -0.2f, 0.3f};
  ImuMonitor monitor(&imu);
  TEST_ASSERT_TRUE(monitor.begin());
  TEST_ASSERT_TRUE(monitor.stats().ready);

  monitor.poll();

  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().samples);
  TEST_ASSERT_TRUE(monitor.hasSample());
  TEST_ASSERT_EQUAL_UINT32(1234, monitor.latest().timestampUs);
  TEST_ASSERT_EQUAL_FLOAT(9.81f, monitor.latest().accelMps2.z);
  TEST_ASSERT_EQUAL_FLOAT(-0.2f, monitor.latest().gyroRadps.y);
}

void test_no_data_is_neither_sample_nor_failure() {
  FakeImu imu;
  imu.nextResult = ReadResult::kNoData;
  ImuMonitor monitor(&imu);
  monitor.begin();
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT32(0, monitor.stats().samples);
  TEST_ASSERT_EQUAL_UINT32(0, monitor.stats().readFailures);
  TEST_ASSERT_FALSE(monitor.hasSample());
}

void test_read_error_is_counted_and_keeps_previous_sample() {
  FakeImu imu;
  imu.nextSample.timestampUs = 42;
  ImuMonitor monitor(&imu);
  monitor.begin();
  monitor.poll();

  imu.nextResult = ReadResult::kError;
  imu.nextSample.timestampUs = 99;
  monitor.poll();

  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().samples);
  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().readFailures);
  TEST_ASSERT_EQUAL_UINT32(42, monitor.latest().timestampUs);
}

void test_sensor_name_is_exposed() {
  FakeImu imu;
  ImuMonitor withSensor(&imu);
  ImuMonitor withoutSensor(nullptr);
  TEST_ASSERT_EQUAL_STRING("fake", withSensor.sensorName());
  TEST_ASSERT_EQUAL_STRING("none", withoutSensor.sensorName());
}

void test_retry_recovers_a_sensor_that_was_missed_at_boot() {
  FakeImu imu;
  imu.beginOk = false;
  ImuMonitor monitor(&imu);
  TEST_ASSERT_FALSE(monitor.begin());

  // Too soon after the first attempt: no retry yet.
  TEST_ASSERT_FALSE(monitor.retryBegin(1000, 2000));
  imu.beginOk = true;
  TEST_ASSERT_FALSE(monitor.retryBegin(2999, 2000));
  TEST_ASSERT_FALSE(monitor.stats().ready);

  // After the interval it retries once and succeeds.
  TEST_ASSERT_TRUE(monitor.retryBegin(3000, 2000));
  TEST_ASSERT_TRUE(monitor.stats().ready);
  // A ready sensor is never re-initialised.
  TEST_ASSERT_FALSE(monitor.retryBegin(10000, 2000));
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().samples);
}

void test_retry_keeps_trying_at_the_interval_while_the_sensor_stays_missing() {
  FakeImu imu;
  imu.beginOk = false;
  ImuMonitor monitor(&imu);
  TEST_ASSERT_FALSE(monitor.retryBegin(0, 2000));
  TEST_ASSERT_FALSE(monitor.retryBegin(1999, 2000));
  TEST_ASSERT_FALSE(monitor.retryBegin(2000, 2000));
  imu.beginOk = true;
  TEST_ASSERT_FALSE(monitor.retryBegin(3999, 2000));
  TEST_ASSERT_TRUE(monitor.retryBegin(4000, 2000));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_no_sensor_is_not_ready);
  RUN_TEST(test_begin_failure_is_not_ready_and_poll_is_ignored);
  RUN_TEST(test_sample_is_counted_and_kept_as_latest);
  RUN_TEST(test_no_data_is_neither_sample_nor_failure);
  RUN_TEST(test_read_error_is_counted_and_keeps_previous_sample);
  RUN_TEST(test_sensor_name_is_exposed);
  RUN_TEST(test_retry_recovers_a_sensor_that_was_missed_at_boot);
  RUN_TEST(test_retry_keeps_trying_at_the_interval_while_the_sensor_stays_missing);
  return UNITY_END();
}
