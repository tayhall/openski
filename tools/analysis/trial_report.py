"""Judge a garden trial from one session export: one command, one table. Numpy only (no scipy).

Usage:
  python tools/analysis/trial_report.py EXPORT.zip [--trial T0|T1|T2|T3|T4|T5|T6|all] [--json out.json]
  python tools/analysis/trial_report.py EXPORT.zip --trial T3 --compare SMOOTH.zip ROUGH.zip
  python tools/analysis/trial_report.py --regress DIR      # every dress-rehearsal ZIP in DIR against its expected verdicts

Each row is one check: trial, check, measured value, the starting pass line, and PASS / FAIL / WARN / N/A / INFO.
  PASS, FAIL  the measured value is on the right / wrong side of the starting pass line.
  WARN        measured, but the recording does not suit the check or the value is marginal; look by hand.
  N/A         the export cannot show it (the reason is in the last column). Never turned into a number.
  INFO        a measured figure the protocol gives no pass line for (recorded for the bench-results entry).
Exit code is 1 when any selected check FAILs. Every line below is a STARTING value from
docs/garden-trial-protocol.md, to be revised once there is real data. The framing stays experimental: these checks judge
hardware, clocks and the data path, not on-snow technique.

With --trial all, checks that make no sense for the recording (still-noise on a recording that is moving, magnetometer
heading on a recording that is not a turn on the spot) are N/A or WARN. With one trial named, the same situation is a
FAIL or WARN, because the trial was supposed to produce that kind of recording.
"""
import argparse
import glob
import json
import math
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_io  # noqa: E402
import mag_heading_check  # noqa: E402
import still_noise_report  # noqa: E402

# ---------------------------------------------------------------- starting values (docs/garden-trial-protocol.md)
FLASH_RATE_HZ, LIVE_RATE_HZ, RATE_TOL_HZ = 104.0, 52.0, 1.0   # T0: "within about 1 Hz of 104 (flash) and 52 (live)"
GAP_LONG_MS = 100.0           # T0 "no gaps": longest interval above this fails (the app's own gaps_over_100ms)
CLOCK_SLOPE_PPM = 100.0       # T0: boot-versus-phone slope under about 100 ppm
STILL_GYRO_RMS_DPS = 2.0      # a recording whose gyro RMS (mean removed) is below this counts as still for the noise figures
STOMP_PING_MS = 15.0          # T1: stomp offset and ping offset agree within about 15 ms
MAG_SPREAD_OK_PCT = mag_heading_check.SPREAD_OK_PCT         # T2: first sanity line, 10 to 15 percent
MAG_SPREAD_WARN_PCT = mag_heading_check.SPREAD_WARN_PCT
MAG_HEADING_OK_DEG = mag_heading_check.HEADING_RMS_OK_DEG   # T2: magnetometer heading follows gyro yaw (starting RMS lines)
MAG_HEADING_WARN_DEG = mag_heading_check.HEADING_RMS_FLAG_DEG
MIN_CANDIDATES_OK = 10        # T3: candidates per boot (the trial asks for 20 repetitions per drill); 1 to 9 is WARN
PHONE_ROWS_PER_S = (90.0, 110.0)    # T4: "about 100 rows a second"; outside this but inside WARN_ROWS is WARN
PHONE_WARN_ROWS_PER_S = (80.0, 120.0)
PHONE_OFFSET_OK_MS, PHONE_OFFSET_WARN_MS = 5.0, 50.0   # T4: phone_clock_check offset "near zero"
PHONE_GAP_WARN_MS, PHONE_GAP_FAIL_MS = 100.0, 500.0    # T4: largest gap between phone rows
EXPECTED_SIDES = ("L", "R")

ORDER = ["T0", "T1", "T2", "T3", "T4", "T5", "T6"]
TITLES = {"T0": "bench soak", "T1": "stomp sync", "T2": "turn on the spot", "T3": "roll drills",
          "T4": "phone reference", "T5": "run flow", "T6": "mount slip"}
PASS, FAIL, WARN, NA, INFO = "PASS", "FAIL", "WARN", "N/A", "INFO"


class Row:
    def __init__(self, trial, check, value, line, status, why="", num=None):
        self.trial, self.check, self.value, self.line, self.status, self.why, self.num = trial, check, value, line, status, why, num

    def as_dict(self):
        d = {"trial": self.trial, "check": self.check, "value": self.value, "pass_line": self.line, "status": self.status, "reason": self.why}
        if self.num is not None:
            d["number"] = self.num
        return d


class Export:
    """Lazy, cached access to the files of one export."""

    def __init__(self, path):
        self.path = path
        self._c = {}

    def text(self, name):
        if name not in self._c:
            self._c[name] = export_io.read_text(self.path, name)
        return self._c[name]

    def json(self, name):
        t = self.text(name)
        if not t:
            return None
        try:
            return json.loads(t)
        except ValueError:
            return None

    def csv(self, name):
        t = self.text(name)
        return export_io.parse_csv(t) if t else {}

    def samples(self):
        if "samples" not in self._c:
            self._c["samples"] = self.csv("samples.csv")
        return self._c["samples"]


def f(x, nd=1, unit=""):
    return "n/a" if x is None else f"{x:.{nd}f}{unit}"


def where(side, source=None):
    return f"{side}" if source is None else f"{side} {source}"


# ---------------------------------------------------------------- T0
def health_streams(ex):
    h = ex.json("session-health.json")
    out = {}
    if h:
        for b in h.get("boots", []):
            out[b["side"]] = b
    return h, out


