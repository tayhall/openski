"""Rights gating, local-file checks and reproducible asset identity."""

import hashlib
import json
import math
from pathlib import Path


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024*1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_manifest(manifest):
    for key in ("recording_id", "skier_id", "source", "context", "timing", "review"):
        if key not in manifest:
            raise ValueError(f"Manifest requires {key}")
    for key in ("recording_id", "skier_id"):
        if not isinstance(manifest[key], str) or not manifest[key].strip():
            raise ValueError(f"{key} must be a nonempty pseudonymous identifier")
    source = manifest["source"]
    for key in ("source_id", "url", "title", "attribution", "published_at", "accessed_at", "evidence_kind", "permission"):
        if key not in source:
            raise ValueError(f"Source requires {key}")
    permission = source["permission"]
    # personal_research: private study of published training footage. Only derived numbers are
    # kept; media is never copied, exported or redistributed (the pipeline never writes frames).
    if permission.get("status") not in ("owned", "licensed", "written_permission", "personal_research"):
        raise ValueError("Processing needs owned, licensed, written_permission or personal_research status")
    if not isinstance(permission.get("evidence"), str) or not permission["evidence"].strip():
        raise ValueError("Permission evidence or ownership declaration is required")
    for key in ("processing_allowed", "derivative_retention_allowed"):
        if permission.get(key) is not True:
            raise ValueError(f"Permission must explicitly allow {key}")
    if manifest["timing"].get("playback_speed") != 1 or manifest["timing"].get("original_timing_confirmed") is not True:
        raise ValueError("Original real-time playback must be confirmed; altered/unknown speed is unsupported")
    review = manifest["review"]
    if review.get("single_skier") is not True or review.get("continuous_shot") is not True:
        raise ValueError("Initial pipeline requires reviewed single-skier, continuous-shot footage")
    if review.get("image_upright") is not True:
        raise ValueError("Decoded image orientation must be reviewed as upright")
    return manifest


def validate_events(data):
    """Reviewed boundaries only; no inference of ski contact from body pose."""
    if not isinstance(data, dict) or not isinstance(data.get("boundaries"), list):
        raise ValueError("Annotations require a boundaries array")
    boundaries = data["boundaries"]
    if len(boundaries) < 2:
        raise ValueError("At least two reviewed boundaries are required")
    previous = -math.inf
    for event in boundaries:
        time = event.get("time_s")
        error = event.get("uncertainty_s")
        if isinstance(time, bool) or not isinstance(time, (int, float)) or not math.isfinite(time) or time < 0 or time <= previous:
            raise ValueError("Boundary times must be finite, nonnegative and strictly increasing")
        if isinstance(error, bool) or not isinstance(error, (int, float)) or not math.isfinite(error) or error < 0:
            raise ValueError("Every reviewed boundary requires nonnegative timing uncertainty")
        if not event.get("reviewer") or not event.get("definition"):
            raise ValueError("Every boundary requires reviewer and definition")
        if event.get("next_turn_direction") not in ("left", "right", "unknown"):
            raise ValueError("next_turn_direction must be left, right or unknown")
        previous = time
    if len({e["definition"] for e in boundaries}) != 1:
        raise ValueError("Turn duration requires a consistent boundary definition")
    return boundaries
