"""Two-wheeler rides from the phone logger (track*.csv): loader + leave-one-ride-out evaluation.

    python -m idr.bike --dir "D:\\MaverickGRID\\bike_rides"          # every *.csv in the folder is one ride

Loader handles the logger's header bug (44 names, 46 fields: satellite counts written twice), any location provider,
~200 Hz IMU binned to 10 Hz on the elapsed-realtime clock, 1 Hz fixes as truth and as the pre-outage GPS.
Heading = gyro rotation about the gravity vector (works for any phone orientation).
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

import numpy as np
import pandas as pd

from .calib import gyro_from_stops
from .features import STRIDE, context_at, imu_features, residual_design
from .harness import PhoneView, hold_1s, integrate
from .models import PARAMS, SpeedModel, StopDetector

DT = 0.1
LENGTHS_S = (10, 20, 30, 60)


def read_ride(path: Path) -> pd.DataFrame:
    rows = list(csv.reader(open(path, newline="", encoding="utf-8-sig")))
    h = rows[0]; width = {len(r) for r in rows[1:]}
    if width == {len(h) + 2} and "satellites_used" in h:
        i = h.index("satellites_used") + 1
        h = h[:i] + ["satellites_visible_dup", "satellites_used_dup"] + h[i:]
    df = pd.DataFrame([r for r in rows[1:] if len(r) == len(h)], columns=h)
    for c in h:
        if c not in ("timestamp_utc", "event_type", "provider", "gnss_measurement"):
            df[c] = pd.to_numeric(df[c], errors="coerce")
    return df


def _bin(t, X, n):
    b = np.clip((t / DT).astype(int), 0, n - 1); out = np.full((n, X.shape[1]), np.nan)
    for j in range(X.shape[1]):
        s = np.bincount(b, np.nan_to_num(X[:, j]), n); c = np.bincount(b, np.isfinite(X[:, j]).astype(float), n)
        out[:, j] = np.where(c > 0, s / np.maximum(c, 1), np.nan)
    return pd.DataFrame(out).interpolate(limit_direction="both").to_numpy()


def load_ride(path: Path) -> dict:
    df = read_ride(path); t0 = df.elapsed_realtime_ns.iloc[0]; t = (df.elapsed_realtime_ns.to_numpy() - t0) / 1e9
    n = int(t[-1] / DT)
    acc = _bin(t, df[["accelerometer_x", "accelerometer_y", "accelerometer_z"]].to_numpy(float), n)
    gyr = _bin(t, df[["gyroscope_x", "gyroscope_y", "gyroscope_z"]].to_numpy(float), n)
    grav = _bin(t, df[["gravity_x", "gravity_y", "gravity_z"]].to_numpy(float), n)
    f = df[df.latitude.notna()]
    f = f.loc[(f[["latitude", "longitude", "speed_mps"]].diff().abs().sum(axis=1) > 0) | (f.index == f.index[0])]
    tf = (f.elapsed_realtime_ns.to_numpy() - t0) / 1e9; tg = np.arange(n) * DT
    lat0, lon0 = f.latitude.median(), f.longitude.median(); k = np.cos(np.radians(lat0)) * 6371000
    fx, fy = np.radians(f.longitude.to_numpy() - lon0) * k, np.radians(f.latitude.to_numpy() - lat0) * 6371000
    spd = f.speed_mps.to_numpy(float).copy(); brg = f.bearing_deg.to_numpy(float).copy(); brg[spd < 1.0] = np.nan
    hb = np.unwrap(np.radians(pd.Series(brg).ffill().bfill().to_numpy()))
    il = np.clip(np.searchsorted(tf, tg, side="right") - 1, 0, None)                   # causal: last fix at or before t
    gu = grav / np.linalg.norm(grav, axis=1, keepdims=True)
    yaw = (gyr * gu).sum(1)
    tr = dict(speed=np.interp(tg, tf, spd), heading=np.interp(tg, tf, hb), x=np.interp(tg, tf, fx), y=np.interp(tg, tf, fy))
    tr["heading_rate"] = np.gradient(tr["heading"], DT)
    ph = dict(acc=acc, grav=grav, gyr=np.column_stack([gyr[:, 0], yaw, gyr[:, 2]]), gyr_raw=gyr,
              gps_speed=spd[il], gps_x=fx[il], gps_y=fy[il])
    return dict(key=path.stem, n=n, ph=ph, tr=tr, provider=str(f.provider.mode().iloc[0]) if len(f) else "none")


def windows(d, every_s=5, first_s=10):
    out = []
    for T in LENGTHS_S:
        L = int(T / DT)
        for k0 in range(int(first_s / DT), d["n"] - L, int(every_s / DT)):
            if d["tr"]["speed"][k0] > 2.0:
                out.append((k0, L))
    return out


def feats_of(d):
    ph = dict(d["ph"], gyr=d["ph"]["gyr_raw"])                       # features use the raw 3-axis gyro
    return imu_features(ph, np.arange(0, d["n"], STRIDE))


def fit(rides, F):
    XA = np.vstack([F[k] for k in rides]); yA = np.concatenate([rides[k]["tr"]["speed"][::STRIDE][: len(F[k])] for k in rides])
    grp = np.concatenate([[k] * len(F[k]) for k in rides]); XR, yR = [], []
    for k, d in rides.items():
        for k0 in range(100, d["n"] - 10, 20):
            if d["tr"]["speed"][k0] <= 2.0:
                continue
            ctx = context_at(d["ph"]["gps_speed"], k0); taus = np.arange(0, 61); ks = k0 + taus * 10; ok = ks < d["n"]
            XR.append(residual_design(F[k][ks[ok] // STRIDE], F[k][k0 // STRIDE], ctx, taus[ok].astype(np.float32)))
            yR.append(d["tr"]["speed"][ks[ok]] - ctx[0])
    XR, yR = np.vstack(XR), np.concatenate(yR)
    small = len(XA) < 20000; saved = dict(PARAMS)
    if small:
        PARAMS.update(n_estimators=150, min_child_samples=20)
    try:
        M = dict(A=SpeedModel().fit(XA, yA), R=SpeedModel().fit(XR, yR), stop=StopDetector().fit(XA, yA < 0.3, yA > 2.0, grp, max_false=0.03))
    finally:
        PARAMS.clear(); PARAMS.update(saved)
    M.update(n_A=len(XA), n_R=len(XR)); return M


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--dir", required=True); ap.add_argument("--out", default="docs/bike")
    a = ap.parse_args(); out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    rides = {p.stem: load_ride(p) for p in sorted(Path(a.dir).glob("*.csv"))}
    F = {k: feats_of(d) for k, d in rides.items()}
    for k, d in rides.items():
        print(f"{k}: {d['n'] * DT:.0f} s, provider {d['provider']}, moving {np.sum(d['tr']['speed'] > 2) * DT:.0f} s", flush=True)
    rows = []
    for held in rides:
        tr = {k: d for k, d in rides.items() if k != held}; M = fit(tr, {k: F[k] for k in tr})
        s = sum(np.nansum((d["ph"]["gyr"][:, 1] * d["tr"]["heading_rate"])[d["tr"]["speed"] > 2]) for d in tr.values()); hs = float(np.sign(s)) or 1.0
        d, Fh = rides[held], F[held]; stopped = M["stop"].predict(Fh); raw = hs * d["ph"]["gyr"][:, 1]
        print(f"held-out {held}: trained on {len(tr)} rides, rows A {M['n_A']}, R {M['n_R']}", flush=True)
        for k0, L in windows(d):
            view = PhoneView(d, k0); nsec = int(np.ceil(L * DT)); r = np.minimum(k0 // STRIDE + np.arange(nsec), len(Fh) - 1)
            ctx = context_at(view.gps_speed_before, k0); r_mu, _ = M["R"].predict(residual_design(Fh[r], Fh[k0 // STRIDE], ctx, np.arange(nsec, dtype=np.float32)))
            s1 = hold_1s(np.where(stopped[r], 0.0, np.maximum(ctx[0] + r_mu, 0)), L)
            b_stop, _ = gyro_from_stops(raw[: k0 + 1], stopped, k0)
            hold = np.full(L, float(ctx[0])); orac = d["tr"]["speed"][k0:k0 + L].copy(); seg = raw[k0:k0 + L]
            for m, sp, yr in (("B1 hold speed, raw gyro", hold, seg), ("B2 true speed, raw gyro", orac, seg),
                              ("S1 model speed, raw gyro", s1, seg), ("S1 + stop-bias gyro", s1, seg + b_stop), ("B1 + stop-bias gyro", hold, seg + b_stop)):
                rows.append(dict(method=m, ride=held, T=int(L * DT), **integrate(d, k0, L, sp, hs, yaw_rate=yr)))
    df = pd.DataFrame(rows); df.to_csv(out / "bike_results.csv", index=False)
    g = df.groupby(["method", "T"]).agg(n=("drift_pct", "size"), drift_med=("drift_pct", "median"), drift_p90=("drift_pct", lambda s: s.quantile(.9)),
                                        speed_mae=("speed_mae", "median"), head_med=("head_err_deg", "median")).round(2)
    txt = ["# Two-wheeler, leave-one-ride-out", "", "| method | T (s) | n | drift median % | p90 % | speed MAE m/s | heading err deg |", "|---|---:|---:|---:|---:|---:|---:|"]
    txt += [f"| {m} | {T} | {int(r.n)} | {r.drift_med:.2f} | {r.drift_p90:.2f} | {r.speed_mae:.2f} | {r.head_med:.1f} |" for (m, T), r in g.iterrows()]
    (out / "BIKE_RESULTS.md").write_text("\n".join(txt), encoding="utf-8"); print("\n".join(txt))


if __name__ == "__main__":
    main()
