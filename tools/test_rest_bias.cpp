#include "RestBias.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

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

int runTests() {
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
  {  // Known, bounded absorption case (not a defect): a steady 0.11 rad/s turn about the vertical
     // passes every gate (|g| < 0.12, gravity constant, no peak-to-peak), so it is learned as bias,
     // but only at the rate limit and never beyond its own magnitude.
    RestBias r; uint32_t t = 0; const uint32_t t0 = t;
    for (int i = 0; i < 12000; ++i, t += 10000U) {
      r.update(t, 0, 0, kG, 0, 0, 0.11f);
      const float elapsed = (t - t0)/1e6f;
      check(std::fabs(r.bias(2)) <= 0.002f*elapsed + 1e-5f, "vertical turn absorbed no faster than the rate limit");
      check(std::fabs(r.bias(2)) <= 0.11f + 1e-5f, "vertical turn absorbed no more than its own rate");
    }
    check(r.refused() == 0, "vertical turn under the limits is not refused");
    check(r.updates() > 0, "vertical turn is the documented absorption case");
  }
  {  // 0.13 rad/s offset: mean under the 0.15 refusal but over the 0.12 per-sample limit. Only the
     // over-limit rule stops it (test (c)'s 0.2 is also caught by the mean guard).
    RestBias r; uint32_t t = 0;
    feedRest(r, t, 20.0f, 0.13f);
    check(r.refused() > 0, "0.13 offset refused");
    check(r.updates() == 0, "no update from a 0.13 offset");
    check(r.bias(0) == 0.0f, "estimate stays at zero for 0.13 offset");
  }
  {  // Non-finite inputs are ineligible and end the window.
    RestBias r; uint32_t t = 0;
    feedRest(r, t, 1.5f, 0.04f);
    check(r.resting(), "window open before the NaN");
    r.update(t, 0, 0, kG, NAN, 0, 0); t += 10000U;
    check(!r.resting(), "NaN closes the window");
    r.update(t, INFINITY, 0, kG, 0.04f, 0, 0); t += 10000U;
    feedRest(r, t, 1.5f, 0.04f);
    check(r.updates() == 0, "no update across a NaN");
    feedRest(r, t, 1.0f, 0.04f);
    check(r.updates() == 1 && std::isfinite(r.bias(0)), "recovers after non-finite samples");
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

// ---- Kotlin parity fixture (android RestBiasParityTest) ----
// Format: "series <name>", then one "s ax ay az gx gy gz" line per 100 Hz sample from t = 1000 ms
// ("x" = missing sample), "c <index> bx by bz updates refused" checkpoints after every 100th sample and after
// any sample on which the update or refusal count changed, then "end".
namespace {
constexpr float kPi = 3.14159265f;
struct Sample { bool present; float v[6]; };
struct Checkpoint { int index; float bias[3]; uint32_t updates, refused; };
struct Series { std::string name; std::vector<Sample> samples; };

float r4(float v) { return std::round(v*10000.0f)/10000.0f; }

// accelNoise applies to x and y only, so a series can sit near the accelerometer peak-to-peak limit without also
// moving |a|; azScale lifts |a| away from 1 g to sit near the stillness fraction.
void addRest(Series& s, float seconds, float gx, float gy, float gz, float noiseAmp,
             float accelNoise = 0.05f, float azScale = 1.0f) {
  for (int i = 0, n = static_cast<int>(seconds*100); i < n; ++i)
    s.samples.push_back({true, {r4(noise(accelNoise)), r4(noise(accelNoise)), r4(kG*azScale + noise(0.05f)),
                                r4(gx + noise(noiseAmp)), r4(gy + noise(noiseAmp)), r4(gz + noise(noiseAmp))}});
}
void addMotion(Series& s, float seconds) {  // walking-like: accel swings about 3 m/s^2, gyro about 1 rad/s
  for (int i = 0, n = static_cast<int>(seconds*100); i < n; ++i) {
    const float ph = 2*kPi*1.8f*i/100.0f;
    s.samples.push_back({true, {r4(1.5f*std::sin(ph)), r4(1.0f*std::cos(ph)), r4(kG + 3.0f*std::sin(ph + 1)),
                                r4(1.0f*std::sin(ph)), r4(0.6f*std::cos(ph)), r4(0.3f*std::sin(2*ph))}});
  }
}
void addHole(Series& s, int samples) {
  for (int i = 0; i < samples; ++i) s.samples.push_back({false, {0, 0, 0, 0, 0, 0}});
}

std::vector<Series> buildSeries() {
  gSeed = 4242;
  std::vector<Series> all;
  { Series s; s.name = "converge";           // constant offset learned in rate-limited steps
    addRest(s, 12.0f, 0.02f, -0.015f, 0.01f, 0.004f); all.push_back(s); }
  { Series s; s.name = "motion_then_rest";    // nothing learned while moving, then it learns
    addMotion(s, 5.0f); addRest(s, 9.0f, 0.025f, 0.0f, -0.01f, 0.004f); all.push_back(s); }
  { Series s; s.name = "gap_in_window";       // a 0.3 s hole splits the still window
    addRest(s, 1.5f, 0.02f, 0.0f, 0.0f, 0.003f); addHole(s, 30); addRest(s, 4.5f, 0.02f, 0.0f, 0.0f, 0.003f);
    all.push_back(s); }
  { Series s; s.name = "offset_refused";      // 0.2 rad/s is rotation, not bias
    addRest(s, 10.0f, 0.2f, 0.0f, 0.0f, 0.004f); all.push_back(s); }
  { Series s; s.name = "steady_turn";         // gravity turns with a 0.1 rad/s rotation: not rest
    for (int i = 0; i < 1000; ++i) {
      const float a = 0.1f*i*0.01f;
      s.samples.push_back({true, {0, r4(kG*std::sin(a)), r4(kG*std::cos(a)), 0.1f, 0, 0}});
    }
    all.push_back(s); }
  // Just inside and just outside each limit (4 s of rest each). A rule that moves shows as a different update or
  // refusal count, so retuning one side of a pair without the other fails the parity test.
  struct Edge { const char* name; float gx, noise, accelNoise, azScale; };
  static const Edge edges[] = {
    {"offset_inside",      0.11f, 0.004f, 0.05f, 1.0f},    // per-sample |g| limit 0.12
    {"offset_over",        0.13f, 0.004f, 0.05f, 1.0f},
    {"gyro_p2p_inside",    0.02f, 0.020f, 0.05f, 1.0f},    // gyro peak-to-peak limit 0.05
    {"gyro_p2p_over",      0.02f, 0.030f, 0.05f, 1.0f},
    {"accel_frac_inside",  0.02f, 0.004f, 0.05f, 1.02f},   // | |a| - g | / g limit 3 %
    {"accel_frac_over",    0.02f, 0.004f, 0.05f, 1.04f},
    {"accel_p2p_inside",   0.02f, 0.004f, 0.20f, 1.0f},    // accelerometer peak-to-peak limit 0.5
    {"accel_p2p_over",     0.02f, 0.004f, 0.30f, 1.0f},
  };
  for (const Edge& e : edges) {
    Series s; s.name = e.name;
    addRest(s, 4.0f, e.gx, 0.0f, 0.0f, e.noise, e.accelNoise, e.azScale);
    all.push_back(s);
  }
  return all;
}

std::vector<Checkpoint> run(const Series& s) {
  RestBias r; std::vector<Checkpoint> out;
  uint32_t lastUpdates = 0, lastRefused = 0;
  for (size_t i = 0; i < s.samples.size(); ++i) {
    const Sample& m = s.samples[i];
    if (m.present)
      r.update(1000000U + static_cast<uint32_t>(i)*10000U, m.v[0], m.v[1], m.v[2], m.v[3], m.v[4], m.v[5]);
    const bool changed = r.updates() != lastUpdates || r.refused() != lastRefused;
    lastUpdates = r.updates(); lastRefused = r.refused();
    if (i % 100 == 99 || changed)
      out.push_back({static_cast<int>(i), {r.bias(0), r.bias(1), r.bias(2)}, r.updates(), r.refused()});
  }
  return out;
}

int emitFixture(const char* path) {
  std::FILE* f = std::fopen(path, "wb");   // binary: the same bytes on every platform
  if (!f) { std::printf("cannot write %s\n", path); return 1; }
  for (const Series& s : buildSeries()) {
    std::fprintf(f, "series %s\n", s.name.c_str());
    for (const Sample& m : s.samples) {
      if (m.present) std::fprintf(f, "s %.4f %.4f %.4f %.4f %.4f %.4f\n", m.v[0], m.v[1], m.v[2], m.v[3], m.v[4], m.v[5]);
      else std::fprintf(f, "x\n");
    }
    for (const Checkpoint& c : run(s))
      std::fprintf(f, "c %d %.6f %.6f %.6f %u %u\n", c.index, c.bias[0], c.bias[1], c.bias[2], c.updates, c.refused);
    std::fprintf(f, "end\n");
  }
  std::fclose(f);
  return 0;
}

int checkFixture(const char* path) {
  std::FILE* f = std::fopen(path, "r");
  if (!f) { std::printf("cannot read %s\n", path); return 1; }
  char line[256]; int seriesSeen = 0;
  Series s; std::vector<Checkpoint> stored; bool inSeries = false;
  while (std::fgets(line, sizeof line, f)) {
    if (std::strncmp(line, "series ", 7) == 0) {
      s = Series(); stored.clear(); inSeries = true;
      s.name = line + 7; while (!s.name.empty() && (s.name.back() == '\n' || s.name.back() == '\r')) s.name.pop_back();
    } else if (!inSeries) {
      if (line[0] != '\n' && line[0] != '\r') { std::printf("unexpected line outside a series: %s", line); return 1; }
    } else if (line[0] == 'x') {
      s.samples.push_back({false, {0, 0, 0, 0, 0, 0}});
    } else if (line[0] == 's') {
      Sample m{true, {0, 0, 0, 0, 0, 0}};
      if (std::sscanf(line, "s %f %f %f %f %f %f", &m.v[0], &m.v[1], &m.v[2], &m.v[3], &m.v[4], &m.v[5]) != 6) {
        std::printf("series %s: bad sample line: %s", s.name.c_str(), line); return 1;
      }
      s.samples.push_back(m);
    } else if (line[0] == 'c') {
      Checkpoint c{}; unsigned u = 0, rf = 0;
      if (std::sscanf(line, "c %d %f %f %f %u %u", &c.index, &c.bias[0], &c.bias[1], &c.bias[2], &u, &rf) != 6) {
        std::printf("series %s: bad checkpoint line: %s", s.name.c_str(), line); return 1;
      }
      c.updates = u; c.refused = rf; stored.push_back(c);
    } else if (std::strncmp(line, "end", 3) == 0) {
      const std::vector<Checkpoint> now = run(s);
      ++seriesSeen;
      if (now.size() != stored.size()) { std::printf("series %s: checkpoint count differs\n", s.name.c_str()); return 1; }
      for (size_t i = 0; i < now.size(); ++i) {
        bool same = now[i].updates == stored[i].updates && now[i].refused == stored[i].refused;
        for (int a = 0; a < 3; ++a) same = same && std::fabs(now[i].bias[a] - stored[i].bias[a]) < 1e-5f;
        if (!same) { std::printf("series %s: checkpoint %zu differs\n", s.name.c_str(), i); return 1; }
      }
      inSeries = false;
    } else {
      std::printf("series %s: unexpected line: %s", s.name.c_str(), line); return 1;
    }
  }
  std::fclose(f);
  if (seriesSeen != 13 || inSeries) { std::printf("expected 13 complete series, saw %d\n", seriesSeen); return 1; }
  std::printf("rest bias fixture matches\n");
  return 0;
}
}  // namespace

int main(int argc, char** argv) {
  if (argc >= 3 && std::strcmp(argv[1], "--emit") == 0) return emitFixture(argv[2]);
  if (argc >= 3 && std::strcmp(argv[1], "--check") == 0) return checkFixture(argv[2]);
  return runTests();
}