def is_still(ex, side):
    """RMS of the gyro (mean removed) over the longest source of this boot, in deg/s; None when there are no samples."""
    s = ex.samples()
    if not s or "side" not in s:
        return None
    m = s["side"] == side
    if not np.any(m):
        return None
    srcs = {k: int(np.sum(m & (s["source"] == k))) for k in set(s["source"][m])}
    use = max(srcs, key=srcs.get)
    m &= s["source"] == use
    g = np.stack([s["gx_radps"][m], s["gy_radps"][m], s["gz_radps"][m]], 1) * 180.0 / math.pi
    return float(np.sqrt(np.mean(np.sum((g - g.mean(0)) ** 2, 1))))


def t0(ex, explicit):
    rows = []
    T = "T0"
    h, boots = health_streams(ex)
    if h is None:
        rows.append(Row(T, "health verdict", "n/a", "usable", NA, "session-health.json is not in the export"))
    for side in EXPECTED_SIDES:
        b = boots.get(side)
        if b is None:
            if h is not None:
                rows.append(Row(T, f"health verdict {side}", "no data", "usable", FAIL, f"boot {side} has no streams in session-health.json"))
            continue
        v = b["verdict"]
        st = PASS if v == "usable" else (WARN if v == "marginal" else FAIL)
        rows.append(Row(T, f"health verdict {side}", v, "usable", st, "; ".join(b.get("reasons", [])) if st != PASS else ""))
        streams = {s["source"]: s for s in b["streams"]}
        for src, nominal in (("flash", FLASH_RATE_HZ), ("live", LIVE_RATE_HZ)):
            s = streams.get(src)
            chk = f"{src} rate {side}"
            line = f"{nominal:.0f} +/- {RATE_TOL_HZ:.0f} Hz"
            if s is None:
                rows.append(Row(T, chk, "no stream", line, FAIL, f"no {src} samples for boot {side}"))
                continue
            d = s["rate_hz"] - nominal
            rows.append(Row(T, chk, f"{s['rate_hz']:.2f} Hz ({d:+.2f})", line, PASS if abs(d) <= RATE_TOL_HZ else FAIL, num=s["rate_hz"]))
        longest = {src: s["longest_gap_ms"] for src, s in streams.items()}
        if longest:
            bad = {src: ms for src, ms in longest.items() if ms > GAP_LONG_MS}
            val = ", ".join(f"{src} {ms:.0f} ms" for src, ms in sorted(longest.items())) + " longest"
            why = ""
            if bad:
                why = "gap over the line in " + ", ".join(sorted(bad)) + "; "
            cap = ex.json("session.json")
            drops = [c["dropped_samples"] for c in (cap or {}).get("captures", []) if c.get("side") == side]
            if drops and drops[0]:
                why += f"firmware reported {drops[0]} dropped flash samples; "
            pct = ", ".join(f"{src} {s['gap_percent']:.2f} %" for src, s in sorted(streams.items()))
            rows.append(Row(T, f"gaps {side}", val, f"longest <= {GAP_LONG_MS:.0f} ms", FAIL if bad else PASS,
                            (why + f"gap share {pct}").strip("; ") if bad else ""))
        # clock slope
        slopes = [s["clock_slope_ppm"] for s in streams.values() if s.get("clock_slope_ppm") is not None]
        fit = ex.json("clock-sync-fit.json") or {}
        fd = None
        if fit.get(side, {}).get("epochs"):
            fd = fit[side]["epochs"][-1].get("drift_ppm")
        slope, src = (slopes[0], "health live stream") if slopes else (fd, "clock-sync fit")
        if slope is None:
            rows.append(Row(T, f"clock slope {side}", "n/a", f"|slope| < {CLOCK_SLOPE_PPM:.0f} ppm", NA, "neither the health report nor the clock-sync fit has a slope"))
        else:
            rows.append(Row(T, f"clock slope {side}", f"{slope:+.1f} ppm ({src})", f"|slope| < {CLOCK_SLOPE_PPM:.0f} ppm",
                            PASS if abs(slope) < CLOCK_SLOPE_PPM else FAIL, num=slope))
        # one clock epoch
        ep = fit.get(side, {}).get("epochs")
        if ep is not None:
            unc = fit[side].get("clock_uncertain_stretches", [])
            rows.append(Row(T, f"single clock epoch {side}", f"{len(ep)} epoch(s), {len(unc)} uncertain stretch(es)", "1 epoch",
                            PASS if len(ep) == 1 else FAIL,
                            "" if len(ep) == 1 else "the boot's clock restarted or jumped during the session; offsets are only valid per epoch"))
    # ping round trips
    cs = ex.csv("clock-sync.csv")
    for side in EXPECTED_SIDES:
        if cs and "side" in cs and len(cs["side"]):
            m = (cs["side"] == side) & (cs["accepted"] == "true") if "accepted" in cs else cs["side"] == side
            n = int(np.sum(m))
            allm = int(np.sum(cs["side"] == side))
            if n:
                rtt = cs["rtt_ns"][m] * 1e-6
                rows.append(Row(T, f"ping round trips {side}", f"{n} accepted of {allm}, median {np.median(rtt):.1f} ms, best {rtt.min():.1f} ms", ">= 1 accepted pair", PASS, num=float(np.median(rtt))))
                continue
        rows.append(Row(T, f"ping round trips {side}", "none", ">= 1 accepted pair", FAIL, f"no accepted ping pair for boot {side} in clock-sync.csv"))
    # still-noise, only for a still recording
    for side in EXPECTED_SIDES:
        rms = is_still(ex, side)
        chk = f"gyro bias drift and noise {side}"
        if rms is None:
            rows.append(Row(T, chk, "no samples", "report only", FAIL if explicit else NA, f"boot {side} has no samples in samples.csv"))
            continue
        if rms > STILL_GYRO_RMS_DPS:
            rows.append(Row(T, chk, f"gyro RMS {rms:.1f} dps", f"still: RMS < {STILL_GYRO_RMS_DPS:.0f} dps", WARN if explicit else NA,
                            "this recording is not still, so bias drift and noise figures would be meaningless (use a T0 soak)"))
            continue
        try:
            res = still_noise_report.analyse_export(ex.path)
            b = res["boots"].get(side, {})
        except SystemExit as e:
            rows.append(Row(T, chk, "n/a", "report only", NA, str(e)))
            continue
        if "error" in b or not b:
            rows.append(Row(T, chk, "n/a", "report only", NA, b.get("error", "no result")))
            continue
        dr = max(abs(v) for v in b["gyro_bias_drift_dps_per_min"])
        bias = max(abs(v) for v in b["gyro_bias_dps"])
        rows.append(Row(T, f"gyro bias and drift {side}", f"bias <= {bias:.3f} dps (worst axis), drift <= {dr:.4f} dps/min over {b['duration_s']:.0f} s",
                        "report only (no protocol line)", INFO, "temperature is not in the export, so no bias-versus-temperature figure", num=dr))
        if b.get("allan"):
            dens = np.mean([v["gyro"]["white_density_per_rthz"] for v in b["allan"]["axes"].values()])
            rows.append(Row(T, f"gyro white noise {side}", f"{dens:.1f} mdps/rtHz (mean of axes)", "compare with the datasheet by hand", INFO, still_noise_report.DATASHEET_NOTE, num=float(dens)))
    rows.append(Row(T, "stalls (acquisition JSON)", "n/a", "stalls == 0", NA, "needs /api/v1/acquisition, not in the export: check by hand"))
    rows.append(Row(T, "battery vs multimeter", "n/a", "within about 50 mV", NA, "needs the multimeter reading and /api/v1/battery, not in the export: check by hand"))
    rows.append(Row(T, "temperature range", "n/a", "report only", NA, "neither the live frame nor the flash record carries a temperature"))
    return rows


