"""Prototype and validation of the offline gesture clock aligner (docs/clock-sync-design.md, "Gesture sync").

Needs numpy only. Public data: Beuken et al., Sensors 2024 (Kaggle leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski,
Apache 2.0); not stored in this repo. Outdoor file "Outdoor Testing/Outdoor Testing/1_23/run10.txt": 61 comma-separated
columns, column 0 a microsecond timestamp, then 10 IMUs x (gyro xyz, accel xyz). All IMUs share one clock, so one IMU's
stream is shifted, stretched and jittered artificially and the aligner must recover the known offset and skew.
The real use is a stomp or tap at the start and end of a session seen by two boots; ski turns stand in for it here.
The Kotlin ClockAlign.kt implements the same algorithm; keep them in step.
Usage: python tools/analysis/clock_align_validate.py <run10.txt> [--synthetic]
"""
import sys
import numpy as np

# ---------------------------------------------------------------- aligner (mirrored in ClockAlign.kt)
GRID_S = 0.002        # correlation grid
PATCH_HALF_S = 0.4    # patch half-width around the transient
SEARCH_S = 0.15       # lag search either side of the coarse peak difference
MIN_NCC = 0.7
DOUBLE_RATIO = 0.6    # a second peak this strong, away from the main one, makes the window ambiguous
DOUBLE_GAP_S = 0.12
MIN_SNR = 8.0         # peak over 1.4826 * MAD of the window
MAX_DRIFT_PPM = 2000.0
STOMPS = []


def STOMP_ENV(u):
    """Stomp envelope: 4 ms rise (the sensor's low-pass filter) then a 30 ms decay; zero before the onset."""
    uu = np.clip(u, 0, None)
    return (1 - np.exp(-uu / 0.004)) * np.exp(-uu / 0.03)


STOMP_AMP = 110.0     # m/s2 on the reference IMU scale, about 11 g


def interp(t, v, tq):
    return np.interp(tq, t, v, left=np.nan, right=np.nan)


def find_transient(t, m):
    """Return (peak_time, status). status in ok, flat, double."""
    if len(t) < 20:
        return None, "flat"
    base = np.median(m)
    f = np.abs(m - base)
    mad = np.median(f)
    i = int(np.argmax(f))
    noise = max(1.4826 * mad, 1e-9)
    if f[i] < MIN_SNR * noise:
        return None, "flat"
    # sub-sample peak by parabola on the three samples around it
    tp = t[i]
    if 0 < i < len(t) - 1:
        a, b, c = f[i - 1], f[i], f[i + 1]
        den = a - 2 * b + c
        if den != 0:
            tp = t[i] + 0.5 * (a - c) / den * (t[i + 1] - t[i - 1]) / 2
    far = np.abs(t - t[i]) > DOUBLE_GAP_S
    if far.any() and f[far].max() >= DOUBLE_RATIO * f[i]:
        # only a rival if it is a separate local maximum
        j = np.where(far)[0][np.argmax(f[far])]
        lo, hi = max(j - 2, 0), min(j + 3, len(f))
        if f[j] >= f[lo:hi].max():
            return tp, "double"
    return tp, "ok"


def refine(tA, mA, tB, mB, pa, pb):
    """NCC refinement. Returns (offset_B_minus_A, ncc) with offset valid at A time pa, or None."""
    h = PATCH_HALF_S
    ga = np.arange(-h, h + GRID_S / 2, GRID_S)
    a = interp(tA, mA - np.median(mA), pa + ga)
    if np.isnan(a).any():
        return None
    a = a - a.mean()
    na = np.sqrt((a * a).sum())
    if na < 1e-12:
        return None
    lags = np.arange(-SEARCH_S, SEARCH_S + GRID_S / 2, GRID_S)
    mBc = mB - np.median(mB)
    ncc = np.full(len(lags), -1.0)
    for k, lag in enumerate(lags):
        b = interp(tB, mBc, pb + lag + ga)
        if np.isnan(b).any():
            continue
        b = b - b.mean()
        nb = np.sqrt((b * b).sum())
        if nb > 1e-12:
            ncc[k] = (a * b).sum() / (na * nb)
    k = int(np.argmax(ncc))
    if k == 0 or k == len(lags) - 1:
        return None  # peak on the search edge: not a real maximum
    y0, y1, y2 = ncc[k - 1], ncc[k], ncc[k + 1]
    den = y0 - 2 * y1 + y2
    frac = 0.5 * (y0 - y2) / den if den < 0 else 0.0
    lag = lags[k] + frac * GRID_S
    return (pb - pa) + lag, float(y1)


