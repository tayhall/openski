"""Judge a garden trial from one session export: one command, one table. Numpy only (no scipy).

Usage:
  python tools/analysis/trial_report.py EXPORT.zip [--trial T0|T1|T2|T3|T4|T5|T6|all] [--json out.json]
  python tools/analysis/trial_report.py EXPORT.zip --trial T3 --compare SMOOTH.zip ROUGH.zip
  python tools/analysis/trial_report.py --regress DIR      # every dress-rehearsal ZIP in DIR against its expected verdicts
  python tools/analysis/trial_report.py --selftest [DIR]   # hand-made and mutated exports (needs clean.zip from DIR)

Each row is one check: trial, check, measured value, the starting pass line, and PASS / FAIL / WARN / N/A / INFO.
  PASS, FAIL  the measured value is on the right / wrong side of the starting pass line.
  WARN        measured, but the recording does not suit the check or the value is marginal; look by hand.
  N/A         the export cannot show it (the reason is in the last column). Never turned into a number.
  INFO        a measured figure the protocol gives no pass line for (recorded for the bench-results entry).
Exit code: 0 all fine, 1 when any selected check FAILs (a tool error while judging a trial is a FAIL row), 2 when the
export (or a --compare file) cannot be read at all or the arguments are wrong. Every line below is a STARTING value
from docs/garden-trial-protocol.md, to be revised once there is real data. The framing stays experimental: these
checks judge hardware, clocks and the data path, not on-snow technique.

With --trial all, a check that cannot apply to this kind of recording is N/A: still-noise on a recording that is
moving, magnetometer heading on a recording with no turn on the spot, stomp sync and the run flow (T1, T5) on a
recording with no stomp transient, roll-drill metrics (T3) on a recording with no roll excursions, phone checks (T4)
when the export has no phone-motion.csv at all. With one trial named, the same situation is a FAIL or WARN, because
that trial should have produced the matching recording. (T0 and T6 apply to every recording.)
"""
import argparse
import contextlib
import csv
import glob
import io
import json
import math
import os
import re
import sys
import tempfile
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_io  # noqa: E402
import mag_heading_check  # noqa: E402
import still_noise_report  # noqa: E402

NAN = float("nan")
# ---------------------------------------------------------------- starting values (docs/garden-trial-protocol.md)
FLASH_RATE_HZ, LIVE_RATE_HZ, RATE_TOL_HZ = 104.0, 52.0, 1.0   # T0: "within about 1 Hz of 104 (flash) and 52 (live)"
GAP_LONG_MS = 100.0           # T0 "no gaps": longest interval above this fails (the app's own gaps_over_100ms)
CLOCK_SLOPE_PPM = 100.0       # T0: boot-versus-phone slope under about 100 ppm
STILL_GYRO_RMS_DPS = 2.0      # a recording whose gyro RMS (mean removed) is below this counts as still for the noise figures
STOMP_ACCEL_DEV_G = 0.5       # a sample whose accelerometer magnitude is this far from 1 g counts as a stomp-like transient
STOMP_PING_MS = 15.0          # T1: stomp offset and ping offset agree within about 15 ms
MAG_SPREAD_OK_PCT = mag_heading_check.SPREAD_OK_PCT         # T2: first sanity line, 10 to 15 percent (corrected figure)
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
PASS, FAIL, WARN, NA, INFO = "PASS", "FAIL", "WARN", "N/A", "INFO"


class ExportError(Exception):
    """The export (or one file in it) cannot be read."""


def jnum(d, k):
    """A number read from exported JSON: None, a missing key, a string or a bool all become NaN."""
    v = d.get(k) if isinstance(d, dict) else None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return NAN
    return float(v)


def fin(x):
    return x is not None and isinstance(x, (int, float)) and math.isfinite(x)


def fm(x, nd=1, unit=""):
    return "n/a" if not fin(x) else f"{x:.{nd}f}{unit}"