# ---------------------------------------------------------------- T1
def t1(ex, explicit):
    T = "T1"
    g = ex.json("gesture-sync.json")
    if g is None:
        return [Row(T, "gesture sync status", "n/a", "measured", NA, "gesture-sync.json is not in the export")]
    rows = []
    status = g.get("status")
    why = g.get("reason") or ""
    if g.get("reject"):
        why += f" (reject: {g['reject']})"
    rows.append(Row(T, "gesture sync status", status, "measured", PASS if status == "measured" else FAIL, "" if status == "measured" else why.strip()))
    ping = g.get("ping") or {}
    if status == "measured":
        s, e = g.get("start") or {}, g.get("end") or {}
        rows.append(Row(T, "stomp offset (right minus left)", f"{g['offset_s'] * 1e3:.2f} ms (start {f(s.get('offset_s', float('nan')) * 1e3, 2)}, end {f(e.get('offset_s', float('nan')) * 1e3, 2)}), drift {g.get('drift_ppm', float('nan')):+.1f} ppm",
                        "report only", INFO, f"start correlation {s.get('correlation', float('nan')):.3f}, end {e.get('correlation', float('nan')):.3f}"))
    d = g.get("stomp_minus_ping_ms")
    line = f"|stomp - ping| <= {STOMP_PING_MS:.0f} ms"
    if status != "measured":
        rows.append(Row(T, "stomp minus ping", "n/a", line, NA, f"no stomp offset to compare (status {status})"))
    elif d is None:
        rows.append(Row(T, "stomp minus ping", "n/a", line, NA, "no ping offset to compare with (" + str(ping.get("reason") or "no ping pairs in the export") + "); the stomp offset itself is measured"))
    else:
        inside = abs(d) <= STOMP_PING_MS
        why = (f"inside the line by {STOMP_PING_MS - abs(d):.1f} ms" if inside else
               f"over the line by {abs(d) - STOMP_PING_MS:.1f} ms: a steady disagreement of this kind is the half-connection-interval ping bias; record it with the connection interval")
        rows.append(Row(T, "stomp minus ping", f"{d:+.2f} ms (ping uncertainty {f(ping.get('uncertainty_s', float('nan')) * 1e3, 1)} ms)", line, PASS if inside else FAIL, why, num=d))
    return rows


