"""Record Wi-Fi/Bluetooth diagnostics without a USB phone connection."""
import argparse
import datetime
import json
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", help="JSONL log file")
    parser.add_argument("--host", default="ski-s3.local")
    parser.add_argument("--duration", type=float, default=60, help="Seconds to monitor")
    args = parser.parse_args()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    previous = None
    previous_uptime = None
    deadline = time.monotonic() + args.duration
    with open(args.output, "a", encoding="utf-8") as log:
        while time.monotonic() < deadline:
            row = {"host_time": datetime.datetime.now(datetime.timezone.utc).isoformat()}
            try:
                with opener.open(f"http://{args.host}/api/v1/status", timeout=3) as response:
                    row["status"] = json.load(response)
                status = row["status"]
                ble = status["ble"]
                state = (ble["connected"], ble["connections"], ble["disconnections"])
                uptime = status["uptime_ms"]
                if previous_uptime is not None and uptime < previous_uptime:
                    row["reboot_detected"] = True
                if state != previous or row.get("reboot_detected"):
                    print(json.dumps(row), flush=True)
                previous = state
                previous_uptime = uptime
            except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as error:
                row["error"] = str(error)
                print(json.dumps(row), flush=True)
                previous = None
            log.write(json.dumps(row) + "\n")
            log.flush()
            time.sleep(min(1, max(0, deadline - time.monotonic())))


if __name__ == "__main__":
    main()
