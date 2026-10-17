"""Re-check of the turn pattern on IMU 5 (probably under the boot), trying roll and pitch as the turn angle on IMUs 4 to 6.

Needs numpy, scipy and vqf (pip install numpy scipy vqf). Public data: Beuken et al., Sensors 2024 (Kaggle
leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski, Apache 2.0); not stored in this repo. The outdoor file
is "Outdoor Testing/Outdoor Testing/1_23/run10.txt" in the dataset archive. Results: docs/bench-results.md
(2026-10-17). Experimental: a ski mount, not a boot cuff.
Usage: python tools/analysis/ski_turn_pattern_imu5.py <run10.txt>
"""
import sys
import numpy as np
from scipy.signal import butter, sosfiltfilt
from vqf import VQF
G=9.80665
d=np.loadtxt(sys.argv[1],delimiter=",",usecols=range(61)); d=d[np.r_[True,np.diff(d[:,0])<20000]]
t=(d[:,0]-d[0,0])/1e6; fs=1/np.median(np.diff(t)); body=d[:,1:61].reshape(len(d),10,6)
def lp(x,fc): return sosfiltfilt(butter(3,fc/(fs/2),output="sos"),x,axis=0)
def rot(q,v):
    w,x,y,z=q.T; vx,vy,vz=v.T
    tx=2*(y*vz-z*vy); ty=2*(z*vx-x*vz); tz=2*(x*vy-y*vx)
    return np.stack([vx+w*tx+y*tz-z*ty, vy+w*ty+z*tx-x*tz, vz+w*tz+x*ty-y*tx],1)
def euler(q):
    w,x,y,z=q.T
    roll=np.degrees(np.arctan2(2*(w*x+y*z),1-2*(x*x+y*y)))
    pitch=np.degrees(np.arcsin(np.clip(2*(w*y-z*x),-1,1)))
    yaw=np.degrees(np.arctan2(2*(w*z+x*y),1-2*(y*y+z*z)))
    return roll,pitch,yaw
for i in (5,4,6):
    gyr=np.ascontiguousarray(body[:,i,:3]); acc=np.ascontiguousarray(body[:,i,3:])
    q=VQF(1/fs).updateBatch(gyr,acc)["quat6D"]
    r,p,y=euler(q); az=rot(q,acc)[:,2]/G
    print(f"imu {i}: roll p5/50/95 = {np.percentile(r,[5,50,95]).round(0)}, pitch = {np.percentile(p,[5,50,95]).round(0)}; gyro std deg/s per axis = {np.degrees(lp(gyr,6).std(0)).round(0)}")
    for name,ang in (("roll",r),("pitch",p)):
        a=lp(ang-np.median(ang),1.5); f=lp(az,6)
        s=np.sign(a); cross=np.where(np.diff(s)!=0)[0]
        apex,trans,minpos=[],[],[]
        for u,v in zip(cross[:-1],cross[1:]):
            dur=(v-u)/fs
            if dur<0.6 or dur>6: continue
            k=u+np.argmax(np.abs(a[u:v]))
            if abs(a[k])<10: continue
            apex.append(f[k]); trans.append(0.5*(f[u]+f[v])); minpos.append(np.argmin(f[u:v])/(v-u))
        if apex:
            apex,trans,minpos=map(np.array,(apex,trans,minpos))
            print(f"   using {name}: {len(apex)} turns, apex load {apex.mean():.2f}±{apex.std():.2f} g, transition {trans.mean():.2f}±{trans.std():.2f} g, apex>transition {np.mean(apex>trans)*100:.0f}%, min position median {np.median(minpos):.2f}, in middle 30-70%: {np.mean((minpos>.3)&(minpos<.7))*100:.0f}%")
        else: print(f"   using {name}: no turns >=10 deg")