# ---------------------------------------------------------------- T2
def t2(ex, explicit):
    T = "T2"
    miss_st = FAIL if explicit else NA
    try:
        r = mag_heading_check.analyse(ex.path)
    except SystemExit as e:
        return [Row(T, "magnetometer field magnitude spread", "n/a", f"<= {MAG_SPREAD_OK_PCT:.0f} % (sanity {MAG_SPREAD_WARN_PCT:.0f} %)", miss_st,
                    "no usable magnetometer data: " + str(e)),
                Row(T, "magnetometer heading vs gyro yaw", "n/a", f"RMS <= {MAG_HEADING_OK_DEG:.0f} deg", miss_st, "no usable magnetometer data")]
    turns = r["gyro_rotation_turns"]["total_absolute"]
    on_spot = turns >= mag_heading_check.MIN_ROTATION_TURNS
    rows = []
    sp = r["magnitude_raw_gauss"]["spread_percent_of_mean"]
    line = f"<= {MAG_SPREAD_OK_PCT:.0f} % (sanity {MAG_SPREAD_WARN_PCT:.0f} %)"
    val = f"{sp:.1f} % of mean {r['magnitude_raw_gauss']['mean']:.3f} G (corrected in-plane {r['magnitude_corrected']['spread_percent_of_mean']:.1f} %)"
    if not on_spot and not explicit:
        rows.append(Row(T, "magnetometer field magnitude spread", val, line, NA, f"only {turns:.2f} turns of gyro yaw: not a level turn on the spot, so the spread says nothing about the field"))
    else:
        st = PASS if sp <= MAG_SPREAD_OK_PCT else (WARN if sp <= MAG_SPREAD_WARN_PCT else FAIL)
        rows.append(Row(T, "magnetometer field magnitude spread", val, line, st, "" if on_spot else f"only {turns:.2f} turns of gyro yaw: not a turn on the spot", num=sp))
    hl = f"RMS <= {MAG_HEADING_OK_DEG:.0f} deg (flag {MAG_HEADING_WARN_DEG:.0f})"
    if not on_spot:
        rows.append(Row(T, "magnetometer heading vs gyro yaw", f"gyro yaw total {turns:.2f} turns", hl, FAIL if explicit else NA,
                        "the recording has no turn on the spot (needs at least " + f"{mag_heading_check.MIN_ROTATION_TURNS:.1f} turns), so heading cannot be compared"))
    else:
        rms = r["yaw_difference_deg"]["rms_offset_and_linear_trend_removed"]
        st = PASS if rms <= MAG_HEADING_OK_DEG else (WARN if rms <= MAG_HEADING_WARN_DEG else FAIL)
        rows.append(Row(T, "magnetometer heading vs gyro yaw", f"RMS {rms:.1f} deg after offset and trend; mag {r['mag_rotation_turns']['net_ccw_positive']:+.2f} turns vs gyro {r['gyro_rotation_turns']['net_ccw_positive']:+.2f}", hl, st,
                        "; ".join(r["flags"]), num=rms))
    return rows


# ---------------------------------------------------------------- T3
def tm_summary(ex):
    a = ex.json("analysis-summary.json")
    return None if not a else a.get("turn_metrics")


def usable_share(tm):
    pb = tm.get("per_boot", {})
    c = sum(b.get("candidates", 0) for b in pb.values())
    u = sum(b.get("usable", 0) for b in pb.values())
    return (u / c if c else None), c


def mean_roughness(tm):
    v = [b["roughness"]["mean"] for b in tm.get("per_boot", {}).values() if b.get("roughness")]
    return float(np.mean(v)) if v else None


def t3(ex, explicit, compare=None):
    T = "T3"
    tm = tm_summary(ex)
    rows = []
    if tm is None:
        rows.append(Row(T, "turn candidates", "n/a", "", NA, "analysis-summary.json has no turn_metrics block"))
    else:
        pb = tm.get("per_boot", {})
        for side in EXPECTED_SIDES:
            b = pb.get(side)
            n = (b or {}).get("candidates", 0)
            line = f">= {MIN_CANDIDATES_OK} candidates"
            st = PASS if n >= MIN_CANDIDATES_OK else (WARN if n > 0 else FAIL)
            rows.append(Row(T, f"candidates {side}", f"{n} candidates, {(b or {}).get('usable', 0)} usable", line, st,
                            "" if n else f"boot {side} produced no turn candidates (missing boot or no roll excursions)", num=n))
        sh, c = usable_share(tm)
        rows.append(Row(T, "usable share", "n/a" if sh is None else f"{sh * 100:.0f} % of {c}", "direction only (rough lower than smooth)", INFO if sh is not None else NA,
                        "" if sh is not None else "no candidates"))
        for side in EXPECTED_SIDES:
            b = pb.get(side)
            if not b or not b.get("candidates"):
                continue
            parts = []
            for k in ("roughness", "peak_deg", "duration_ms", "early_roll_rate_dps", "edge_hold", "build_ms", "peak_phase"):
                d = b.get(k)
                if d and d.get("mean") is not None:
                    parts.append(f"{k} {d['mean']:.3g} (sd {d['sd']:.3g})" if d.get("sd") is not None else f"{k} {d['mean']:.3g}")
            rows.append(Row(T, f"per-boot spread {side}", "; ".join(parts), "record before choosing any threshold", INFO))
        ba = tm.get("boot_agreement") or {}
        if ba.get("pairs_with_data"):
            rows.append(Row(T, "left-right agreement", f"{ba['pairs_with_data']} pairs, derivative similarity {ba['mean_derivative_similarity']:.3f}, roll correlation {ba['mean_roll_correlation']:.3f}, wedge fraction {ba['mean_wedge_fraction']:.3f}",
                            "direction only", INFO))
        else:
            rows.append(Row(T, "left-right agreement", "no pairs", "direction only", NA, "needs candidates from both boots with no gaps over 100 ms"))
    if compare:
        rows += t3_compare(compare[0], compare[1])
    else:
        rows.append(Row(T, "smooth vs rough direction", "n/a", "rough shows lower usable share or higher roughness; lower similarity; larger wedge fraction", NA,
                        "needs two sessions: rerun with --compare SMOOTH.zip ROUGH.zip"))
    return rows


