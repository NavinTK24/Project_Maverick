#!/usr/bin/env python3
import os, glob, math
import pandas as pd
import numpy as np

ROOT = r'C:\Users\STUDENT\Desktop\Maverick\Maverick45\IO-VNBD\Unsynchronised V and S Dataset\Categorised IOVNB (V) Dataset\V Dataset'


def local_xy(lat, lon, lat0, lon0):
    x = (lon - lon0) * 111320.0 * np.cos(np.radians(lat0))
    y = (lat - lat0) * 111320.0
    return x, y


def gnss_course(lat, lon, t):
    x, y = local_xy(lat, lon, lat[0], lon[0])
    dt = np.diff(t, prepend=t[0])
    dx = np.diff(x, prepend=x[0])
    dy = np.diff(y, prepend=y[0])
    ok = (dt > 0.05) & (np.hypot(dx, dy) / np.where(dt > 0, dt, 1) > 2.0)
    course = np.full_like(x, np.nan, dtype=float)
    course[1:] = np.arctan2(dy[1:], dx[1:])
    return course, ok

files = sorted(glob.glob(os.path.join(ROOT, '**', '*.csv'), recursive=True))
non_m = [f for f in files if not os.path.relpath(f, ROOT).split(os.sep)[0].startswith('M')]
print('files_total', len(files))
print('files_non_m', len(non_m))

rows = []
for f in non_m:
    df = pd.read_csv(f)
    df.columns = [c.strip() for c in df.columns]
    try:
        t = df.iloc[:, 1].to_numpy(float)
        lat = df.iloc[:, 2].to_numpy(float)
        lon = df.iloc[:, 3].to_numpy(float)
        v_kmh = df.iloc[:, 4].to_numpy(float)
        head_deg = df.iloc[:, 5].to_numpy(float)
        yaw_deg = df.iloc[:, 14].to_numpy(float)
        ax_g = df.iloc[:, 16].to_numpy(float)
    except Exception:
        continue
    t = t - t[0]
    dt = np.diff(t)
    sample = float(np.median(dt)) if len(dt) else float('nan')
    speed = v_kmh / 3.6
    x, y = local_xy(lat, lon, lat[0], lon[0])
    head_rad = np.pi/2 - np.radians(head_deg)
    course, ok = gnss_course(lat, lon, t)
    if np.isfinite(course).any() and np.isfinite(yaw_deg).any():
        yaw = np.radians(yaw_deg)
        hr = np.gradient(np.unwrap(head_rad), t)
        mask = (speed > 3.0) & (np.abs(hr) < 0.8) & np.isfinite(hr) & np.isfinite(yaw)
        if mask.sum() > 50:
            yaw_scale = float(np.sum(yaw[mask] * hr[mask]) / max(np.sum(yaw[mask] ** 2), 1e-9))
            yaw_corr = float(np.corrcoef(yaw[mask], hr[mask])[0, 1])
        else:
            yaw_scale = float('nan'); yaw_corr = float('nan')
    else:
        yaw_scale = float('nan'); yaw_corr = float('nan')
    dv = np.gradient(speed, t)
    ax = ax_g * 9.80665
    mask_ax = (speed > 3.0) & np.isfinite(dv) & np.isfinite(ax)
    if mask_ax.sum() > 50:
        ax_scale = float(np.sum(ax[mask_ax] * dv[mask_ax]) / max(np.sum(ax[mask_ax] ** 2), 1e-9))
        ax_corr = float(np.corrcoef(ax[mask_ax], dv[mask_ax])[0, 1])
    else:
        ax_scale = float('nan'); ax_corr = float('nan')
    rows.append({
        'file': os.path.relpath(f, ROOT),
        'driver': os.path.relpath(f, ROOT).split(os.sep)[0],
        'duration_s': float(t[-1]),
        'sample_s': sample,
        'speed_mean_mps': float(np.mean(speed)),
        'speed_max_mps': float(np.max(speed)),
        'yaw_scale': yaw_scale,
        'yaw_corr': yaw_corr,
        'ax_scale': ax_scale,
        'ax_corr': ax_corr,
    })

summary = pd.DataFrame(rows)
print('\nSUMMARY')
print(summary[['driver','duration_s','sample_s','speed_mean_mps','speed_max_mps','yaw_corr','ax_corr']].round(3).to_string(index=False))
print('\nCORRELATION SUMMARY')
print(f"yaw_corr median={summary['yaw_corr'].median():.3f}, mean={summary['yaw_corr'].mean():.3f}, min={summary['yaw_corr'].min():.3f}")
print(f"ax_corr median={summary['ax_corr'].median():.3f}, mean={summary['ax_corr'].mean():.3f}, min={summary['ax_corr'].min():.3f}")
print(f"sample_s median={summary['sample_s'].median():.3f}, mean={summary['sample_s'].mean():.3f}, max={summary['sample_s'].max():.3f}")
print(f"duration_s median={summary['duration_s'].median():.3f}, mean={summary['duration_s'].mean():.3f}, max={summary['duration_s'].max():.3f}")
