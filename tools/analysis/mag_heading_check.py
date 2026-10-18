"""T2 turn-on-the-spot check: magnetometer sanity, calibration fit and agreement with gyro yaw. Numpy only (no scipy).

Reads magnetometer.csv (raw LIS3MDL counts and gauss, uncalibrated) and samples.csv (the same boot's accelerometer and
gyro) from a session export (ZIP or a directory of CSVs). Experimental and unvalidated: a level turn on the spot only
visits a circle of field directions, so the tool says what it can and cannot determine.

What it reports:
  * field magnitude mean, spread and spread as a percent of the mean, raw and after the fitted correction. The
    first sanity line from the trial protocol is a spread under about 10 to 15 percent for a level turn. The raw
    figure includes the hard-iron offset, so a large offset alone can break it;
  * hard-iron offset (centre) and soft-iron shape. If the field samples cover a sphere (tumbling the boot) a full
    3D sphere and ellipsoid fit is made. If they cover only a plane (a level turn), only the in-plane centre and the
    in-plane ellipse axis ratio are observable; the component of the offset along the plane normal cannot be found
    from a level turn and is reported as unobservable;
  * heading: tilt-compensated with the accelerometer's gravity direction (low-passed accelerometer = up). The field
    is projected into the horizontal plane, an ellipse is fitted there and mapped to a circle, and heading is the angle
    of the result. Without an accelerometer the boot is assumed level (sensor z up), and the report says so;
  * agreement with the gyro yaw (gyro rate about the gravity direction, integrated) after removing a constant
    offset: RMS difference, plus the same after also removing a linear trend (a constant gyro bias integrates to one),
    and the net and total rotation counts from each.
Sign convention: yaw is counter-clockwise positive about up. A field fixed in the world rotates the other way in the
sensor frame, so mag yaw = minus the field angle in the right-handed horizontal basis.

Usage: python tools/analysis/mag_heading_check.py <export.zip|dir> [--side L] [--json out.json]
       python tools/analysis/mag_heading_check.py --selftest
"""
import argparse
import json
import math
import os
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_io  # noqa: E402

G = 9.80665
# Starting values, not validated:
SPREAD_OK_PCT = 10.0          # level-turn magnitude spread (after correction) at or below this is good
SPREAD_WARN_PCT = 15.0        # above this is flagged
SOFT_IRON_RATIO_FLAG = 1.15   # largest/smallest fitted axis above this is flagged as a notable soft-iron effect
COVERAGE_PLANAR = 0.30        # smallest/largest principal std of the field points below this means planar coverage
HEADING_RMS_OK_DEG = 10.0     # RMS mag-vs-gyro yaw difference (constant offset and linear trend removed) at or below this is good
HEADING_RMS_FLAG_DEG = 20.0   # above this is flagged
MIN_ROTATION_TURNS = 0.8      # total mag rotation below this: the trial was not a turn on the spot
GRAVITY_SMOOTH_S = 0.5        # accelerometer low-pass window for the gravity direction
COUNT_SWING_DEG = 30.0        # reversals smaller than this are not counted as rotation
COUNT_SMOOTH_S = 2.0          # heading smoothing before counting rotations (keeps noise from inflating the total)
MAG_MIN_SAMPLES = 100


# ------------------------------------------------------------------ geometry fits
def fit_sphere(p):
    """Least squares sphere: |p|^2 = 2 c.p + k. Returns (centre, radius)."""
    A = np.hstack([2 * p, np.ones((len(p), 1))])
    sol = np.linalg.lstsq(A, np.sum(p * p, 1), rcond=None)[0]
    c = sol[:3]
    return c, math.sqrt(max(sol[3] + c @ c, 0.0))


