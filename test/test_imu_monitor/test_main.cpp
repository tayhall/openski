#include <unity.h>

#include "ImuMonitor.h"
#include "RecordFlags.h"

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

void test_stalled_sensor_is_marked_not_ready_and_reinitialised() {
  FakeImu imu;
  ImuMonitor monitor(&imu);
  TEST_ASSERT_TRUE(monitor.begin());
  monitor.poll();                                   // one sample, then the chip goes quiet
  TEST_ASSERT_FALSE(monitor.checkStall(10000, 1000));  // first sight of this sample count starts the clock
  imu.nextResult = ReadResult::kNoData;
  for (int i = 0; i < 50; ++i) monitor.poll();
  TEST_ASSERT_FALSE(monitor.checkStall(10900, 1000));
  TEST_ASSERT_TRUE(monitor.stats().ready);
  TEST_ASSERT_TRUE(monitor.checkStall(11001, 1000));
  TEST_ASSERT_FALSE(monitor.stats().ready);
  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().stalls);
  imu.nextResult = ReadResult::kSample;
  TEST_ASSERT_TRUE(monitor.retryBegin(11002, 2000));  // the existing retry path brings it back
  TEST_ASSERT_TRUE(monitor.stats().ready);
}

void test_checkstall_ignores_a_sensor_that_keeps_delivering() {
  FakeImu imu;
  ImuMonitor monitor(&imu);
  TEST_ASSERT_TRUE(monitor.begin());
  uint32_t now = 5000;
  for (int i = 0; i < 500; ++i, now += 10) {
    imu.nextSample.timestampUs = now * 1000U;
    monitor.poll();
    TEST_ASSERT_FALSE(monitor.checkStall(now, 1000));
  }
  TEST_ASSERT_EQUAL_UINT32(0, monitor.stats().stalls);
  TEST_ASSERT_TRUE(monitor.stats().ready);
}

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

void test_timing_quality_handles_wrap_and_strict_gap_threshold() {
  FakeImu imu;
  ImuMonitor monitor(&imu);
  monitor.begin();
  imu.nextSample.timestampUs = 0xfffffff0U;
  monitor.poll();
  imu.nextSample.timestampUs += 10000U;
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().timestampWraps);
  TEST_ASSERT_EQUAL_UINT32(0, monitor.stats().timestampGaps);
  TEST_ASSERT_EQUAL_UINT32(10000, monitor.stats().lastIntervalUs);
  TEST_ASSERT_TRUE(monitor.latest().qualityFlags & openski::imu::kTimestampWrap);
  imu.nextSample.timestampUs += 20000U;
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT32(0, monitor.stats().timestampGaps);
  imu.nextResult = ReadResult::kError;
  monitor.poll();
  imu.nextResult = ReadResult::kSample;
  imu.nextSample.timestampUs += 20001U;
  imu.nextSample.qualityFlags = openski::imu::kAccelNearRail | openski::imu::kGyroNearRail;
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().timestampGaps);
  TEST_ASSERT_EQUAL_UINT32(20001, monitor.stats().maxIntervalUs);
  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().accelNearRailSamples);
  TEST_ASSERT_EQUAL_UINT32(1, monitor.stats().gyroNearRailSamples);
  TEST_ASSERT_TRUE(monitor.latest().qualityFlags & openski::imu::kTimestampGap);
  imu.nextSample.timestampUs += 10000U;
  imu.nextSample.qualityFlags = 0;
  monitor.poll();
  TEST_ASSERT_EQUAL_UINT8(0, monitor.latest().qualityFlags);
}

void test_record_flags_pack_into_the_low_timestamp_bits() {
  using namespace openski::imu;
  const uint32_t packed = packRecordTimestamp(1234567U, kRecordAccelNearRail | kRecordDroppedBefore);
  TEST_ASSERT_EQUAL_UINT8(5, recordFlags(packed));
  TEST_ASSERT_EQUAL_UINT32(1234567U & ~7U, recordTimestamp(packed));
  TEST_ASSERT_EQUAL_UINT32(0xfffffff8U, recordTimestamp(packRecordTimestamp(0xffffffffU, 7)));
  TEST_ASSERT_EQUAL_UINT8(0, recordFlags(packRecordTimestamp(7U, 0)));
  TEST_ASSERT_EQUAL_UINT8(3, recordFlagsFromQuality(kAccelNearRail | kGyroNearRail | kTimestampGap));
  TEST_ASSERT_EQUAL_UINT8(0, recordFlagsFromQuality(kTimestampGap | kTimestampWrap));
}

void test_record_dropped_before_flag_is_bit_two_and_keeps_timestamp_resolution() {
  using namespace openski::imu;
  const uint32_t packed = packRecordTimestamp(1000U, kRecordDroppedBefore);
  TEST_ASSERT_EQUAL_UINT8(4, recordFlags(packed));
  TEST_ASSERT_EQUAL_UINT32(1000U, recordTimestamp(packed));  // 1000 is a multiple of 8
  // Flags never reach the timestamp bits above bit 2, for any flag byte.
  const uint32_t noisy = packRecordTimestamp(1000U, 0xffU);
  TEST_ASSERT_EQUAL_UINT8(7, recordFlags(noisy));
  TEST_ASSERT_EQUAL_UINT32(1000U, recordTimestamp(noisy));
  // Consecutive 10 ms samples stay ordered at 8 us resolution with and without flags.
  TEST_ASSERT_TRUE(recordTimestamp(packRecordTimestamp(11000U, 7)) >
                   recordTimestamp(packRecordTimestamp(10000U, 0)));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_timing_quality_handles_wrap_and_strict_gap_threshold);
  RUN_TEST(test_record_flags_pack_into_the_low_timestamp_bits);
  RUN_TEST(test_record_dropped_before_flag_is_bit_two_and_keeps_timestamp_resolution);
  RUN_TEST(test_no_sensor_is_not_ready);
  RUN_TEST(test_begin_failure_is_not_ready_and_poll_is_ignored);
  RUN_TEST(test_sample_is_counted_and_kept_as_latest);
  RUN_TEST(test_no_data_is_neither_sample_nor_failure);
  RUN_TEST(test_read_error_is_counted_and_keeps_previous_sample);
  RUN_TEST(test_sensor_name_is_exposed);
  RUN_TEST(test_stalled_sensor_is_marked_not_ready_and_reinitialised);
  RUN_TEST(test_checkstall_ignores_a_sensor_that_keeps_delivering);
  RUN_TEST(test_retry_recovers_a_sensor_that_was_missed_at_boot);
  RUN_TEST(test_retry_keeps_trying_at_the_interval_while_the_sensor_stays_missing);
  return UNITY_END();
}