def t3_compare(smooth_path, rough_path):
    T = "T3"
    sm, ro = tm_summary(Export(smooth_path)), tm_summary(Export(rough_path))
    tag = f"(smooth {os.path.basename(smooth_path)}, rough {os.path.basename(rough_path)})"
    if sm is None or ro is None:
        return [Row(T, "smooth vs rough direction", "n/a", "", NA, "one of the sessions has no turn_metrics block " + tag)]
    rows = []
    ss, _ = usable_share(sm)
    rs, _ = usable_share(ro)
    rgs, rgr = mean_roughness(sm), mean_roughness(ro)
    line = "rough: lower usable share or higher roughness"
    if ss is None or rs is None or rgs is None or rgr is None:
        rows.append(Row(T, "rough vs smooth: usable share or roughness", "n/a", line, NA, "no candidates in one session " + tag))
    else:
        better = rs < ss or rgr > rgs
        worse = rs > ss and rgr < rgs
        st = PASS if better else (FAIL if worse else WARN)
        rows.append(Row(T, "rough vs smooth: usable share or roughness", f"usable {ss * 100:.0f} % -> {rs * 100:.0f} %, roughness {rgs:.3f} -> {rgr:.3f}", line, st,
                        "" if better else "no difference in either" if st == WARN else "rough looks smoother than smooth", num=rgr - rgs))
    sa, ra = sm.get("boot_agreement") or {}, ro.get("boot_agreement") or {}
    a, b = sa.get("mean_derivative_similarity"), ra.get("mean_derivative_similarity")
    line = "rough: lower left-right derivative similarity"
    if a is None or b is None:
        rows.append(Row(T, "rough vs smooth: derivative similarity", "n/a", line, NA, "one session has no left-right pairs " + tag))
    else:
        rows.append(Row(T, "rough vs smooth: derivative similarity", f"{a:.3f} -> {b:.3f}", line, PASS if b < a else (WARN if b == a else FAIL), num=b - a))
    a, b = sa.get("mean_wedge_fraction"), ra.get("mean_wedge_fraction")
    line = "rough: larger wedge fraction"
    if a is None or b is None:
        rows.append(Row(T, "rough vs smooth: wedge fraction", "n/a", line, NA, "one session has no left-right pairs " + tag))
    else:
        rows.append(Row(T, "rough vs smooth: wedge fraction", f"{a:.3f} -> {b:.3f}", line, PASS if b > a else (WARN if b == a else FAIL),
                        "no difference: the wedge fraction does not separate these two sessions" if b == a else "", num=b - a))
    return rows


# ---------------------------------------------------------------- T4
def t4(ex, explicit):
    T = "T4"
    txt = ex.text("phone-motion.csv")
    ph = ex.csv("phone-motion.csv")
    n = len(ph.get("session_elapsed_ms", [])) if ph else 0
    line_rows = f"{PHONE_ROWS_PER_S[0]:.0f} to {PHONE_ROWS_PER_S[1]:.0f} rows/s"
    rows = []
    off = None
    if txt:
        m = re.search(r"offset_ms=(-?\d+(?:\.\d+)?)", txt)
        if m:
            off = float(m.group(1))
    line_off = f"|offset| <= {PHONE_OFFSET_OK_MS:.0f} ms"
    if off is None:
        rows.append(Row(T, "phone clock offset", "not recorded", line_off, FAIL if txt is not None else NA,
                        "phone-motion.csv carries no phone_clock_check offset, so the sensor clock base is unknown" if txt is not None else "phone-motion.csv is not in the export"))
    else:
        st = PASS if abs(off) <= PHONE_OFFSET_OK_MS else (WARN if abs(off) <= PHONE_OFFSET_WARN_MS else FAIL)
        rows.append(Row(T, "phone clock offset", f"{off:+.3f} ms", line_off, st, num=off))
    if n < 2:
        rows.append(Row(T, "phone rows per second", "0 rows" if txt is not None else "n/a", line_rows, FAIL if txt is not None else NA,
                        "no phone motion rows in the export" if txt is not None else "phone-motion.csv is not in the export"))
        rows.append(Row(T, "phone largest gap", "n/a", f"< {PHONE_GAP_WARN_MS:.0f} ms", NA, "no phone rows"))
    else:
        t = ph["session_elapsed_ms"]
        span = (t.max() - t.min()) / 1e3
        rate = (n - 1) / span if span > 0 else float("nan")
        lo, hi = PHONE_ROWS_PER_S
        wl, wh = PHONE_WARN_ROWS_PER_S
        st = PASS if lo <= rate <= hi else (WARN if wl <= rate <= wh else FAIL)
        rows.append(Row(T, "phone rows per second", f"{rate:.1f} rows/s ({n} rows over {span:.1f} s)", line_rows, st, num=rate))
        gap = float(np.max(np.diff(np.sort(t))))
        st = PASS if gap < PHONE_GAP_WARN_MS else (WARN if gap < PHONE_GAP_FAIL_MS else FAIL)
        rows.append(Row(T, "phone largest gap", f"{gap:.0f} ms", f"< {PHONE_GAP_WARN_MS:.0f} ms (fail at {PHONE_GAP_FAIL_MS:.0f})", st, num=gap))
    rows.append(Row(T, "phone storage error", "n/a", "none", NA, "a storage error is not recorded in the export: check by hand"))
    rows.append(Row(T, "phone battery drain over 30 min", "n/a", "note only", NA, "needs the phone battery readings, not in the export: note by hand"))
    rows.append(Row(T, "phone gyro vs boot roll rate", "n/a", "observation only", NA, "observation only: no torso metric is built, so the tool computes nothing here"))
    return rows


