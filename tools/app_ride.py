"""Load a ride recorded by the Maverick Android app into the 10 Hz arrays the models use, exactly like the app's engine
tick: accelerometer and gyroscope averaged over each 100 ms tick (anti-aliasing), gravity = latest value.

Two recording formats are read:
  * new: <drive>/sensors.csv (one row per 20 ms at 50 Hz, one column per axis, with n_acc / n_gyr event counts) + gnss.csv
  * old: ride_<date>/imu.csv (one row per sensor event) + gnss.csv

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


def _binmean(t_ns, vals, grid_ns):
    """Mean of samples in (grid[k-1], grid[k]]; the latest earlier value where a bin is empty (NaN before any sample)."""
    c = np.vstack([np.zeros((1, vals.shape[1])), np.cumsum(vals, axis=0)])
    hi = np.searchsorted(t_ns, grid_ns, side="right"); lo = np.concatenate([[np.searchsorted(t_ns, grid_ns[0] - int(DT * 1e9), side="right")], hi[:-1]])
    cnt = (hi - lo)[:, None]; out = np.where(cnt > 0, (c[hi] - c[lo]) / np.maximum(cnt, 1), np.nan)
    last = _latest(t_ns, vals, grid_ns); return np.where(np.isnan(out), last, out)


def _from_sensors_csv(f):
    """10 Hz acc / gyr / grav from sensors.csv: event-count-weighted mean of the rows inside each 100 ms tick (= the mean of
    all events, as the app's engine computes it); gravity = the latest row. Returns grid (ns of each tick END), acc, gyr, grav."""
    d = pd.read_csv(f / "sensors.csv").sort_values("t_ns"); t = d.t_ns.to_numpy(np.int64)
    step = int(np.median(np.diff(t))) if len(t) > 1 else 20_000_000
    tick = (t - t[0]) // int(DT * 1e9); nt = int(tick[-1]) + 1
    def wmean(cols, ncol):
        v = d[cols].to_numpy(float); w = d[ncol].to_numpy(float)
        num = np.zeros((nt, 3)); den = np.zeros(nt)
        np.add.at(num, tick, v * w[:, None]); np.add.at(den, tick, w)
        out = np.where(den[:, None] > 0, num / np.maximum(den, 1)[:, None], np.nan)
        return pd.DataFrame(out).ffill().to_numpy()      # a tick with no events repeats the previous value (as the app does)
    acc = wmean(["acc_x_mps2", "acc_y_mps2", "acc_z_mps2"], "n_acc"); gyr = wmean(["gyr_x_rads", "gyr_y_rads", "gyr_z_rads"], "n_gyr")
    g = d[["grav_x_mps2", "grav_y_mps2", "grav_z_mps2"]].to_numpy(float); grav = np.full((nt, 3), np.nan)
    last = np.zeros(nt, int) - 1; last[tick] = np.arange(len(t))                     # last row of each tick (rows are sorted)
    ok = last >= 0; grav[ok] = g[last[ok]]; grav = pd.DataFrame(grav).ffill().to_numpy()
    grid = t[0] + (np.arange(nt) + 1) * int(DT * 1e9) - step       # time of the last row in each tick
    return grid, acc, gyr, grav


def load_app_ride(folder, key=None):
    f = Path(folder); gn = pd.read_csv(f / "gnss.csv")
    if (f / "sensors.csv").exists():
        grid, acc, gyr, grav = _from_sensors_csv(f)
    else:
        imu = pd.read_csv(f / "imu.csv")
        S = {s: g.sort_values("t_ns") for s, g in imu.groupby("sensor")}
        t_start = max(S["acc"].t_ns.iloc[0], S["gyr"].t_ns.iloc[0]); t_end = min(S["acc"].t_ns.iloc[-1], S["gyr"].t_ns.iloc[-1])
        grid = np.arange(t_start, t_end, int(DT * 1e9), dtype=np.int64)
        acc = _binmean(S["acc"].t_ns.to_numpy(), S["acc"][["x", "y", "z"]].to_numpy().astype(float), grid)
        gyr = _binmean(S["gyr"].t_ns.to_numpy(), S["gyr"][["x", "y", "z"]].to_numpy().astype(float), grid)
        if "grav" in S:
            grav = _latest(S["grav"].t_ns.to_numpy(), S["grav"][["x", "y", "z"]].to_numpy(), grid)
        else:                                                    # same low-pass the app uses when there is no gravity sensor
            a = S["acc"][["x", "y", "z"]].to_numpy(); g = np.empty_like(a); g[0] = a[0]
            for k in range(1, len(a)): g[k] = 0.995 * g[k - 1] + 0.005 * a[k]
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
