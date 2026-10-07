#pragma once
#include <cmath>
#include <cstdint>

namespace openski::motion {
struct Excursion {
  uint32_t sequence = 0, startUs = 0, peakUs = 0, endUs = 0;
  uint8_t axis = 0;       // sensor axis (0=x, 1=y, 2=z) the tilt rotated about
  float peakDegrees = 0;  // total tilt away from neutral at the peak
  float aboutDegrees = 0; // signed rotation about `axis` at the peak (right-hand rule)
  float axisFraction = 0; // share of the peak rotation about `axis`
};

// Absolute tilt from gravity, fused with the gyro so fast movement is not
// corrupted by linear acceleration. Inputs are corrected sensor-frame values:
// accel in m/s^2 (reads +g along whichever axis points up), gyro in rad/s.
//
// Tilt is measured against a neutral pose captured automatically after the
// sensor has been still at start-up (or recaptured with zero()). Rotation about
// the neutral up direction cannot be seen by gravity and is never reported.
class TiltTracker {
 public:
  static constexpr float kGravity = 9.80665f;

  void update(uint32_t timestamp, float ax, float ay, float az, float gx, float gy, float gz) {
    const float magnitude = std::sqrt(ax*ax + ay*ay + az*az);
    if (magnitude < 0.5f*kGravity || magnitude > 1.5f*kGravity) return;  // free fall / impact
    const float a[3] = {ax/magnitude, ay/magnitude, az/magnitude};
    if (!hasTime_) { seed(timestamp, a); return; }
    const uint32_t delta = timestamp - lastTime_;
    lastTime_ = timestamp;
    if (delta == 0) return;
    if (delta > 100000U) {  // sample loss: the gyro bridge is no longer trustworthy
      ++gaps_;
      abandonExcursion();
      seed(timestamp, a);
      return;
    }
    const float dt = delta/1000000.0f;
    // The world's up vector moves through the body frame at -omega x up.
    const float w[3] = {gx, gy, gz};
    float next[3] = {
      up_[0] - (w[1]*up_[2] - w[2]*up_[1])*dt,
      up_[1] - (w[2]*up_[0] - w[0]*up_[2])*dt,
      up_[2] - (w[0]*up_[1] - w[1]*up_[0])*dt};
    // Pull toward the accelerometer only when it is reading close to 1 g.
    const float error = std::fabs(magnitude - kGravity)/kGravity;
    if (error < 0.08f) {
      const float alpha = dt/(0.5f + dt);
      for (int i=0;i<3;++i) next[i] += alpha*(a[i]-next[i]);
    }
    normalise(next);
    for (int i=0;i<3;++i) up_[i] = next[i];
    const float speed = std::sqrt(gx*gx + gy*gy + gz*gz);
    trackNeutral(timestamp, speed, error);
    if (!neutralReady_) return;
    measure();
    runExcursion(timestamp);
  }

  // Recapture the neutral pose from the next stationary period.
  void zero() { neutralReady_ = false; stillSinceUs_ = 0; stillSince_ = false; abandonExcursion(); tilt_ = 0; rotation_[0]=rotation_[1]=rotation_[2]=0; }

  bool neutralReady() const { return neutralReady_; }
  bool active() const { return active_; }
  float tiltDegrees() const { return tilt_*57.2957795f; }
  // Signed rotation vector (degrees) about each sensor axis, right-hand rule.
  float aboutDegrees(int axis) const { return rotation_[axis]*57.2957795f; }
  // Axis currently nearest the neutral up direction (rotation about it is
  // invisible to gravity), or -1 when the neutral pose is not aligned with one.
  int verticalAxis() const {
    if (!neutralReady_) return -1;
    int best = 0;
    for (int i=1;i<3;++i) if (std::fabs(neutral_[i])>std::fabs(neutral_[best])) best=i;
    return std::fabs(neutral_[best])>0.9f ? best : -1;
  }
  int verticalSign() const { const int axis = verticalAxis(); return axis<0 ? 0 : (neutral_[axis]>0 ? 1 : -1); }
  uint32_t count() const { return count_; }
  uint32_t rejected() const { return rejected_; }
  uint32_t gaps() const { return gaps_; }
  uint8_t recent(Excursion* output, uint8_t capacity) const {
    const uint8_t count = count_<16 ? static_cast<uint8_t>(count_) : 16;
    const uint8_t copied = count<capacity ? count : capacity;
    for (uint8_t i=0;i<copied;++i) output[i] = history_[(count_-copied+i)%16];
    return copied;
  }