# ---------------------------------------------------------------- T5
def t5(ex, explicit):
    T = "T5"
    s = ex.samples()
    sess = ex.json("session.json") or {}
    caps = {c.get("side"): c for c in sess.get("captures", [])}
    _, boots = health_streams(ex)
    rows = []
    for side in EXPECTED_SIDES:
        n = 0
        if s and "side" in s:
            n = int(np.sum((s["side"] == side) & (s["source"] == "flash")))
        c = caps.get(side)
        if n == 0:
            rows.append(Row(T, f"flash download {side}", "no flash samples", "flash present and validated", FAIL,
                            f"boot {side} has no flash rows in samples.csv" + (f" (capture state {c['state']})" if c else "")))
        else:
            ok = c is not None and c.get("state") in ("erased", "verified")
            why = "" if ok else ("no capture record in session.json" if c is None else f"capture state {c['state']}, {c.get('error') or 'no error text'}")
            exp = c.get("expected_samples") if c else None
            rows.append(Row(T, f"flash download {side}", f"{n} flash samples" + (f" of {exp} expected" if exp is not None else "") + (f", state {c['state']}" if c else ""),
                            "flash present, state erased or verified", PASS if ok else FAIL, why))
        fl = {x["source"]: x for x in boots.get(side, {}).get("streams", [])}.get("flash")
        if fl is None:
            rows.append(Row(T, f"flash source health {side}", "no flash stream", "usable", FAIL if n == 0 else NA, "no flash stream in session-health.json"))
        else:
            rows.append(Row(T, f"flash source health {side}", fl["verdict"], "usable", PASS if fl["verdict"] == "usable" else (WARN if fl["verdict"] == "marginal" else FAIL),
                            "; ".join(fl.get("reasons", []))))
    g = ex.json("gesture-sync.json")
    if g is None:
        rows.append(Row(T, "stomp sync measured", "n/a", "measured", NA, "gesture-sync.json is not in the export"))
    else:
        rows.append(Row(T, "stomp sync measured", g.get("status"), "measured", PASS if g.get("status") == "measured" else FAIL, g.get("reason") or ""))
        if g.get("status") == "measured":
            rows.append(Row(T, "end stomp found", "found in both boots", "found", PASS if g.get("end") else FAIL, "" if g.get("end") else "no end stomp window in gesture-sync.json"))
        else:
            rows.append(Row(T, "end stomp found", "not measured", "found", NA, f"sync status {g.get('status')}"))
    for chk in ("run saved", "boots returned to their starting mode", "run GPS track", "no data loss on the phone"):
        rows.append(Row(T, chk, "n/a", "yes", NA, "not visible in a session export: check on the phone"))
    return rows


# ---------------------------------------------------------------- T6
def t6(ex, explicit):
    T = "T6"
    a = ex.json("analysis-summary.json")
    rows = []
    for side in EXPECTED_SIDES:
        ms = (a or {}).get(side, {}).get("mount_slip") if a else None
        if not ms:
            rows.append(Row(T, f"mount slip {side}", "n/a", "no slip flagged", NA, "analysis-summary.json has no mount_slip block for this boot"))
        elif not ms.get("available"):
            rows.append(Row(T, f"mount slip {side}", "unavailable", "no slip flagged", NA, ms.get("reason") or "mount-slip check not available"))
        else:
            fl = ms.get("flagged_windows", 0)
            rows.append(Row(T, f"mount slip {side}", f"{fl} of {ms.get('windows_checked')} windows flagged, max axis deviation {ms.get('max_axis_deviation_deg', float('nan')):.2f} deg, max force shift {ms.get('max_force_shift_g', float('nan')):.3f} g",
                            "no slip flagged", PASS if fl == 0 else FAIL,
                            "" if fl == 0 else "slip flagged: retighten and repeat the roll drill", num=fl))
    return rows


FUNCS = {"T0": t0, "T1": t1, "T2": t2, "T4": t4, "T5": t5, "T6": t6}


def run_checks(path, trials, compare=None):
    ex = Export(path)
    explicit = len(trials) == 1
    rows = []
    for t in trials:
        try:
            if t == "T3":
                rows += t3(ex, explicit, compare)
            else:
                rows += FUNCS[t](ex, explicit)
        except Exception as e:   # a malformed export must not hide the other trials
            rows.append(Row(t, "tool error", type(e).__name__, "", WARN, f"{e} (check the export by hand)"))
    return rows


# ---------------------------------------------------------------- output
def table(rows, width=44):
    hdr = ("Trial", "Check", "Measured", "Starting pass line", "Verdict", "Reason")
    data = [hdr] + [(r.trial, r.check, r.value, r.line, r.status, r.why) for r in rows]
    cw = [max(len(str(d[i])) for d in data) for i in range(5)]
    cw = [min(c, w) for c, w in zip(cw, (5, 38, 70, 48, 7))]
    out = []
    for i, d in enumerate(data):
        cells = []
        for j in range(5):
            s = str(d[j])
            cells.append((s if len(s) <= cw[j] else s[:cw[j] - 3] + "...").ljust(cw[j]))
        line = " | ".join(cells)
        if d[5]:
            line += " | " + d[5]
        out.append(line.rstrip())
        if i == 0:
            out.append("-+-".join("-" * c for c in cw))
    return "\n".join(out)


def full_detail(rows):
    """Untruncated value and line text for rows whose table cells were cut."""
    out = []
    for r in rows:
        if len(r.value) > 70 or len(r.line) > 48 or len(r.check) > 38:
            out.append(f"  {r.trial} {r.check}: {r.value} [{r.line}]")
    return out


def counts(rows):
    c = {PASS: 0, FAIL: 0, WARN: 0, NA: 0, INFO: 0}
    for r in rows:
        c[r.status] += 1
    return c


def cmd_report(a):
    trials = ORDER if a.trial == "all" else [a.trial]
    rows = run_checks(a.export, trials, a.compare)
    print(f"Trial report: {a.export}   (starting values; experimental; trials: {', '.join(trials)})\n")
    print(table(rows))
    det = full_detail(rows)
    if det:
        print("\nFull text of cells shortened above:")
        print("\n".join(det))
    c = counts(rows)
    print("\n" + "  ".join(f"{k} {v}" for k, v in c.items()))
    if a.json:
        with open(a.json, "w") as fh:
            json.dump({"input": a.export, "trials": trials, "counts": c, "rows": [r.as_dict() for r in rows]}, fh, indent=1)
    return 1 if c[FAIL] else 0


