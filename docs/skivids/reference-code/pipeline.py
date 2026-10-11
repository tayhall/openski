"""Local permitted-video analysis using decoder presentation timestamps."""

import csv
import importlib.metadata
import json
import math
import shutil
import tempfile
from pathlib import Path

from .contracts import read_json, sha256, validate_manifest
from .geometry import measure_pose


METRIC_FIELDS = ["frame_index", "timestamp_s", "inference_timestamp_ms", "metric_id", "side", "value", "unit", "min_visibility", "min_presence", "confidence", "flags"]


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def analyse(video_path, model_path, manifest_path, output_path, threshold=0.5):
    manifest = validate_manifest(read_json(manifest_path))
    if not math.isfinite(threshold) or not 0 <= threshold <= 1:
        raise ValueError("Tracking threshold must be between zero and one")
    video, model, output = Path(video_path).resolve(), Path(model_path).resolve(), Path(output_path).resolve()
    for path in (video, model):
        if not path.is_file():
            raise ValueError(f"Missing local file: {path}")
    if output.exists():
        raise ValueError("Output directory must not exist; refusing to overwrite research records")
    # Imports occur after permission gate, before creating any output.
    import av
    import mediapipe as mp
    options = mp.tasks.vision.PoseLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        num_poses=1,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    # Stage derived data only; no video copies or frames are written to disk.
    staging = Path(tempfile.mkdtemp(prefix=".openski-", dir=output.parent))
    count = detected = 0
    first_time = last_time = previous_ms = None
    dimensions = None
    try:
        with av.open(str(video)) as container, mp.tasks.vision.PoseLandmarker.create_from_options(options) as detector, \
                (staging / "landmarks.jsonl").open("w", encoding="utf-8") as landmarks_file, \
                (staging / "metrics.csv").open("w", newline="", encoding="utf-8") as metrics_file:
            if not container.streams.video:
                raise ValueError("No video stream")
            writer = csv.DictWriter(metrics_file, fieldnames=METRIC_FIELDS)
            writer.writeheader()
            for frame in container.decode(video=0):
                if frame.pts is None or frame.time_base is None:
                    raise ValueError("Missing presentation timestamp; nominal FPS fallback is prohibited")
                timestamp = float(frame.pts * frame.time_base)
                if not math.isfinite(timestamp) or (last_time is not None and timestamp <= last_time):
                    raise ValueError("Missing or non-increasing presentation time")
                if first_time is None:
                    first_time = timestamp
                time_s = timestamp - first_time
                time_ms = round(time_s * 1000)
                if previous_ms is not None and time_ms <= previous_ms:
                    raise ValueError("Presentation timestamps collide at MediaPipe millisecond resolution")
                if dimensions is not None and dimensions != (frame.width, frame.height):
                    raise ValueError("Changing image dimensions require separate recording segments")
                dimensions = (frame.width, frame.height)
                image = mp.Image(image_format=mp.ImageFormat.SRGB, data=frame.to_ndarray(format="rgb24"))
                result = detector.detect_for_video(image, time_ms)
                def serialise(p):
                    return {key: (float(getattr(p, key)) if getattr(p, key, None) is not None and math.isfinite(getattr(p, key)) else None)
                            for key in ("x", "y", "z", "visibility", "presence")}
                landmarks = [serialise(p) for p in result.pose_landmarks[0]] if result.pose_landmarks else []
                world = [serialise(p) for p in result.pose_world_landmarks[0]] if result.pose_world_landmarks else []
                # Reject unusable x/y before geometry; preserve missing coordinates as null.
                usable = landmarks if all(p["x"] is not None and p["y"] is not None for p in landmarks) else []
                landmarks_file.write(json.dumps(dict(frame_index=count, timestamp_s=time_s,
                    inference_timestamp_ms=time_ms, width=frame.width, height=frame.height,
                    landmarks=landmarks, model_estimated_world_landmarks=world), allow_nan=False) + "\n")
                for row in measure_pose(usable, frame.width, frame.height, threshold):
                    row.update(frame_index=count, timestamp_s=time_s, inference_timestamp_ms=time_ms)
                    row["flags"] = json.dumps(row["flags"])
                    writer.writerow(row)
                count += 1
                detected += bool(landmarks)
                last_time, previous_ms = timestamp, time_ms
        if count == 0:
            raise ValueError("No decoded frames")
        write_json(staging / "analysis.json", {
            "schema_version": "0.1.0", "status": "exploratory_unvalidated", "manifest": manifest,
            "assets": {"video_sha256": sha256(video), "model_sha256": sha256(model)},
            "method": {"id": "mediapipe_apparent_2d_v0.1.0", "packages": {p: importlib.metadata.version(p) for p in ("mediapipe", "av")},
                "tracking_quality_threshold": threshold, "threshold_validated": False,
                "coordinate_space": "image_2d_pixels", "reference_axis": "image_vertical_up",
                "inclination_sign": "positive_toward_image_right", "knee_convention": "180_minus_included_angle",
                "filtering": "none", "interpolation": "none", "confidence_calibrated": False},
            "video": {"width": dimensions[0], "height": dimensions[1], "first_pts_s": first_time,
                "last_relative_pts_s": last_time-first_time, "frames": count, "pose_detected_frames": detected},
            "limitations": ["2D projection, not anatomical ground truth", "No ski edge or loading measurements",
                "Manual identity/side/occlusion review required", "No automatic turn or phase inference",
                "Detection coverage is not measurement accuracy"]})
        staging.rename(output)
    except BaseException:
        # Only the pipeline-created staging directory is removed, never user inputs.
        shutil.rmtree(staging)
        raise
    return output
