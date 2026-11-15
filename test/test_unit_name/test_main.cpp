#include <string.h>
#include <unity.h>

#include "UnitName.h"

using openski::identity::formatHostname;

namespace {
const uint8_t kMac[6] = {0x24, 0x6f, 0x28, 0xa1, 0xb2, 0xc3};
}

void test_suffix_uses_last_two_mac_bytes() {
  char out[32];
  const size_t n = formatHostname("ski-boot", kMac, true, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("ski-boot-b2c3", out);
  TEST_ASSERT_EQUAL_UINT32(13, n);
}

void test_distinct_macs_give_distinct_names() {
  uint8_t other[6];
  memcpy(other, kMac, 6);
  other[5] = 0xc4;
  char a[32], b[32];
  formatHostname("ski-boot", kMac, true, a, sizeof(a));
  formatHostname("ski-boot", other, true, b, sizeof(b));
  TEST_ASSERT_TRUE(strcmp(a, b) != 0);
}

void test_same_mac_is_stable() {
  char a[32], b[32];
  formatHostname("ski-boot", kMac, true, a, sizeof(a));
  formatHostname("ski-boot", kMac, true, b, sizeof(b));
  TEST_ASSERT_EQUAL_STRING(a, b);
}

void test_zero_padding_and_lowercase() {
  const uint8_t zeros[6] = {1, 2, 3, 4, 0x00, 0x00};
  const uint8_t ffs[6] = {1, 2, 3, 4, 0x0f, 0xff};
  char out[32];
  formatHostname("ski-boot", zeros, true, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("ski-boot-0000", out);
  formatHostname("ski-boot", ffs, true, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("ski-boot-0fff", out);
}

void test_suffix_off_returns_prefix_only() {
  char out[32];
  const size_t n = formatHostname("ski-xiao-l", kMac, false, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("ski-xiao-l", out);
  TEST_ASSERT_EQUAL_UINT32(10, n);
}

void test_truncates_without_overrun() {
  char out[16];
  memset(out, 'X', sizeof(out));
  const size_t n = formatHostname("a-very-long-prefix", kMac, true, out, 8);
  TEST_ASSERT_EQUAL_UINT32(7, n);
  TEST_ASSERT_EQUAL_CHAR('\0', out[7]);
  TEST_ASSERT_EQUAL_STRING("a--b2c3", out);
  for (int i = 8; i < 16; ++i) TEST_ASSERT_EQUAL_CHAR('X', out[i]);
}

void test_zero_size_buffer_writes_nothing() {
  char out[4] = {'X', 'X', 'X', 'X'};
  TEST_ASSERT_EQUAL_UINT32(0, formatHostname("ski", kMac, true, out, 0));
  TEST_ASSERT_EQUAL_CHAR('X', out[0]);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_suffix_uses_last_two_mac_bytes);
  RUN_TEST(test_distinct_macs_give_distinct_names);
  RUN_TEST(test_same_mac_is_stable);
  RUN_TEST(test_zero_padding_and_lowercase);
  RUN_TEST(test_suffix_off_returns_prefix_only);
  RUN_TEST(test_truncates_without_overrun);
  RUN_TEST(test_zero_size_buffer_writes_nothing);
  return UNITY_END();
}
