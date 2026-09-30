"""Causal IMU features from 10 Hz phone data, plus pre-outage context features.

Every feature at index i uses samples <= i only.
"""
from __future__ import annotations

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

from .data import DT

STRIDE = 10                    # one feature row per second
WINDOWS = (10, 50, 100)        # 1 s, 5 s, 10 s
BANDS = ((0.3, 1.0), (1.0, 2.0), (2.0, 3.5), (3.5, 5.0))


def _signals(ph: dict) -> dict:
    acc, grav, gyr = ph["acc"], ph["grav"], ph["gyr"]
    gu = grav / np.maximum(np.linalg.norm(grav, axis=1, keepdims=True), 1e-6)
    lin = acc - grav
    vert = np.sum(lin * gu, axis=1)
    horiz = np.linalg.norm(lin - vert[:, None] * gu, axis=1)
    return dict(amag=np.linalg.norm(acc, axis=1), vert=vert, horiz=horiz,
                g0=gyr[:, 0], g1=gyr[:, 1], g2=gyr[:, 2], gmag=np.linalg.norm(gyr, axis=1))


def _names() -> list[str]:
    n = []
    for w in WINDOWS:
        for s in ("amag", "vert", "horiz", "g0", "g1", "g2", "gmag"):
            n += [f"{s}_std_{w}", f"{s}_rms_{w}", f"{s}_p90abs_{w}"]
        if w >= 50:
            for s in ("amag", "vert"):
                n += [f"{s}_band{lo}-{hi}_{w}" for lo, hi in BANDS]
    return n


IMU_NAMES = _names()
KEY_ENERGY = [n for n in IMU_NAMES if "_band" in n or n.startswith(("vert_std", "horiz_std", "amag_std"))]


def imu_features(ph: dict, idx: np.ndarray) -> np.ndarray:
    """Features at the requested sample indices (windows end at idx, inclusive). Indices < max window are edge-padded."""
    sig = _signals(ph); n = len(sig["amag"]); W = max(WINDOWS)
    pad = {k: np.concatenate([np.full(W - 1, v[0]), v]) for k, v in sig.items()}
    cols = []
    for w in WINDOWS:
        for s in ("amag", "vert", "horiz", "g0", "g1", "g2", "gmag"):
            win = sliding_window_view(pad[s], w)[idx + (W - 1) - (w - 1)]
            dm = win - win.mean(1, keepdims=True)
            cols += [dm.std(1), np.sqrt((win ** 2).mean(1)), np.percentile(np.abs(dm), 90, axis=1)]
        if w >= 50:
            f = np.fft.rfftfreq(w, DT)
            for s in ("amag", "vert"):
                win = sliding_window_view(pad[s], w)[idx + (W - 1) - (w - 1)]
                P = np.abs(np.fft.rfft(win - win.mean(1, keepdims=True), axis=1)) ** 2 / w
                cols += [np.log(P[:, (f >= lo) & (f < hi)].sum(1) + 1e-6) for lo, hi in BANDS]
    X = np.column_stack(cols).astype(np.float32)
    assert X.shape[1] == len(IMU_NAMES)
    return X


CONTEXT_NAMES = ["v_last", "v_mean30", "v_mean60", "v_trend60", "gps_age_s", "tau_s"]
DELTA_NAMES = [f"d_{n}" for n in KEY_ENERGY]


def context_at(gps_speed: np.ndarray, k0: int) -> np.ndarray:
    """Pre-outage phone-GPS context at outage start k0 (uses samples <= k0 only)."""
    v = gps_speed[: k0 + 1]
    n30, n60 = int(30 / DT), int(60 / DT)
    last = float(v[-1])
    changes = np.flatnonzero(np.diff(v) != 0)
    age = (len(v) - 1 - (changes[-1] + 1)) * DT if len(changes) else len(v) * DT
    w60 = v[-n60:]
    trend = float(np.polyfit(np.arange(len(w60)) * DT, w60, 1)[0]) if len(w60) > 10 else 0.0
    return np.array([last, float(v[-n30:].mean()), float(w60.mean()), trend, age], dtype=np.float32)


def residual_design(imu_t: np.ndarray, imu_k0: np.ndarray, ctx: np.ndarray, tau: np.ndarray) -> np.ndarray:
    """Inputs of the residual model: IMU now, change of vibration energy since the outage start, context, time since start."""
    key = [IMU_NAMES.index(n) for n in KEY_ENERGY]
    delta = imu_t[:, key] - imu_k0[key][None, :]
    c = np.repeat(ctx[None, :], len(imu_t), axis=0)
    return np.column_stack([imu_t, delta, c, tau]).astype(np.float32)


RESIDUAL_NAMES = IMU_NAMES + DELTA_NAMES + CONTEXT_NAMES
