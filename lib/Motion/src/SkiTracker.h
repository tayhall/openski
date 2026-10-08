#pragma once
#include <cmath>
#include <cstdint>

namespace openski::motion {
// A sensor axis (0=x, 1=y, 2=z) and the sign that makes it point the stated way on the leg.
struct AxisMap { uint8_t axis; int8_t sign; };
// Which sensor axis points up the leg (L), forward (F) and sideways (S).
struct Mounting { AxisMap up, forward, lateral; };

struct SkiEvent {
  uint32_t sequence = 0, startUs = 0, endUs = 0;
  float peakRollDegrees = 0;  // signed; positive = the leg leans toward +S
  float peakRateDps = 0;      // magnitude of the smoothed roll rate
  float pitchDegrees = 0;     // at the roll peak; positive = leaning forward
  bool positive = false;      // side of the half-turn (roll > 0)
  bool outsideEnvelope = false, pitchOutside = false, skippedSamples = false;
};

struct SkiHeartbeat {
  float vibrationMps2 = 0;  // RMS of high-passed |a| over the interval
  float gyroDps = 0;        // RMS of angular speed over the interval
};

// Half-turn recogniser for a boot-cuff sensor. Inputs are corrected sensor-frame values:
// accel in m/s^2 (reads +g along whichever axis points up), gyro in rad/s.
// Roll is lean about the forward axis, pitch is lean about the lateral axis, both measured
// from a pose captured by zero(). Experimental: cuff lean in a sensor-derived frame, not ski edge angle.
class SkiTracker {
 public:
  static constexpr float kGravity = 9.80665f;
  static constexpr float kDegrees = 57.2957795f;

  explicit SkiTracker(Mounting mounting) : map_(mounting) {}

  void update(uint32_t t, float ax, float ay, float az, float gx, float gy, float gz) {
    const float mag = std::sqrt(ax*ax + ay*ay + az*az);
    const float rate = std::sqrt(gx*gx + gy*gy + gz*gz);
    meter(mag, rate, t);
    // Impacts and free fall carry no usable gravity direction, and a shock can spike the gyro.
    // Hold the state through them rather than integrate garbage.
    if (mag > kImpactG*kGravity || mag < kFreeFallG*kGravity || rate > kMaxRateRadps) {
      skipped_ = true;
      return;
    }
    const float a[3] = {ax/mag, ay/mag, az/mag};
    if (!hasTime_) { seed(t, a); return; }
    const uint32_t delta = t - lastTime_;
    if (delta == 0) return;
    lastTime_ = t;
    if (delta > kGapUs) {  // sample loss: the gyro bridge is no longer trustworthy
      ++gaps_; gapSeen_ = true;
      abandon();
      seed(t, a);
      return;
    }
    const float dt = delta/1000000.0f;
    const float w[3] = {gx, gy, gz};
    float next[3] = {
      up_[0] - (w[1]*up_[2] - w[2]*up_[1])*dt,
      up_[1] - (w[2]*up_[0] - w[0]*up_[2])*dt,
      up_[2] - (w[0]*up_[1] - w[1]*up_[0])*dt};
    // The accelerometer pull fades out as |a| leaves 1 g, so hard turns follow the gyro alone.
    const float error = std::fabs(mag - kGravity)/kGravity;
    const float weight = error < kPullLimit ? 1.0f - error/kPullLimit : 0.0f;
    if (weight > 0) {
      const float alpha = dt/(0.5f + dt)*weight;
      for (int i = 0; i < 3; ++i) next[i] += alpha*(a[i] - next[i]);
    }
    normalise(next);
    for (int i = 0; i < 3; ++i) up_[i] = next[i];
    if (!neutralReady_) { trackNeutral(t, rate, error); return; }
    measure(dt);
    runSegment(t);
  }

  // Recapture the neutral pose from the next stationary second.
  void zero() {
    neutralReady_ = false; stillSince_ = false; zeroing_ = true;
    abandon(); roll_ = pitch_ = rollRate_ = 0; hasRoll_ = false;
  }

