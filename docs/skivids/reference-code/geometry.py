"""Apparent image-plane geometry. Image x points right and y points down."""

import math


def included_angle(a, b, c):
    """Angle ABC in degrees; None for nonfinite or degenerate geometry."""
    u = (a[0] - b[0], a[1] - b[1])
    v = (c[0] - b[0], c[1] - b[1])
    if not all(math.isfinite(x) for x in (*u, *v)):
        return None
    scale = math.hypot(*u) * math.hypot(*v)
    if scale <= 1e-12:
        return None
    return math.degrees(math.acos(max(-1, min(1, (u[0]*v[0] + u[1]*v[1])/scale))))


def inclination(bottom, top):
    """Signed degrees from upward image vertical; positive leans image-right."""
    dx, dy = top[0] - bottom[0], bottom[1] - top[1]
    if not all(math.isfinite(x) for x in (dx, dy)) or math.hypot(dx, dy) <= 1e-12:
        return None
    return math.degrees(math.atan2(dx, dy))


def difference(a, b):
    return (a - b + 180) % 360 - 180


def measure_pose(landmarks, width, height, threshold):
    """Return metrics with their contributing-landmark diagnostics.

    Threshold is a configurable tracking mask, not a validated accuracy bound.
    Low-quality values are suppressed rather than interpolated.
    """
    definitions = [
        ("knee_flexion_apparent_2d_deg", "left", (23, 25, 27), "knee"),
        ("knee_flexion_apparent_2d_deg", "right", (24, 26, 28), "knee"),
        ("leg_inclination_image_deg", "left", (27, 23), "inclination"),
        ("leg_inclination_image_deg", "right", (28, 24), "inclination"),
        ("torso_inclination_image_deg", "not_applicable", (23, 24, 11, 12), "torso"),
        ("torso_leg_angulation_apparent_2d_deg", "left", (23, 24, 11, 12, 27), "angulation"),
        ("torso_leg_angulation_apparent_2d_deg", "right", (23, 24, 11, 12, 28), "angulation"),
    ]
    rows = []
    for metric, side, indices, kind in definitions:
        flags, value = [], None
        vis, presence = None, None
        if len(landmarks) != 33:
            flags.append("pose_not_detected")
        else:
            points = [landmarks[i] for i in indices]
            diagnostics = [p.get(k) for p in points for k in ("visibility", "presence")]
            if any(x is None or not math.isfinite(x) for x in diagnostics):
                flags.append("missing_landmark_quality")
            else:
                vis = min(p["visibility"] for p in points)
                presence = min(p["presence"] for p in points)
                if min(vis, presence) < threshold:
                    flags.append("low_landmark_quality")
            coords = [(p["x"]*width, p["y"]*height) for p in points]
            if any(not math.isfinite(x) for p in coords for x in p):
                flags.append("nonfinite_landmark")
            if any(not (0 <= p["x"] <= 1 and 0 <= p["y"] <= 1) for p in points):
                flags.append("out_of_frame_landmark")
            if not flags:
                if kind == "knee":
                    angle = included_angle(*coords)
                    value = None if angle is None else 180 - angle
                elif kind == "inclination":
                    value = inclination(*coords)
                else:
                    pelvis = tuple((coords[0][i]+coords[1][i])/2 for i in (0, 1))
                    shoulders = tuple((coords[2][i]+coords[3][i])/2 for i in (0, 1))
                    value = inclination(pelvis, shoulders)
                    if kind == "angulation":
                        # Anatomical hip on the relevant side, not pelvis midpoint.
                        hip = coords[0] if side == "left" else coords[1]
                        leg = inclination(coords[4], hip)
                        value = None if value is None or leg is None else difference(value, leg)
                if value is None:
                    flags.append("degenerate_geometry")
        rows.append(dict(metric_id=metric, side=side, value=value, unit="deg",
                         min_visibility=vis, min_presence=presence,
                         confidence=None, flags=flags + ["unvalidated_2d", "manual_identity_review_required"]))
    return rows