def median_dt(t):
    return float(np.median(np.diff(t)))


def align(tA, mA, tB, mB, winStart, winEnd):
    """winStart/winEnd = ((a0, a1), (b0, b1)) windows in each stream's own time. Returns dict."""
    ends = []
    for (wa, wb) in (winStart, winEnd):
        sa = (tA >= wa[0]) & (tA <= wa[1])
        sb = (tB >= wb[0]) & (tB <= wb[1])
        pa, sta = find_transient(tA[sa], mA[sa])
        pb, stb = find_transient(tB[sb], mB[sb])
        if sta == "flat" or stb == "flat":
            return {"ok": False, "reason": "no_transient"}
        if sta == "double" or stb == "double":
            return {"ok": False, "reason": "ambiguous"}
        r = refine(tA, mA, tB, mB, pa, pb)
        if r is None:
            return {"ok": False, "reason": "no_match"}
        off, ncc = r
        if ncc < MIN_NCC:
            return {"ok": False, "reason": "low_correlation"}
        dt = max(median_dt(tA), median_dt(tB))
        sigma = 0.5 * dt * (1 + 2 * (1 - ncc))
        ends.append((pa, off, sigma))
    (a0, o0, s0), (a1, o1, s1) = ends
    span = a1 - a0
    if span <= 1.0:
        return {"ok": False, "reason": "windows_too_close"}
    drift = (o1 - o0) / span
    if abs(drift) * 1e6 > MAX_DRIFT_PPM:
        return {"ok": False, "reason": "drift_implausible"}
    return {"ok": True, "refA": a0, "offset": o0, "driftPpm": drift * 1e6,
            "offsetSigma": s0, "driftSigmaPpm": np.hypot(s0, s1) / span * 1e6}


# ---------------------------------------------------------------- experiments
def load(path):
    d = np.loadtxt(path, delimiter=",", usecols=range(61))
    keep = np.r_[True, np.diff(d[:, 0]) < 20000]
    d = d[keep]
    t = (d[:, 0] - d[0, 0]) / 1e6
    return t, d[:, 1:61].reshape(len(d), 10, 6)


def accel_mag(body, i):
    return np.linalg.norm(body[:, i, 3:6], axis=1)


def pick_transient(t, m, lo, hi):
    s = (t >= lo) & (t <= hi)
    f = np.abs(m - np.median(m))
    idx = np.where(s)[0]
    return t[idx[np.argmax(f[idx])]]


def make_b(t, m, offset, ppm, jitter_s, rng, dropout=0.0):
    """Sensor B clock: tB = offset + t * (1 + ppm*1e-6); samples jittered in time and some dropped."""
    n = len(t)
    keep = rng.random(n) >= dropout
    tt = t[keep]
    mm = m[keep]
    # jitter: the sample was taken at a jittered true time; the value is the signal there, stamped nominally
    tj = tt + rng.normal(0, jitter_s, len(tt))
    vals = np.interp(tj, t, m)
    return offset + tt * (1 + ppm * 1e-6), vals


def trial_real(t, body, ia, ib, rng, offset, ppm, jitter_s, noise_frac=0.0, win=2.5, dropout=0.0):
    mA = accel_mag(body, ia)
    mB0 = accel_mag(body, ib)
    n0 = noise_frac * np.std(mB0)
    mB0 = mB0 + rng.normal(0, n0, len(mB0)) if n0 > 0 else mB0
    tB, mB = make_b(t, mB0, offset, ppm, jitter_s, rng, dropout)
    # transients: strongest in first and last tenth of the run, windows +-win around them
    n = t[-1]
    if STOMPS:
        p0 = pick_transient(t, mA, STOMPS[0] - 1.0, STOMPS[0] + 1.0)  # the user knows roughly when the stomp was
        p1 = pick_transient(t, mA, STOMPS[1] - 1.0, STOMPS[1] + 1.0)
    else:
        p0 = pick_transient(t, mA, 3, n * 0.1)
        p1 = pick_transient(t, mA, n * 0.9, n - 3)
    def w(p):
        b = p + offset + (p * ppm * 1e-6)
        # the user only knows B's offset roughly: 0.2 s error in the window centre
        b += rng.uniform(-0.2, 0.2)
        return ((p - win, p + win), (b - win, b + win))
    r = align(t, mA, tB, mB, w(p0), w(p1))
    return r, p0, p1


def decimate(t, body, k):
    """Block-average k samples (about 100 Hz from the 714 Hz file) to match the boot's rate."""
    n = len(t) // k * k
    return t[:n].reshape(-1, k).mean(axis=1), body[:n].reshape(-1, k, 10, 6).mean(axis=1)


