#include "UnitIdentity.h"

#include <esp_system.h>
#if __has_include(<esp_mac.h>)
#include <esp_mac.h>
#endif

#include "AppConfig.h"
#include "UnitName.h"

namespace openski::identity {

const char* hostname() {
  static char name[40];
  static bool built = false;
  if (!built) {
    uint8_t mac[6] = {0, 0, 0, 0, 0, 0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
#ifdef OPENSKI_HOSTNAME_MAC_SUFFIX
    constexpr bool kSuffix = true;
#else
    constexpr bool kSuffix = false;
#endif
    formatHostname(config::kHostname, mac, kSuffix, name, sizeof(name));
    built = true;
  }
  return name;
}

const char* bleName() {
  static char name[32];
  static bool built = false;
  if (!built) {
    uint8_t mac[6] = {0, 0, 0, 0, 0, 0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
#ifdef OPENSKI_HOSTNAME_MAC_SUFFIX
    constexpr bool kSuffix = true;
#else
    constexpr bool kSuffix = false;
#endif
    formatHostname(config::kBleName, mac, kSuffix, name, sizeof(name));
    built = true;
  }
  return name;
}

}  // namespace openski::identity
