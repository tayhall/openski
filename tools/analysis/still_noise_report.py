"""T0 bench soak report: boots lying still. Numpy only (no scipy).

Reads samples.csv from a session export (ZIP or a directory of CSVs). Per boot (side) it prints the actual sample
rate and interval spread, gyro bias and drift, Allan-deviation noise densities and floors for gyro and accelerometer,
accelerometer magnitude against 1 g, and the quality flag counts. The export carries no temperature, and the report
says so rather than inventing a bias-versus-temperature figure.

Allan conventions (see docs/garden-trial-protocol.md T0):
  * overlapping Allan variance of the (linearly detrended) still data, clusters of m samples, tau = m / fs;
  * white noise: ADEV(tau) = sqrt(S/2)/sqrt(tau) where S is the one-sided power spectral density, so the
    datasheet-style density sqrt(S) = sqrt(2) * ADEV(tau) * sqrt(tau). (The IEEE angle-random-walk figure N = ADEV(1 s)
    is the smaller number, a factor sqrt(2) below the density.) Taken as the median over tau in WHITE_TAU_S;
  * bias-instability floor: the minimum of the ADEV curve and that minimum divided by 0.664 (the flicker-noise
    convention); when a random walk is present the minimum is only an upper bound on the true flicker floor;
  * rate random walk K (ADEV = K sqrt(tau/3)) from the long-tau end after removing the white part, reported only if resolved.
The datasheet comparison is left to the reader: this tool never quotes datasheet values.

Usage: python tools/analysis/still_noise_report.py <export.zip|dir> [--json out.json] [--source flash|live]
       python tools/analysis/still_noise_report.py --selftest
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
RAD2DEG = 180.0 / math.pi
# Starting values, not validated:
WHITE_TAU_S = (0.1, 1.0)     # tau range used for the white-noise density
MAX_CLUSTER_FRACTION = 9     # largest cluster = N // 9, so at least about 8 independent averages remain
GAP_FACTOR = 2.5             # an interval above this times the median splits the stream into segments for the Allan run
MIN_SAMPLES = 2000
QUALITY_BITS = {0: "accel_near_rail", 1: "gyro_near_rail", 2: "dropped_before"}
DATASHEET_NOTE = ("to compare with the datasheet, not checked here (the datasheet has not been read in this project; "
                  "the tool quotes no datasheet value)")


# ------------------------------------------------------------------ maths
def allan_deviation(y, fs, max_fraction=MAX_CLUSTER_FRACTION):
    """Overlapping Allan deviation. Returns (tau_s, adev) for log-spaced cluster sizes."""
    y = np.asarray(y, dtype=float)
    n = len(y)
    top = max(2, n // max_fraction)
    ms = np.unique(np.round(np.logspace(0, math.log10(top), 40)).astype(int))
    c = np.concatenate([[0.0], np.cumsum(y)])
    taus, devs = [], []
    for m in ms:
        avg = (c[m:] - c[:-m]) / m
        d = avg[m:] - avg[:-m]
        if len(d) < 2:
            continue
        taus.append(m / fs)
        devs.append(math.sqrt(0.5 * float(np.mean(d * d))))
    return np.array(taus), np.array(devs)


def detrend(t, y):
    p = np.polyfit(t - t[0], y, 1)
    return y - np.polyval(p, t - t[0]), p[0]


def analyse_channel(t, y, fs, unit_scale):
    """Allan analysis of one channel; results are multiplied by unit_scale (for example 1e3 for dps -> mdps)."""
    yd, _ = detrend(t, y)
    taus, adev = allan_deviation(yd, fs)
    i_min = int(np.argmin(adev))
    sel = (taus >= WHITE_TAU_S[0]) & (taus <= WHITE_TAU_S[1]) & (taus <= taus[i_min] * 1.0001)
    if sel.sum() < 2:   # the minimum lies inside or before the window: use everything up to the minimum
        sel = taus <= taus[i_min]
    dens_pts = math.sqrt(2.0) * adev[sel] * np.sqrt(taus[sel])
    density = float(np.median(dens_pts))
    sigma_w = float(np.std(np.diff(y)) / math.sqrt(2.0))      # slow terms cancel in the first difference
    out = {
        "white_density_per_rthz": density * unit_scale,
        "white_density_crosscheck_per_rthz": sigma_w * math.sqrt(2.0 / fs) * unit_scale,
        "adev_floor": float(adev[i_min]) * unit_scale,
        "adev_floor_tau_s": float(taus[i_min]),
        "bias_instability_flicker_convention": float(adev[i_min]) / 0.664 * unit_scale,
        "tau_s": taus.tolist(), "adev": (adev * unit_scale).tolist(),
    }
    w2 = (density / math.sqrt(2.0)) ** 2        # ADEV^2 = w2 / tau for the white part
    late = taus >= 2.0 * taus[i_min]
    out["rate_random_walk_per_rts"] = None
    if late.sum() >= 3 and adev[-1] > 1.2 * adev[i_min]:
        k2 = 3.0 * (adev[late] ** 2 - w2 / taus[late]) / taus[late]
        k2 = k2[k2 > 0]
        if len(k2) >= 2:
            out["rate_random_walk_per_rts"] = float(math.sqrt(np.median(k2))) * unit_scale
    return out


def longest_segment(t, med_dt):
    brk = np.where(np.diff(t) > GAP_FACTOR * med_dt)[0]
    edges = np.concatenate([[0], brk + 1, [len(t)]])
    k = int(np.argmax(np.diff(edges)))
    return int(edges[k]), int(edges[k + 1])


# ------------------------------------------------------------------ per-boot analysis
def boot_time_s(d):
    us = d["sensor_time_us"]
    if np.all(np.isfinite(us)):
        return us * 1e-6
    return d["sensor_time_ms"] * 1e-3


def analyse_boot(d):
    t = boot_time_s(d)
    order = np.argsort(t, kind="stable")
    t = t[order]
    d = {k: v[order] for k, v in d.items()}
    dt = np.diff(t)
    med = float(np.median(dt))
    dur = float(t[-1] - t[0])
    r = {"samples": int(len(t)), "duration_s": dur,
         "rate_hz_median_interval": 1.0 / med, "rate_hz_mean": (len(t) - 1) / dur,
         "interval_ms": {"median": med * 1e3, "std": float(np.std(dt)) * 1e3, "min": float(dt.min()) * 1e3,
                         "max": float(dt.max()) * 1e3, "p99": float(np.percentile(dt, 99)) * 1e3},
         "gaps_over_2.5x_median": int(np.sum(dt > GAP_FACTOR * med))}
    gyro = np.stack([d["gx_radps"], d["gy_radps"], d["gz_radps"]], 1) * RAD2DEG        # dps
    acc = np.stack([d["ax_mps2"], d["ay_mps2"], d["az_mps2"]], 1) / G                  # g
    tm = t - t[0]
    r["gyro_bias_dps"] = [float(np.mean(gyro[:, k])) for k in range(3)]
    r["gyro_bias_drift_dps_per_min"] = [float(np.polyfit(tm, gyro[:, k], 1)[0] * 60.0) for k in range(3)]
    tenth = max(1, len(t) // 10)
    r["gyro_bias_start_end_dps"] = [[float(np.mean(gyro[:tenth, k])), float(np.mean(gyro[-tenth:, k]))] for k in range(3)]
    mag = np.linalg.norm(acc, axis=1)
    r["accel_magnitude_g"] = {"mean": float(mag.mean()), "std": float(mag.std()), "error_vs_1g_percent": float((mag.mean() - 1) * 100)}
    r["accel_mean_g"] = [float(v) for v in acc.mean(0)]
    q = d["quality"].astype(np.int64) if "quality" in d and np.all(np.isfinite(d["quality"])) else np.zeros(len(t), dtype=np.int64)
    r["quality"] = {name: {"samples": int(np.sum((q >> b) & 1)), "percent": float(np.mean((q >> b) & 1) * 100)}
                    for b, name in QUALITY_BITS.items()}
    a, b = longest_segment(t, med)
    r["allan_segment"] = {"samples": b - a, "seconds": float(t[b - 1] - t[a]), "of_total_samples": int(len(t))}
    if b - a < MIN_SAMPLES:
        r["allan"] = None
        r["allan_note"] = f"longest gap-free stretch has only {b - a} samples (< {MIN_SAMPLES}); Allan not computed"
        return r
    ts = t[a:b]
    fs = 1.0 / float(np.median(np.diff(ts)))
    r["allan"] = {"gyro_unit": "mdps/sqrt(Hz) density, mdps floors, mdps/sqrt(s) random walk",
                  "accel_unit": "ug/sqrt(Hz) density, ug floors", "axes": {}}
    for k, ax in enumerate("xyz"):
        r["allan"]["axes"][ax] = {"gyro": analyse_channel(ts, gyro[a:b, k], fs, 1e3),
                                  "accel": analyse_channel(ts, acc[a:b, k], fs, 1e6)}
    return r


def pick_boots(cols, prefer):
    """Split samples.csv by side and choose, per side, the source with the most rows (or the preferred one)."""
    out = {}
    for side in sorted(set(cols["side"])):
        m_side = cols["side"] == side
        srcs = {s: int(np.sum(m_side & (cols["source"] == s))) for s in sorted(set(cols["source"][m_side]))}
        use = prefer if prefer in srcs else max(srcs, key=srcs.get)
        m = m_side & (cols["source"] == use)
        out[side] = (use, srcs, {k: v[m] for k, v in cols.items() if k not in ("side", "source")})
    return out


def analyse_export(path, prefer=None):
    cols = export_io.load(path, "samples.csv")
    if not cols or "side" not in cols or len(cols["side"]) == 0:
        raise SystemExit("samples.csv missing or empty")
    res = {"input": str(path), "boots": {},
           "temperature": "Not available: samples.csv, the live frame and the flash record carry no temperature column or "
                          "event, so bias against temperature is not computed."}
    for side, (use, srcs, sub) in pick_boots(cols, prefer).items():
        if len(sub["ax_mps2"]) < 100:
            res["boots"][side] = {"error": "fewer than 100 samples"}
            continue
        b = analyse_boot(sub)
        b["source_used"] = use
        b["rows_by_source"] = srcs
        res["boots"][side] = b
    return res


# ------------------------------------------------------------------ report
def fmt_report(res):
    L = [f"Still-noise report: {res['input']}", ""]
    for side, b in res["boots"].items():
        L.append(f"== Boot {side} ==")
        if "error" in b:
            L += [b["error"], ""]
            continue
        L.append(f"source {b['source_used']} (rows {b['rows_by_source']}); {b['samples']} samples over {b['duration_s']:.1f} s")
        iv = b["interval_ms"]
        L.append(f"sample rate {b['rate_hz_mean']:.3f} Hz mean, {b['rate_hz_median_interval']:.3f} Hz from the median interval; "
                 f"interval ms median {iv['median']:.3f}, std {iv['std']:.3f}, min {iv['min']:.3f}, p99 {iv['p99']:.3f}, max {iv['max']:.3f}; "
                 f"{b['gaps_over_2.5x_median']} gaps over {GAP_FACTOR}x the median interval")
        L.append("gyro bias dps (x y z): " + "  ".join(f"{v:+.4f}" for v in b["gyro_bias_dps"]))
        L.append("gyro bias drift dps/min, linear fit (x y z): " + "  ".join(f"{v:+.4f}" for v in b["gyro_bias_drift_dps_per_min"]))
        L.append("gyro bias first tenth -> last tenth dps: " + "  ".join(f"{s[0]:+.4f}->{s[1]:+.4f}" for s in b["gyro_bias_start_end_dps"]))
        am = b["accel_magnitude_g"]
        L.append(f"accel magnitude {am['mean']:.5f} g (std {am['std'] * 1e3:.3f} mg), {am['error_vs_1g_percent']:+.3f} % from 1 g "
                 "(one orientation only: this mixes scale error and offsets)")
        L.append("quality flags: " + "; ".join(f"{n} {v['samples']} ({v['percent']:.3f} %)" for n, v in b["quality"].items()))
        if b.get("allan") is None:
            L.append(b.get("allan_note", "Allan not computed"))
        else:
            sg = b["allan_segment"]
            L.append(f"Allan run on {sg['seconds']:.0f} s ({sg['samples']} of {sg['of_total_samples']} samples, longest gap-free stretch), linearly detrended")
            L.append("  axis | gyro white mdps/rtHz (xcheck) | gyro ADEV floor mdps @tau (/0.664) | gyro RRW mdps/rts "
                     "|| accel white ug/rtHz (xcheck) | accel floor ug @tau (/0.664)")
            for ax, v in b["allan"]["axes"].items():
                g, c = v["gyro"], v["accel"]
                rw = g["rate_random_walk_per_rts"]
                L.append(f"   {ax}   | {g['white_density_per_rthz']:8.2f} ({g['white_density_crosscheck_per_rthz']:.2f}) | "
                         f"{g['adev_floor']:7.3f} @{g['adev_floor_tau_s']:.1f}s ({g['bias_instability_flicker_convention']:.3f}) | "
                         f"{'not resolved' if rw is None else format(rw, '.3f'):>12} || "
                         f"{c['white_density_per_rthz']:8.1f} ({c['white_density_crosscheck_per_rthz']:.1f}) | "
                         f"{c['adev_floor']:7.2f} @{c['adev_floor_tau_s']:.1f}s ({c['bias_instability_flicker_convention']:.2f})")
            L.append(f"  Datasheet comparison: {DATASHEET_NOTE}.")
        L.append("")
    L.append("Temperature: " + res["temperature"])
    return "\n".join(L)


# ------------------------------------------------------------------ selftest
def synth_boot(rng, fs, secs, bias_dps, drift_dpm, gyro_dens_mdps, rrw_dps_rts, acc_dens_ug, acc_scale, jitter_ms, n_drop):
    n = int(fs * secs)
    tc = np.arange(n) / fs
    t = np.maximum.accumulate(tc + rng.normal(0, jitter_ms * 1e-3, n))
    walk = np.cumsum(rng.normal(0, rrw_dps_rts / math.sqrt(fs), (n, 3)), axis=0)
    true_bias = np.array(bias_dps)[None, :] + np.array(drift_dpm)[None, :] * (tc[:, None] / 60.0) + walk
    gyro_dps = true_bias + rng.normal(0, gyro_dens_mdps * 1e-3 * math.sqrt(fs / 2.0), (n, 3))
    v = np.array([0.012, -0.008, 1.0])
    acc_g = v / np.linalg.norm(v) * acc_scale + rng.normal(0, acc_dens_ug * 1e-6 * math.sqrt(fs / 2.0), (n, 3))
    q = np.zeros(n, dtype=int)
    q[rng.choice(n, n_drop, replace=False)] = 4
    return t, gyro_dps, acc_g, q, true_bias


def selftest():
    rng = np.random.default_rng(12345)
    fs, secs = 104.0, 600
    truth = dict(bias=[0.30, -0.20, 0.10], drift=[0.05, -0.03, 0.02], gdens=6.0, rrw=0.002, adens=80.0, scale=1.002)
    t, gyro, acc, q, true_bias = synth_boot(rng, fs, secs, truth["bias"], truth["drift"], truth["gdens"], truth["rrw"],
                                            truth["adens"], truth["scale"], 0.2, 12)
    n = len(t)
    header = ["side", "source", "capture_id", "record_index", "session_elapsed_ms", "aligned_time_ms", "received_at_ms",
              "sensor_time_ms", "sensor_time_us", "sequence", "ax_mps2", "ay_mps2", "az_mps2", "gx_radps", "gy_radps", "gz_radps", "quality"]
    rows = [["L", "flash", 1, i, "", "", "", int(t[i] * 1e3), int(t[i] * 1e6), "", *(acc[i] * G), *(gyro[i] / RAD2DEG), q[i]] for i in range(n)]
    with tempfile.TemporaryDirectory() as d:
        export_io.write_csvs(d, {"samples.csv": (header, rows)})
        res = analyse_export(d)
    print(fmt_report(res))
    print()
    b = res["boots"]["L"]
    fails = []

    def check(name, got, want, tol, absolute=True):
        err = abs(got - want) if absolute else abs(got / want - 1)
        ok = err <= tol
        print(f"{'ok  ' if ok else 'FAIL'} {name}: recovered {got:.5g}, truth {want:.5g}, "
              f"{'abs' if absolute else 'rel'} error {err:.3g} (tolerance {tol})")
        if not ok:
            fails.append(name)

    check("sample rate Hz", b["rate_hz_mean"], fs, 0.2)
    check("interval std ms (jitter 0.2 ms per stamp, differences x sqrt2)", b["interval_ms"]["std"], 0.2 * math.sqrt(2), 0.08)
    mean_bias = true_bias.mean(0)
    for k, ax in enumerate("xyz"):
        check(f"gyro bias {ax} dps (vs realised mean incl. walk)", b["gyro_bias_dps"][k], mean_bias[k], 0.003)
        check(f"gyro bias drift {ax} dps/min", b["gyro_bias_drift_dps_per_min"][k], truth["drift"][k], 0.02)
    check("accel magnitude g", b["accel_magnitude_g"]["mean"], truth["scale"], 0.0005)
    check("dropped-flag count", b["quality"]["dropped_before"]["samples"], 12, 0)
    w2 = (truth["gdens"] * 1e-3 / math.sqrt(2)) ** 2
    taus = np.logspace(-1, 2, 2000)
    floor_truth = float(np.sqrt(w2 / taus + truth["rrw"] ** 2 * taus / 3.0).min()) * 1e3
    for ax in "xyz":
        g = b["allan"]["axes"][ax]["gyro"]
        c = b["allan"]["axes"][ax]["accel"]
        check(f"gyro white density {ax} mdps/rtHz", g["white_density_per_rthz"], truth["gdens"], 0.10, False)
        check(f"gyro white cross-check {ax}", g["white_density_crosscheck_per_rthz"], truth["gdens"], 0.05, False)
        check(f"accel white density {ax} ug/rtHz", c["white_density_per_rthz"], truth["adens"], 0.10, False)
        rw = g["rate_random_walk_per_rts"]
        if rw is None:
            print(f"FAIL gyro rate random walk {ax}: not resolved")
            fails.append("rrw " + ax)
        else:
            check(f"gyro rate random walk {ax} dps/rts", rw * 1e-3, truth["rrw"], 0.45, False)
        check(f"gyro ADEV floor {ax} mdps", g["adev_floor"], floor_truth, 0.35, False)
    print()
    if fails:
        print("SELFTEST FAILED: " + ", ".join(fails))
        return 1
    print("SELFTEST PASSED (tolerances: rate 0.2 Hz; drift 0.02 dps/min abs; bias 3 mdps abs; white density 10 % rel; "
          "random walk 45 % rel; ADEV floor 35 % rel; one 10-minute realisation, so the statistical tolerances are loose)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("export", nargs="?")
    ap.add_argument("--json")
    ap.add_argument("--source", choices=["flash", "live"])
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())
    if not a.export:
        ap.error("export path required")
    res = analyse_export(a.export, a.source)
    print(fmt_report(res))
    if a.json:
        with open(a.json, "w") as f:
            json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()