def inject_stomp(t, body, ts_list, amp=30.0):
    """Add the same damped 18 Hz stomp, along the accelerometer z axis, to every IMU (scaled per IMU)."""
    out = body.copy()
    for ts in ts_list:
        u = t - ts
        env = (u >= 0) * STOMP_ENV(u) * np.cos(2 * np.pi * 18 * u)
        for i in range(10):
            out[:, i, 5] += amp * (0.6 + 0.08 * i) * env
    return out


def run_real(path, natural=False):
    t, body = load(path)
    print(f"{len(t)} samples, {t[-1]:.0f} s, {1 / np.median(np.diff(t)):.0f} Hz")
    t, body = decimate(t, body, 7)
    print(f"decimated to {1 / np.median(np.diff(t)):.1f} Hz")
    if not natural:
        # the run starts and ends mid-ski, so put each stomp in the quietest 5 s stretch of the first and last 30 s
        # (standing still before and after the run is what a real stomp would see)
        m5 = accel_mag(body, 5)
        dev = np.abs(m5 - np.median(m5))
        def quiet(lo, hi):
            best, bt = 1e9, lo
            for c in np.arange(lo + 2.5, hi - 2.5, 0.5):
                x = dev[(t > c - 2.5) & (t < c + 2.5)].max()
                if x < best:
                    best, bt = x, c
            return bt, best
        q0, v0 = quiet(0, 30)
        q1, v1 = quiet(t[-1] - 30, t[-1])
        STOMPS[:] = [q0, q1]
        body = inject_stomp(t, body, [q0, q1], amp=STOMP_AMP)
        print(f"stomps (about {STOMP_AMP * 0.9:.0f} m/s2) injected at {q0:.1f} s and {q1:.1f} s; quietest natural peaks there {v0:.0f} and {v1:.0f} m/s2")
    else:
        print("natural ski transients only, no injected stomp")
    rng = np.random.default_rng(1)
    pairs = [(5, 0), (5, 9), (0, 9), (4, 5), (3, 7)]
    print("\nReal data: B shifted/stretched/jittered, known answers. err = recovered - truth")
    print("offset(s) ppm  jitter(ms) noise  trials ok  offset-err ms (p50 / max)  drift-err ppm (p50 / max)")
    for (off, ppm, jit, nf) in [(0.37, 0, 0, 0), (1.234, 40, 0, 0), (-0.8, -60, 2, 0), (2.5, 25, 3, 0.2),
                                (0.05, 100, 3, 0.5), (3.1, -20, 5, 0.5)]:
        errs_o, errs_d, ok, tot, reasons = [], [], 0, 0, {}
        for (ia, ib) in pairs:
            for rep in range(6):
                r, p0, _ = trial_real(t, body, ia, ib, rng, off, ppm, jit * 1e-3, nf)
                tot += 1
                if r["ok"]:
                    ok += 1
                    truth = off + p0 * ppm * 1e-6  # offset at A time refA
                    errs_o.append((r["offset"] - (off + r["refA"] * ppm * 1e-6)) * 1e3)
                    errs_d.append(r["driftPpm"] - ppm)
                else:
                    reasons[r["reason"]] = reasons.get(r["reason"], 0) + 1
        eo, ed = np.abs(errs_o), np.abs(errs_d)
        print(f"{off:7.3f} {ppm:5d} {jit:6.0f} {nf:7.1f} {tot:6d} {ok:3d}   "
              f"{np.median(eo) if len(eo) else float('nan'):6.2f} / {eo.max() if len(eo) else float('nan'):6.2f}"
              f"   {np.median(ed) if len(ed) else float('nan'):8.2f} / {ed.max() if len(ed) else float('nan'):8.2f}"
              f"   rejected: {reasons}")


def synth_signal(rng, dur, fs, spikes, noise=0.2):
    t = np.arange(0, dur, 1 / fs)
    m = 9.8 + rng.normal(0, noise, len(t))
    for (ts, amp) in spikes:
        # a stomp: a short damped oscillation
        u = t - ts
        env = (u >= 0) * STOMP_ENV(u)
        m += amp * env * np.cos(2 * np.pi * 18 * u)
    return t, m