  bool zeroed() const { return neutralReady_; }
  bool zeroing() const { return zeroing_; }
  float rollDegrees() const { return roll_*kDegrees; }
  float pitchDegrees() const { return pitch_*kDegrees; }
  uint32_t count() const { return count_; }
  uint32_t rejected() const { return rejected_; }
  uint32_t gaps() const { return gaps_; }
  // True if a sample gap happened since the last call (for the heartbeat flag).
  bool takeGapSeen() { const bool seen = gapSeen_; gapSeen_ = false; return seen; }
  uint8_t recent(SkiEvent* output, uint8_t capacity) const {
    const uint8_t available = count_ < 16 ? static_cast<uint8_t>(count_) : 16;
    const uint8_t copied = available < capacity ? available : capacity;
    for (uint8_t i = 0; i < copied; ++i) output[i] = history_[(count_ - copied + i) % 16];
    return copied;
  }
  // RMS vibration and gyro activity since the last call.
  SkiHeartbeat takeHeartbeat() {
    SkiHeartbeat beat;
    if (meterCount_ > 0) {
      beat.vibrationMps2 = std::sqrt(vibrationSum_/meterCount_);
      beat.gyroDps = std::sqrt(gyroSum_/meterCount_)*kDegrees;
    }
    vibrationSum_ = gyroSum_ = 0; meterCount_ = 0;
    return beat;
  }

 private:
  static constexpr float kImpactG = 2.5f;       // |a| above this: skip the sample
  static constexpr float kFreeFallG = 0.3f;
  static constexpr float kMaxRateRadps = 12.0f; // about 690 deg/s, beyond any plausible turn
  static constexpr float kPullLimit = 0.15f;    // fractional |a| error where the accel pull reaches zero
  static constexpr uint32_t kGapUs = 100000U;
  static constexpr uint32_t kStillUs = 1000000U;
  static constexpr uint32_t kMinimumUs = 150000U;
  static constexpr float kDeadbandRadians = 1.5f/57.2957795f;
  static constexpr float kBandRadians = 8.0f/57.2957795f;
  static constexpr float kEnvelopeRollDegrees = 60.0f, kEnvelopeRateDps = 300.0f;
  static constexpr uint32_t kEnvelopeMinUs = 300000U, kEnvelopeMaxUs = 4000000U;
  static constexpr float kEnvelopePitchMin = -15.0f, kEnvelopePitchMax = 45.0f;

