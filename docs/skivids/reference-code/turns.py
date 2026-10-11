"""Turn detection from a boot roll-angle trace.

A turn switch is a zero crossing of the de-trended roll angle with hysteresis, so small wobbles
around upright do not create turns. This is an operational definition (boot tilt reversal), not a
validated measure of ski edge change or load transfer.
"""
import numpy as np


def detect_turns(roll_deg, fs, min_amp_deg=6.0, min_dur_s=0.3, baseline_s=4.0):
    """Return a list of turns: start_s, end_s, duration_s, direction, peak_deg.

    direction is 'right' when roll is positive (tilt right), 'left' when negative.
    """
    roll = np.asarray(roll_deg, float)
    k = max(1, int(baseline_s * fs))
    kernel = np.ones(k) / k
    pad = np.pad(roll, (k // 2, k - 1 - k // 2), mode="edge")
    x = roll - np.convolve(pad, kernel, mode="valid")
    state, start, turns = 0, 0, []
    for i, v in enumerate(x):
        s = 1 if v > min_amp_deg else (-1 if v < -min_amp_deg else 0)
        if s != 0 and s != state:
            if state != 0:
                seg = x[start:i]
                # boundary = last zero crossing before the new lobe
                idx = np.flatnonzero(np.sign(x[start:i + 1][:-1]) != np.sign(x[start:i + 1][1:]))
                end = start + (idx[-1] if len(idx) else len(seg) - 1)
                turns.append((start, end, state))
                start = end
            else:
                start = max(0, i - int(0.5 * fs))
            state = s
    if state != 0:  # close the final lobe at the last zero crossing, or at the end of the trace
        idx = np.flatnonzero(np.sign(x[start:-1]) != np.sign(x[start + 1:]))
        turns.append((start, start + (idx[-1] if len(idx) else len(x) - 1 - start), state))
    out = []
    for a, b, s in turns:
        seg = x[a:b + 1]
        dur = (b - a) / fs
        if dur >= min_dur_s and len(seg):
            out.append(dict(start_s=a / fs, end_s=b / fs, duration_s=dur,
                            direction="right" if s > 0 else "left", peak_deg=float(seg[np.argmax(np.abs(seg))])))
    return out


class StreamingTurnDetector:
    """Causal turn detector: feed roll angle sample by sample, get a turn when its lobe ends.

    The baseline is an exponential moving average, so decisions use only past data. A turn is
    emitted at the zero crossing that ends a lobe whose peak exceeded `min_amp_deg`.
    """

    def __init__(self, fs, min_amp_deg=6.0, min_dur_s=0.3, baseline_tau_s=3.0):
        self.fs, self.amp, self.min_dur = fs, min_amp_deg, int(min_dur_s * fs)
        self.k = 1.0 / (baseline_tau_s * fs)
        self.base, self.n = None, 0
        self.sign, self.peak, self.start = 0, 0.0, 0

    def push(self, roll_deg):
        """Return a turn dict if this sample closes one, else None."""
        if self.base is None:
            self.base = roll_deg
        self.base += self.k * (roll_deg - self.base)
        x = roll_deg - self.base
        i, self.n = self.n, self.n + 1
        s = 1 if x > 0 else -1
        if self.sign == 0:
            if abs(x) > self.amp:
                self.sign, self.peak, self.start = s, x, i
            return None
        if s == self.sign:
            if abs(x) > abs(self.peak):
                self.peak = x
            return None
        out = None
        if abs(self.peak) >= self.amp and i - self.start >= self.min_dur:
            out = dict(start_s=self.start / self.fs, end_s=i / self.fs, duration_s=(i - self.start) / self.fs,
                       direction="right" if self.sign > 0 else "left", peak_deg=float(self.peak))
        self.sign, self.peak, self.start = s, x, i
        return out


def heading_rate_dps(gyro_b, acc_b, fs, gravity_s=1.0):
    """Rotation rate about the vertical in deg/s: gyro projected on the gravity direction.

    The direction is the accelerometer averaged over `gravity_s` seconds, so it needs no attitude
    estimator and stays usable through vibration. Turns appear as sign changes of this rate.
    """
    gyro = np.asarray(gyro_b, float)
    acc = np.asarray(acc_b, float)
    k = max(1, int(round(gravity_s * fs)))
    kern = np.ones(k) / k
    up = np.stack([np.convolve(np.pad(acc[:, i], (k // 2, k - 1 - k // 2), mode="edge"), kern, mode="valid")
                   for i in range(3)], axis=1)
    up = up / np.maximum(np.linalg.norm(up, axis=1, keepdims=True), 1e-9)
    return np.degrees(np.sum(gyro * up, axis=1))


def detect_turns_heading(gyro_b, acc_b, fs, smooth_s=0.8, amp_sd=0.6, min_dur_s=0.4, baseline_s=4.0):
    """Turn switches as zero crossings of the smoothed heading rate (the best signal on public ski data).

    Defaults come from a tuning on five skiers of the SkiC-LSTM data; on the five held-out skiers they gave
    F1 0.74 at +-0.5 s (docs/ski-turn-benchmark.md). Returns the same dicts as detect_turns, where
    direction is the sign of the heading rate: 'right' for a positive rate about the up axis, else 'left'
    (check the sign convention against the boot frame before using it for left/right labelling).
    """
    rate = heading_rate_dps(gyro_b, acc_b, fs)
    k = max(1, int(round(smooth_s * fs)))
    rate = np.convolve(np.pad(rate, (k // 2, k - 1 - k // 2), mode="edge"), np.ones(k) / k, mode="valid")
    return detect_turns(rate, fs, min_amp_deg=amp_sd * float(np.std(rate)), min_dur_s=min_dur_s, baseline_s=baseline_s)