def run_synthetic():
    rng = np.random.default_rng(2)
    print("\nSynthetic stomps (100 Hz, two ends 600 s apart, independent sensor noise)")
    print("case                       result")
    def two(t0, t1, amp=30.0, extra=()):
        return [(t0, amp), (t1, amp)] + list(extra)
    fs = 100.0
    def case(name, off, ppm, jitter, noise, spikesA, spikesB):
        t, mA = synth_signal(rng, 620, fs, spikesA, noise)
        _, mB0 = synth_signal(rng, 620, fs, spikesB, noise)
        tB, mB = make_b(t, mB0, off, ppm, jitter, rng)
        p0, p1 = 5.0, 610.0
        def w(p):
            b = p + off + p * ppm * 1e-6 + rng.uniform(-0.2, 0.2)
            return ((p - 3, p + 3), (b - 3, b + 3))
        r = align(t, mA, tB, mB, w(p0), w(p1))
        if r["ok"]:
            eo = (r["offset"] - (off + r["refA"] * ppm * 1e-6)) * 1e3
            ed = r["driftPpm"] - ppm
            print(f"{name:<26} ok  offset err {eo:6.2f} ms  drift err {ed:6.2f} ppm  sigma {r['offsetSigma']*1e3:.1f} ms / {r['driftSigmaPpm']:.1f} ppm")
        else:
            print(f"{name:<26} REJECT {r['reason']}")
    sp = two(5.0, 610.0)
    spB = [(5.0 + 0.0, 30.0), (610.0, 30.0)]
    case("clean", 1.2, 50, 0.0, 0.2, sp, spB)
    case("jitter 3 ms noise", -0.7, -80, 0.003, 0.4, sp, spB)
    case("jitter 5 ms big noise", 2.0, 30, 0.005, 1.5, sp, spB)
    case("very noisy", 0.5, 20, 0.003, 6.0, sp, spB)
    case("no transient at end", 1.0, 40, 0.001, 0.2, [(5.0, 30.0)], [(5.0, 30.0)])
    case("flat both", 1.0, 40, 0.001, 0.2, [], [])
    case("double stomp (A and B)", 1.0, 40, 0.001, 0.2, sp + [(5.4, 28.0), (610.4, 28.0)], spB + [(5.4, 28.0), (610.4, 28.0)])
    case("B misses end stomp", 1.0, 40, 0.001, 0.2, sp, [(5.0, 30.0)])
    case("500 ppm skew", 0.3, 500, 0.001, 0.2, sp, spB)
    case("5000 ppm skew", 0.3, 5000, 0.001, 0.2, sp, spB)


def run_stamp_jitter():
    """Timestamp jitter: the value is taken at the true time but stamped with an error (e.g. a polling loop tick).
    The signal is evaluated analytically at the jittered time, a harsher test than interpolating sampled data."""
    rng = np.random.default_rng(5)
    fs = 100.0
    t = np.arange(0, 620, 1 / fs)
    def sig(tt):
        v = np.full_like(tt, 9.8)
        for ts in (5.0, 610.0):
            u = tt - ts
            v += 30.0 * (u >= 0) * STOMP_ENV(u) * np.cos(2 * np.pi * 18 * u)
        return v
    print("\nStamp jitter sweep (analytic stomp, 18 Hz damped, sensor noise 0.3 m/s2, offset 1.0 s, 40 ppm, 20 trials each)")
    print("jitter ms  accepted  offset-err ms (p50 / max)  drift-err ppm (p50 / max)")
    for jit in (0.0, 0.5, 1.0, 2.0, 3.0):
        eo, ed, ok, why = [], [], 0, {}
        for _ in range(20):
            mA = sig(t) + rng.normal(0, 0.3, len(t))
            mB = sig(t + rng.normal(0, jit * 1e-3, len(t))) + rng.normal(0, 0.3, len(t))
            tB = 1.0 + t * (1 + 40e-6)
            def w(p):
                b = p + 1.0 + p * 40e-6 + rng.uniform(-0.2, 0.2)
                return ((p - 3, p + 3), (b - 3, b + 3))
            r = align(t, mA, tB, mB, w(5.0), w(610.0))
            if r["ok"]:
                ok += 1
                eo.append(abs(r["offset"] - (1.0 + r["refA"] * 40e-6)) * 1e3)
                ed.append(abs(r["driftPpm"] - 40))
            else:
                why[r["reason"]] = why.get(r["reason"], 0) + 1
        q = lambda x, f: f(x) if x else float("nan")
        print(f"{jit:7.1f}   {ok:3d}/20    {q(eo, np.median):6.2f} / {q(eo, np.max):6.2f}     {q(ed, np.median):7.2f} / {q(ed, np.max):7.2f}   rejected: {why}")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        run_real(args[0], natural="--natural" in sys.argv)
    run_synthetic()
    run_stamp_jitter()
