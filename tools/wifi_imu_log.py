"""Logs the board's Wi-Fi sensor endpoint to CSV, with no USB link needed.

Usage: python tools/wifi_imu_log.py out.csv [hours]

Polls http://ski.local/api/v1/imu (then a fallback IP) twice a second and writes the host time, board uptime,
sensor temperature, accelerometer (m/s^2), gyro (rad/s) and read failures. A drop in uptime means the board was
power-cycled, so a cold start shows up as a fresh run of rows. Needs the PC on the same network as the board.
"""
import sys, time, json, datetime, urllib.request

HOSTS = ["ski.local", "192.168.43.117"]
out_path = sys.argv[1]
duration = float(sys.argv[2]) * 3600 if len(sys.argv) > 2 else 3 * 3600
def stamp(): return datetime.datetime.now().isoformat(timespec="milliseconds")

with open(out_path, "a", encoding="utf-8") as f:
    f.write("# columns: host_time,uptime_us,temp_c,ax,ay,az,gx_rad_s,gy_rad_s,gz_rad_s,read_failures\n")
    started = time.time(); next_poll = time.monotonic(); down = False
    while time.time() - started < duration:
        sample = None
        for host in HOSTS:
            try:
                with urllib.request.urlopen(f"http://{host}/api/v1/imu", timeout=2) as r:
                    sample = json.loads(r.read()); break
            except Exception:
                continue
        if sample and sample.get("has_sample"):
            if down: f.write(f"# {stamp()} board reachable again\n"); down = False
            a, g = sample["accel_mps2"], sample["gyro_radps"]
            f.write(f"{stamp()},{sample['timestamp_us']},{sample['temperature_c']},{a['x']},{a['y']},{a['z']},{g['x']},{g['y']},{g['z']},{sample['read_failures']}\n")
        elif not down:
            f.write(f"# {stamp()} board unreachable\n"); down = True
        f.flush()
        next_poll += 0.5
        time.sleep(max(0, next_poll - time.monotonic()))