def sanitize(o):
    """JSON-safe copy: non-finite floats become None, numpy scalars become Python numbers."""
    if isinstance(o, dict):
        return {str(k): sanitize(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [sanitize(v) for v in o]
    if isinstance(o, (np.floating, np.integer)):
        o = o.item()
    if isinstance(o, float) and not math.isfinite(o):
        return None
    return o


_COLLECT = None   # while run_checks judges a trial, every Row created registers here, so rows built before an exception survive it


class Row:
    def __init__(self, trial, check, value, line, status, why="", num=None):
        self.trial, self.check, self.value, self.line, self.status, self.why, self.num = trial, check, value, line, status, why, num
        if _COLLECT is not None:
            _COLLECT.append(self)

    def as_dict(self):
        d = {"trial": self.trial, "check": self.check, "value": self.value, "pass_line": self.line, "status": self.status, "reason": self.why}
        if self.num is not None:
            d["number"] = self.num
        return sanitize(d)


def miss(explicit):
    """Status for data the trial should have produced but the export lacks: FAIL when the trial is named, N/A under 'all'."""
    return FAIL if explicit else NA


class Export:
    """Lazy, cached access to the files of one export."""

    def __init__(self, path):
        self.path = path
        self._c = {}

    def validate(self):
        if not os.path.exists(self.path):
            raise ExportError(f"{self.path}: no such file or directory")
        if os.path.isdir(self.path):
            return
        if not zipfile.is_zipfile(self.path):
            raise ExportError(f"{self.path}: not a ZIP file")
        try:
            with zipfile.ZipFile(self.path) as z:
                bad = z.testzip()
        except (zipfile.BadZipFile, OSError) as e:
            raise ExportError(f"{self.path}: corrupt ZIP ({e})")
        if bad:
            raise ExportError(f"{self.path}: corrupt ZIP (bad checksum in {bad})")

    def text(self, name):
        if name not in self._c:
            try:
                self._c[name] = export_io.read_text(self.path, name)
            except (zipfile.BadZipFile, OSError, UnicodeDecodeError, KeyError) as e:
                raise ExportError(f"{name} cannot be read from {self.path}: {e}")
        return self._c[name]

    def json(self, name):
        t = self.text(name)
        if not t:
            return None
        try:
            return json.loads(t)
        except ValueError as e:
            raise ExportError(f"{name} is not valid JSON ({e})")

    def csv(self, name):
        t = self.text(name)
        if not t:
            return {}
        try:
            return export_io.parse_csv(t)
        except Exception as e:
            raise ExportError(f"{name} is not a readable CSV ({e})")

    def samples(self):
        if "samples" not in self._c:
            self._c["samples"] = self.csv("samples.csv")
        return self._c["samples"]


# ---------------------------------------------------------------- recording kind (decides N/A under 'all')
def has_stomp(ex):
    """True when some boot has a stomp-like accelerometer transient (|a| further than STOMP_ACCEL_DEV_G from 1 g)."""
    s = ex.samples()
    if not s or "ax_mps2" not in s:
        return False
    a = np.linalg.norm(np.stack([s["ax_mps2"], s["ay_mps2"], s["az_mps2"]], 1), axis=1) / 9.80665
    a = a[np.isfinite(a)]
    return bool(len(a) and np.max(np.abs(a - 1.0)) > STOMP_ACCEL_DEV_G)


def candidate_total(tm):
    return int(sum(v for v in (jnum(b, "candidates") for b in (tm or {}).get("per_boot", {}).values()) if fin(v)))


# ---------------------------------------------------------------- T0
def health_streams(ex):
    h = ex.json("session-health.json")
    out = {}
    if h:
        for b in h.get("boots", []):
            out[b["side"]] = b
    return h, out


def gyro_rms_dps(ex, side):
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
    return float(np.sqrt(np.nanmean(np.sum((g - np.nanmean(g, 0)) ** 2, 1))))


def t0(ex, explicit):
    rows = []
    T = "T0"
    h, boots = health_streams(ex)
    fit = ex.json("clock-sync-fit.json")
    fit = fit if isinstance(fit, dict) else {}
    sess = ex.json("session.json")
    for side in EXPECTED_SIDES:
        b = boots.get(side)
        streams = {s["source"]: s for s in (b or {}).get("streams", [])}
        if b is None:
            st = miss(explicit) if h is None else FAIL
            why = "session-health.json is not in the export" if h is None else f"boot {side} has no streams in session-health.json"
            rows.append(Row(T, f"health verdict {side}", "no data", "usable", st, why))
            rows.append(Row(T, f"flash rate {side}", "no stream", f"{FLASH_RATE_HZ:.0f} +/- {RATE_TOL_HZ:.0f} Hz", st, why))
            rows.append(Row(T, f"live rate {side}", "no stream", f"{LIVE_RATE_HZ:.0f} +/- {RATE_TOL_HZ:.0f} Hz", st, why))
            rows.append(Row(T, f"gaps {side}", "n/a", f"longest <= {GAP_LONG_MS:.0f} ms", st, why))
        else:
            v = b.get("verdict")
            st = PASS if v == "usable" else (WARN if v == "marginal" else FAIL)
            rows.append(Row(T, f"health verdict {side}", str(v), "usable", st, "; ".join(b.get("reasons", [])) if st != PASS else ""))
            for src, nominal in (("flash", FLASH_RATE_HZ), ("live", LIVE_RATE_HZ)):
                s = streams.get(src)
                chk = f"{src} rate {side}"
                line = f"{nominal:.0f} +/- {RATE_TOL_HZ:.0f} Hz"
                if s is None:
                    rows.append(Row(T, chk, "no stream", line, FAIL, f"no {src} samples for boot {side}"))
                    continue
                rate = jnum(s, "rate_hz")
                if not fin(rate):
                    rows.append(Row(T, chk, "n/a", line, NA, "rate_hz is null in session-health.json (too few samples to measure)"))
                    continue
                d = rate - nominal
                rows.append(Row(T, chk, f"{rate:.2f} Hz ({d:+.2f})", line, PASS if abs(d) <= RATE_TOL_HZ else FAIL, num=rate))
            longest = {src: jnum(s, "longest_gap_ms") for src, s in streams.items()}
            longest = {src: ms for src, ms in longest.items() if fin(ms)}
            if longest:
                bad = {src: ms for src, ms in longest.items() if ms > GAP_LONG_MS}
                val = ", ".join(f"{src} {ms:.0f} ms" for src, ms in sorted(longest.items())) + " longest"
                why = ""
                if bad:
                    why = "gap over the line in " + ", ".join(sorted(bad)) + "; "
                    drops = [c.get("dropped_samples") for c in (sess or {}).get("captures", []) if c.get("side") == side]
                    if drops and fin(drops[0]) and drops[0]:
                        why += f"firmware reported {int(drops[0])} dropped flash samples; "
                    pct = ", ".join(f"{src} {fm(jnum(s, 'gap_percent'), 2)} %" for src, s in sorted(streams.items()))
                    why += f"gap share {pct}"
                rows.append(Row(T, f"gaps {side}", val, f"longest <= {GAP_LONG_MS:.0f} ms", FAIL if bad else PASS, why.strip("; ")))
            else:
                rows.append(Row(T, f"gaps {side}", "n/a", f"longest <= {GAP_LONG_MS:.0f} ms", NA, "no stream with a measured longest gap"))
        # clock slope: the LIVE stream's health slope, else a FITTED drift (an unfitted placeholder is not a measurement)
        fside = fit.get(side) or {}
        ep = [e for e in (fside.get("epochs") or []) if isinstance(e, dict)]
        live_slope = jnum(streams.get("live"), "clock_slope_ppm")
        slope, src, why = None, "", ""
        if fin(live_slope):
            slope, src = live_slope, "health live stream"
        elif ep and ep[-1].get("drift_fitted") is True and fin(jnum(ep[-1], "drift_ppm")):
            slope, src = jnum(ep[-1], "drift_ppm"), "clock-sync fit"
        else:
            why = "no health slope for the live stream and no fitted clock-sync drift (drift_fitted is not true or there is no fit): a placeholder is not a measurement"
        sline = f"|slope| < {CLOCK_SLOPE_PPM:.0f} ppm"
        if slope is None:
            rows.append(Row(T, f"clock slope {side}", "n/a", sline, NA, why))
        else:
            rows.append(Row(T, f"clock slope {side}", f"{slope:+.1f} ppm ({src})", sline, PASS if abs(slope) < CLOCK_SLOPE_PPM else FAIL, num=slope))
        # one clock epoch (always one row)
        if not fit:
            rows.append(Row(T, f"single clock epoch {side}", "no fit", "1 epoch", miss(explicit), "clock-sync-fit.json is not in the export"))
        elif not ep:
            rows.append(Row(T, f"single clock epoch {side}", "no fit", "1 epoch", NA, str(fside.get("reason") or "clock-sync-fit.json has no epoch for this boot (no accepted ping pairs)")))
        else:
            unc = fside.get("clock_uncertain_stretches") or []
            rows.append(Row(T, f"single clock epoch {side}", f"{len(ep)} epoch(s), {len(unc)} uncertain stretch(es)", "1 epoch", PASS if len(ep) == 1 else FAIL,
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
                rtt = rtt[np.isfinite(rtt)]
                if not len(rtt):
                    rows.append(Row(T, f"ping round trips {side}", f"{n} accepted, no round-trip time", ">= 1 accepted pair", WARN, "rtt_ns is blank for every accepted pair"))
                    continue
                rows.append(Row(T, f"ping round trips {side}", f"{n} accepted of {allm}, median {np.nanmedian(rtt):.1f} ms, best {np.nanmin(rtt):.1f} ms", ">= 1 accepted pair", PASS, num=float(np.nanmedian(rtt))))
                continue
        rows.append(Row(T, f"ping round trips {side}", "none", ">= 1 accepted pair", FAIL, f"no accepted ping pair for boot {side} in clock-sync.csv"))
    # still-noise, only for a still recording
    for side in EXPECTED_SIDES:
        rms = gyro_rms_dps(ex, side)
        chk = f"gyro bias and drift {side}"
        if rms is None:
            rows.append(Row(T, chk, "no samples", "report only", miss(explicit), f"boot {side} has no samples in samples.csv"))
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
        rows.append(Row(T, chk, f"bias <= {bias:.3f} dps (worst axis), drift <= {dr:.4f} dps/min over {b['duration_s']:.0f} s",
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
    if not explicit and not has_stomp(ex):
        return [Row(T, "gesture sync status", "n/a", "measured", NA, f"no stomp-like transient (accelerometer more than {STOMP_ACCEL_DEV_G} g from 1 g) in the recording: not a T1 recording")]
    g = ex.json("gesture-sync.json")
    if g is None:
        return [Row(T, "gesture sync status", "n/a", "measured", miss(explicit), "gesture-sync.json is not in the export")]
    rows = []
    status = g.get("status")
    why = g.get("reason") or ""
    if g.get("reject"):
        why += f" (reject: {g['reject']})"
    rows.append(Row(T, "gesture sync status", str(status), "measured", PASS if status == "measured" else FAIL, "" if status == "measured" else why.strip()))
    ping = g.get("ping") if isinstance(g.get("ping"), dict) else {}
    if status == "measured":
        s, e = g.get("start") or {}, g.get("end") or {}
        rows.append(Row(T, "stomp offset (right minus left)",
                        f"{fm(jnum(g, 'offset_s') * 1e3, 2)} ms (start {fm(jnum(s, 'offset_s') * 1e3, 2)}, end {fm(jnum(e, 'offset_s') * 1e3, 2)}), drift {fm(jnum(g, 'drift_ppm'), 1)} ppm",
                        "report only", INFO, f"start correlation {fm(jnum(s, 'correlation'), 3)}, end {fm(jnum(e, 'correlation'), 3)}"))
    d = jnum(g, "stomp_minus_ping_ms")
    line = f"|stomp - ping| <= {STOMP_PING_MS:.0f} ms"
    if status != "measured":
        rows.append(Row(T, "stomp minus ping", "n/a", line, NA, f"no stomp offset to compare (status {status})"))
    elif not fin(d):
        rows.append(Row(T, "stomp minus ping", "n/a", line, NA, "no ping offset to compare with (" + str(ping.get("reason") or "no ping pairs in the export") + "); the stomp offset itself is measured"))
    else:
        inside = abs(d) <= STOMP_PING_MS
        why = (f"inside the line by {STOMP_PING_MS - abs(d):.1f} ms" if inside else
               f"over the line by {abs(d) - STOMP_PING_MS:.1f} ms: a steady disagreement of this kind is the half-connection-interval ping bias; record it with the connection interval")
        rows.append(Row(T, "stomp minus ping", f"{d:+.2f} ms (ping uncertainty {fm(jnum(ping, 'uncertainty_s') * 1e3, 1)} ms)", line, PASS if inside else FAIL, why, num=d))
    return rows


# ---------------------------------------------------------------- T2
def t2(ex, explicit):
    T = "T2"
    sline = f"corrected <= {MAG_SPREAD_OK_PCT:.0f} % (sanity {MAG_SPREAD_WARN_PCT:.0f} %)"
    hline = f"RMS <= {MAG_HEADING_OK_DEG:.0f} deg (flag {MAG_HEADING_WARN_DEG:.0f})"
    try:
        r = mag_heading_check.analyse(ex.path)
    except SystemExit as e:
        return [Row(T, "magnetometer field magnitude spread", "n/a", sline, miss(explicit), "no usable magnetometer data: " + str(e)),
                Row(T, "magnetometer heading vs gyro yaw", "n/a", hline, miss(explicit), "no usable magnetometer data")]
    gyro = r.get("gyro_rotation_turns")
    magt = r.get("mag_rotation_turns") or {}
    gyro_total = jnum(gyro, "total_absolute") if gyro else NAN
    turns = gyro_total if fin(gyro_total) else jnum(magt, "total_absolute")
    src = "gyro yaw" if fin(gyro_total) else "magnetometer heading (no gyro rows overlap the magnetometer samples)"
    on_spot = fin(turns) and turns >= mag_heading_check.MIN_ROTATION_TURNS
    rows = []
    pc = jnum(r.get("magnitude_corrected"), "spread_percent_of_mean")
    pr = jnum(r.get("magnitude_raw_gauss"), "spread_percent_of_mean")
    quantity = (r.get("magnitude_corrected") or {}).get("quantity", "")
    val = f"{fm(pc)} % corrected (raw {fm(pr)} % of mean {fm(jnum(r.get('magnitude_raw_gauss'), 'mean'), 3)} G)"
    if not on_spot and not explicit:
        rows.append(Row(T, "magnetometer field magnitude spread", val, sline, NA, f"only {fm(turns, 2)} turns of {src}: not a level turn on the spot, so the spread says nothing about the field"))
    elif not fin(pc):
        rows.append(Row(T, "magnetometer field magnitude spread", val, sline, NA, "corrected spread not available"))
    else:
        st = PASS if pc <= MAG_SPREAD_OK_PCT else (WARN if pc <= MAG_SPREAD_WARN_PCT else FAIL)
        why = quantity + ("" if on_spot else f"; only {fm(turns, 2)} turns of {src}: not a turn on the spot")
        rows.append(Row(T, "magnetometer field magnitude spread", val, sline, st, why.strip("; "), num=pc))
    yd = r.get("yaw_difference_deg")
    if not gyro or not yd:
        rows.append(Row(T, "magnetometer heading vs gyro yaw", "n/a", hline, NA, "no gyro samples overlap the magnetometer samples in time, so there is no gyro yaw to compare with"))
    elif not fin(turns):
        rows.append(Row(T, "magnetometer heading vs gyro yaw", "n/a", hline, NA, "the number of turns is not available (null or non-finite), so heading cannot be compared"))
    elif not on_spot:
        rows.append(Row(T, "magnetometer heading vs gyro yaw", f"gyro yaw total {fm(turns, 2)} turns", hline, miss(explicit),
                        "the recording has no turn on the spot (needs at least " + f"{mag_heading_check.MIN_ROTATION_TURNS:.1f} turns), so heading cannot be compared"))
    else:
        rms = jnum(yd, "rms_offset_and_linear_trend_removed")
        if not fin(rms):
            rows.append(Row(T, "magnetometer heading vs gyro yaw", "n/a", hline, NA, "the heading RMS is null or non-finite in the analysis result"))
        else:
            st = PASS if rms <= MAG_HEADING_OK_DEG else (WARN if rms <= MAG_HEADING_WARN_DEG else FAIL)
            rows.append(Row(T, "magnetometer heading vs gyro yaw", f"RMS {fm(rms)} deg after offset and trend; mag {fm(jnum(magt, 'net_ccw_positive'), 2)} turns vs gyro {fm(jnum(gyro, 'net_ccw_positive'), 2)}", hline, st,
                            "; ".join(r.get("flags", [])), num=rms))
    return rows


# ---------------------------------------------------------------- T3
def tm_summary(ex):
    a = ex.json("analysis-summary.json")
    return None if not a else a.get("turn_metrics")


def usable_share(tm):
    pb = tm.get("per_boot", {})
    c = sum(v for v in (jnum(b, "candidates") for b in pb.values()) if fin(v))
    u = sum(v for v in (jnum(b, "usable") for b in pb.values()) if fin(v))
    return (u / c if c else None), int(c)


def mean_roughness(tm):
    """Candidate-weighted mean of the per-boot mean roughness (the candidates pooled, like the usable share)."""
    num = den = 0.0
    for b in tm.get("per_boot", {}).values():
        m, w = jnum(b.get("roughness"), "mean"), jnum(b, "candidates")
        if fin(m) and fin(w) and w > 0:
            num += m * w
            den += w
    return num / den if den else None


def alignment_row(ex, ba, have_pairs):
    """T3: which clock alignment the left-right agreement used, and its uncertainty (report only, never FAIL).
    INFO when stomp or pings were applied without disagreement; WARN when nothing was applied (live-clock timeline,
    unmeasured bias of tens of ms) or stomp and ping disagree; N/A for an older export or when there are no pairs."""
    T, line = "T3", "aligned by stomp or ping, no disagreement"
    al = ba.get("clock_alignment") if isinstance(ba.get("clock_alignment"), dict) else None
    if al is None:
        return Row(T, "left-right alignment", "n/a", line, NA, "analysis-summary.json has no clock_alignment block (export made before the aligned agreement)")
    if not have_pairs:
        return Row(T, "left-right alignment", str(al.get("source")), line, NA, "no left-right pairs to align: " + str(al.get("reason") or ""))
    src, applied, dis = al.get("source"), al.get("applied") is True, al.get("disagreement") is True
    comp = (ex.json("analysis-summary.json") or {}).get("boot_comparison") or {}
    onset, unal = jnum(comp, "median_right_minus_left_onset_ms"), jnum(comp, "unaligned_median_right_minus_left_onset_ms")
    value = (f"{src}, shift {fm(jnum(al, 'shift_ms'), 1)} ms +/- {fm(jnum(al, 'uncertainty_ms'), 1)} ms, stomp minus ping {fm(jnum(al, 'stomp_minus_ping_ms'), 1)} ms; "
             f"median onset lag {fm(onset, 1)} ms (unaligned {fm(unal, 1)} ms)"
             + (", within the uncertainty" if comp.get("onset_lag_timing_limited") is True else ""))
    st = INFO if applied and not dis else WARN
    return Row(T, "left-right alignment", value, line, st, str(al.get("reason") or ""), num=jnum(al, "uncertainty_ms"))


def t3(ex, explicit, compare=None):
    T = "T3"
    tm = tm_summary(ex)
    rows = []
    if tm is None or (not explicit and candidate_total(tm) == 0):
        rows.append(Row(T, "turn candidates", "n/a", f">= {MIN_CANDIDATES_OK} per boot", miss(explicit),
                        "analysis-summary.json has no turn_metrics block" if tm is None else "no roll excursions in the recording: not a roll-drill recording"))
    else:
        pb = tm.get("per_boot", {})
        for side in EXPECTED_SIDES:
            b = pb.get(side) or {}
            n = jnum(b, "candidates")
            n = int(n) if fin(n) else 0
            u = jnum(b, "usable")
            line = f">= {MIN_CANDIDATES_OK} candidates"
            st = PASS if n >= MIN_CANDIDATES_OK else (WARN if n > 0 else FAIL)
            rows.append(Row(T, f"candidates {side}", f"{n} candidates, {int(u) if fin(u) else 0} usable", line, st,
                            "" if n else f"boot {side} produced no turn candidates (missing boot or no roll excursions)", num=n))
        sh, c = usable_share(tm)
        rows.append(Row(T, "usable share", "n/a" if sh is None else f"{sh * 100:.0f} % of {c}", "direction only (rough lower than smooth)", INFO if sh is not None else NA,
                        "" if sh is not None else "no candidates"))
        for side in EXPECTED_SIDES:
            b = pb.get(side)
            if not b or not fin(jnum(b, "candidates")) or not b.get("candidates"):
                continue
            parts = []
            for k in ("roughness", "peak_deg", "duration_ms", "early_roll_rate_dps", "edge_hold", "build_ms", "peak_phase"):
                d = b.get(k)
                mean = jnum(d, "mean")
                if fin(mean):
                    sd = jnum(d, "sd")
                    parts.append(f"{k} {mean:.3g} (sd {sd:.3g})" if fin(sd) else f"{k} {mean:.3g}")
            rows.append(Row(T, f"per-boot spread {side}", "; ".join(parts), "record before choosing any threshold", INFO))
        ba = tm.get("boot_agreement") or {}
        pairs = jnum(ba, "pairs_with_data")
        al = ba.get("clock_alignment") if isinstance(ba.get("clock_alignment"), dict) else None
        lim = jnum(ba, "pairs_timing_limited")
        timing = "" if al is None else (f"; aligned by {al.get('source')} (uncertainty {fm(jnum(al, 'uncertainty_ms'), 1)} ms), "
                                         f"{int(lim) if fin(lim) else 'n/a'} pairs timing-limited")
        if fin(pairs) and pairs:
            rows.append(Row(T, "left-right agreement", f"{int(pairs)} pairs, derivative similarity {fm(jnum(ba, 'mean_derivative_similarity'), 3)}, roll correlation {fm(jnum(ba, 'mean_roll_correlation'), 3)}, wedge fraction {fm(jnum(ba, 'mean_wedge_fraction'), 3)}{timing}",
                            "direction only", INFO))
        else:
            rows.append(Row(T, "left-right agreement", "no pairs", "direction only", NA, "needs candidates from both boots with no gaps over 100 ms"))
        rows.append(alignment_row(ex, ba, fin(pairs) and bool(pairs)))
    if compare:
        rows += t3_compare(compare[0], compare[1])
    else:
        rows.append(Row(T, "smooth vs rough direction", "n/a", "rough shows lower usable share or higher roughness; lower similarity; larger wedge fraction", NA,
                        "needs two sessions: rerun with --compare SMOOTH.zip ROUGH.zip"))
    return rows


def direction(smooth, rough, expected):
    """+1 when rough moved the expected way ('lower' or 'higher') against smooth, -1 the wrong way, 0 equal, None unknown."""
    if not fin(smooth) or not fin(rough):
        return None
    if math.isclose(smooth, rough, rel_tol=1e-6, abs_tol=1e-12):
        return 0
    return 1 if (rough < smooth) == (expected == "lower") else -1


def combine(dirs):
    """The explicit rule for 'lower usable share or higher roughness': each metric is compared separately.
    PASS only when at least one moves the expected way and none the wrong way; WARN when they contradict, when none
    moves, or when one is wrong and the other equal; FAIL when both go the wrong way."""
    ds = [d for d in dirs if d is not None]
    if not ds:
        return NA
    good, bad = ds.count(1), ds.count(-1)
    if good and not bad:
        return PASS
    if bad == len(ds) and bad >= 2:
        return FAIL
    return WARN


def compare_summaries(sm, ro, tag=""):
    T = "T3"
    rows = []
    ss, _ = usable_share(sm)
    rs, _ = usable_share(ro)
    rgs, rgr = mean_roughness(sm), mean_roughness(ro)
    ds = direction(ss if ss is not None else NAN, rs if rs is not None else NAN, "lower")
    dr = direction(rgs if rgs is not None else NAN, rgr if rgr is not None else NAN, "higher")
    word = {1: "expected way", -1: "WRONG way", 0: "equal", None: "unknown"}
    rows.append(Row(T, "rough vs smooth: usable share", "n/a" if ss is None or rs is None else f"{ss * 100:.0f} % -> {rs * 100:.0f} %", "rough lower", INFO if ds is not None else NA, word[ds] + " " + tag))
    rows.append(Row(T, "rough vs smooth: roughness", "n/a" if rgs is None or rgr is None else f"{rgs:.3f} -> {rgr:.3f}", "rough higher", INFO if dr is not None else NA, word[dr] + " " + tag))
    st = combine([ds, dr])
    why = {PASS: "", NA: "no candidates in one session " + tag,
           WARN: "contradictory, equal or only partly wrong: usable share " + word[ds] + ", roughness " + word[dr],
           FAIL: "both usable share and roughness moved the wrong way: rough looks smoother than smooth"}[st]
    rows.append(Row(T, "rough vs smooth: usable share or roughness", f"usable share {word[ds]}, roughness {word[dr]}",
                    "at least one expected way and none the wrong way", st, why))
    sa, ra = sm.get("boot_agreement") or {}, ro.get("boot_agreement") or {}
    a, b = jnum(sa, "mean_derivative_similarity"), jnum(ra, "mean_derivative_similarity")
    line = "rough: lower left-right derivative similarity"
    if not (fin(a) and fin(b)):
        rows.append(Row(T, "rough vs smooth: derivative similarity", "n/a", line, NA, "one session has no left-right pairs " + tag))
    else:
        d = direction(a, b, "lower")
        rows.append(Row(T, "rough vs smooth: derivative similarity", f"{a:.3f} -> {b:.3f}", line, PASS if d == 1 else (WARN if d == 0 else FAIL), num=b - a))
    a, b = jnum(sa, "mean_wedge_fraction"), jnum(ra, "mean_wedge_fraction")
    line = "rough: larger wedge fraction"
    if not (fin(a) and fin(b)):
        rows.append(Row(T, "rough vs smooth: wedge fraction", "n/a", line, NA, "one session has no left-right pairs " + tag))
    else:
        d = direction(a, b, "higher")
        rows.append(Row(T, "rough vs smooth: wedge fraction", f"{a:.3f} -> {b:.3f}", line, PASS if d == 1 else (WARN if d == 0 else FAIL),
                        "no difference: the wedge fraction does not separate these two sessions" if d == 0 else "", num=b - a))
    return rows


def t3_compare(smooth_path, rough_path):
    T = "T3"
    if os.path.abspath(smooth_path) == os.path.abspath(rough_path) or (os.path.exists(smooth_path) and os.path.exists(rough_path) and os.path.samefile(smooth_path, rough_path)):
        return [Row(T, "smooth vs rough direction", "same file", "two different sessions", FAIL, f"--compare was given the same file twice ({smooth_path}); a session cannot be compared with itself")]
    se, re_ = Export(smooth_path), Export(rough_path)
    sm, ro = tm_summary(se), tm_summary(re_)
    tag = f"(smooth {os.path.basename(smooth_path)}, rough {os.path.basename(rough_path)})"
    if sm is None or ro is None:
        return [Row(T, "smooth vs rough direction", "n/a", "", NA, "one of the sessions has no turn_metrics block " + tag)]
    return compare_summaries(sm, ro, tag)


# ---------------------------------------------------------------- T4
def t4(ex, explicit):
    T = "T4"
    txt = ex.text("phone-motion.csv")
    ph = ex.csv("phone-motion.csv")
    line_rows = f"{PHONE_ROWS_PER_S[0]:.0f} to {PHONE_ROWS_PER_S[1]:.0f} rows/s"
    line_off = f"|offset| <= {PHONE_OFFSET_OK_MS:.0f} ms"
    rows = []
    if txt is None:
        st = miss(explicit)
        why = "phone-motion.csv is not in the export" + ("" if explicit else ": not a recording with phone motion")
        rows.append(Row(T, "phone clock offset", "n/a", line_off, st, why))
        rows.append(Row(T, "phone rows per second", "n/a", line_rows, st, why))
        rows.append(Row(T, "phone largest gap", "n/a", f"< {PHONE_GAP_WARN_MS:.0f} ms", st, why))
    else:
        off = None
        m = re.search(r"(?:Clock base check:|phone_clock_check)\s+offset_ms=\s*([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?|[+-]?(?:nan|inf(?:inity)?))", txt, re.IGNORECASE)
        if m:
            off = float(m.group(1))
            if not math.isfinite(off):
                off = None
        if off is None:
            rows.append(Row(T, "phone clock offset", "not recorded", line_off, FAIL, "phone-motion.csv carries no phone_clock_check offset, so the sensor clock base is unknown"))
        else:
            st = PASS if abs(off) <= PHONE_OFFSET_OK_MS else (WARN if abs(off) <= PHONE_OFFSET_WARN_MS else FAIL)
            rows.append(Row(T, "phone clock offset", f"{off:+.3f} ms", line_off, st, num=off))
        t = ph.get("phone_ns") if ph else None
        t = np.sort(t[np.isfinite(t)]) * 1e-6 if t is not None and len(t) else np.array([])    # phone_ns -> ms; always present, unlike session_elapsed_ms
        n = len(t)
        if n < 2:
            rows.append(Row(T, "phone rows per second", "0 rows", line_rows, FAIL, "no phone motion rows in the export"))
            rows.append(Row(T, "phone largest gap", "n/a", f"< {PHONE_GAP_WARN_MS:.0f} ms", NA, "no phone rows"))
        else:
            span = (t[-1] - t[0]) / 1e3
            rate = (n - 1) / span if span > 0 else NAN
            lo, hi = PHONE_ROWS_PER_S
            wl, wh = PHONE_WARN_ROWS_PER_S
            if not fin(rate):
                rows.append(Row(T, "phone rows per second", f"{n} rows over {span:.1f} s", line_rows, NA, "the rows span no time (identical phone_ns), so no rate can be measured"))
            else:
                st = PASS if lo <= rate <= hi else (WARN if wl <= rate <= wh else FAIL)
                rows.append(Row(T, "phone rows per second", f"{fm(rate)} rows/s ({n} rows over {span:.1f} s, from phone_ns)", line_rows, st, num=rate))
            gap = float(np.max(np.diff(t)))
            st = PASS if gap < PHONE_GAP_WARN_MS else (WARN if gap < PHONE_GAP_FAIL_MS else FAIL)
            rows.append(Row(T, "phone largest gap", f"{gap:.0f} ms", f"< {PHONE_GAP_WARN_MS:.0f} ms (fail at {PHONE_GAP_FAIL_MS:.0f})", st, num=gap))
    rows.append(Row(T, "phone storage error", "n/a", "none", NA, "a storage error is not recorded in the export: check by hand"))
    rows.append(Row(T, "phone battery drain over 30 min", "n/a", "note only", NA, "needs the phone battery readings, not in the export: note by hand"))
    rows += torso_rows(ex)
    return rows


def spread_text(d, nd=3):
    """'mean (sd x, n k)' from a {n, mean, sd} spread block; 'n/a' when it is null."""
    mean = jnum(d, "mean")
    if not fin(mean):
        return "n/a"
    sd, n = jnum(d, "sd"), jnum(d, "n")
    return f"{mean:.{nd}g}" + (f" (sd {sd:.{nd}g}" if fin(sd) else " (sd n/a") + (f", n {int(n)})" if fin(n) else ")")


def torso_rows(ex):
    """T4 torso metrics (experimental, unvalidated): INFO when computed, WARN when refused or marginal, never FAIL."""
    T = "T4"
    line = "observation only (experimental, unvalidated)"
    a = ex.json("analysis-summary.json")
    tm = a.get("torso_metrics") if isinstance(a, dict) else None
    if not isinstance(tm, dict):
        return [Row(T, "torso metrics status", "n/a", line, NA, "analysis-summary.json has no torso_metrics block (an export from before the torso metrics)")]
    rows = []
    status = tm.get("status")
    reason = tm.get("reason") or ""
    if status != "computed":
        rows.append(Row(T, "torso metrics status", str(status), line, WARN, reason or "torso metrics not computed"))
        return rows
    n, nm, lim = jnum(tm, "rows"), jnum(tm, "rows_with_metrics"), jnum(tm, "rows_timing_limited")
    rows.append(Row(T, "torso metrics status", f"computed: {fm(n, 0)} rows, {fm(nm, 0)} with metrics, run coverage {fm(jnum(tm, 'run_coverage_fraction') * 100, 0)} %",
                    line, INFO, reason))
    timing = tm.get("timing") if isinstance(tm.get("timing"), dict) else {}
    parts, unmeasured = [], []
    for side in EXPECTED_SIDES:
        t = timing.get(side)
        if not isinstance(t, dict):
            continue
        if t.get("source") != "ping":
            unmeasured.append(side)
        parts.append(f"{side} {t.get('source')} bias removed {fm(jnum(t, 'bias_removed_ms'), 1)} ms, uncertainty <= {fm(jnum(t, 'max_uncertainty_ms'), 1)} ms")
    val = "; ".join(parts) + f"; {fm(lim, 0)} rows timing-limited"
    if unmeasured:
        rows.append(Row(T, "torso timing", val, line, WARN, f"boot {', '.join(unmeasured)}: no ping measurement of the phone-to-boot timeline bias, so an allowance of tens of ms is used"))
    else:
        rows.append(Row(T, "torso timing", val, line, INFO))
    q = tm.get("quietness") if isinstance(tm.get("quietness"), dict) else {}
    rows.append(Row(T, "torso quietness", f"run gyro RMS {fm(jnum(q, 'run_gyro_rms_dps'), 2)} dps (still floor {fm(jnum(q, 'still_gyro_rms_dps'), 2)} dps), horizontal accel SD {fm(jnum(q, 'run_horizontal_accel_sd_mps2'), 3)} m/s^2; "
                    f"per turn gyro RMS {spread_text(q.get('per_turn_gyro_rms_dps'))} dps", line + "; lower = quieter upper body, not a score", INFO, num=jnum(q, "run_gyro_rms_dps")))
    pb = tm.get("per_boot") if isinstance(tm.get("per_boot"), dict) else {}
    for side in EXPECTED_SIDES:
        b = pb.get(side)
        if not isinstance(b, dict):
            continue
        lv = b.get("tilt_vs_roll") if isinstance(b.get("tilt_vs_roll"), dict) else {}
        ratio = lv.get("tilt_to_roll_ratio")
        if fin(jnum(ratio, "mean")):
            rows.append(Row(T, f"torso tilt vs boot roll {side}", f"tilt/roll {spread_text(ratio)}, correlation across turns {fm(jnum(lv, 'correlation_across_turns'), 3)}, "
                            f"tilt {spread_text(lv.get('torso_tilt_peak_deg'))} deg", line + "; total tilt (sideways and fore-aft), magnitudes, not lateral roll", INFO, num=jnum(ratio, "mean")))
        else:
            rows.append(Row(T, f"torso tilt vs boot roll {side}", "n/a", line, NA, "no candidate with a torso tilt and a boot roll peak"))
        yw = b.get("yaw") if isinstance(b.get("yaw"), dict) else {}
        turns = jnum(yw, "turns")
        if fin(turns) and turns > 0:
            rows.append(Row(T, f"torso yaw vs boot heading {side}", f"{int(turns)} turns, torso/boot heading {spread_text(yw.get('torso_to_boot_heading_ratio'))}, "
                            f"yaw-rate correlation {spread_text(yw.get('yaw_rate_correlation'))}, sign agreement {fm(jnum(yw, 'sign_agreement_fraction'), 2)}",
                            line, INFO, num=jnum(jnum_dict(yw, "torso_to_boot_heading_ratio"), "mean")))
        else:
            rows.append(Row(T, f"torso yaw vs boot heading {side}", "n/a", line, NA, "the boot did not turn by the minimum heading change in any candidate, so the yaw comparison is not meaningful"))
    return rows


def jnum_dict(d, k):
    v = d.get(k) if isinstance(d, dict) else None
    return v if isinstance(v, dict) else {}


# ---------------------------------------------------------------- T5
def t5(ex, explicit):
    T = "T5"
    if not explicit and not has_stomp(ex):
        return [Row(T, "run flow", "n/a", "see T5", NA, f"no stomp-like transient in the recording: not a run-flow recording")]
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
                            f"boot {side} has no flash rows in samples.csv" + (f" (capture state {c.get('state')})" if c else "")))
        else:
            ok = c is not None and c.get("state") in ("erased", "verified")
            why = "" if ok else ("no capture record in session.json" if c is None else f"capture state {c.get('state')}, {c.get('error') or 'no error text'}")
            exp = jnum(c, "expected_samples") if c else NAN
            rows.append(Row(T, f"flash download {side}", f"{n} flash samples" + (f" of {int(exp)} expected" if fin(exp) else "") + (f", state {c.get('state')}" if c else ""),
                            "flash present, state erased or verified", PASS if ok else FAIL, why))
        fl = {x["source"]: x for x in boots.get(side, {}).get("streams", [])}.get("flash")
        if fl is None:
            rows.append(Row(T, f"flash source health {side}", "no flash stream", "usable", FAIL if n == 0 else NA, "no flash stream in session-health.json"))
        else:
            v = fl.get("verdict")
            rows.append(Row(T, f"flash source health {side}", str(v), "usable", PASS if v == "usable" else (WARN if v == "marginal" else FAIL),
                            "; ".join(fl.get("reasons", []))))
    g = ex.json("gesture-sync.json")
    if g is None:
        rows.append(Row(T, "stomp sync measured", "n/a", "measured", miss(explicit), "gesture-sync.json is not in the export"))
    else:
        rows.append(Row(T, "stomp sync measured", str(g.get("status")), "measured", PASS if g.get("status") == "measured" else FAIL, g.get("reason") or ""))
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
        ms = ((a or {}).get(side) or {}).get("mount_slip") if isinstance(a, dict) else None
        if not ms:
            rows.append(Row(T, f"mount slip {side}", "n/a", "no slip flagged", NA, "analysis-summary.json has no mount_slip block for this boot"))
        elif not ms.get("available"):
            rows.append(Row(T, f"mount slip {side}", "unavailable", "no slip flagged", NA, ms.get("reason") or "mount-slip check not available"))
        else:
            fl = jnum(ms, "flagged_windows")
            if not fin(fl):
                rows.append(Row(T, f"mount slip {side}", "n/a", "no slip flagged", NA, "flagged_windows is null in the mount_slip block"))
                continue
            rows.append(Row(T, f"mount slip {side}", f"{int(fl)} of {fm(jnum(ms, 'windows_checked'), 0)} windows flagged, max axis deviation {fm(jnum(ms, 'max_axis_deviation_deg'), 2)} deg, max force shift {fm(jnum(ms, 'max_force_shift_g'), 3)} g",
                            "no slip flagged", PASS if fl == 0 else FAIL,
                            "" if fl == 0 else "slip flagged: retighten and repeat the roll drill", num=fl))
    return rows


FUNCS = {"T0": t0, "T1": t1, "T2": t2, "T4": t4, "T5": t5, "T6": t6}


def run_checks(path, trials, compare=None):
    """Raises ExportError when the export cannot be read at all; a failure while judging one trial is a FAIL row."""
    ex = Export(path)
    ex.validate()
    explicit = len(trials) == 1
    rows = []
    global _COLLECT
    for t in trials:
        _COLLECT = []
        try:
            if t == "T3":
                t3(ex, explicit, compare)
            else:
                FUNCS[t](ex, explicit)
        except SystemExit as e:
            Row(t, "tool error", "SystemExit", "", FAIL, f"a called analysis tool exited ({e}); the rows above are what was judged before it stopped; the rest of this trial is treated as a FAIL")
        except Exception as e:   # never let one malformed file hide the verdict of the others, and never pass it silently
            Row(t, "tool error", type(e).__name__, "", FAIL, f"{e}; the rows above are what was judged before it stopped; the rest of this trial is treated as a FAIL, check the export by hand")
        finally:
            rows += _COLLECT
            _COLLECT = None
    return rows


# ---------------------------------------------------------------- output
def table(rows):
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
    return [f"  {r.trial} {r.check}: {r.value} [{r.line}]" for r in rows if len(r.value) > 70 or len(r.line) > 48 or len(r.check) > 38]


def counts(rows):
    c = {PASS: 0, FAIL: 0, WARN: 0, NA: 0, INFO: 0}
    for r in rows:
        c[r.status] += 1
    return c


def write_json(path, payload):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(sanitize(payload), fh, indent=1, allow_nan=False)


def cmd_report(a):
    trials = ORDER if a.trial == "all" else [a.trial]
    try:
        for cp in (a.compare or []):
            Export(cp).validate()
    except ExportError as e:
        print(f"ERROR: cannot read a --compare file: {e}. Nothing was judged.", file=sys.stderr)
        return 2
    try:
        rows = run_checks(a.export, trials, a.compare)
    except ExportError as e:
        print(f"ERROR: cannot read the export: {e}. No trial could be judged.", file=sys.stderr)
        return 2
    print(f"Trial report: {a.export}   (starting values; experimental; trials: {', '.join(trials)})\n")
    print(table(rows))
    det = full_detail(rows)
    if det:
        print("\nFull text of cells shortened above:")
        print("\n".join(det))
    c = counts(rows)
    print("\n" + "  ".join(f"{k} {v}" for k, v in c.items()))
    if a.json:
        write_json(a.json, {"input": a.export, "trials": trials, "counts": c, "rows": [r.as_dict() for r in rows]})
    return 1 if c[FAIL] else 0


# ---------------------------------------------------------------- mutated exports (selftest and regress)
def mutate(src, dst, edits):
    """Copy a ZIP, applying edits {member: None (delete) | bytes | callable(bytes)->bytes}."""
    with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
        for info in zi.infolist():
            data = zi.read(info.filename)
            if info.filename in edits:
                e = edits[info.filename]
                if e is None:
                    continue
                data = e(data) if callable(e) else e
            zo.writestr(info.filename, data)


def edit_json(fn):
    def go(data):
        o = json.loads(data.decode("utf-8"))
        fn(o)
        return json.dumps(o).encode("utf-8")
    return go


def edit_csv(fn):
    """fn(header, rows) -> rows; '#' comment lines are kept in front."""
    def go(data):
        lines = data.decode("utf-8").splitlines()
        comments = [l for l in lines if l.startswith("#")]
        body = list(csv.reader([l for l in lines if not l.startswith("#")]))
        out = io.StringIO()
        w = csv.writer(out, lineterminator="\n")
        for l in comments:
            out.write(l + "\n")
        w.writerow(body[0])
        w.writerows(fn(body[0], body[1:]))
        return out.getvalue().encode("utf-8")
    return go


def run_cli(argv):
    out, err = io.StringIO(), io.StringIO()
    code = 0
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            main(argv)
        except SystemExit as e:
            code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
    return code, out.getvalue(), err.getvalue()


def get(rows, trial, prefix):
    return [r for r in rows if r.trial == trial and r.check.startswith(prefix)]


_LAST_TMP = None


def selftest(d):
    """Run _selftest in a temporary directory that is always removed, and check that it was."""
    global _LAST_TMP
    with tempfile.TemporaryDirectory(prefix="trial_report_selftest_") as tmp:
        _LAST_TMP = tmp
        code = _selftest(d, tmp)
    if os.path.exists(_LAST_TMP):
        print("FAILED: the selftest left its temporary directory behind: " + _LAST_TMP)
        return 1
    return code


def _selftest(d, tmp):
    clean = os.path.join(d, "clean.zip")
    if not os.path.exists(clean):
        print(f"selftest needs clean.zip in {d} (see docs/garden-trial-protocol.md, Dress rehearsal)")
        return 2
    results = []

    def check(name, cond, detail=""):
        results.append((name, bool(cond), detail))

    P = lambda n: os.path.join(tmp, n)   # noqa: E731

    # 1. unreadable exports and tool errors are failures, never a quiet exit 0
    code, _, err = run_cli([P("does-not-exist.zip")])
    check("1 missing path exits non-zero and says so", code != 0 and "cannot read" in err, f"exit {code}")
    with open(P("garbage.zip"), "wb") as fh:
        fh.write(b"not a zip at all")
    code, _, err = run_cli([P("garbage.zip")])
    check("1 corrupt ZIP exits non-zero", code != 0 and "cannot read" in err, f"exit {code}")
    mutate(clean, P("badjson.zip"), {"gesture-sync.json": b"{not json"})
    code, out, _ = run_cli([P("badjson.zip"), "--trial", "T1"])
    check("1 corrupt member is a FAIL row (exit 1), not a WARN", code == 1 and "tool error" in out and "not valid JSON" in out, f"exit {code}")

    # 2. magnetometer samples that overlap no gyro rows
    def shift_mag(h, rows):
        i = h.index("sensor_ms")
        for r in rows:
            r[i] = str(int(float(r[i])) + 100000000)
        return rows
    mutate(clean, P("mag-no-overlap.zip"), {"magnetometer.csv": edit_csv(shift_mag)})
    rows = run_checks(P("mag-no-overlap.zip"), ["T2"])
    sp, hd = get(rows, "T2", "magnetometer field magnitude spread"), get(rows, "T2", "magnetometer heading")
    check("2 no gyro overlap: no tool error, spread verdict kept, heading N/A with the reason",
          not get(rows, "T2", "tool error") and sp and sp[0].status in (PASS, WARN, FAIL) and hd and hd[0].status == NA and "overlap" in hd[0].why,
          "; ".join(f"{r.check}={r.status}" for r in rows))

    # 3. null fields in exported JSON (key present, value null)
    def nullify(o):
        for side in "LR":
            o.get(side, {}).get("mount_slip", {}).update(max_axis_deviation_deg=None, max_force_shift_g=None)
        ba = o["turn_metrics"]["boot_agreement"]
        ba.update(mean_roll_correlation=None, mean_wedge_fraction=None)
        o["turn_metrics"]["per_boot"]["L"]["duration_ms"]["mean"] = None
    def null_gesture(o):
        o["ping"]["uncertainty_s"] = None
    def null_health(o):
        for s in o["boots"][0]["streams"]:
            s["rate_hz"] = None
            s["longest_gap_ms"] = None
    mutate(clean, P("nulls.zip"), {"analysis-summary.json": edit_json(nullify), "gesture-sync.json": edit_json(null_gesture), "session-health.json": edit_json(null_health)})
    rows = run_checks(P("nulls.zip"), ORDER)
    check("3 null fields: no tool error anywhere", not [r for r in rows if r.check == "tool error"], "; ".join(f"{r.trial} {r.why}" for r in rows if r.check == "tool error"))
    check("3 null health rate is N/A with a reason", all(r.status == NA and "null" in r.why for r in get(rows, "T0", "flash rate L") + get(rows, "T0", "live rate L")) and get(rows, "T0", "flash rate L"))
    check("3 null mount-slip numbers still give a verdict", get(rows, "T6", "mount slip L") and get(rows, "T6", "mount slip L")[0].status == PASS)
    check("3 null ping uncertainty prints n/a", "n/a ms" in get(rows, "T1", "stomp minus ping")[0].value)

    # 3b. alignment block: null numbers print n/a, a pre-alignment export is N/A (never a tool error or FAIL)
    def null_alignment(o):
        o["turn_metrics"]["boot_agreement"]["clock_alignment"].update(shift_ms=None, uncertainty_ms=None, stomp_minus_ping_ms=None)
        o["turn_metrics"]["boot_agreement"]["pairs_timing_limited"] = None
    def no_alignment(o):
        o["turn_metrics"]["boot_agreement"].pop("clock_alignment", None)
        o["boot_comparison"].pop("clock_alignment", None)
    mutate(clean, P("align-null.zip"), {"analysis-summary.json": edit_json(null_alignment)})
    rows = run_checks(P("align-null.zip"), ["T3"])
    al = get(rows, "T3", "left-right alignment")
    check("3b null alignment numbers: no tool error, n/a printed", al and not get(rows, "T3", "tool error") and "n/a" in al[0].value and al[0].status != FAIL,
          al[0].value if al else "no row")
    mutate(clean, P("align-missing.zip"), {"analysis-summary.json": edit_json(no_alignment)})
    al = get(run_checks(P("align-missing.zip"), ["T3"]), "T3", "left-right alignment")
    check("3b export without an alignment block is N/A", al and al[0].status == NA and "no clock_alignment block" in al[0].why, al[0].why if al else "no row")

    # 4. unfitted drift is not a measurement; an empty epoch list is not a clock restart
    def unfit(o):
        o["L"]["epochs"][-1].update(drift_fitted=False, drift_ppm=0.0)
        o["R"]["epochs"] = []
    def nohealthslope(o):
        for s in o["boots"][0]["streams"]:
            s["clock_slope_ppm"] = None
    mutate(clean, P("unfitted.zip"), {"clock-sync-fit.json": edit_json(unfit), "session-health.json": edit_json(nohealthslope)})
    rows = run_checks(P("unfitted.zip"), ["T0"])
    cl, ce = get(rows, "T0", "clock slope L"), get(rows, "T0", "single clock epoch R")
    check("4 unfitted drift placeholder is N/A, not PASS", cl and cl[0].status == NA, cl[0].status if cl else "no row")
    check("4 empty epochs: N/A with a no-fit reason, not 'clock restarted'", ce and ce[0].status == NA and "restarted" not in ce[0].why, ce[0].why if ce else "no row")

    # 5. phone rows with a blank session_elapsed_ms (the exporter leaves it blank without a clock check)
    def blank_elapsed(h, rows):
        i = h.index("session_elapsed_ms")
        for r in rows:
            r[i] = ""
        return rows
    mutate(clean, P("phone-blank.zip"), {"phone-motion.csv": edit_csv(blank_elapsed)})
    rows = run_checks(P("phone-blank.zip"), ["T4"])
    pr = get(rows, "T4", "phone rows per second")
    check("5 blank session_elapsed_ms: rate and gap come from phone_ns", pr and pr[0].status == PASS and fin(pr[0].num) and get(rows, "T4", "phone largest gap")[0].status == PASS, pr[0].value if pr else "no row")

    # 6. T2 judges the corrected spread, the same figure mag_heading_check judges
    rows = run_checks(clean, ["T2"])
    r = mag_heading_check.analyse(clean)
    sp = get(rows, "T2", "magnetometer field magnitude spread")
    check("6 T2 spread row is the corrected figure, raw shown beside it",
          sp and abs(sp[0].num - r["magnitude_corrected"]["spread_percent_of_mean"]) < 1e-9 and "raw" in sp[0].value and "corrected" in sp[0].value, sp[0].value if sp else "no row")

    # 7. T3 direction rule
    def tm(share, rough):
        n = 20
        b = {"candidates": n, "usable": round(share * n), "roughness": {"mean": rough, "sd": 1.0}}
        return {"per_boot": {"L": b, "R": dict(b)}, "boot_agreement": {"mean_derivative_similarity": 0.9, "mean_wedge_fraction": 0.1}}
    cases = [("equal share, rougher rough", (1.0, 5.0), (1.0, 6.0), PASS), ("lower share, lower roughness (contradict)", (1.0, 5.0), (0.8, 4.0), WARN),
             ("higher share, lower roughness (both wrong)", (0.8, 5.0), (1.0, 4.0), FAIL), ("equal, equal", (1.0, 5.0), (1.0, 5.0), WARN),
             ("lower share, rougher", (1.0, 5.0), (0.8, 6.0), PASS), ("equal share, smoother rough", (1.0, 5.0), (1.0, 4.0), WARN)]
    for name, s, rr, want in cases:
        got = get(compare_summaries(tm(*s), tm(*rr)), "T3", "rough vs smooth: usable share or roughness")[0].status
        check(f"7 T3 rule: {name} -> {want}", got == want, f"got {got}")
    code, _, err = run_cli([clean, "--trial", "T0", "--compare", clean, clean])
    check("7 --compare with a trial other than T3/all is an argument error", code == 2, f"exit {code}")
    code, out, _ = run_cli([clean, "--trial", "T3", "--compare", clean, clean])
    check("7 --compare with the same file twice FAILs with a clear message", code == 1 and "same file twice" in out, f"exit {code}")

    # 8. 'all' on recordings that are not the trial's recording: N/A, not FAIL
    rng = np.random.default_rng(1)
    def still(h, rows):
        ix = {k: h.index(k) for k in ("ax_mps2", "ay_mps2", "az_mps2", "gx_radps", "gy_radps", "gz_radps")}
        for r in rows:
            r[ix["ax_mps2"]], r[ix["ay_mps2"]], r[ix["az_mps2"]] = (f"{v:.4f}" for v in (0.02 * rng.standard_normal(), 0.02 * rng.standard_normal(), 9.80665 + 0.02 * rng.standard_normal()))
            for k in ("gx_radps", "gy_radps", "gz_radps"):
                r[ix[k]] = f"{0.002 * rng.standard_normal():.5f}"
        return rows
    def no_stomp_json(o):
        o.clear()
        o.update({"status": "refused", "reason": "no clear stomp at one end in one of the boots", "format_version": 1})
    def no_candidates(o):
        for b in o["turn_metrics"]["per_boot"].values():
            b["candidates"], b["usable"] = 0, 0
    mutate(clean, P("soak.zip"), {"samples.csv": edit_csv(still), "gesture-sync.json": edit_json(no_stomp_json),
                                  "analysis-summary.json": edit_json(no_candidates), "phone-motion.csv": None})
    rows = run_checks(P("soak.zip"), ORDER)
    other = [r for r in rows if r.trial in ("T1", "T2", "T3", "T4", "T5") and r.status == FAIL]
    check("8 a still soak (no stomp, no rolls, no phone file) under 'all': nothing FAILs in T1 to T5", not other, "; ".join(f"{r.trial} {r.check}" for r in other))
    check("8 the same soak: T1, T3, T4, T5 are N/A", all(get(rows, t, "")[0].status == NA for t in ("T1", "T3", "T4", "T5")))
    check("8 the same soak: the still-noise figures are really computed (INFO row)", get(rows, "T0", "gyro bias and drift L") and get(rows, "T0", "gyro bias and drift L")[0].status == INFO)
    rows = run_checks(P("soak.zip"), ["T1"])
    check("8 naming T1 on that soak does FAIL", get(rows, "T1", "gesture sync status")[0].status == FAIL, "")
    mutate(clean, P("nophone.zip"), {"phone-motion.csv": None})
    rows = run_checks(P("nophone.zip"), ORDER)
    check("8 a missing phone-motion.csv under 'all' is N/A, named T4 FAILs",
          all(r.status == NA for r in get(rows, "T4", "phone clock offset") + get(rows, "T4", "phone rows")) and run_checks(P("nophone.zip"), ["T4"])[0].status == FAIL)

    # 9. --json is strict JSON, utf-8, no bare NaN
    check("9 sanitize maps non-finite floats to None", sanitize({"a": NAN, "b": [float("inf"), 1.5], "c": np.float64("nan")}) == {"a": None, "b": [None, 1.5], "c": None})
    for src in (clean, P("nulls.zip")):
        outp = P("out.json")
        code, _, _ = run_cli([src, "--json", outp])
        def refuse(c):
            raise ValueError("non-finite constant " + c)
        try:
            with open(outp, "r", encoding="utf-8") as fh:
                doc = json.loads(fh.read(), parse_constant=refuse)
            ok = bool(doc.get("rows"))
        except (ValueError, OSError):
            ok = False
        check(f"9 --json parses with a strict loader ({os.path.basename(src)})", ok)
    nan_row = Row("T9", "x", "n/a", "", INFO, num=NAN).as_dict()
    check("9 a NaN number in a row is written as null", nan_row["number"] is None)

    # ---- second review
    # R1 an unreadable --compare file exits 2 (not a generic tool-error FAIL)
    code, _, err = run_cli([clean, "--trial", "T3", "--compare", P("garbage.zip"), clean])
    check("R1 unreadable --compare file exits 2 with a clear message", code == 2 and "--compare" in err, f"exit {code}")
    code, _, err = run_cli([clean, "--trial", "T3", "--compare", clean, P("does-not-exist.zip")])
    check("R1 missing --compare file exits 2", code == 2, f"exit {code}")

    # R2 rows built before an exception survive it
    real = still_noise_report.analyse_export
    def boom(*a, **k):
        raise RuntimeError("synthetic failure")
    still_noise_report.analyse_export = boom
    try:
        rows = run_checks(P("soak.zip"), ["T0"])
    finally:
        still_noise_report.analyse_export = real
    check("R2 an exception mid-trial keeps the earlier rows and adds a FAIL tool error",
          get(rows, "T0", "health verdict L") and get(rows, "T0", "ping round trips R") and get(rows, "T0", "tool error") and get(rows, "T0", "tool error")[0].status == FAIL,
          f"{len(rows)} rows")

    # R3 null / non-finite figures are N/A, never FAIL
    real_an = mag_heading_check.analyse
    def fake(path, side=None):
        r = real_an(path, side)
        r["gyro_rotation_turns"] = {"net_ccw_positive": 1.0, "total_absolute": 3.0}
        r["yaw_difference_deg"] = {"rms_offset_and_linear_trend_removed": None}
        return r
    mag_heading_check.analyse = fake
    try:
        rows = run_checks(clean, ["T2"])
    finally:
        mag_heading_check.analyse = real_an
    hd = get(rows, "T2", "magnetometer heading")
    check("R3 a null heading RMS is N/A, not FAIL", hd and hd[0].status == NA, hd[0].status if hd else "no row")
    def same_ns(h, rows):
        i = h.index("phone_ns")
        for r in rows:
            r[i] = "7200000000000"
        return rows
    mutate(clean, P("phone-same-ns.zip"), {"phone-motion.csv": edit_csv(same_ns)})
    rows = run_checks(P("phone-same-ns.zip"), ["T4"])
    pr = get(rows, "T4", "phone rows per second")
    check("R3 phone rows with zero time span: rate N/A, not FAIL", pr and pr[0].status == NA, pr[0].status if pr else "no row")

    # R4 missing session-health.json / clock-sync-fit.json: named trial FAILs, 'all' is N/A, rows always present
    mutate(clean, P("nohealth.zip"), {"session-health.json": None, "clock-sync-fit.json": None})
    for trials, want in ((["T0"], FAIL), (ORDER, NA)):
        rows = run_checks(P("nohealth.zip"), trials)
        sel = [r for r in rows if r.trial == "T0" and r.check.split(" ")[0] in ("health", "flash", "live", "gaps", "single") and r.check.split(" ")[-1] in ("L", "R")]
        check(f"R4 missing health and fit files, {'named T0' if want == FAIL else 'all'}: {len(sel)} rows, all {want}",
              len(sel) == 10 and all(r.status == want for r in sel), "; ".join(f"{r.check}={r.status}" for r in sel if r.status != want))

    # R5 the slope is the live stream's, not the first stream's
    def slopes(o):
        for s in o["boots"][0]["streams"]:
            s["clock_slope_ppm"] = 10.0 if s["source"] == "live" else 500.0
    mutate(clean, P("slope-live.zip"), {"session-health.json": edit_json(slopes)})
    sl = get(run_checks(P("slope-live.zip"), ["T0"]), "T0", "clock slope L")
    check("R5 live slope is used and labelled live (flash 500 ppm ignored)", sl and sl[0].status == PASS and "+10.0" in sl[0].value and "live" in sl[0].value, sl[0].value if sl else "no row")
    def live_null(o):
        for s in o["boots"][0]["streams"]:
            s["clock_slope_ppm"] = None if s["source"] == "live" else 500.0
    mutate(clean, P("slope-flash-only.zip"), {"session-health.json": edit_json(live_null)})
    sl = get(run_checks(P("slope-flash-only.zip"), ["T0"]), "T0", "clock slope L")
    check("R5 a flash-only health slope is never labelled 'health live stream'", sl and "500" not in sl[0].value and "health live" not in sl[0].value, sl[0].value if sl else "no row")

    # R6 null per-boot entry in clock-sync-fit.json
    def null_r(o):
        o["R"] = None
    mutate(clean, P("fit-null.zip"), {"clock-sync-fit.json": edit_json(null_r)})
    rows = run_checks(P("fit-null.zip"), ["T0"])
    check("R6 clock-sync-fit.json with R: null does not raise", not get(rows, "T0", "tool error") and get(rows, "T0", "single clock epoch R")[0].status == NA)

    # R7 one check name whatever the outcome
    names_moving = {r.check for r in run_checks(clean, ["T0"]) if r.check.startswith("gyro bias")}
    names_still = {r.check for r in run_checks(P("soak.zip"), ["T0"]) if r.check.startswith("gyro bias")}
    check("R7 still-noise check has the same name for a moving and a still recording", names_moving == names_still == {"gyro bias and drift L", "gyro bias and drift R"}, f"{sorted(names_moving)} vs {sorted(names_still)}")

    # R8 phone clock offset forms
    def with_offset(text):
        return lambda data: data.decode("utf-8").replace("offset_ms=-0.425", "offset_ms=" + text).encode("utf-8")
    for text, want, val in (("-1.5e-1", PASS, -0.15), ("+2.0", PASS, 2.0), ("NaN", FAIL, None), ("60.0", FAIL, 60.0)):
        mutate(clean, P("off.zip"), {"phone-motion.csv": with_offset(text)})
        of = get(run_checks(P("off.zip"), ["T4"]), "T4", "phone clock offset")
        check(f"R8 offset_ms={text} -> {want}" + (" (not recorded)" if val is None else ""),
              of and of[0].status == want and ((val is None and of[0].value == "not recorded") or (val is not None and abs(of[0].num - val) < 1e-9)), of[0].value if of else "no row")

    # R10 roughness is weighted by candidates, like the usable share
    def boot(n, rough):
        return {"candidates": n, "usable": n, "roughness": {"mean": rough, "sd": 1.0}}
    smooth = {"per_boot": {"L": boot(20, 5.0), "R": boot(20, 5.0)}, "boot_agreement": {}}
    rough = {"per_boot": {"L": boot(2, 20.0), "R": boot(18, 3.0)}, "boot_agreement": {}}
    st = get(compare_summaries(smooth, rough), "T3", "rough vs smooth: usable share or roughness")[0].status
    check("R10 a 2-candidate boot cannot drive the roughness direction (pooled 4.7 < 5, not the unweighted 11.5)", st != PASS and abs(mean_roughness(rough) - 4.7) < 1e-9, f"{st}, pooled {mean_roughness(rough)}")

    # R11 torso metrics (experimental): never a FAIL, whatever the block says
    def torso_nulls(o):
        tm = o["torso_metrics"]
        tm.update(run_coverage_fraction=None, rows_timing_limited=None)
        tm["quietness"].update(run_gyro_rms_dps=None, per_turn_gyro_rms_dps=None)
        for b in tm.get("per_boot", {}).values():
            b["tilt_vs_roll"].update(tilt_to_roll_ratio=None, correlation_across_turns=None)
            b["yaw"].update(turns=None)
        for t in tm.get("timing", {}).values():
            t.update(bias_removed_ms=None, max_uncertainty_ms=None)
    mutate(clean, P("torso-null.zip"), {"analysis-summary.json": edit_json(torso_nulls)})
    rows = get(run_checks(P("torso-null.zip"), ["T4"]), "T4", "torso")
    check("R11 null torso numbers: rows present, no tool error, no FAIL", rows and not [r for r in rows if r.status == FAIL] and all(r.check != "tool error" for r in rows),
          "; ".join(f"{r.check}={r.status}" for r in rows))
    def torso_refused(o):
        o["torso_metrics"] = {"experimental": True, "status": "refused", "reason": "refused: the phone covers 20 % of the session, under 50 %"}
    mutate(clean, P("torso-refused.zip"), {"analysis-summary.json": edit_json(torso_refused)})
    st = get(run_checks(P("torso-refused.zip"), ["T4"]), "T4", "torso metrics status")
    check("R11 a refused torso block is WARN with its reason", st and st[0].status == WARN and "covers" in st[0].why, st[0].why if st else "no row")
    def torso_missing(o):
        o.pop("torso_metrics", None)
    mutate(clean, P("torso-missing.zip"), {"analysis-summary.json": edit_json(torso_missing)})
    st = get(run_checks(P("torso-missing.zip"), ["T4"]), "T4", "torso")
    check("R11 an export without a torso block is one N/A row", len(st) == 1 and st[0].status == NA and "no torso_metrics block" in st[0].why, "; ".join(r.why for r in st))
    def torso_unmeasured(o):
        o["torso_metrics"]["timing"]["R"]["source"] = "unmeasured"
    mutate(clean, P("torso-unmeasured.zip"), {"analysis-summary.json": edit_json(torso_unmeasured)})
    st = get(run_checks(P("torso-unmeasured.zip"), ["T4"]), "T4", "torso timing")
    check("R11 unmeasured torso timing is WARN, not FAIL", st and st[0].status == WARN and "allowance" in st[0].why, st[0].why if st else "no row")

    w = max(len(n) for n, _, _ in results)
    for n, ok, dtl in results:
        print(f"{n.ljust(w)} | {'ok' if ok else 'FAILED'}" + ("" if ok or not dtl else f" | {dtl}"))
    bad = [n for n, ok, _ in results if not ok]
    print(f"\nSELFTEST {'PASSED' if not bad else 'FAILED'}: {len(results) - len(bad)} of {len(results)} checks")
    return 1 if bad else 0


# ---------------------------------------------------------------- regression over the dress-rehearsal ZIPs
class Expect:
    def __init__(self, zipname, trial, prefix, allowed, why, reason_has=None, explicit=None):
        self.zipname, self.trial, self.prefix, self.allowed, self.why = zipname, trial, prefix, set(allowed), why
        self.reason_has, self.explicit = reason_has, explicit   # explicit: run only this trial (one-trial rules)


def build_expectations():
    E = []
    ok = (PASS, INFO, WARN, NA)   # "no FAIL"

    def nofail(z, trial, why):
        E.append(Expect(z, trial, "", ok, why))   # empty prefix = every row of that trial

    for z in ("clean.zip", "clean-no-lag.zip"):
        for t in ("T0", "T1", "T3", "T4", "T5", "T6"):
            nofail(z, t, "a clean session has no FAIL in " + t)
    E.append(Expect("clean.zip", "T1", "stomp minus ping", (PASS,), "clean stomp and ping agree"))
    E.append(Expect("clean.zip", "T2", "magnetometer heading", (NA,), "the garden session has no turn on the spot: N/A, not a verdict"))
    E.append(Expect("clean.zip", "T0", "gyro bias and drift", (NA,), "moving recording: still-noise is N/A under 'all'"))
    E.append(Expect("clean.zip", "T0", "gyro bias and drift", (WARN,), "moving recording: still-noise is WARN when T0 is named", explicit=True))
    E.append(Expect("clean.zip", "T3", "rough vs smooth: derivative similarity", (PASS,), "the late right boot lowers left-right similarity (compared with clean-no-lag.zip)"))
    E.append(Expect("clean.zip", "T3", "rough vs smooth: usable share or roughness", (PASS,), "compared with clean-no-lag.zip: equal usable share, slightly rougher right boot"))
    E.append(Expect("clean.zip", "T3", "rough vs smooth: wedge fraction", (WARN,), "synthetic wedge fraction is 0 in both: WARN (cannot separate), never FAIL"))
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
    # boot-to-boot alignment used by the left-right agreement (T3)
    E.append(Expect("clean.zip", "T3", "left-right alignment", (INFO,), "clean: the stomp agrees with the pings and is applied", reason_has="agrees with the pings"))
    E.append(Expect("fault-right-timeline-late.zip", "T3", "left-right alignment", (INFO,), "a 40 ms late right timeline is corrected by the stomp", reason_has="stomp sync measured"))
    E.append(Expect("fault-right-timeline-late.zip", "T3", "", ok, "a timeline bias is not a FAIL"))
    E.append(Expect("fault-no-end-stomp.zip", "T3", "left-right alignment", (INFO,), "no end stomp: the pings are used", reason_has="pings only"))
    E.append(Expect("fault-no-pings.zip", "T3", "left-right alignment", (INFO,), "no pings: the stomp alone is used", reason_has="no ping pairs"))
    E.append(Expect("fault-clock-reset.zip", "T3", "left-right alignment", (WARN,), "a clock reset leaves the agreement unaligned", reason_has="not aligned"))
    # the generator's ping bias is at most half the interval difference (15 ms), below the left ping uncertainty alone (half a 45 ms return leg)
    E.append(Expect("fault-asymmetric-ping-bias.zip", "T3", "left-right alignment", (INFO,), "ping bias inside the ping uncertainty: the stomp is kept", reason_has="agrees with the pings"))
    E.append(Expect("fault-long-uptime.zip", "T1", "stomp minus ping", (PASS,), "boots up for hours (3 and 5 clock wraps): stomp and ping in one frame agree"))
    E.append(Expect("fault-long-uptime.zip", "T3", "left-right alignment", (INFO,), "boots up for hours: the stomp is applied", reason_has="agrees with the pings"))
    E.append(Expect("fault-long-uptime.zip", "T1", "", ok, "boots up for hours: no FAIL in T1"))
    # torso metrics (T4, experimental: INFO, WARN or N/A, never FAIL)
    E.append(Expect("clean.zip", "T4", "torso metrics status", (INFO,), "clean: torso metrics computed", reason_has="computed"))
    E.append(Expect("clean.zip", "T4", "torso timing", (INFO,), "clean: pings measure the phone-to-boot timeline bias", reason_has="ping"))
    E.append(Expect("clean.zip", "T4", "torso yaw vs boot heading", (NA,), "the default garden boots do not turn about the vertical", reason_has="did not turn"))
    for t in ("T0", "T1", "T3", "T4", "T5", "T6"):
        nofail("torso.zip", t, "the torso-truth session has no FAIL in " + t)
    E.append(Expect("torso.zip", "T4", "torso tilt vs boot roll", (INFO,), "torso tilts 0.4 of the boot roll (sideways only in this session)"))
    E.append(Expect("torso.zip", "T4", "torso yaw vs boot heading", (INFO,), "torso turns half the boot heading, same sign"))
    E.append(Expect("fault-no-phone.zip", "T4", "torso metrics status", (WARN,), "no phone rows: torso metrics refused", reason_has="no phone motion rows"))
    E.append(Expect("fault-phone-clock-unchecked.zip", "T4", "torso metrics status", (WARN,), "no phone clock check: torso metrics refused", reason_has="phone_clock_check"))
    E.append(Expect("fault-phone-clock-unchecked.zip", "T4", "phone clock offset", (FAIL,), "the phone clock check is missing", reason_has="phone_clock_check"))
    return E


def cmd_regress(d):
    zips = {os.path.basename(p): p for p in glob.glob(os.path.join(d, "*.zip"))}
    cache = {}

    def rows_for(name, explicit_trial=None):
        key = (name, explicit_trial)
        if key not in cache:
            p = zips[name]
            if explicit_trial:
                cache[key] = run_checks(p, [explicit_trial])
            else:
                cmp_ = (zips["clean-no-lag.zip"], p) if name == "clean.zip" and "clean-no-lag.zip" in zips else None
                cache[key] = run_checks(p, ORDER, cmp_)
        return cache[key]

    res = []
    exps = build_expectations()
    for e in exps:
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
    # 'all' on a ZIP that lacks a source must not FAIL the unrelated trials
    for name, unrelated in (("fault-no-magnetometer.zip", ("T1", "T3", "T4", "T5", "T6")), ("fault-live-only.zip", ("T2", "T3", "T4", "T6"))):
        if name in zips:
            bad = [r for r in rows_for(name) if r.trial in unrelated and r.status == FAIL]
            res.append((f"{name} 'all': no FAIL in unrelated trials {','.join(unrelated)}", "ok" if not bad else "WRONG",
                        "; ".join(f"{r.trial} {r.check}" for r in bad) or "unrelated trials unaffected", not bad))
    # numeric: aligning removes the injected 40 ms right-timeline bias from the onset lag (true lag 60 ms)
    if "fault-right-timeline-late.zip" in zips:
        try:
            s = Export(zips["fault-right-timeline-late.zip"]).json("analysis-summary.json")["boot_comparison"]
            al, un = jnum(s, "median_right_minus_left_onset_ms"), jnum(s, "unaligned_median_right_minus_left_onset_ms")
            good = fin(al) and fin(un) and abs(al - 60.0) < abs(un - 60.0) and abs(al - 60.0) <= 12.0
            res.append(("fault-right-timeline-late.zip aligned onset lag nearer the true 60 ms", "ok" if good else "WRONG",
                        f"aligned {fm(al, 1)} ms, unaligned {fm(un, 1)} ms", good))
        except (ExportError, KeyError, TypeError) as e:
            res.append(("fault-right-timeline-late.zip aligned onset lag", "WRONG", str(e), False))
    # numeric: the torso-truth session reads its known tilt (0.4 x boot roll) and heading (0.5 x boot heading; 0.475 on the
    # right boot, whose window is 60 ms after the torso's turn) ratios
    if "torso.zip" in zips:
        try:
            pb = Export(zips["torso.zip"]).json("analysis-summary.json")["torso_metrics"]["per_boot"]
            for side in EXPECTED_SIDES:
                tilt = jnum(pb[side]["tilt_vs_roll"]["tilt_to_roll_ratio"], "mean")
                yaw = jnum(pb[side]["yaw"]["torso_to_boot_heading_ratio"], "mean")
                want_yaw = 0.5 if side == "L" else 0.475
                good = fin(tilt) and fin(yaw) and abs(tilt - 0.4) <= 0.01 and abs(yaw - want_yaw) <= 0.01
                res.append((f"torso.zip {side} tilt and heading ratios near the truth (0.4, {want_yaw})", "ok" if good else "WRONG",
                            f"tilt/roll {fm(tilt, 3)}, torso/boot heading {fm(yaw, 3)}", good))
        except (ExportError, KeyError, TypeError) as e:
            res.append(("torso.zip torso ratios", "WRONG", str(e), False))
    # numeric: the biased ping must disagree more than the clean one
    try:
        cl = [r for r in rows_for("clean.zip") if r.check == "stomp minus ping"][0]
        bi = [r for r in rows_for("fault-asymmetric-ping-bias.zip") if r.check == "stomp minus ping"][0]
        good = abs(bi.num) > abs(cl.num)
        res.append(("fault-asymmetric-ping-bias.zip vs clean.zip |stomp - ping|", "ok" if good else "WRONG",
                    f"{bi.num:+.2f} ms vs {cl.num:+.2f} ms; line {STOMP_PING_MS:.0f} ms ({bi.status})", good))
    except (KeyError, IndexError, TypeError):
        res.append(("fault-asymmetric-ping-bias.zip vs clean.zip |stomp - ping|", "MISSING", "needs both zips", False))
    w = max(len(r[0]) for r in res)
    print(f"Regression over {len(zips)} ZIP(s) in {d}")
    print("T3 direction checks use clean-no-lag.zip as the smooth session and clean.zip as the rough one (right boot 60 ms late).\n")
    for lab, st, msg, good in res:
        print(f"{lab.ljust(w)} | {st.ljust(7)} | {msg}")
    extra = sorted(set(zips) - {e.zipname for e in exps})
    if extra:
        print("\nZIPs with no expectation (just reported): " + ", ".join(extra))
    print("\nPer-ZIP verdict counts (trial all):")
    for name in sorted(zips):
        c = counts(rows_for(name))
        print(f"  {name.ljust(36)} " + "  ".join(f"{k} {v}" for k, v in c.items()))
    nbad = sum(1 for r in res if not r[3])
    print(f"\n{len(res) - nbad} of {len(res)} expectations met" + ("" if not nbad else f"; {nbad} NOT met"))
    print(f"\nMutated-export selftest (findings the {len(zips)} ZIPs cannot show):")
    st = selftest(d)
    return 1 if (nbad or st) else 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Judge a garden trial from a session export.")
    ap.add_argument("export", nargs="?")
    ap.add_argument("--trial", default="all", choices=ORDER + ["all"])
    ap.add_argument("--compare", nargs=2, metavar=("SMOOTH.zip", "ROUGH.zip"), help="T3 direction checks between a smooth and a rough session")
    ap.add_argument("--json")
    ap.add_argument("--regress", metavar="DIR", help="run over the dress-rehearsal ZIPs in DIR and assert the expected verdicts")
    ap.add_argument("--selftest", nargs="?", const="", metavar="DIR", help="hand-made and mutated exports; needs clean.zip from DIR (default: $OPENSKI_DRESS_REHEARSAL_DIR or android/app/build/dress-rehearsal)")
    a = ap.parse_args(argv)
    if a.selftest is not None:
        d = a.selftest or os.environ.get("OPENSKI_DRESS_REHEARSAL_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "android", "app", "build", "dress-rehearsal")
        sys.exit(selftest(d))
    if a.regress:
        sys.exit(cmd_regress(a.regress))
    if not a.export:
        ap.error("export path required (or --regress DIR)")
    if a.compare and a.trial not in ("T3", "all"):
        ap.error(f"--compare only applies to --trial T3 (or all), not {a.trial}")
    sys.exit(cmd_report(a))


if __name__ == "__main__":
    main()