def fit_quadric_nd(p):
    """Fit x^T Q x + b.x + d = 0 by the smallest singular vector of the design matrix (norm-1 constraint), in n dims.

    Returns (centre, semi_axes_sorted_desc, W, ok) where W maps (p - centre) onto a sphere whose radius is the
    geometric mean of the semi-axes. ok is False if the fitted surface is not an ellipsoid."""
    n = p.shape[1]
    mu = p.mean(0)
    sc = np.std(p - mu)
    x = (p - mu) / sc
    cols, idx = [], []
    for i in range(n):
        for j in range(i, n):
            cols.append(x[:, i] * x[:, j] * (1.0 if i == j else 2.0)); idx.append((i, j))
    for i in range(n):
        cols.append(x[:, i])
    cols.append(np.ones(len(x)))
    D = np.stack(cols, 1)
    v = np.linalg.svd(D, full_matrices=False)[2][-1]
    Q = np.zeros((n, n))
    for k, (i, j) in enumerate(idx):
        Q[i, j] = Q[j, i] = v[k]
    b = v[len(idx):len(idx) + n]
    d = v[-1]
    if np.trace(Q) < 0:
        Q, b, d = -Q, -b, -d
    ev, evec = np.linalg.eigh(Q)
    if ev.min() <= 1e-12 * max(abs(ev.max()), 1e-30):
        return None
    c = -0.5 * np.linalg.solve(Q, b)
    s = c @ Q @ c - d
    if s <= 0:
        return None
    axes = np.sqrt(s / ev)
    r = float(np.exp(np.mean(np.log(axes))))
    W = evec @ np.diag(r / axes) @ evec.T
    order = np.argsort(-axes)
    return c * sc + mu, axes[order] * sc, W, evec[:, order], r * sc


