"""Boot-IMU orientation: mounting calibration and roll estimation.

Boot frame (docs/sensor-integration.md): +X forward, +Y left, +Z up through the sole.
Positive roll is rotation about +X, i.e. the boot tilting to the skier's right.
Sensor samples are accelerometer specific force (m/s^2, reads +g upward at rest) and gyro (rad/s).

Status: research code, validated only against simulated signals so far. Offline (zero-phase) analysis.
"""
import numpy as np

G = 9.80665


class CalibrationError(ValueError):
    """Raised when the calibration movements are too poor to trust."""


def _unit(v):
    n = np.linalg.norm(v)
    if n < 1e-9:
        raise CalibrationError("Degenerate vector in calibration")
    return v / n


def calibrate_mounting(static_acc, static_gyro, flex_gyro, fs, max_gravity_error=0.12, min_axis_energy=0.8,
                       max_static_gyro_std=0.08):
    """Estimate the sensor-to-boot rotation from two short movements.

    1. Stand still, boots flat: gravity gives boot +Z in sensor axes; the gyro mean is its bias.
    2. Bend the knees forward, then straighten (a few repeats): the gyro rotates about boot +Y.
       The first excursion is taken as forward flexion (positive rotation about +Y).

    Returns dict with R_bs (3x3, v_boot = R_bs @ v_sensor), gyro_bias, and quality diagnostics.
    """
    static_acc, static_gyro, flex_gyro = (np.asarray(a, float) for a in (static_acc, static_gyro, flex_gyro))
    if len(static_acc) < fs or len(flex_gyro) < fs:
        raise CalibrationError("Calibration segments must be at least one second")
    acc_mean = static_acc.mean(axis=0)
    g_err = abs(np.linalg.norm(acc_mean) - G) / G
    if g_err > max_gravity_error:
        raise CalibrationError(f"Static segment magnitude is {g_err:.0%} away from 1 g: not standing still?")
    if static_gyro.std(axis=0).max() > max_static_gyro_std:
        raise CalibrationError("Gyro not quiet during the standing segment")
    z_b = _unit(acc_mean)
    bias = static_gyro.mean(axis=0)

    w = flex_gyro - bias
    w_perp = w - np.outer(w @ z_b, z_b)  # remove the part that is rotation about vertical
    _, s, vt = np.linalg.svd(w_perp, full_matrices=False)
    axis = vt[0]
    energy = float(s[0] ** 2 / max((s ** 2).sum(), 1e-12))
    if energy < min_axis_energy:
        raise CalibrationError(f"Knee-flex motion not about a single axis (energy {energy:.2f})")
    proj = w_perp @ axis
    big = np.flatnonzero(np.abs(proj) > 0.4 * np.abs(proj).max())
    if len(big) == 0:
        raise CalibrationError("No clear knee-flex movement")
    if proj[big[0]] < 0:  # first excursion must be forward flexion = +Y rotation
        axis = -axis
    y_b = _unit(axis - (axis @ z_b) * z_b)
    x_b = np.cross(y_b, z_b)
    R_bs = np.vstack([x_b, y_b, z_b])
    return dict(R_bs=R_bs, gyro_bias=bias, gravity_error=float(g_err), axis_energy=energy,
                flex_peak_rad_s=float(np.abs(proj).max()), fs=fs)


def to_boot_frame(cal, acc, gyro):
    R = cal["R_bs"]
    return np.asarray(acc, float) @ R.T, (np.asarray(gyro, float) - cal["gyro_bias"]) @ R.T


def estimate_roll(acc_b, gyro_b, fs, tau_s=4.0, gate=0.15):
    """Roll angle (degrees, + = tilt right) from boot-frame data by complementary filtering.

    The accelerometer corrects drift only when |f| is within `gate` of 1 g, because cornering
    force contaminates it exactly when the boot is most tilted. Returns (roll_deg, trusted_fraction).
    """
    n = len(gyro_b)
    dt = 1.0 / fs
    alpha = tau_s / (tau_s + dt)
    mag = np.linalg.norm(acc_b, axis=1)
    ok = np.abs(mag - G) < gate * G
    phi_acc = np.arctan2(acc_b[:, 1], acc_b[:, 2])
    phi = np.zeros(n)
    phi[0] = phi_acc[0] if ok[0] else 0.0
    for i in range(1, n):
        pred = phi[i - 1] + gyro_b[i, 0] * dt
        phi[i] = alpha * pred + (1 - alpha) * phi_acc[i] if ok[i] else pred
    return np.degrees(phi), float(ok.mean())


