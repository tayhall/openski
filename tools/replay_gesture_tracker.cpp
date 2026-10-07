#include "GestureTracker.h"
#include <cstdio>

// Diagnostic replay only: interpolate sparse HTTP snapshots to 100 Hz.
// This cannot reconstruct omitted peaks or provide classifier accuracy.
int main() {
  openski::motion::GestureTracker tracker;
  unsigned timestamp, previous=0;
  float x,y,z,last[3]{};
  bool first=true;
  while(std::scanf("%u %f %f %f",&timestamp,&x,&y,&z)==4) {
    const uint32_t gap=timestamp-previous;
    if(!first && gap>0 && gap<=1000000U) {
      for(uint32_t delta=10000;delta<gap;delta+=10000) {
        const float fraction=static_cast<float>(delta)/gap;
        tracker.update(previous+delta,last[0]+(x-last[0])*fraction,
                       last[1]+(y-last[1])*fraction,last[2]+(z-last[2])*fraction);
      }
    }
    tracker.update(timestamp,x,y,z);
    previous=timestamp; last[0]=x; last[1]=y; last[2]=z; first=false;
  }
  openski::motion::Gesture events[16];
  const auto count=tracker.recent(events,16);
  std::printf("count=%u rejected=%u gaps=%u\n",tracker.count(),tracker.rejected(),tracker.gaps());
  for(unsigned i=0;i<count;++i) std::printf("sequence=%u axis=%c angle=%.2f duration_ms=%u\n",
    events[i].sequence,'x'+events[i].axis,events[i].angleDegrees,(events[i].endUs-events[i].startUs)/1000);
}
