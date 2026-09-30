# MaverickGRID edge engine

The same engine as the phone app, for any **external IMU** (vehicle IMU, MEMS or FOG, any sample rate, e.g. 200 Hz),
running on any computer with Java 11+ (laptop, Raspberry Pi, Jetson...). No other dependencies.

    java -jar maverick-edge.jar --model models/edge --imu imu.csv --gnss gnss.csv --map roads.mgr --out track.csv

| file | columns (header line required) |
|---|---|
| `imu.csv` | `t_s, ax_mps2 (forward), ay_mps2 (left), yaw_rate_rps` – any rate |
| `gnss.csv` (optional) | `t_s, lat, lon, speed_mps, bearing_deg, accuracy_m` – leave rows out while GNSS is lost |
| `roads.mgr` (optional) | road network from `python -m idr.osm_pack` |
| `track.csv` (output) | `t_s, lat, lon, heading_deg, speed_mps, mode` – **one row per IMU sample** (200 Hz in → 200 Hz out) |

`--yaw-sign -1` (default) if yaw rate is positive turning left (counter-clockwise, as in IO-VNBD); `--yaw-sign 1` if positive turning right.

How it works: the AI speed model and stop detector run at 10 Hz on 100 ms averages of the IMU (anti-aliasing);
between model ticks the position is propagated at the full IMU rate with the high-rate yaw. GNSS loss is detected
1.2 s after the last fix; the engine switches to dead reckoning + map matching on the same sample and back when
2 good fixes return.

Measured on IO-VNBD held-out drive S1, vehicle IMU (no wheel speed), 10 Hz data repeated to 200 Hz to exercise the
high-rate path: 60 s outages every 3 min, median drift 6.2 %; 1.4 h of 200 Hz data processed in about 8 s on a desktop.
The model in `models/edge` is trained on all 38 IO-VNBD drives that carry the vehicle IMU (drive M excluded).
