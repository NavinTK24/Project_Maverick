"""Online self-calibration while GNSS is healthy — no standstill, no calibration drive.

Everything here uses only data from BEFORE the outage start k0 (phone IMU and phone GPS), so it can run live:
  * speed_band_correction : per-speed-band offset between the IMU speed model and GPS speed on this trip
  * gyro_from_course      : gyro bias and scale from GPS course changes while moving (no stop needed)
  * gyro_from_stops       : gyro bias from natural stops found by the IMU stop detector
  * heading_trust         : whether this phone's gyro follows the vehicle at all on this trip
"""
from __future__ import annotations

import numpy as np

from .data import DT
from .features import STRIDE

BANDS = (0.0, 5.0, 10.0, 15.0, 20.0, 99.0)          # m/s bands of the model's own prediction
WINDOW_S = 900                                        # look back at most 15 min


def _fix_idx(gps_x: np.ndarray, k0: int) -> np.ndarray:
    """Indices (<= k0) where a new phone GPS fix arrived (position changed)."""
    ch = np.flatnonzero(np.abs(np.diff(gps_x[: k0 + 1])) > 1e-6) + 1
    return ch[ch >= max(0, k0 - int(WINDOW_S / DT))]


def speed_band_correction(a_mu_rows: np.ndarray, gps_speed: np.ndarray, gps_x: np.ndarray, k0: int, late: int, min_pairs: int = 5):
    """Median (GPS speed - model speed) per band of model speed, from fresh fixes before k0.
    The phone GPS reports the state `late` samples earlier, so a fix at j is compared with the model at j - late."""
    fx = _fix_idx(gps_x, k0); fx = fx[fx - late >= 0]
    rows = (fx - late) // STRIDE; ok = rows < len(a_mu_rows); fx, rows = fx[ok], rows[ok]
    a, g = a_mu_rows[rows], gps_speed[fx]; mv = g > 2.0; a, g = a[mv], g[mv]
    glob = float(np.median(g - a)) if len(a) >= min_pairs else 0.0
    corr = []
    for lo, hi in zip(BANDS[:-1], BANDS[1:]):
        m = (a >= lo) & (a < hi)
        corr.append(float(np.median(g[m] - a[m])) if m.sum() >= min_pairs else glob)
    return np.array(corr), int(len(a))


def apply_band(a_mu: np.ndarray, corr: np.ndarray) -> np.ndarray:
    b = np.clip(np.searchsorted(BANDS, a_mu, side="right") - 1, 0, len(corr) - 1)
    return np.maximum(a_mu + corr[b], 0.0)


def gyro_from_course(rate: np.ndarray, gps_x: np.ndarray, gps_y: np.ndarray, gps_speed: np.ndarray, k0: int, late: int):
    """Fit course change = s * integrated gyro + b * dt over consecutive GPS chord pairs before k0.
    Returns dict(scale, bias, r, n). rate = raw compass-convention yaw rate (heading_sign * gyro)."""
    fx = _fix_idx(gps_x, k0)
    if len(fx) < 4:
        return dict(scale=1.0, bias=0.0, r=np.nan, n=0)
    dx, dy = np.diff(gps_x[fx]), np.diff(gps_y[fx]); dist = np.hypot(dx, dy); crs = np.arctan2(dx, dy)
    mid = ((fx[:-1] + fx[1:]) // 2) - late
    G, C, T = [], [], []
    for i in range(len(crs) - 1):
        if dist[i] < 15 or dist[i + 1] < 15 or gps_speed[fx[i + 1]] < 3 or mid[i] < 0:
            continue
        a, b = mid[i], mid[i + 1]
        if b <= a or b > k0:
            continue
        G.append(float(np.sum(rate[a:b]) * DT)); C.append(float(np.angle(np.exp(1j * (crs[i + 1] - crs[i]))))); T.append((b - a) * DT)
    G, C, T = map(np.asarray, (G, C, T))
    if len(G) < 8:
        return dict(scale=1.0, bias=0.0, r=np.nan, n=int(len(G)))
    keep = np.abs(C - G) < np.radians(45)                       # drop chord pairs spoiled by sharp manoeuvres
    r = float(np.corrcoef(G, C)[0, 1]) if np.std(G) > 0 and np.std(C) > 0 else np.nan
    if keep.sum() < 8:
        return dict(scale=1.0, bias=0.0, r=r, n=int(keep.sum()))
    A = np.column_stack([G[keep], T[keep]]); s, b = np.linalg.lstsq(A, C[keep], rcond=None)[0]
    return dict(scale=float(np.clip(s, 0.8, 1.25)), bias=float(b), r=r, n=int(keep.sum()))


def gyro_from_stops(rate: np.ndarray, stopped_rows: np.ndarray, k0: int, min_run: int = 3):
    """Bias = -mean(rate) over IMU-detected stops (>= min_run s) in the window before k0. Returns (bias, seconds used)."""
    r0 = max(0, (k0 - int(WINDOW_S / DT)) // STRIDE); r1 = k0 // STRIDE
    st = stopped_rows[r0:r1]; samples = []
    i = 0
    while i < len(st):
        if st[i]:
            j = i
            while j < len(st) and st[j]:
                j += 1
            if j - i >= min_run:
                a, b = (r0 + i + 1) * STRIDE, (r0 + j - 1) * STRIDE        # drop the first and last second of each stop
                if b > a:
                    samples.append(rate[a:b])
            i = j
        else:
            i += 1
    if not samples:
        return 0.0, 0.0
    x = np.concatenate(samples); return float(-np.mean(x)), len(x) * DT


def heading_trust(fit: dict, r_min: float = 0.6) -> bool:
    """True if the gyro demonstrably follows GPS course changes on this trip (or there is no evidence either way)."""
    return not (fit["n"] >= 8 and np.isfinite(fit["r"]) and fit["r"] < r_min)