 private:
  static constexpr float kEnterRadians = 0.34906585f;  // 20 degrees out
  static constexpr float kExitRadians = 0.17453293f;   // back inside 10 degrees
  static constexpr uint32_t kMinimumUs = 150000U;
  static constexpr uint32_t kMaximumUs = 15000000U;
  static constexpr uint32_t kStillUs = 1000000U;

  static void normalise(float* v) {
    const float length = std::sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
    if (length > 0) { v[0]/=length; v[1]/=length; v[2]/=length; }
  }
  void seed(uint32_t timestamp, const float* a) {
    for (int i=0;i<3;++i) up_[i] = a[i];
    lastTime_ = timestamp; hasTime_ = true;
    stillSince_ = false;
  }
  void trackNeutral(uint32_t timestamp, float speed, float error) {
    if (neutralReady_) return;
    if (speed < 0.12f && error < 0.05f) {
      if (!stillSince_) { stillSince_ = true; stillSinceUs_ = timestamp; }
      if (timestamp - stillSinceUs_ >= kStillUs) {
        for (int i=0;i<3;++i) neutral_[i] = up_[i];
        neutralReady_ = true;
      }
    } else stillSince_ = false;
  }
  void measure() {
    // Rotation of the sensor away from neutral. Gravity moves the opposite way
    // in the sensor frame, hence up x neutral: positive matches the gyro sign.
    const float cx = up_[1]*neutral_[2] - up_[2]*neutral_[1];
    const float cy = up_[2]*neutral_[0] - up_[0]*neutral_[2];
    const float cz = up_[0]*neutral_[1] - up_[1]*neutral_[0];
    const float sine = std::sqrt(cx*cx + cy*cy + cz*cz);
    const float cosine = neutral_[0]*up_[0] + neutral_[1]*up_[1] + neutral_[2]*up_[2];
    tilt_ = std::atan2(sine, cosine);
    if (sine > 1e-6f) {
      rotation_[0] = tilt_*cx/sine; rotation_[1] = tilt_*cy/sine; rotation_[2] = tilt_*cz/sine;
    } else rotation_[0] = rotation_[1] = rotation_[2] = 0;
  }
  void runExcursion(uint32_t timestamp) {
    if (locked_) { if (tilt_ < kExitRadians) locked_ = false; return; }
    if (!active_) {
      if (tilt_ < kEnterRadians) return;
      active_ = true; startUs_ = timestamp; peak_ = 0;
    }
    if (tilt_ > peak_) {
      peak_ = tilt_; peakUs_ = timestamp;
      for (int i=0;i<3;++i) peakRotation_[i] = rotation_[i];
    }
    if (timestamp - startUs_ > kMaximumUs) {  // held away from neutral, not a movement
      ++rejected_; abandonExcursion(); locked_ = true; return;
    }
    if (tilt_ >= kExitRadians) return;
    if (timestamp - startUs_ >= kMinimumUs) {
      int axis = 0;
      for (int i=1;i<3;++i) if (std::fabs(peakRotation_[i])>std::fabs(peakRotation_[axis])) axis=i;
      const float norm = std::sqrt(peakRotation_[0]*peakRotation_[0] + peakRotation_[1]*peakRotation_[1] +
                                   peakRotation_[2]*peakRotation_[2]);
      ++count_;
      history_[(count_-1)%16] = {count_, startUs_, peakUs_, timestamp, static_cast<uint8_t>(axis),
                                 peak_*57.2957795f, peakRotation_[axis]*57.2957795f,
                                 norm>0 ? std::fabs(peakRotation_[axis])/norm : 0};
    } else ++rejected_;
    abandonExcursion();
  }
  void abandonExcursion() { active_ = false; peak_ = 0; }

  bool hasTime_ = false, neutralReady_ = false, stillSince_ = false, active_ = false, locked_ = false;
  uint32_t lastTime_ = 0, stillSinceUs_ = 0, startUs_ = 0, peakUs_ = 0;
  uint32_t count_ = 0, rejected_ = 0, gaps_ = 0;
  float up_[3]{0,0,1}, neutral_[3]{0,0,1}, rotation_[3]{}, peakRotation_[3]{};
  float tilt_ = 0, peak_ = 0;
  Excursion history_[16]{};
};
}