# ------------------------------------------------------------------ pipeline
def moving_average(x, w):
    w = max(1, int(w))
    if w == 1:
        return x.copy()
    k = np.ones(w) / w
    pad = np.pad(x, ((w // 2, w - 1 - w // 2), (0, 0)), mode="edge")
    return np.stack([np.convolve(pad[:, i], k, mode="valid") for i in range(x.shape[1])], 1)


def path_length(a, threshold):
    """Total travelled angle counting only swings that reach `threshold` degrees (a zigzag filter), so wobble while
    standing still does not add up to fake rotation."""
    pivot = ext = float(a[0]); d = 0; total = 0.0
    for x in a:
        if d == 0:
            if abs(x - pivot) >= threshold:
                d = 1 if x > pivot else -1; ext = x
        elif d > 0:
            if x > ext:
                ext = x
            elif ext - x >= threshold:
                total += ext - pivot; pivot = ext; d = -1; ext = x
        else:
            if x < ext:
                ext = x
            elif x - ext >= threshold:
                total += pivot - ext; pivot = ext; d = 1; ext = x
    return total + (abs(ext - pivot) if d != 0 else 0.0)


def count_turns(angle_deg, t, smooth_s=COUNT_SMOOTH_S):
    """Net and total turns of an unwrapped angle. Smoothed first, then total path length with a zigzag threshold."""
    rate = 1.0 / float(np.median(np.diff(t)))
    a = moving_average(angle_deg[:, None], round(smooth_s * rate))[:, 0]
    return float((a[-1] - a[0]) / 360.0), float(path_length(a, COUNT_SWING_DEG) / 360.0)


def choose_samples(cols, side, t_lo, t_hi):
    m = cols["side"] == side
    best = None
    for s in sorted(set(cols["source"][m])):
        mm = m & (cols["source"] == s)
        t = cols["sensor_time_ms"][mm] * 1e-3
        ov = np.sum((t >= t_lo) & (t <= t_hi))
        if best is None or ov > best[0]:
            best = (ov, s, mm)
    return best


def analyse(path, side=None):
    mag = export_io.load(path, "magnetometer.csv")
    if not mag or len(mag.get("side", [])) == 0:
        raise SystemExit("magnetometer.csv missing or empty (only the boot with a LIS3MDL writes it)")
    sides = sorted(set(mag["side"]))
    side = side or ("L" if "L" in sides else sides[0])
    m = mag["side"] == side
    tm = mag["sensor_ms"][m] * 1e-3
    P = np.stack([mag["gauss_x"][m], mag["gauss_y"][m], mag["gauss_z"][m]], 1)
    o = np.argsort(tm)
    tm, P = tm[o], P[o]
    nr = mag["near_range"][m][o] if "near_range" in mag else np.array([])
    return analyse_arrays(tm, P, export_io.load(path, "samples.csv"), side, int(np.sum(nr == "true")) if len(nr) else 0)


def analyse_arrays(tm, P, samples, side, near_range_count=0):
    if len(tm) < MAG_MIN_SAMPLES:
        raise SystemExit(f"only {len(tm)} magnetometer samples; need at least {MAG_MIN_SAMPLES}")
    notes, flags = [], []
    r = {"side": side, "mag_samples": int(len(tm)), "duration_s": float(tm[-1] - tm[0]),
         "mag_rate_hz": float((len(tm) - 1) / (tm[-1] - tm[0])), "near_range_samples": near_range_count, "notes": notes, "flags": flags}
    mag_raw = np.linalg.norm(P, axis=1)
    r["magnitude_raw_gauss"] = {"mean": float(mag_raw.mean()), "std": float(mag_raw.std()),
                                "spread_percent_of_mean": float(mag_raw.std() / mag_raw.mean() * 100)}
    if near_range_count:
        flags.append(f"{near_range_count} magnetometer samples were flagged near the range limit")

    # ---- gravity direction and gyro yaw
    up = None
    yaw_gyro = None
    if samples is not None and "side" in samples and np.any(samples["side"] == side):
        best = choose_samples(samples, side, tm[0], tm[-1])
        if best is not None and best[0] > 50:
            mm = best[2]
            ts = samples["sensor_time_ms"][mm] * 1e-3
            o = np.argsort(ts)
            ts = ts[o]
            acc = np.stack([samples[k][mm][o] for k in ("ax_mps2", "ay_mps2", "az_mps2")], 1)
            gyr = np.stack([samples[k][mm][o] for k in ("gx_radps", "gy_radps", "gz_radps")], 1)
            fs = 1.0 / float(np.median(np.diff(ts)))
            ua = moving_average(acc, GRAVITY_SMOOTH_S * fs)
            ua /= np.linalg.norm(ua, axis=1, keepdims=True)
            wz = np.sum(gyr * ua, 1)                                   # rad/s about up (accelerometer reads +up when still)
            yaw_s = np.concatenate([[0.0], np.cumsum(0.5 * (wz[1:] + wz[:-1]) * np.diff(ts))]) * 180.0 / math.pi
            yaw_gyro = np.interp(tm, ts, yaw_s)
            up = np.stack([np.interp(tm, ts, ua[:, k]) for k in range(3)], 1)
            up /= np.linalg.norm(up, axis=1, keepdims=True)
            r["samples_source"] = best[1]
            r["accel_mean_g_when_still_check"] = float(np.mean(np.linalg.norm(acc, axis=1)) / G)
    if up is None:
        notes.append("No accelerometer/gyro rows overlap the magnetometer in time, so the heading assumes a level boot "
                     "(sensor z up) and the gyro comparison is skipped. Tilt compensation needs the accelerometer.")
        up = np.tile([0.0, 0.0, 1.0], (len(tm), 1))
    else:
        notes.append("Tilt compensated with the accelerometer's gravity direction (low-passed over "
                     f"{GRAVITY_SMOOTH_S} s); valid while the boot is not accelerating, which a slow turn on the spot satisfies.")

    # ---- 3D shape: coverage, sphere, ellipsoid or planar ellipse
    Pc = P - P.mean(0)
    sv = np.sqrt(np.maximum(np.linalg.eigvalsh(Pc.T @ Pc / len(P)), 0))
    coverage = float(sv[0] / sv[-1])
    r["coverage_min_over_max_std"] = coverage
    corr = {}
    if coverage >= COVERAGE_PLANAR:
        r["coverage"] = "spherical (3D fit made)"
        c_s, rad_s = fit_sphere(P)
        r["hard_iron_sphere_fit_gauss"] = {"offset": c_s.tolist(), "radius": rad_s}
        q = fit_quadric_nd(P)
        if q is None:
            flags.append("3D ellipsoid fit failed (data do not describe an ellipsoid)")
            centre, W = c_s, np.eye(3)
        else:
            centre, axes, W, _, rr = q
            r["ellipsoid_fit"] = {"offset_gauss": centre.tolist(), "semi_axes_gauss": axes.tolist(),
                                  "axis_ratios_over_smallest": (axes / axes.min()).tolist(),
                                  "max_over_min": float(axes.max() / axes.min()), "mean_radius_gauss": rr}
            if axes.max() / axes.min() > SOFT_IRON_RATIO_FLAG:
                flags.append(f"soft-iron: ellipsoid axis ratio {axes.max() / axes.min():.2f} above {SOFT_IRON_RATIO_FLAG}")
        corrected = (P - centre) @ W.T
        r["hard_iron_offset_gauss"] = centre.tolist()
    else:
        r["coverage"] = "planar (level turn): only the in-plane centre and in-plane ellipse are observable"
        mu = P.mean(0)
        evec = np.linalg.eigh(Pc.T @ Pc)[1]            # ascending; first column is the plane normal
        n_hat, B = evec[:, 0], evec[:, 1:]
        p2 = (P - mu) @ B
        q = fit_quadric_nd(p2)
        if q is None:
            flags.append("in-plane ellipse fit failed (data do not describe an ellipse)")
            c2, W2, axes2 = p2.mean(0), np.eye(2), np.array([1.0, 1.0])
        else:
            c2, axes2, W2, _, _ = q
        centre = mu + B @ c2
        r["hard_iron_offset_gauss"] = {"in_plane_point_gauss": centre.tolist(), "plane_normal": n_hat.tolist(),
                                       "note": "only the component of the offset in the plane is determined; along the normal it is unobservable from a level turn"}
        r["inplane_ellipse"] = {"semi_axes_gauss": axes2.tolist(), "axis_ratio": float(axes2.max() / axes2.min())}
        if axes2.max() / axes2.min() > SOFT_IRON_RATIO_FLAG:
            flags.append(f"soft-iron: in-plane ellipse axis ratio {axes2.max() / axes2.min():.2f} above {SOFT_IRON_RATIO_FLAG}")
        # In-plane radius about the fitted centre, NOT whitened, so soft-iron distortion still shows as spread. The
        # normal component's absolute offset is unobservable from a level turn, so the total field magnitude cannot
        # be judged here; the normal-axis wobble (tilt) is reported on its own.
        h = p2 - c2
        corrected = h
        r["hard_iron_offset_gauss"]["normal_component_unobservable"] = True
        wobble = float(np.std((P - mu) @ n_hat))
        r["normal_axis_wobble"] = {"std_gauss": wobble, "percent_of_inplane_radius":
                                   float(wobble / max(float(np.mean(np.linalg.norm(h, axis=1))), 1e-12) * 100)}
    # corrected magnitude spread. Three-axis coverage: the full field. Planar coverage: the in-plane radius only.
    mag_c = np.linalg.norm(corrected, axis=1)
    r["magnitude_corrected"] = {"mean": float(mag_c.mean()), "std": float(mag_c.std()),
                                "spread_percent_of_mean": float(mag_c.std() / mag_c.mean() * 100),
                                "quantity": "in-plane radius about the fitted centre (not whitened); total field magnitude "
                                            "cannot be judged from a level turn" if coverage < COVERAGE_PLANAR else "total field magnitude"}
    if coverage < COVERAGE_PLANAR and r["normal_axis_wobble"]["percent_of_inplane_radius"] > SPREAD_WARN_PCT:
        notes.append(f"normal-axis wobble is {r['normal_axis_wobble']['percent_of_inplane_radius']:.1f} % of the in-plane radius (tilt during the turn)")
    pct_raw, pct_c = r["magnitude_raw_gauss"]["spread_percent_of_mean"], r["magnitude_corrected"]["spread_percent_of_mean"]
    if pct_c > SPREAD_WARN_PCT:
        flags.append(f"field magnitude spread {pct_c:.1f} % after correction is above {SPREAD_WARN_PCT} %")
    elif pct_c > SPREAD_OK_PCT:
        notes.append(f"field magnitude spread {pct_c:.1f} % after correction is between {SPREAD_OK_PCT} and {SPREAD_WARN_PCT} % (marginal)")
    if pct_raw > SPREAD_WARN_PCT and pct_c <= SPREAD_WARN_PCT:
        notes.append(f"raw spread {pct_raw:.1f} % is high but is mostly the hard-iron offset (removed in the corrected figure)")

    # ---- heading from the horizontal projection
    # One reference axis for the whole recording (the sensor axis that is on average more horizontal), so the heading
    # basis never jumps; samples where it is close to vertical are noted rather than switched to another axis.
    axis = 0 if np.mean(np.abs(up[:, 0])) <= np.mean(np.abs(up[:, 1])) else 1
    ref = np.zeros(3)
    ref[axis] = 1.0
    e1 = ref[None, :] - (up @ ref)[:, None] * up
    near_vertical = float(np.mean(np.linalg.norm(e1, axis=1) < 0.2))
    if near_vertical > 0.05:
        notes.append(f"the heading reference axis (sensor {'xy'[axis]}) is within about 12 degrees of vertical for {near_vertical * 100:.0f} % of the samples; heading is unreliable there")
    e1 /= np.maximum(np.linalg.norm(e1, axis=1, keepdims=True), 1e-9)
    e2 = np.cross(up, e1)
    hx, hy = np.sum(P * e1, 1), np.sum(P * e2, 1)
    H = np.column_stack([hx, hy])
    qh = fit_quadric_nd(H)
    if qh is None:
        flags.append("horizontal-plane ellipse fit failed; heading uses the uncorrected projection")
        Hc = H - H.mean(0)
    else:
        ch, axes_h, Wh, _, _ = qh
        Hc = (H - ch) @ Wh.T
        r["horizontal_ellipse"] = {"centre_gauss": ch.tolist(), "semi_axes_gauss": axes_h.tolist(), "axis_ratio": float(axes_h.max() / axes_h.min())}
    psi = np.degrees(np.unwrap(np.arctan2(Hc[:, 1], Hc[:, 0])))
    yaw_mag = -psi                                  # counter-clockwise positive, see the module docstring
    net, total = count_turns(yaw_mag, tm)
    r["mag_rotation_turns"] = {"net_ccw_positive": net, "total_absolute": total}
    if total < MIN_ROTATION_TURNS:
        flags.append(f"magnetometer saw only {total:.2f} turns in total; not a turn-on-the-spot recording")
    if yaw_gyro is not None:
        gnet, gtot = count_turns(yaw_gyro, tm)
        r["gyro_rotation_turns"] = {"net_ccw_positive": gnet, "total_absolute": gtot}
        diff = yaw_mag - yaw_gyro
        d0 = diff - diff.mean()
        t0 = tm - tm.mean()
        d1 = diff - np.polyval(np.polyfit(t0, diff, 1), t0)
        r["yaw_difference_deg"] = {"rms_constant_offset_removed": float(np.sqrt(np.mean(d0 ** 2))),
                                   "rms_offset_and_linear_trend_removed": float(np.sqrt(np.mean(d1 ** 2))),
                                   "linear_trend_deg_per_min": float(np.polyfit(t0, diff, 1)[0] * 60.0),
                                   "constant_offset_deg": float(diff.mean())}
        rr = r["yaw_difference_deg"]["rms_offset_and_linear_trend_removed"]
        if rr > HEADING_RMS_FLAG_DEG:
            flags.append(f"mag heading disagrees with gyro yaw: RMS {rr:.1f} deg (limit {HEADING_RMS_FLAG_DEG})")
        elif rr > HEADING_RMS_OK_DEG:
            notes.append(f"mag-vs-gyro RMS {rr:.1f} deg is above the good line {HEADING_RMS_OK_DEG} deg")
        if abs(gnet - net) > 0.25 or abs(gtot - total) > 0.25:
            flags.append(f"rotation counts differ: mag net {net:.2f} / total {total:.2f}, gyro net {gnet:.2f} / total {gtot:.2f}")
    r["verdict"] = "FLAGGED" if flags else "no flags (starting thresholds, unvalidated)"
    return r


def fmt_report(r):
    L = [f"Magnetometer heading check, boot {r['side']}: {r['mag_samples']} samples over {r['duration_s']:.1f} s ({r['mag_rate_hz']:.2f} Hz)", ""]
    m = r["magnitude_raw_gauss"]
    L.append(f"field magnitude raw: mean {m['mean']:.4f} G, std {m['std']:.4f} G, spread {m['spread_percent_of_mean']:.1f} % of mean "
             f"(sanity line: under about {SPREAD_OK_PCT:.0f} to {SPREAD_WARN_PCT:.0f} %; this one includes the hard-iron offset)")
    L.append(f"field coverage: {r['coverage']} (min/max principal std {r['coverage_min_over_max_std']:.3f})")
    ho = r["hard_iron_offset_gauss"]
    if isinstance(ho, list):
        L.append("hard-iron offset (3D ellipsoid centre) G: " + "  ".join(f"{v:+.4f}" for v in ho))
        s = r.get("hard_iron_sphere_fit_gauss")
        if s:
            L.append("  sphere-only fit centre G: " + "  ".join(f"{v:+.4f}" for v in s["offset"]) + f"  radius {s['radius']:.4f}")
        e = r.get("ellipsoid_fit")
        if e:
            L.append("  soft-iron ellipsoid semi-axes G: " + "  ".join(f"{v:.4f}" for v in e["semi_axes_gauss"]) +
                     "; ratios over smallest: " + "  ".join(f"{v:.3f}" for v in e["axis_ratios_over_smallest"]) +
                     f"; max/min {e['max_over_min']:.3f}")
    else:
        L.append("hard-iron offset: in-plane centre point G " + "  ".join(f"{v:+.4f}" for v in ho["in_plane_point_gauss"]) +
                 "; plane normal " + "  ".join(f"{v:+.3f}" for v in ho["plane_normal"]))
        L.append("  " + ho["note"])
        e = r.get("inplane_ellipse")
        if e:
            L.append("  in-plane soft-iron ellipse semi-axes G: " + "  ".join(f"{v:.4f}" for v in e["semi_axes_gauss"]) + f"; axis ratio {e['axis_ratio']:.3f}")
    c = r["magnitude_corrected"]
    L.append(f"field magnitude corrected: mean {c['mean']:.4f} G, std {c['std']:.4f} G, spread {c['spread_percent_of_mean']:.1f} % ({c['quantity']})")
    if "horizontal_ellipse" in r:
        h = r["horizontal_ellipse"]
        L.append(f"horizontal-plane ellipse (heading calibration): centre {h['centre_gauss'][0]:+.4f} {h['centre_gauss'][1]:+.4f} G, axis ratio {h['axis_ratio']:.3f}")
    t = r["mag_rotation_turns"]
    L.append(f"rotation from magnetometer heading: net {t['net_ccw_positive']:+.2f} turns (counter-clockwise positive), total {t['total_absolute']:.2f} turns")
    if "gyro_rotation_turns" in r:
        g = r["gyro_rotation_turns"]
        d = r["yaw_difference_deg"]
        L.append(f"rotation from gyro yaw about gravity: net {g['net_ccw_positive']:+.2f}, total {g['total_absolute']:.2f} turns")
        L.append(f"mag minus gyro yaw: constant offset {d['constant_offset_deg']:+.1f} deg; RMS {d['rms_constant_offset_removed']:.2f} deg after removing it, "
                 f"{d['rms_offset_and_linear_trend_removed']:.2f} deg after also removing a linear trend ({d['linear_trend_deg_per_min']:+.2f} deg/min, the integrated gyro bias)")
    if r["near_range_samples"]:
        L.append(f"near-range samples: {r['near_range_samples']}")
    L += ["", "Notes:"] + ["  " + n for n in r["notes"]]
    L += ["", "Result: " + r["verdict"]] + ["  FLAG: " + f for f in r["flags"]]
    L.append("Thresholds are starting values, not validated; absolute heading is experimental.")
    return "\n".join(L)


# ------------------------------------------------------------------ selftest
def rot_z(a):
    c, s = np.cos(a), np.sin(a)
    z, o = np.zeros_like(a), np.ones_like(a)
    return np.stack([np.stack([c, -s, z], -1), np.stack([s, c, z], -1), np.stack([z, z, o], -1)], -2)


def rot_x(a):
    c, s = np.cos(a), np.sin(a)
    z, o = np.zeros_like(a), np.ones_like(a)
    return np.stack([np.stack([o, z, z], -1), np.stack([z, c, -s], -1), np.stack([z, s, c], -1)], -2)


def rot_y(a):
    c, s = np.cos(a), np.sin(a)
    z, o = np.zeros_like(a), np.ones_like(a)
    return np.stack([np.stack([c, z, s], -1), np.stack([z, o, z], -1), np.stack([-s, z, c], -1)], -2)


def simulate_turns(rng, offset, S, degrade, fs_i=104.0, fs_m=10.0, secs=300.0):
    """Three counter-clockwise then three clockwise slow circles, with +-3 degrees of pitch/roll wobble."""
    n = int(secs * fs_i)
    t = np.arange(n) / fs_i
    yaw = np.interp(t, [0, 60, 120, 180, secs], np.array([0, 3, 3, 0, 0]) * 2 * np.pi)   # 3 CCW circles, hold, 3 CW circles, hold
    pitch = np.radians(3) * np.sin(2 * np.pi * t / 13.0)
    roll = np.radians(3) * np.sin(2 * np.pi * t / 9.0 + 1.0)
    R = rot_z(yaw) @ rot_y(pitch) @ rot_x(roll)                # sensor-to-world
    Rt = np.swapaxes(R, 1, 2)
    Bw = np.array([0.25, 0.0, 0.40])                           # horizontal 0.25 G, vertical 0.40 G
    Bs = np.einsum("nij,j->ni", Rt, Bw)
    ws = np.einsum("nij,j->ni", Rt, np.array([0, 0, 1.0]))     # up in sensor frame
    # body angular velocity from R^T dR/dt
    dR = (R[2:] - R[:-2]) * (fs_i / 2)
    Om = np.einsum("nji,njk->nik", R[1:-1], dR)               # R^T dR
    w = np.stack([Om[:, 2, 1], Om[:, 0, 2], Om[:, 1, 0]], 1)
    w = np.vstack([w[:1], w, w[-1:]])
    gyro = w + np.radians(np.array([0.3, -0.2, 0.15])) + rng.normal(0, np.radians(0.05), (n, 3))   # 0.15 dps bias on z-ish axes
    acc = (ws * 1.0 + rng.normal(0, 0.002, (n, 3))) * G
    step = int(fs_i / fs_m)
    idx = np.arange(0, n, step)
    meas = np.einsum("ij,nj->ni", S, Bs[idx]) + offset[None, :]
    if degrade:   # large unmodelled distortion: heading-dependent term, a wobble along the vertical, and extra noise
        ph = yaw[idx]
        meas = meas + 0.20 * np.stack([np.sin(2 * ph + 0.3), np.cos(3 * ph), np.sin(1.7 * ph + 1.0)], 1)
        meas = meas + 0.10 * np.stack([np.zeros(len(idx)), np.zeros(len(idx)), np.sign(np.sin(0.9 * t[idx]))], 1)
    meas = meas + rng.normal(0, 0.003, meas.shape)
    return t, gyro, acc, t[idx], meas, yaw


def write_export(d, t, gyro, acc, tm, meas):
    sh = ["side", "source", "capture_id", "record_index", "session_elapsed_ms", "aligned_time_ms", "received_at_ms",
          "sensor_time_ms", "sensor_time_us", "sequence", "ax_mps2", "ay_mps2", "az_mps2", "gx_radps", "gy_radps", "gz_radps", "quality"]
    rows = [["L", "flash", "0b9d5c3e-1c2a-4e44-9f5b-6a3c1d2e7f10", i, "", "", "", int(round(t[i] * 1e3)), int(round(t[i] * 1e6)), "", *acc[i], *gyro[i], 0] for i in range(len(t))]
    mh = ["side", "sensor_ms", "phone_receipt_ms", "session_elapsed_ms", "raw_x", "raw_y", "raw_z", "gauss_x", "gauss_y", "gauss_z",
          "flags", "near_range", "missed_samples", "calibration"]
    mrows = [["L", int(round(tm[i] * 1e3)), "", "", *np.round(meas[i] * 6842).astype(int), *meas[i], 0, "false", "false", "uncalibrated"]
             for i in range(len(tm))]
    export_io.write_csvs(d, {"samples.csv": (sh, rows), "magnetometer.csv": (mh, mrows)})


def selftest():
    rng = np.random.default_rng(7)
    offset = np.array([0.12, -0.08, 0.20])
    S = np.diag([1.0, 0.92, 1.05])
    fails = []

    def check(name, got, want, tol):
        ok = abs(got - want) <= tol
        print(f"{'ok  ' if ok else 'FAIL'} {name}: recovered {got:.4f}, truth {want:.4f}, abs error {abs(got - want):.4f} (tolerance {tol})")
        if not ok:
            fails.append(name)

    # --- case 1: level turn on the spot, clean
    t, gyro, acc, tm, meas, yaw = simulate_turns(rng, offset, S, False)
    with tempfile.TemporaryDirectory() as d:
        write_export(d, t, gyro, acc, tm, meas)
        r = analyse(d)
    print("=== Case 1: level turn, hard-iron offset, soft-iron scaling, clean ===")
    print(fmt_report(r))
    print()
    # truth: in-plane offset is the x,y part of the offset (plane normal is near z); ratio x:y semi-axes 1/0.92
    c = np.array(r["hard_iron_offset_gauss"]["in_plane_point_gauss"])
    # the plane point lies somewhere on the plane; its in-plane (x,y) coordinates should be the offset's x,y
    check("hard-iron offset x (in plane) G", c[0], offset[0], 0.01)
    check("hard-iron offset y (in plane) G", c[1], offset[1], 0.01)
    check("in-plane soft-iron axis ratio (x:y = 1/0.92)", r["inplane_ellipse"]["axis_ratio"], 1 / 0.92, 0.03)
    check("mag total turns (truth 6)", r["mag_rotation_turns"]["total_absolute"], 6.0, 0.15)
    check("mag net turns (truth 0)", r["mag_rotation_turns"]["net_ccw_positive"], 0.0, 0.1)
    check("gyro total turns (truth 6)", r["gyro_rotation_turns"]["total_absolute"], 6.0, 0.15)
    check("yaw RMS deg after offset+trend removal (truth 0, bound 5; limited by the unobservable normal-axis offset times the tilt wobble)", r["yaw_difference_deg"]["rms_offset_and_linear_trend_removed"], 0.0, 5.0)
    check("corrected spread percent (bound 10)", r["magnitude_corrected"]["spread_percent_of_mean"], 0.0, 10.0)
    if r["flags"]:
        print("FAIL clean case raised flags:", r["flags"]); fails.append("clean flagged")

    # --- case 2: tumbling coverage, 3D sphere and ellipsoid
    n = 3000
    q = rng.normal(size=(n, 3))
    q /= np.linalg.norm(q, axis=1, keepdims=True)
    meas3 = 0.47 * q @ S.T + offset + rng.normal(0, 0.003, (n, 3))
    tm3 = np.arange(n) * 0.1
    r3 = analyse_arrays(tm3, meas3, None, "L")
    print("\n=== Case 2: tumbling boot (full 3D coverage), no accelerometer ===")
    print(fmt_report(r3))
    print()
    for k, ax in enumerate("xyz"):
        check(f"3D ellipsoid offset {ax} G", r3["ellipsoid_fit"]["offset_gauss"][k], offset[k], 0.01)
    check("3D ellipsoid max/min axis ratio (truth 1.05/0.92)", r3["ellipsoid_fit"]["max_over_min"], 1.05 / 0.92, 0.03)
    for k, ax in enumerate("xyz"):
        check(f"3D sphere-only offset {ax} G (biased by soft iron)", r3["hard_iron_sphere_fit_gauss"]["offset"][k], offset[k], 0.03)

    # --- case 3: degraded, large unmodelled distortion; the tool must flag it
    t, gyro, acc, tm, meas, yaw = simulate_turns(rng, offset, S, True)
    with tempfile.TemporaryDirectory() as d:
        write_export(d, t, gyro, acc, tm, meas)
        r = analyse(d)
    print("\n=== Case 3: degraded, large unmodelled distortion ===")
    print(fmt_report(r))
    print()
    if r["flags"]:
        print(f"ok   degraded case flagged ({len(r['flags'])} flags)")
    else:
        print("FAIL degraded case raised no flag"); fails.append("degraded not flagged")
    if fails:
        print("\nSELFTEST FAILED: " + ", ".join(fails))
        return 1
    print("\nSELFTEST PASSED (tolerances: offsets 0.01 G in plane and for the ellipsoid, 0.03 G for the biased sphere-only fit; "
          "axis ratios 0.03; rotation counts 0.15 turns total and 0.1 net; yaw RMS under 5 deg; clean case must raise no flags, degraded case at least one)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("export", nargs="?")
    ap.add_argument("--side")
    ap.add_argument("--json")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())
    if not a.export:
        ap.error("export path required")
    r = analyse(a.export, a.side)
    print(fmt_report(r))
    if a.json:
        with open(a.json, "w") as f:
            json.dump(r, f, indent=1)


if __name__ == "__main__":
    main()
