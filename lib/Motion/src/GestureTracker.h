#pragma once
#include <cmath>
#include <cstdint>

namespace openski::motion {
struct Gesture {
  uint32_t sequence = 0, startUs = 0, endUs = 0;
  uint8_t axis = 0;
  float angleDegrees = 0, peakRadps = 0, axisFraction = 0;
};

// Sensor-frame rotation excursions, independent of Wi-Fi and Android.
// Net angle and axis consistency reject small shake and mixed pickup motions.
class GestureTracker {
 public:
  void update(uint32_t timestamp, float x, float y, float z) {
    if (!hasTime_) { lastTime_ = timestamp; hasTime_ = true; return; }
    const uint32_t delta = timestamp-lastTime_;
    lastTime_ = timestamp;
    if (delta == 0) return;
    if (delta > 100000U) {
      resetMovement();
      for(int i=0;i<3;++i) filtered_[i]=0;
      ++gaps_; return;
    }
    const float dt = delta/1000000.0f;
    const float raw[3] = {x,y,z};
    const float alpha = dt/(0.025f+dt);
    for (int i=0;i<3;++i) filtered_[i] += alpha*(raw[i]-filtered_[i]);
    int axis = 0;
    for (int i=1;i<3;++i) if (std::fabs(filtered_[i])>std::fabs(filtered_[axis])) axis=i;
    const float largest = std::fabs(filtered_[axis]);
    if (!active_) {
      if (largest < 0.28f) return;
      active_ = true; startUs_ = timestamp-delta; lastMovingUs_ = timestamp;
    }
    if (timestamp-startUs_ > 8000000U) { resetMovement(); ++rejected_; return; }
    // Close an established excursion before a sustained direction reversal.
    int established = 0;
    for (int i=1;i<3;++i) if (std::fabs(angle_[i])>std::fabs(angle_[established])) established=i;
    const bool reversing = std::fabs(angle_[established]) >= kMinimumAngle &&
      filtered_[established]*angle_[established] < 0 &&
      std::fabs(filtered_[established]) > 0.28f;
    if (reversing) {
      if (!reversal_) { reversal_=true; reversalUs_=timestamp; }
      if (timestamp-reversalUs_ >= 60000U) {
        complete(timestamp);
        active_=true; startUs_=timestamp; lastMovingUs_=timestamp;
      }
    } else reversal_=false;
    for (int i=0;i<3;++i) {
      angle_[i] += filtered_[i]*dt;
      travel_[i] += std::fabs(filtered_[i])*dt;
    }
    if (largest>peak_) peak_=largest;
    if (largest>0.16f) lastMovingUs_=timestamp;
    if (timestamp-lastMovingUs_>=250000U) complete(lastMovingUs_);
  }

  uint32_t count() const { return count_; }
  uint32_t rejected() const { return rejected_; }
  uint32_t gaps() const { return gaps_; }
  bool active() const { return active_; }
  uint8_t recent(Gesture* output, uint8_t capacity) const {
    const uint8_t count = count_<16 ? static_cast<uint8_t>(count_) : 16;
    const uint8_t copied = count<capacity ? count : capacity;
    for(uint8_t i=0;i<copied;++i) output[i]=history_[(count_-copied+i)%16];
    return copied;
  }
 private:
  static constexpr float kMinimumAngle = 0.13962634f; // 8 degrees
  void complete(uint32_t end) {
    int axis=0;
    for(int i=1;i<3;++i) if(std::fabs(angle_[i])>std::fabs(angle_[axis])) axis=i;
    const float total=travel_[0]+travel_[1]+travel_[2];
    const float fraction=total>0 ? travel_[axis]/total : 0;
    const float consistency=travel_[axis]>0 ? std::fabs(angle_[axis])/travel_[axis] : 0;
    if(std::fabs(angle_[axis])>=kMinimumAngle && fraction>=0.65f && consistency>=0.70f && end-startUs_>=150000U) {
      ++count_;
      history_[(count_-1)%16]={count_,startUs_,end,static_cast<uint8_t>(axis),
                              angle_[axis]*57.2957795f,peak_,fraction};
    } else ++rejected_;
    resetMovement();
  }
  void resetMovement() {
    active_=false; reversal_=false; peak_=0;
    for(int i=0;i<3;++i) { angle_[i]=0; travel_[i]=0; }
  }
  bool hasTime_=false, active_=false, reversal_=false;
  uint32_t lastTime_=0,startUs_=0,lastMovingUs_=0,reversalUs_=0;
  uint32_t count_=0,rejected_=0,gaps_=0;
  float filtered_[3]{},angle_[3]{},travel_[3]{},peak_=0;
  Gesture history_[16]{};
};
}
