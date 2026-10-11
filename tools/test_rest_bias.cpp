#include "RestBias.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>

using namespace openski::motion;

namespace {
constexpr float kG = 9.80665f;
void check(bool ok, const char* what) {
  if (!ok) { std::printf("FAILED: %s\n", what); std::exit(1); }
}
unsigned gSeed = 12345;
float noise(float amplitude) {  // deterministic, uniform in +-amplitude
  gSeed = gSeed*1664525u + 1013904223u;
  return (static_cast<int>((gSeed >> 16) & 0xff) - 128)/128.0f*amplitude;
}
// 100 Hz at rest (gravity on z) with a constant gyro offset on x and optional noise.
void feedRest(RestBias& r, uint32_t& t, float seconds, float offsetX, float noiseAmp = 0.0f) {
  const int n = static_cast<int>(seconds*100);
  for (int i = 0; i < n; ++i, t += 10000U)
    r.update(t, 0, 0, kG, offsetX + noise(noiseAmp), noise(noiseAmp), noise(noiseAmp));
}
}  // namespace

int main() {
  {  // (a) constant 0.04 rad/s on x: converges, never faster than 0.002 rad/s per second.
    RestBias r; uint32_t t = 1000000U; const uint32_t t0 = t;
    float at5 = -1;
    for (int i = 0; i < 3000; ++i, t += 10000U) {
      r.update(t, 0, 0, kG, 0.04f, 0, 0);
      const float elapsed = (t - t0)/1e6f;
      check(r.bias(0) <= 0.002f*elapsed + 1e-5f, "estimate never moves faster than 0.002 rad/s per second");
      if (i == 500) at5 = r.bias(0);
    }
    check(at5 <= 0.010f, "at 5 s the estimate is at most 0.010");
    check(at5 > 0.004f, "estimate is moving by 5 s");
    check(std::fabs(r.bias(0) - 0.04f) < 0.005f, "converges to the offset");
    check(std::fabs(r.bias(1)) < 1e-6f && std::fabs(r.bias(2)) < 1e-6f, "other axes stay at zero");
    check(r.updates() >= 10 && r.refused() == 0, "updates counted, none refused");
  }
  {  // (b) steady 0.10 rad/s rotation with gravity turning in the sensor frame is never absorbed.
    RestBias r; uint32_t t = 0;
    const float w = 0.10f;
    for (int i = 0; i < 6000; ++i, t += 10000U) {
      const float a = w*i*0.01f;
      r.update(t, 0, kG*std::sin(a), kG*std::cos(a), w, 0, 0);
    }
    check(r.updates() == 0, "steady rotation is not absorbed");
    check(std::fabs(r.bias(0)) < 1e-6f, "estimate untouched by rotation");
  }
  {  // (c) a 0.2 rad/s offset at rest is refused and the estimate stays at zero.
    RestBias r; uint32_t t = 0;
    feedRest(r, t, 20.0f, 0.2f);
    check(r.refused() > 0, "large offset refused");
    check(r.updates() == 0, "no update from a large offset");
    check(r.bias(0) == 0.0f, "estimate stays at zero");
  }
  {  // (d) 1.5 g never opens a window.
    RestBias r; uint32_t t = 0;
    bool everResting = false;
    for (int i = 0; i < 3000; ++i, t += 10000U) {
      r.update(t, 0, 0, 1.5f*kG, 0.03f, 0, 0);
      everResting = everResting || r.resting();
    }
    check(!everResting, "no window opens under 1.5 g");
    check(r.updates() == 0 && r.refused() == 0, "nothing counted under 1.5 g");
  }
  {  // (e) a 150 ms gap resets the window, so nothing completes across it.
    RestBias r; uint32_t t = 0;
    feedRest(r, t, 1.5f, 0.04f);
    check(r.resting(), "window open before the gap");
    t += 150000U;
    feedRest(r, t, 1.5f, 0.04f);
    check(r.updates() == 0, "no update across a 150 ms gap");
    feedRest(r, t, 1.0f, 0.04f);
    check(r.updates() == 1, "window completes once 2 s are continuous after the gap");
  }
  {  // (f) +-0.01 rad/s noise does not stop convergence.
    RestBias r; uint32_t t = 0;
    feedRest(r, t, 40.0f, 0.04f, 0.01f);
    check(r.updates() >= 10, "noisy windows still complete");
    check(std::fabs(r.bias(0) - 0.04f) < 0.005f, "converges with noise");
    check(std::fabs(r.bias(1)) < 0.005f, "zero axes stay near zero with noise");
  }
  {  // Clock wrap: starting 1 s before the 32-bit rollover still converges.
    RestBias r; uint32_t t = 0xFFFFFFFFU - 1000000U;
    feedRest(r, t, 30.0f, 0.04f);
    check(t < 0xFFFFFFFFU - 1000000U, "clock wrapped during the test");
    check(std::fabs(r.bias(0) - 0.04f) < 0.005f, "converges across the wrap");
  }
  {  // reset() clears the estimate and counters.
    RestBias r; uint32_t t = 0;
    feedRest(r, t, 10.0f, 0.04f);
    check(r.bias(0) > 0, "estimate moved");
    r.reset();
    check(r.bias(0) == 0 && r.updates() == 0 && r.refused() == 0 && !r.resting(), "reset clears state");
  }
  std::printf("test_rest_bias: all checks passed\n");
  return 0;
}
