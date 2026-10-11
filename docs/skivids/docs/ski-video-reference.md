# The Beuken ski video: what it is and what we got from it

Date: 2026-10-11. Code: `tools/digitize_beuken_video.py`, `tools/match_beuken_video.py`.

## What the video is

`combined_video_2.mp4` from the Kaggle dataset `leopoldbeuken/distributed-imu-sensors-on-an-alpine-ski` (Apache 2.0): 100 s, 3840 x 2160, 30 fps, 618 MB, stored locally in `footage/beuken/` (not in git). It is a point-of-view clip looking down the ski from the binding, with the authors' own chart panels drawn beside it: Boot Accel-z, Boot Roll (deg), Boot Yaw and Boot Gyro-z. The chart cursor sits at the current time, so video time equals the chart's time axis.

## What we did

1. **Digitised the roll and yaw charts** from the video at 10 fps (1,000 frames): pixel to value mapping from the axis ticks. Output `outputs/beuken_video_chart.csv`. This is the authors' filtered estimate, read off the picture, so it carries reading error of a couple of degrees at most (INFERRED; not calibrated).
2. **Tried to find the raw IMU data behind it** by correlating the chart roll with VQF roll for every IMU in the ten `1_23` outdoor runs and in the `4_29` and `5_17` files, with a +-6 s lag search and three axis choices.

## Result of the matching: not found

The best correlation was 0.38 (run 2, any IMU), no better than other IMUs of the same run, so no raw file can be tied to the video. Plausible reasons, none verified: the charts come from a separate boot-mounted IMU that is not in the public files; the charts were produced by a heavily filtered pipeline that differs from a plain attitude filter; or they come from a recording that was not uploaded. **So we cannot use the video as ground truth for our estimators.**

## What the chart does give us: a real reference for carved skiing

From the digitised roll over 100 s of real skiing (skier level not stated; the paper says an intermediate and an expert skier were recorded):

| Measure | Value |
|---|---|
| Roll range, p5 / p95 | -37 / +38 degrees |
| Turns found | 60 in 100 s (about 6 per 10 s) |
| Single-turn duration, p25 / median / p75 | 1.1 / 1.3 / 1.5 s (a left-right cycle of about 2.6 s) |
| Peak roll magnitude per turn, p25 / median / p75 | 31 / 35 / 40 degrees |
| Rhythm regularity (CV of turn duration) | 0.61: far less regular than our drill videos |

These agree with the lean and cycle figures we derived from YouTube footage (carving cycle about 3 s, lean half-range about 37 to 40 degrees), which is mild support for those numbers. They are not independent: the source is a different skier on a different slope, so it supports plausibility, not accuracy.

## What it suggests

- Real rhythm is irregular (CV 0.6). Any turn-timing feedback has to expect uneven turns, and the drill videos' clean humps are an idealisation.
- A boot or ski roll of 30 to 40 degrees at peak, with roughly 1.3 s per turn, is a sensible default for a carving drill's expected range. It is a reference range, not a target.

## Limits

One clip, one skier pair, chart read from pixels, no matched raw data, and the "boot" in the charts may not be the ski-mounted IMUs we analysed in `docs/ski-imu-run.md`.
