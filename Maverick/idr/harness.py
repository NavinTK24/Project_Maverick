"""Outage protocol, the ONE shared heading/position integrator, and metrics. Every method goes through integrate()."""
from __future__ import annotations

import numpy as np

from .data import DT

LENGTHS_S = (30, 60, 120)
START_EVERY_S = 60
FIRST_START_S = 60
MIN_START_SPEED = 5 / 3.6


def outage_windows(drive: dict) -> list[tuple[int, int]]:
    """(k0, L) pairs: start every 60 s from t >= 60 s where VBOX speed > 5 km/h. k0 is a multiple of 10 (1 s grid)."""
    out, n, v = [], drive["n"], drive["tr"]["speed"]
    for T in LENGTHS_S:
        L = int(T / DT)
        for k0 in range(int(FIRST_START_S / DT), n - L, int(START_EVERY_S / DT)):
            if v[k0] > MIN_START_SPEED:
                out.append((k0, L))
    return out


class PhoneView:
    """What an estimator may see during an outage starting at k0: all phone IMU, phone GPS only up to k0."""

    def __init__(self, drive: dict, k0: int):
        self.k0 = k0
        self.imu = {k: drive["ph"][k] for k in ("acc", "grav", "gyr")}
        self.gps_speed_before = drive["ph"]["gps_speed"][: k0 + 1].copy()
        self.gps_x_before = drive["ph"]["gps_x"][: k0 + 1].copy() if "gps_x" in drive["ph"] else None
        self.gps_y_before = drive["ph"]["gps_y"][: k0 + 1].copy() if "gps_y" in drive["ph"] else None


def integrate(drive: dict, k0: int, L: int, speed: np.ndarray, heading_sign: float, oracle_heading: bool = False,
              yaw_rate: np.ndarray | None = None) -> dict:
    """Dead-reckon from the true state at k0 with a 10 Hz speed series of length L. Returns per-outage metrics."""
    tr = drive["tr"]; seg = slice(k0, k0 + L)
    assert len(speed) == L
    if oracle_heading:
        psi = tr["heading"][seg]
    else:
        rate = heading_sign * drive["ph"]["gyr"][seg, 1] if yaw_rate is None else yaw_rate
        assert len(rate) == L
        psi = tr["heading"][k0] + np.concatenate([[0.0], np.cumsum(rate[:-1]) * DT])
    x = tr["x"][k0] + np.concatenate([[0.0], np.cumsum(speed[:-1] * np.sin(psi[:-1])) * DT])
    y = tr["y"][k0] + np.concatenate([[0.0], np.cumsum(speed[:-1] * np.cos(psi[:-1])) * DT])
    tx, ty = tr["x"][seg], tr["y"][seg]
    e = np.hypot(x - tx, y - ty)
    dist = float(np.sum(np.hypot(np.diff(tx), np.diff(ty))))
    v = tr["speed"][seg]
    he = np.degrees(np.angle(np.exp(1j * (psi[-1] - tr["heading"][k0 + L - 1]))))
    return dict(end_m=float(e[-1]), drift_pct=100 * float(e[-1]) / max(dist, 1e-6), rmse_m=float(np.sqrt(np.mean(e ** 2))),
                max_m=float(e.max()), head_err_deg=abs(float(he)), dist_m=dist, speed_mae=float(np.mean(np.abs(speed - v))),
                speed_bias=float(np.mean(speed - v)), changed=bool((v.max() - v.min()) * 3.6 > 10))


def hold_1s(values_per_s: np.ndarray, L: int) -> np.ndarray:
    """Zero-order hold of one-per-second predictions onto the 10 Hz grid (causal)."""
    return np.repeat(values_per_s, int(1 / DT))[:L]


def summarise(df, by=("method", "T")):
    import pandas as pd
    g = df.groupby(list(by))
    return pd.DataFrame(dict(
        n=g.size(),
        drift_med=g.drift_pct.median(), drift_p90=g.drift_pct.quantile(0.9), drift_worst=g.drift_pct.max(),
        under10_pct=g.drift_pct.apply(lambda s: 100 * float((s < 10).mean())),
        end_med_m=g.end_m.median(), rmse_med_m=g.rmse_m.median(), head_med_deg=g.head_err_deg.median(),
        speed_mae_med=g.speed_mae.median(), speed_bias_med=g.speed_bias.median(),
    )).round(2)
