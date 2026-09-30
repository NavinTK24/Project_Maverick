"""Loading, unit fixes and phone/vehicle re-synchronisation for IO-VNBD.

Facts this module relies on (each is re-checked and printed by run.py):
  * S- column "GPS SPEED (Kmh)" holds m/s (VBOX/phone ratio ~= 1.0 after this module, ~= 3.6 if converted again);
  * phone yaw rate = "GYROSCOPE Pitch (rad/s)" (the phone lies flat in its holder);
  * the "synchronised" S/V pairs can be offset by seconds to minutes, so every drive is re-synced from its own data.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
from scipy.signal import correlate, find_peaks

DT = 0.1                      # both S- and V- files are logged at 10 Hz
TEST_LOCKED = {"M"}           # never loaded
R_EARTH = 6371000.0
MAX_LAG_S = 600.0


def find_root(start: str | Path | None) -> Path:
    """Locate the 'Categorised IOVNB Dataset' folder inside the synchronised data."""
    candidates = []
    bases = [Path(start)] if start else [Path.cwd(), *Path.cwd().parents]
    for base in bases:
        if base.name == "Categorised IOVNB Dataset" and "Synchronised" in str(base):
            return base
        candidates += [p for p in base.glob("**/Categorised IOVNB Dataset") if "Synchronised" in str(p) and "Unsynchronised" not in str(p)]
        if candidates:
            return candidates[0]
    raise FileNotFoundError("Synchronised 'Categorised IOVNB Dataset' folder not found; pass --root")


def list_drives(root: Path) -> list[dict]:
    out = []
    for s in sorted(root.rglob("S-*.csv")):
        key = s.stem[2:]
        v = list(s.parent.glob("V-*.csv"))
        if key in TEST_LOCKED or "Driver B" in str(s.parent):
            continue
        if not v:
            continue
        out.append(dict(key=key, group=s.relative_to(root).parts[0], s=s, v=v[0]))
    return out


def _read(path: Path) -> pd.DataFrame:
    for enc in ("utf-8-sig", "latin-1"):
        try:
            return pd.read_csv(path, encoding=enc)
        except UnicodeDecodeError:
            continue
    raise UnicodeDecodeError


def _col(df: pd.DataFrame, key: str) -> np.ndarray:
    names = [c for c in df.columns if key.lower() in c.lower()]
    if not names:
        raise KeyError(f"column containing '{key}' not found")
    x = pd.to_numeric(df[names[0]], errors="coerce")
    return x.interpolate(limit_direction="both").to_numpy(float)


def load_raw(d: dict) -> dict:
    """Raw phone and vehicle arrays, each on its own 10 Hz row index (not yet aligned)."""
    s, v = _read(d["s"]), _read(d["v"])
    ph = dict(
        acc=np.column_stack([_col(s, f"ACCELEROMETER {a}") for a in "XYZ"]),
        grav=np.column_stack([_col(s, f"GRAVITY {a}") for a in "XYZ"]),
        gyr=np.column_stack([_col(s, f"GYROSCOPE {a}") for a in ("Yaw", "Pitch", "Roll")]),
        gps_speed=_col(s, "GPS SPEED"),                          # m/s despite the "Kmh" label
        gps_lat=_col(s, "GPS LATITUDE"), gps_lon=_col(s, "GPS LONGITUDE"),
    )
    tr = dict(
        speed=_col(v, "Velocity (km") / 3.6,
        heading=np.radians(_col(v, "Heading")),                   # compass, clockwise from north
        yaw=np.radians(_col(v, "Yaw Rate")),
        lat=_col(v, "Latitude"), lon=_col(v, "Longitude"),
    )
    return dict(**d, ph=ph, tr=tr)


def _xcorr(a: np.ndarray, b: np.ndarray, max_lag: int) -> tuple[np.ndarray, np.ndarray]:
    """Normalised c[L] ~ corr(a[i + L], b[i]) for |L| <= max_lag (a lags b by L samples when L > 0)."""
    a = np.nan_to_num(a - np.nanmean(a)); b = np.nan_to_num(b - np.nanmean(b))
    c = correlate(a, b, mode="full", method="fft")
    lags = np.arange(-len(b) + 1, len(a))
    keep = np.abs(lags) <= max_lag
    return lags[keep], c[keep] / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12)


def _corr_at(a: np.ndarray, b: np.ndarray, lag: int) -> float:
    x, y = (a[lag:], b[:len(b) - lag]) if lag >= 0 else (a[:lag], b[-lag:])
    n = min(len(x), len(y)); x, y = x[:n], y[:n]
    if n < 200 or np.std(x) == 0 or np.std(y) == 0:
        return float("nan")
    return float(np.corrcoef(x, y)[0, 1])


def gyro_lag(raw: dict) -> dict:
    """Phone yaw gyro vs V- yaw rate: top-3 peaks within +-600 s, pick the one whose phone-GPS speed also lines up."""
    g = raw["ph"]["gyr"][:, 1]; y = raw["tr"]["yaw"]
    lags, c = _xcorr(g, y, int(MAX_LAG_S / DT))
    pk, _ = find_peaks(np.abs(c), distance=int(5 / DT))
    top = pk[np.argsort(-np.abs(c[pk]))[:3]] if len(pk) else np.array([int(np.argmax(np.abs(c)))])
    cands = []
    for i in top:
        L = int(lags[i])
        # phone GPS speed runs a few seconds late, so search its best lag within +-15 s of the gyro lag
        sp = max((( _corr_at(raw["ph"]["gps_speed"], raw["tr"]["speed"], L + k), L + k) for k in range(-150, 151, 5)), key=lambda t: -np.inf if np.isnan(t[0]) else t[0])
        cands.append(dict(lag=L, gyro_corr=float(c[i]), speed_corr=sp[0], speed_lag=sp[1]))
    best = max(cands, key=lambda t: (abs(t["gyro_corr"]) > 0.5, t["speed_corr"]))
    return dict(best=best, candidates=cands)


def speed_lag(raw: dict) -> tuple[int, float]:
    lags, c = _xcorr(raw["ph"]["gps_speed"], raw["tr"]["speed"], int(MAX_LAG_S / DT))
    i = int(np.argmax(c)); return int(lags[i]), float(c[i])


def align(raw: dict, lag: int) -> dict:
    """Shift truth onto the phone clock. lag > 0: phone sample i + lag matches truth sample i."""
    ph, tr = raw["ph"], raw["tr"]
    n_ph, n_tr = len(ph["acc"]), len(tr["speed"])
    if lag >= 0:
        p0, t0 = lag, 0
    else:
        p0, t0 = 0, -lag
    n = min(n_ph - p0, n_tr - t0)
    if n < 600:
        raise ValueError(f"only {n} overlapping samples after a lag of {lag * DT:.1f} s")
    P = {k: v[p0:p0 + n] for k, v in ph.items()}
    T = {k: v[t0:t0 + n] for k, v in tr.items()}
    lat0, lon0 = np.nanmedian(T["lat"]), np.nanmedian(T["lon"])
    k = np.cos(np.radians(lat0)) * R_EARTH
    T["x"] = np.radians(T["lon"] - lon0) * k; T["y"] = np.radians(T["lat"] - lat0) * R_EARTH
    P["gps_x"] = np.radians(P["gps_lon"] - lon0) * k; P["gps_y"] = np.radians(P["gps_lat"] - lat0) * R_EARTH
    T["heading_rate"] = np.gradient(np.unwrap(T["heading"]), DT)
    return dict(key=raw["key"], group=raw["group"], lag=lag, n=n, ph=P, tr=T, t=np.arange(n) * DT)


def prepare_all(root: Path, log=print) -> tuple[dict, dict]:
    """Load every non-M drive, re-sync it, classify it. Returns (drives, status table)."""
    raws = {}
    for d in list_drives(root):
        try:
            raws[d["key"]] = load_raw(d)
        except (KeyError, ValueError) as e:
            log(f"SKIP {d['key']}: {e}")
    status, gl = {}, {}
    for k, r in raws.items():
        g = gyro_lag(r); gl[k] = g
        status[k] = dict(group=r["group"], gyro_lag_s=g["best"]["lag"] * DT, gyro_corr=g["best"]["gyro_corr"],
                         speed_corr_at_gyro_lag=g["best"]["speed_corr"], speed_lag_s=g["best"]["speed_lag"] * DT)
    usable = [k for k, s in status.items() if abs(s["gyro_corr"]) >= 0.8 and s["speed_corr_at_gyro_lag"] >= 0.8]
    offsets = [gl[k]["best"]["speed_lag"] - gl[k]["best"]["lag"] for k in usable]
    gps_late = int(np.median(offsets)) if offsets else 30
    drives = {}
    for k, r in raws.items():
        s = status[k]
        if k in usable:
            lag, how = gl[k]["best"]["lag"], "gyro"
        else:
            sl, sc = speed_lag(r)
            s["speed_only_corr"] = sc
            lag, how = sl - gps_late, "speed-corrected"
        try:
            a = align(r, lag)
        except ValueError as e:
            s.update(lag_used_s=lag * DT, sync=how, role="EXCLUDED", note=str(e)); log(f"EXCLUDE {k}: {e}"); continue
        mv = a["tr"]["speed"] > 5 / 3.6
        s.update(lag_used_s=lag * DT, sync=how, hours=a["n"] * DT / 3600, km=float(np.sum(a["tr"]["speed"]) * DT / 1000),
                 moving_pct=float(mv.mean() * 100),
                 speed_corr=_corr_at(a["ph"]["gps_speed"], a["tr"]["speed"], gps_late),
                 speed_ratio=float(np.median(a["tr"]["speed"][mv]) / max(np.median(a["ph"]["gps_speed"][mv]), 1e-6)) if mv.sum() > 100 else float("nan"))
        s["role"] = "USABLE" if k in usable else ("SPEED-ONLY" if s["speed_corr"] >= 0.9 and s["km"] > 0.5 else "EXCLUDED")
        drives[k] = a
    status["_gps_late_s"] = gps_late * DT
    return drives, status