# ---------------------------------------------------------------- regression over the dress-rehearsal ZIPs
class Expect:
    def __init__(self, zipname, trial, prefix, allowed, why, reason_has=None, check=None, explicit=None):
        self.zipname, self.trial, self.prefix, self.allowed, self.why = zipname, trial, prefix, set(allowed), why
        self.reason_has, self.check, self.explicit = reason_has, check, explicit   # explicit: run only this trial (one-trial rules)


def build_expectations():
    E = []
    ok = (PASS, INFO, WARN, NA)   # "no FAIL"

    def nofail(z, trial, why):
        E.append(Expect(z, trial, "", ok, why))   # empty prefix = every row of that trial

    for z in ("clean.zip", "clean-no-lag.zip"):
        for t in ("T0", "T1", "T3", "T4", "T5", "T6"):
            nofail(z, t, "a clean session has no FAIL in " + t)
    E.append(Expect("clean.zip", "T1", "stomp minus ping", (PASS,), "clean stomp and ping agree"))
    E.append(Expect("clean.zip", "T2", "magnetometer heading", (NA,), "the garden session has no turn on the spot: N/A, not a verdict", explicit=False))
    E.append(Expect("clean.zip", "T0", "gyro bias drift and noise", (NA,), "moving recording: still-noise is N/A under 'all'"))
    E.append(Expect("clean.zip", "T0", "gyro bias drift and noise", (WARN,), "moving recording: still-noise is WARN when T0 is named", explicit=True))
    E.append(Expect("clean.zip", "T3", "rough vs smooth: derivative similarity", (PASS,), "the late right boot lowers left-right similarity (compared with clean-no-lag.zip)"))
    E.append(Expect("clean.zip", "T3", "rough vs smooth: usable share or roughness", ok, "compared with clean-no-lag.zip: not a FAIL"))
    E.append(Expect("clean.zip", "T3", "rough vs smooth: wedge fraction", (WARN, PASS), "synthetic wedge fraction is 0 in both: WARN (cannot separate), never FAIL"))
    E.append(Expect("fault-no-end-stomp.zip", "T1", "gesture sync status", (FAIL,), "no end stomp refuses the sync", reason_has="no clear stomp"))
    E.append(Expect("fault-no-end-stomp.zip", "T1", "stomp minus ping", (NA,), "nothing to compare when the sync is refused"))
    E.append(Expect("fault-no-end-stomp.zip", "T5", "stomp sync measured", (FAIL,), "the run flow needs the stomp"))
    E.append(Expect("fault-stomp-one-boot.zip", "T1", "gesture sync status", (FAIL,), "a stomp on one boot only is refused", reason_has="stomp"))
    E.append(Expect("fault-no-phone.zip", "T4", "phone rows per second", (FAIL,), "no phone rows", reason_has="no phone"))
    E.append(Expect("fault-no-phone.zip", "T4", "phone clock offset", (FAIL,), "no phone clock check recorded", reason_has="phone_clock_check"))
    E.append(Expect("fault-no-pings.zip", "T1", "stomp minus ping", (NA,), "no pings: the comparison is N/A, not FAIL", reason_has="no ping"))
    E.append(Expect("fault-no-pings.zip", "T1", "gesture sync status", (PASS,), "the stomp still measures without pings"))
    E.append(Expect("fault-no-pings.zip", "T0", "ping round trips", (FAIL,), "T0 wants ping round trips recorded"))
    E.append(Expect("fault-clock-reset.zip", "T1", "gesture sync status", (FAIL,), "a clock reset refuses the stomp sync", reason_has="clock"))
    E.append(Expect("fault-clock-reset.zip", "T1", "stomp minus ping", (NA,), "no stomp offset when the clock reset"))
    E.append(Expect("fault-clock-reset.zip", "T0", "single clock epoch L", (FAIL,), "the left boot's clock has two epochs", reason_has="restarted"))
    E.append(Expect("fault-no-right-boot.zip", "T1", "gesture sync status", (FAIL,), "needs both boots", reason_has="only the left boot"))
    E.append(Expect("fault-no-right-boot.zip", "T0", "health verdict R", (FAIL,), "the right boot has no data"))
    E.append(Expect("fault-no-right-boot.zip", "T3", "candidates R", (FAIL,), "no candidates for the missing boot"))
    E.append(Expect("fault-no-right-boot.zip", "T6", "mount slip R", (NA,), "no calibration for the missing boot", reason_has="calibration"))
    E.append(Expect("fault-no-right-boot.zip", "T5", "flash download R", (FAIL,), "no flash for the missing boot"))
    E.append(Expect("fault-heavy-clipping.zip", "T0", "health verdict", (FAIL, WARN), "heavy clipping lowers both boots' health verdict", reason_has="full scale"))
    E.append(Expect("fault-heavy-clipping.zip", "T5", "flash source health", (FAIL, WARN), "the flash source health is poor"))
    E.append(Expect("fault-asymmetric-ping-bias.zip", "T1", "stomp minus ping", (PASS, FAIL), "a bias shows up in the comparison and the reason says which side of the line", reason_has="the line"))
    E.append(Expect("fault-clock-wraps.zip", "T0", "", ok, "a wrap in the boot clock is handled: no FAIL"))
    E.append(Expect("fault-clock-wraps.zip", "T1", "", ok, "a wrap in the boot clock is handled: no FAIL"))
    E.append(Expect("fault-flash-gap.zip", "T0", "gaps R", (FAIL,), "a 1.45 s flash gap is over the 100 ms line", reason_has="flash"))
    E.append(Expect("fault-flash-gap.zip", "T0", "gaps L", (PASS,), "boot L is clean"))
    E.append(Expect("fault-flash-gap.zip", "T1", "gesture sync status", (PASS,), "live samples cover the flash gap"))
    E.append(Expect("fault-flash-and-live-gap.zip", "T0", "gaps R", (FAIL,), "gap in both flash and live", reason_has="live"))
    E.append(Expect("fault-live-only.zip", "T0", "flash rate", (FAIL,), "no flash stream", reason_has="no flash"))
    E.append(Expect("fault-live-only.zip", "T5", "flash download", (FAIL,), "no flash for either boot"))
    E.append(Expect("fault-live-only.zip", "T1", "gesture sync status", (FAIL,), "the stomp is refused on the aliased 52 Hz stream", reason_has="too different"))
    E.append(Expect("fault-no-magnetometer.zip", "T2", "magnetometer field magnitude spread", (NA,), "no magnetometer data: N/A under 'all'"))
    E.append(Expect("fault-no-magnetometer.zip", "T2", "magnetometer field magnitude spread", (FAIL,), "no magnetometer data is a FAIL when T2 is named", explicit=True))
    for z in ("fault-no-magnetometer.zip", "fault-flash-gap.zip", "fault-flash-and-live-gap.zip"):
        nofail(z, "T6", "slip is unaffected")
    return E