  static void normalise(float* v) {
    const float length = std::sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
    if (length > 0) { v[0] /= length; v[1] /= length; v[2] /= length; }
  }
  // Leg-frame components (L, F, S) of a sensor-frame vector.
  void toLeg(const float* v, float* leg) const {
    leg[0] = map_.up.sign*v[map_.up.axis];
    leg[1] = map_.forward.sign*v[map_.forward.axis];
    leg[2] = map_.lateral.sign*v[map_.lateral.axis];
  }
  void seed(uint32_t t, const float* a) {
    for (int i = 0; i < 3; ++i) up_[i] = a[i];
    lastTime_ = t; hasTime_ = true; stillSince_ = false; hasRoll_ = false;
  }
  void trackNeutral(uint32_t t, float rate, float error) {
    if (!zeroing_) return;
    if (rate < 0.12f && error < 0.05f) {
      if (!stillSince_) { stillSince_ = true; stillSinceUs_ = t; }
      if (t - stillSinceUs_ >= kStillUs) {
        float leg[3];
        toLeg(up_, leg);
        if (leg[0] < 0.5f) { stillSince_ = false; return; }  // not roughly upright on the leg
        // Minimal rotation taking the neutral pose onto the leg's up axis (Rodrigues).
        kx_[0] = 0; kx_[1] = leg[2]; kx_[2] = -leg[1];
        cosine_ = leg[0];
        neutralReady_ = true; zeroing_ = false;
      }
    } else stillSince_ = false;
  }
  void rotateToNeutral(const float* u, float* out) const {
    const float* k = kx_;
    const float kxu[3] = {k[1]*u[2]-k[2]*u[1], k[2]*u[0]-k[0]*u[2], k[0]*u[1]-k[1]*u[0]};
    const float kkxu[3] = {k[1]*kxu[2]-k[2]*kxu[1], k[2]*kxu[0]-k[0]*kxu[2], k[0]*kxu[1]-k[1]*kxu[0]};
    for (int i = 0; i < 3; ++i) out[i] = u[i] + kxu[i] + kkxu[i]/(1.0f + cosine_);
  }
  void measure(float dt) {
    float leg[3], u[3];
    toLeg(up_, leg);
    rotateToNeutral(leg, u);
    const float previous = roll_;
    // Leaning toward +S makes world-up gain a -S component in the leg frame.
    roll_ = -std::asin(u[2] > 1 ? 1.0f : (u[2] < -1 ? -1.0f : u[2]));
    pitch_ = std::atan2(-u[1], u[0]);
    if (hasRoll_) {
      const float alpha = dt/(0.025f + dt);
      rollRate_ += alpha*((roll_ - previous)/dt - rollRate_);
    } else { rollRate_ = 0; hasRoll_ = true; }
  }
  void meter(float mag, float rate, uint32_t) {
    // High-pass |a| against a slow average so gravity and slow lean drop out.
    meanMag_ += 0.02f*(mag - meanMag_);
    const float hp = mag - meanMag_;
    vibrationSum_ += hp*hp; gyroSum_ += rate*rate; ++meterCount_;
  }
  void beginSegment(uint32_t t, int sign) {
    side_ = sign; startUs_ = t; peak_ = 0; peakRate_ = 0; pitchAtPeak_ = 0; skippedInSegment_ = false;
  }
  void abandon() { side_ = 0; peak_ = 0; peakRate_ = 0; skippedInSegment_ = false; skipped_ = false; }
  void runSegment(uint32_t t) {
    if (skipped_) { skippedInSegment_ = true; skipped_ = false; }
    const int sign = roll_ >= 0 ? 1 : -1;
    if (sign != lastSign_) { zeroUs_ = t; lastSign_ = sign; }
    if (side_ == 0) {
      if (std::fabs(roll_) > kDeadbandRadians) beginSegment(t, sign);
    } else if (roll_*side_ < -kDeadbandRadians) {
      finish(zeroUs_);
      beginSegment(zeroUs_, sign);
    }
    if (side_ != 0) {
      if (std::fabs(roll_) > peak_) { peak_ = std::fabs(roll_); pitchAtPeak_ = pitch_; }
      const float rate = std::fabs(rollRate_);
      if (rate > peakRate_) peakRate_ = rate;
    }
  }
  void finish(uint32_t endUs) {
    if (peak_ < kBandRadians) return;  // wobble, not a half-turn
    const uint32_t duration = endUs - startUs_;
    if (duration < kMinimumUs) { ++rejected_; return; }
    SkiEvent e;
    e.sequence = ++count_;
    e.startUs = startUs_; e.endUs = endUs;
    e.positive = side_ > 0;
    e.peakRollDegrees = side_*peak_*kDegrees;
    e.peakRateDps = peakRate_*kDegrees;
    e.pitchDegrees = pitchAtPeak_*kDegrees;
    e.pitchOutside = e.pitchDegrees < kEnvelopePitchMin || e.pitchDegrees > kEnvelopePitchMax;
    e.outsideEnvelope = e.pitchOutside || std::fabs(e.peakRollDegrees) > kEnvelopeRollDegrees ||
                        e.peakRateDps > kEnvelopeRateDps || duration < kEnvelopeMinUs ||
                        duration > kEnvelopeMaxUs;
    e.skippedSamples = skippedInSegment_;
    history_[(count_ - 1) % 16] = e;
  }

  Mounting map_;
  bool hasTime_ = false, neutralReady_ = false, zeroing_ = false, stillSince_ = false;
  bool hasRoll_ = false, skipped_ = false, skippedInSegment_ = false, gapSeen_ = false;
  uint32_t lastTime_ = 0, stillSinceUs_ = 0, startUs_ = 0, zeroUs_ = 0;
  uint32_t count_ = 0, rejected_ = 0, gaps_ = 0;
  int side_ = 0, lastSign_ = 1;
  float up_[3]{0, 0, 1}, kx_[3]{}, cosine_ = 1;
  float roll_ = 0, pitch_ = 0, rollRate_ = 0, peak_ = 0, peakRate_ = 0, pitchAtPeak_ = 0;
  float meanMag_ = kGravity, vibrationSum_ = 0, gyroSum_ = 0;
  uint32_t meterCount_ = 0;
  SkiEvent history_[16]{};
};
}  // namespace openski::motion
