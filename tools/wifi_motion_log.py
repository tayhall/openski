"""Capture bench motion predictions and raw IMU snapshots over Wi-Fi."""
import argparse
import datetime
import json
import time
import urllib.request
import urllib.error

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output")
parser.add_argument("--host", default="ski-s3.local")
parser.add_argument("--duration", type=float, default=90)
args = parser.parse_args()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
deadline = time.monotonic() + args.duration
previous = None
with open(args.output, "a", encoding="utf-8") as log:
    while time.monotonic() < deadline:
        row = {"host_time": datetime.datetime.now(datetime.timezone.utc).isoformat()}
        try:
            for endpoint in ("motion", "imu"):
                with opener.open(f"http://{args.host}/api/v1/{endpoint}", timeout=2) as response:
                    row[endpoint] = json.load(response)
            state = (row["motion"]["label"], row["motion"].get("gesture_count",0))
            if state != previous:
                print(json.dumps(row["motion"]), flush=True)
            previous = state
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            row["error"] = str(error)
        log.write(json.dumps(row) + "\n")
        log.flush()
        time.sleep(min(0.2, max(0, deadline-time.monotonic())))
