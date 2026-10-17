"""Row 5 feasibility: tilt-compensated vertical load from a ski-mounted IMU, per-IMU quality, apex versus transition load.

Needs numpy, scipy and vqf (pip install numpy scipy vqf). Public data: Beuken et al., Sensors 2024 (Kaggle
leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski, Apache 2.0); not stored in this repo. The outdoor file
is "Outdoor Testing/Outdoor Testing/1_23/run10.txt" in the dataset archive. Results: docs/bench-results.md
(2026-10-17). Experimental: a ski mount, not a boot cuff.
Usage: python tools/analysis/ski_vertical_load.py <run10.txt>
"""
import sys
import numpy as np
from scipy.signal import butter, filtfilt, sosfiltfilt
from vqf import VQF

G = 9.80665
d = np.loadtxt(sys.argv[1], delimiter=",", usecols=range(61))
ts = d[:, 0]
keep = np.r_[True, np.diff(ts) < 20000]
d = d[keep]
t = (d[:, 0] - d[0, 0]) / 1e6
fs = 1.0 / np.median(np.diff(t))
body = d[:, 1:61].reshape(len(d), 10, 6)
print(f"{len(d)} samples, {t[-1]:.0f} s, {fs:.0f} Hz")

def lp(x, fc, order=3):
    sos = butter(order, fc / (fs / 2), output="sos")
    return sosfiltfilt(sos, x, axis=0)

def roll_deg(q):  # same convention as the research repo's analyse_ski_run.py
    w, x, y, z = q.T
    return np.degrees(np.arctan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)))

def rotate(q, v):
    w, x, y, z = q.T
    vx, vy, vz = v.T
    tx = 2 * (y * vz - z * vy); ty = 2 * (z * vx - x * vz); tz = 2 * (x * vy - y * vx)
    return np.stack([vx + w * tx + y * tz - z * ty, vy + w * ty + z * tx - x * tz, vz + w * tz + x * ty - y * tx], axis=1)

print("\nPer IMU raw signal quality")
print("imu  within15%of1g  clipped(any axis>=15.5g)  p99|a| g")
for i in range(10):
    a = body[:, i, 3:6]
    mag = np.linalg.norm(a, axis=1) / G
    print(f"{i:>3}  {np.mean(np.abs(mag - 1) < 0.15) * 100:10.0f}%  {np.mean(np.any(np.abs(a) >= 15.5 * G, axis=1)) * 100:18.0f}%      {np.percentile(mag, 99):6.1f}")

results = {}
for i in (0, 4, 5, 9):
    gyr = np.ascontiguousarray(body[:, i, 0:3]); acc = np.ascontiguousarray(body[:, i, 3:6])
    q = VQF(1.0 / fs).updateBatch(gyr, acc)["quat6D"]
    roll = roll_deg(q)
    world = rotate(q, acc)  # specific force in the world frame, z up
    az = world[:, 2] / G     # vertical load in g (1 g at rest)
    results[i] = (q, roll, az, acc)

print("\nVertical load (world-frame az from VQF), zero-phase 3rd-order Butterworth")
print("imu  cutoff  p1    p5    p50   p95   p99   <0.25g   >2g")
for i, (q, roll, az, acc) in results.items():
    for fc in (3, 6, 12):
        f = lp(az, fc)
        p = np.percentile(f, [1, 5, 50, 95, 99])
        print(f"{i:>3}  {fc:>4}Hz {p[0]:5.2f} {p[1]:5.2f} {p[2]:5.2f} {p[3]:5.2f} {p[4]:5.2f}  {np.mean(f < 0.25) * 100:5.1f}%  {np.mean(f > 2) * 100:5.1f}%")

print("\nRow 5 roll-only projection vs full quaternion vertical (6 Hz)")
for i, (q, roll, az, acc) in results.items():
    rr = np.radians(lp(roll, 6))
    best = None
    for ax_other, sgn in ((4, 1), (4, -1), (3, 1), (3, -1)):
        pr = (lp(acc[:, 2], 6) * np.cos(rr) + sgn * lp(acc[:, ax_other - 3], 6) * np.sin(rr)) / G
        c = np.corrcoef(pr, lp(az, 6))[0, 1]; e = np.sqrt(np.mean((pr - lp(az, 6)) ** 2))
        if best is None or e < best[0]: best = (e, c, ax_other - 3, sgn)
    print(f"imu {i}: best projection axis={'xyz'[best[2]]} sign={best[3]:+d}  corr={best[1]:.2f}  rms diff={best[0]:.2f} g")

print("\nTurn structure: load at apex vs at transition (IMU 0 and 5, 6 Hz)")
for i in (0, 5):
    q, roll, az, acc = results[i]
    r = lp(roll, 1.5)
    f = lp(az, 6)
    sign = np.sign(r); cross = np.where(np.diff(sign) != 0)[0]
    apex, trans, minpos = [], [], []
    for a, b in zip(cross[:-1], cross[1:]):
        if (b - a) / fs < 0.6 or (b - a) / fs > 6: continue
        seg = slice(a, b)
        k = a + np.argmax(np.abs(r[seg]))
        if abs(r[k]) < 10: continue
        apex.append(f[k]); trans.append(0.5 * (f[a] + f[b]))
        minpos.append((a + np.argmin(f[seg]) - a) / (b - a))
    apex, trans, minpos = map(np.array, (apex, trans, minpos))
    print(f"imu {i}: {len(apex)} turns; load at apex {apex.mean():.2f} g (sd {apex.std():.2f}), at transitions {trans.mean():.2f} g (sd {trans.std():.2f}); apex>transition in {np.mean(apex > trans) * 100:.0f}% of turns")
    print(f"        position of the load minimum within the turn (0=start,1=end): median {np.median(minpos):.2f}, share in the middle 30-70%: {np.mean((minpos > 0.3) & (minpos < 0.7)) * 100:.0f}%")

print("\nAgreement between IMUs (6 Hz vertical load correlation)")
ks = list(results)
for a in ks:
    print(" ", a, [f"{np.corrcoef(lp(results[a][2], 6), lp(results[b][2], 6))[0, 1]:.2f}" for b in ks])