def estimate_attitude(acc_b, gyro_b, fs, tau_s=4.0, ki=0.0, gate=0.15, q0=None):
    """Full 3D attitude from boot-frame data (quaternion complementary filter, Mahony style).

    The one-axis estimate_roll() integrates only the roll-axis gyro and ignores the coupling between
    pitch, yaw rate and roll rate, which matters when the boot pitches while the skier turns. This filter
    integrates all three gyro axes and corrects tilt with the accelerometer's gravity direction, gated by
    force magnitude exactly as before.

    tau_s: tilt correction time constant (kp = 1 / tau_s).
    ki: integral gain for online gyro-bias estimation (0 disables; use small values, accel is contaminated).
    Returns (roll_deg, pitch_deg, trusted_fraction). Roll > 0 tilts to the right (rotation about +X).
    """
    acc = np.asarray(acc_b, float)
    gyr = np.asarray(gyro_b, float)
    n = len(gyr)
    dt = 1.0 / fs
    kp = 1.0 / tau_s
    mag = np.linalg.norm(acc, axis=1)
    ok = np.abs(mag - G) < gate * G
    unit = acc / np.maximum(mag, 1e-9)[:, None]
    if q0 is None:  # initial tilt from the first trusted accelerometer sample
        i0 = int(np.argmax(ok)) if ok.any() else 0
        roll0 = np.arctan2(unit[i0, 1], unit[i0, 2])
        pitch0 = np.arctan2(-unit[i0, 0], np.hypot(unit[i0, 1], unit[i0, 2]))
        cr, sr, cp, sp = np.cos(roll0 / 2), np.sin(roll0 / 2), np.cos(pitch0 / 2), np.sin(pitch0 / 2)
        q = np.array([cr * cp, sr * cp, cr * sp, -sr * sp])  # ZYX, yaw 0
    else:
        q = np.array(q0, float)
    roll = np.empty(n)
    pitch = np.empty(n)
    bias = np.zeros(3)
    for i in range(n):
        w = gyr[i] - bias
        if ok[i]:
            qw, qx, qy, qz = q
            up = np.array([2 * (qx * qz - qw * qy), 2 * (qw * qx + qy * qz), qw * qw - qx * qx - qy * qy + qz * qz])
            err = np.cross(unit[i], up)
            if ki:
                bias -= ki * err * dt
            w = w + kp * err
        qw, qx, qy, qz = q
        wx, wy, wz = w
        q = q + 0.5 * dt * np.array([-qx * wx - qy * wy - qz * wz, qw * wx + qy * wz - qz * wy,
                                     qw * wy - qx * wz + qz * wx, qw * wz + qx * wy - qy * wx])
        q /= np.linalg.norm(q)
        qw, qx, qy, qz = q
        roll[i] = np.degrees(np.arctan2(2 * (qw * qx + qy * qz), 1 - 2 * (qx * qx + qy * qy)))
        pitch[i] = np.degrees(np.arcsin(np.clip(2 * (qw * qy - qz * qx), -1, 1)))
    return roll, pitch, float(ok.mean())


class MountMonitor:
    """Flags a strap that moved after calibration.

    Compares two baseline-relative quantities in boot-frame data: the direction of the principal
    horizontal gyro axis (turning should rotate about boot X; a slipped strap rotates it in the
    horizontal plane) and the windowed mean specific force (a tilted mount shifts it).
    The first `baseline_s` of the run is the reference.
    """

    def __init__(self, fs, window_s=4.0, baseline_s=12.0, axis_deg=8.0, force_g=0.10):
        self.fs, self.w, self.b = fs, int(window_s * fs), int(baseline_s * fs)
        self.axis_deg, self.force_g = axis_deg, force_g

    @staticmethod
    def _axis_deg(gyro_b):
        sxx, syy, sxy = (gyro_b[:, 0] ** 2).sum(), (gyro_b[:, 1] ** 2).sum(), (gyro_b[:, 0] * gyro_b[:, 1]).sum()
        return float(np.degrees(0.5 * np.arctan2(2 * sxy, sxx - syy)))

    def check(self, acc_b, gyro_b):
        """Return a list of (time_s, reason), one per window that deviates; empty when stable.

        time_s is the end of the deviating window, i.e. when a live monitor could first raise it."""
        n = len(gyro_b)
        if n < self.b + self.w:
            return []
        step = max(1, self.w // 2)
        ref_axis = self._axis_deg(gyro_b[:self.b])
        # Reference force from several baseline windows: a window is rarely a whole number of turns,
        # so its mean cornering force varies naturally; use that spread as the tolerance.
        starts = list(range(0, self.b - self.w + 1, step)) or [0]
        means = np.array([acc_b[a:a + self.w, :2].mean(axis=0) / G for a in starts])
        ref_force = np.median(means, axis=0)
        spread = float(np.max(np.linalg.norm(means - ref_force, axis=1)))
        tol = max(self.force_g, 2.0 * spread)
        flags = []
        for a in range(self.b, n - self.w + 1, step):
            ax = self._axis_deg(gyro_b[a:a + self.w])
            d_ax = (ax - ref_axis + 90) % 180 - 90
            d_f = float(np.linalg.norm(acc_b[a:a + self.w, :2].mean(axis=0) / G - ref_force))
            if abs(d_ax) > self.axis_deg:
                flags.append(((a + self.w) / self.fs, f"gyro roll axis moved {d_ax:.0f} deg"))
            elif d_f > tol:
                flags.append(((a + self.w) / self.fs, f"mean force shifted {d_f:.2f} g"))
        return flags
