"""Load a ride recorded by the MaverickGRID Android app (ride_<date>/imu.csv + gnss.csv) into the 10 Hz arrays the
models use, sampled exactly like the app's engine tick (latest sensor value every 100 ms).

    from idr.app_ride import load_app_ride;  d = load_app_ride("rides/ride_20261001_101500")
    d["ph"]: acc, grav, gyr (n x 3), gps_speed (held 1 Hz GNSS speed);  d["tr"]: speed, x, y, heading from GNSS;  d["n"]
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

DT = 0.1
R_EARTH = 6378137.0


def _latest(t_ns, vals, grid_ns):
    """Latest sample at or before each grid time (NaN before the first sample)."""
    i = np.searchsorted(t_ns, grid_ns, side="right") - 1
    out = np.full((len(grid_ns), vals.shape[1]), np.nan); ok = i >= 0; out[ok] = vals[i[ok]]
    return out


def load_app_ride(folder, key=None):
    f = Path(folder); imu = pd.read_csv(f / "imu.csv"); gn = pd.read_csv(f / "gnss.csv")
    S = {s: g.sort_values("t_ns") for s, g in imu.groupby("sensor")}
    t_start = max(S["acc"].t_ns.iloc[0], S["gyr"].t_ns.iloc[0]); t_end = min(S["acc"].t_ns.iloc[-1], S["gyr"].t_ns.iloc[-1])
    grid = np.arange(t_start, t_end, int(DT * 1e9), dtype=np.int64)
    acc = _latest(S["acc"].t_ns.to_numpy(), S["acc"][["x", "y", "z"]].to_numpy(), grid)
    gyr = _latest(S["gyr"].t_ns.to_numpy(), S["gyr"][["x", "y", "z"]].to_numpy(), grid)
    if "grav" in S:
        grav = _latest(S["grav"].t_ns.to_numpy(), S["grav"][["x", "y", "z"]].to_numpy(), grid)
    else:                                                    # same low-pass the app uses when there is no gravity sensor
        a = S["acc"][["x", "y", "z"]].to_numpy(); g = np.empty_like(a); g[0] = a[0]
        for k in range(1, len(a)): g[k] = 0.98 * g[k - 1] + 0.02 * a[k]
        grav = _latest(S["acc"].t_ns.to_numpy(), g, grid)
    gn = gn.sort_values("t_ns"); gt = gn.t_ns.to_numpy()
    lat0, lon0 = float(gn.lat.median()), float(gn.lon.median()); k = np.cos(np.radians(lat0)) * R_EARTH
    gx = np.radians(gn.lon.to_numpy() - lon0) * k; gy = np.radians(gn.lat.to_numpy() - lat0) * R_EARTH
    held = _latest(gt, np.column_stack([gn.speed_mps.to_numpy(), gx, gy, gn.bearing_deg.to_numpy()]), grid)
    # truth for training = GNSS itself (interpolated), valid only where fixes are fresh (< 1.5 s)
    age = (grid - gt[np.clip(np.searchsorted(gt, grid, side="right") - 1, 0, len(gt) - 1)]) / 1e9
    speed = np.interp(grid, gt, gn.speed_mps.to_numpy()); x = np.interp(grid, gt, gx); y = np.interp(grid, gt, gy)
    br = np.unwrap(np.radians(gn.bearing_deg.ffill().fillna(0).to_numpy())); heading = np.interp(grid, gt, br)
    bad = age > 1.5; speed[bad] = np.nan; x[bad] = np.nan; y[bad] = np.nan
    ok = ~np.isnan(acc).any(1) & ~np.isnan(gyr).any(1) & ~np.isnan(grav).any(1); first = int(np.argmax(ok))
    sl = slice(first, len(grid))
    ph = dict(acc=acc[sl], grav=grav[sl], gyr=gyr[sl], gps_speed=np.nan_to_num(held[sl, 0], nan=0.0))
    hr = np.gradient(np.unwrap(heading[sl])) / DT
    tr = dict(speed=speed[sl], x=x[sl], y=y[sl], heading=heading[sl], heading_rate=hr, lat=np.degrees(y[sl] / R_EARTH) + lat0, lon=np.degrees(x[sl] / k) + lon0)
    return dict(key=key or f.name, group="app", ph=ph, tr=tr, n=len(ph["acc"]), t0_ns=int(grid[first]))
