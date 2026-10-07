#include "GestureTracker.h"
#include <cassert>
#include <cmath>
#include <cstdio>

using openski::motion::GestureTracker;
using openski::motion::Gesture;
void feed(GestureTracker& tracker,uint32_t& t,int samples,float x,float y,float z) {
  for(int i=0;i<samples;++i) { t+=10000; tracker.update(t,x,y,z); }
}
int main() {
  {
    GestureTracker tracker; uint32_t t=0;
    feed(tracker,t,100,0.03f,0,0);
    feed(tracker,t,100,0.03f,0,0.785398f);
    feed(tracker,t,100,0.03f,0,0);
    feed(tracker,t,100,0.03f,0,-0.785398f);
    feed(tracker,t,100,0.03f,0,0);
    Gesture output[16]; assert(tracker.recent(output,16)==2);
    assert(output[0].axis==2 && output[1].axis==2);
    assert(std::fabs(output[0].angleDegrees-45)<5);
    assert(std::fabs(output[1].angleDegrees+45)<5);
  }
  {
    GestureTracker tracker; uint32_t t=0;
    for(int i=0;i<1000;++i) feed(tracker,t,1,0.03f,0.20f*std::sin(i*0.4f),0.20f*std::sin(i*0.6f));
    assert(tracker.count()==0);
  }
  {
    GestureTracker tracker; uint32_t t=0;
    feed(tracker,t,100,0,0,0); feed(tracker,t,100,0,0.8f,0.8f);
    feed(tracker,t,100,0,0,0); assert(tracker.count()==0); assert(tracker.rejected()>0);
  }
  {
    GestureTracker tracker; uint32_t t=0;
    feed(tracker,t,100,0,0,0); feed(tracker,t,30,0,0.8f,0);
    t+=500000; tracker.update(t,0,0,0);
    feed(tracker,t,100,0,0,0); assert(tracker.count()==0); assert(tracker.gaps()==1);
  }
  {
    GestureTracker tracker; uint32_t t=UINT32_MAX-500000;
    feed(tracker,t,100,0,0,0); feed(tracker,t,100,-0.8f,0,0);
    feed(tracker,t,100,0,0,0); assert(tracker.count()==1); assert(tracker.gaps()==0);
  }
  {
    GestureTracker tracker; uint32_t t=0;
    feed(tracker,t,100,0,0,0);
    for(int i=0;i<20;++i) { feed(tracker,t,100,0,0,0.8f); feed(tracker,t,100,0,0,0); }
    Gesture output[16]; assert(tracker.count()==20); assert(tracker.recent(output,16)==16);
    assert(output[0].sequence==5 && output[15].sequence==20);
  }
  {
    GestureTracker tracker; uint32_t t=0;
    feed(tracker,t,100,0,0,0); feed(tracker,t,100,0,0,0.8f);
    feed(tracker,t,100,0,0,-0.8f); feed(tracker,t,100,0,0,0);
    assert(tracker.count()==2);
  }
  std::puts("7 gesture tracker scenarios passed");
}
