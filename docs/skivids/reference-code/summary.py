"""Per-turn summaries from externally reviewed, same-definition boundaries."""

import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

from .contracts import read_json, validate_events, validate_manifest
from .pipeline import write_json


def summarise(analysis_path, annotations_path, output_path, schema_path):
    import jsonschema
    folder, output = Path(analysis_path), Path(output_path)
    analysis = read_json(folder / "analysis.json")
    manifest = validate_manifest(analysis["manifest"])
    boundaries = validate_events(read_json(annotations_path))
    if boundaries[-1]["time_s"] > analysis["video"]["last_relative_pts_s"]:
        raise ValueError("Reviewed boundary lies outside the decoded recording")
    if output.exists():
        raise ValueError("Refusing to overwrite turn output")
    schema = read_json(schema_path)
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    with (folder / "metrics.csv").open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    records = []
    for index, (start, end) in enumerate(zip(boundaries, boundaries[1:])):
        groups = defaultdict(list)
        for row in rows:
            if start["time_s"] <= float(row["timestamp_s"]) < end["time_s"]:
                groups[(row["metric_id"], row["side"])].append(row)
        if not groups:
            raise ValueError("Turn has no decoded samples")
        def quality(coverage, flags, uncertainty=None):
            return dict(valid_fraction=coverage, confidence=None, confidence_method=None,
                        uncertainty=uncertainty, flags=flags, validation_id=None)
        metrics = [dict(metric_id="turn_duration_s", value=end["time_s"]-start["time_s"], unit="s",
            side="not_applicable", coordinate_space="time", reference_axis=None, method="reviewed_boundary_difference",
            statistic="duration", quality=quality(None, ["reviewed_event_not_sensor_validation"],
                start["uncertainty_s"]+end["uncertainty_s"]))]
        for (metric_id, side), samples in groups.items():
            valid = [float(row["value"]) for row in samples if row["value"] and math.isfinite(float(row["value"]))]
            flags = sorted({flag for row in samples for flag in json.loads(row["flags"])})
            flags.append("coverage_is_frame_fraction_not_time_fraction")
            metrics.append(dict(metric_id=metric_id, value=statistics.median(valid) if valid else None,
                unit="deg", side=side, coordinate_space="image_2d_pixels", reference_axis="image_vertical_up" if "inclination" in metric_id else None,
                method=analysis["method"]["id"], statistic="median",
                quality=quality(len(valid)/len(samples), flags)))
        record = dict(schema_version="0.1.0", record_id=f"{manifest['recording_id']}-turn-{index:04d}",
            record_status="exploratory_observation", source=manifest["source"], recording_id=manifest["recording_id"],
            method_id=analysis["method"]["id"], context=manifest["context"],
            turn=dict(direction=start["next_turn_direction"], direction_basis=start.get("direction_basis"),
                start_s=start["time_s"], end_s=end["time_s"], boundary_definition=start["definition"], partial=False,
                events=[dict(event="transition", time_s=e["time_s"], uncertainty_s=e["uncertainty_s"],
                    observable=True, basis=f"{e['definition']}; reviewer: {e['reviewer']}") for e in (start, end)]),
            measurements=metrics, limitations=analysis["limitations"] + [
                "Median summaries can hide phase-specific movement; retain trajectories",
                "Duration uncertainty is the sum of reviewer bounds, not a confidence interval",
                "Frame coverage differs from temporal coverage for variable frame rate"])
        validator.validate(record)
        records.append(record)
    # Serialize and validate all records before publishing the output.
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, allow_nan=False) + "\n")
    return len(records)