def cmd_regress(d):
    zips = {os.path.basename(p): p for p in glob.glob(os.path.join(d, "*.zip"))}
    expected_files = {"clean.zip", "clean-no-lag.zip"}
    cache = {}

    def rows_for(name, explicit_trial=None):
        key = (name, explicit_trial)
        if key not in cache:
            p = zips[name]
            if explicit_trial:
                cache[key] = run_checks(p, [explicit_trial])
            else:
                cmp_ = (zips["clean-no-lag.zip"], p) if name == "clean.zip" and "clean-no-lag.zip" in zips else None
                rows = run_checks(p, ORDER, cmp_)
                cache[key] = rows
        return cache[key]

    res = []
    for e in build_expectations():
        label = f"{e.zipname} {e.trial} {e.prefix or '(all rows)'}" + (" [T-only run]" if e.explicit else "")
        if e.zipname not in zips:
            res.append((label, "MISSING", "zip not found in " + d, False))
            continue
        rows = rows_for(e.zipname, e.trial if e.explicit else None)
        sel = [r for r in rows if r.trial == e.trial and r.check.startswith(e.prefix)]
        if not sel:
            res.append((label, "NO ROW", "no check starts with that text", False))
            continue
        bad = [r for r in sel if r.status not in e.allowed]
        if not bad and e.reason_has:
            bad = [r for r in sel if e.reason_has.lower() not in (r.why + " " + r.value).lower()]
            if bad:
                res.append((label, "REASON", f"{bad[0].check}: reason lacks '{e.reason_has}': '{bad[0].why}'", False))
                continue
        if bad:
            res.append((label, "WRONG", f"{bad[0].check} is {bad[0].status} ({bad[0].value}; {bad[0].why}); expected {'/'.join(sorted(e.allowed))}", False))
        else:
            res.append((label, "ok", "/".join(sorted({r.status for r in sel})) + ": " + e.why, True))
    # numeric: the biased ping must disagree more than the clean one
    try:
        cl = [r for r in rows_for("clean.zip") if r.check == "stomp minus ping"][0]
        bi = [r for r in rows_for("fault-asymmetric-ping-bias.zip") if r.check == "stomp minus ping"][0]
        good = abs(bi.num) > abs(cl.num)
        res.append(("fault-asymmetric-ping-bias.zip vs clean.zip |stomp - ping|", "ok" if good else "WRONG",
                    f"{bi.num:+.2f} ms vs {cl.num:+.2f} ms; line {STOMP_PING_MS:.0f} ms ({bi.status})", good))
    except (KeyError, IndexError, TypeError):
        res.append(("fault-asymmetric-ping-bias.zip vs clean.zip |stomp - ping|", "MISSING", "needs both zips", False))
    # print
    w = max(len(r[0]) for r in res)
    print(f"Regression over {len(zips)} ZIP(s) in {d}")
    print("T3 direction checks use clean-no-lag.zip as the smooth session and clean.zip as the rough one (right boot 60 ms late).\n")
    for lab, st, msg, good in res:
        print(f"{lab.ljust(w)} | {st.ljust(7)} | {msg}")
    extra = sorted(set(zips) - {e.zipname for e in build_expectations()})
    if extra:
        print("\nZIPs with no expectation (just reported): " + ", ".join(extra))
    print("\nPer-ZIP verdict counts (trial all):")
    for name in sorted(zips):
        c = counts(rows_for(name))
        print(f"  {name.ljust(36)} " + "  ".join(f"{k} {v}" for k, v in c.items()))
    nbad = sum(1 for r in res if not r[3])
    print(f"\n{len(res) - nbad} of {len(res)} expectations met" + ("" if not nbad else f"; {nbad} NOT met"))
    return 1 if nbad else 0


def main():
    ap = argparse.ArgumentParser(description="Judge a garden trial from a session export.")
    ap.add_argument("export", nargs="?")
    ap.add_argument("--trial", default="all", choices=ORDER + ["all"])
    ap.add_argument("--compare", nargs=2, metavar=("SMOOTH.zip", "ROUGH.zip"), help="T3 direction checks between a smooth and a rough session")
    ap.add_argument("--json")
    ap.add_argument("--regress", metavar="DIR", help="run over the dress-rehearsal ZIPs in DIR and assert the expected verdicts")
    a = ap.parse_args()
    if a.regress:
        sys.exit(cmd_regress(a.regress))
    if not a.export:
        ap.error("export path required (or --regress DIR)")
    sys.exit(cmd_report(a))


if __name__ == "__main__":
    main()
