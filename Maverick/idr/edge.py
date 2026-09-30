"""Edge engine on an EXTERNAL IMU (IO-VNBD vehicle unit: yaw rate, longitudinal and lateral acceleration, 10 Hz).

Inputs  : V- file yaw rate, longitudinal acceleration, lateral acceleration (IMU only) + GNSS (VBOX, used as 1 Hz fixes
          before the outage only). NOT used: wheel speeds, indicated speed, steering, gear, pedals (no speedometer feed).
Truth   : V- file GNSS track.
Methods : B1 hold last GNSS speed + raw yaw-rate heading
          E1 S1-style learned speed (last GNSS speed + learned correction) + stop-bias heading
          E2 E1 + physics input: integral of longitudinal acceleration since the outage start
Leave-one-drive-out over the same 4 test drives as the phone results (S1, S2, S3a, S3c); training on every other drive.
Drive M is never loaded.      python -m idr.edge --root "<...>\\Categorised IOVNB Dataset" [--test S3b,S4,Y1 --out docs/edge_tune]
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from .calib import gyro_from_stops
from .data import DT, _col, _read, align, list_drives, find_root
from .features import STRIDE, context_at, imu_features, residual_design
from .harness import PhoneView, hold_1s, integrate, outage_windows
from .models import SpeedModel, StopDetector

G = 9.80665
TRK = []
TEST = ["S1", "S2", "S3a", "S3c"]          # held-out test drives (override with --test, e.g. S3b,S4,Y1 for map tuning)


def load_edge(d):
    v = _read(d["v"])
    yaw = np.radians(_col(v, "Yaw Rate")); ax = _col(v, "Longitudinal Acceleration") * G; ay = _col(v, "Lateral Acceleration") * G
    n = len(yaw)
    ph = dict(acc=np.column_stack([ax, ay, np.full(n, G)]), grav=np.column_stack([np.zeros(n), np.zeros(n), np.full(n, G)]),
              gyr=np.column_stack([np.zeros(n), yaw, np.zeros(n)]))
    spd = _col(v, "Velocity (km") / 3.6
    hold = spd.copy(); hold[np.arange(n) % 10 != 0] = np.nan; hold = pd.Series(hold).ffill().bfill().to_numpy()   # 1 Hz GNSS fixes
    ph.update(gps_speed=hold, gps_lat=_col(v, "Latitude"), gps_lon=_col(v, "Longitude"))
    tr = dict(speed=spd, heading=np.radians(_col(v, "Heading")), yaw=yaw, lat=_col(v, "Latitude"), lon=_col(v, "Longitude"))
    a = align(dict(key=d["key"], group=d["group"], ph=ph, tr=tr), 0)
    a["ax"] = ax[: a["n"]]
    return a


def physics_col(d, k0, rows_abs):
    """Integral of longitudinal acceleration from k0 to each requested sample (m/s)."""
    c = np.concatenate([[0.0], np.cumsum(d["ax"]) * DT])
    return (c[np.minimum(rows_abs, d["n"] - 1)] - c[k0]).astype(np.float32)


def rows_for(d, F, phys):
    X, y = [], []
    for k0 in range(int(60 / DT), d["n"] - 10, int(20 / DT)):
        if d["tr"]["speed"][k0] <= 5 / 3.6:
            continue
        ctx = context_at(d["ph"]["gps_speed"], k0); taus = np.arange(0, 121, 5); ks = k0 + (taus / DT).astype(int); ok = ks < d["n"]
        taus, ks = taus[ok], ks[ok]
        R = residual_design(F[ks // STRIDE], F[k0 // STRIDE], ctx, taus.astype(np.float32))
        if phys:
            R = np.column_stack([R, physics_col(d, k0, ks)])
        X.append(R); y.append(d["tr"]["speed"][ks] - ctx[0])
    return (np.vstack(X), np.concatenate(y)) if X else None


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--root"); ap.add_argument("--out", default="docs/edge"); ap.add_argument("--test", default=",".join(TEST))
    a = ap.parse_args(); TEST[:] = a.test.split(","); out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    drives = {}
    for d in list_drives(find_root(a.root)):
        try:
            e = load_edge(d)
            if np.sum(e["tr"]["speed"] > 5 / 3.6) * DT > 120:
                drives[d["key"]] = e
        except (KeyError, ValueError):
            pass
    print(f"{len(drives)} drives with a usable external IMU, {sum(d['n'] for d in drives.values()) * DT / 3600:.1f} h", flush=True)
    F = {k: imu_features(d["ph"], np.arange(0, d["n"], STRIDE)) for k, d in drives.items()}
    rows = []; global TRK; TRK = []
    for sk in [t for t in TEST if t in drives]:
        tk = [k for k in drives if k != sk]
        XA = np.vstack([F[k] for k in tk]); yA = np.concatenate([drives[k]["tr"]["speed"][::STRIDE][: len(F[k])] for k in tk])
        grp = np.concatenate([[k] * len(F[k]) for k in tk])
        stop = StopDetector().fit(XA, yA < 1 / 3.6, yA > 5 / 3.6, grp)
        models = {}
        for name, phys in (("E1", False), ("E2", True)):
            rr = [r for r in (rows_for(drives[k], F[k], phys) for k in tk) if r is not None]
            models[name] = SpeedModel().fit(np.vstack([r[0] for r in rr]), np.concatenate([r[1] for r in rr]))
        hs = float(np.sign(sum(np.nansum((drives[k]["ph"]["gyr"][:, 1] * drives[k]["tr"]["heading_rate"])[drives[k]["tr"]["speed"] > 2]) for k in tk)))
        d, Fh = drives[sk], F[sk]; stop_rows = stop.predict(Fh); raw = hs * d["ph"]["gyr"][:, 1]
        print(f"{sk}: trained on {len(tk)} drives; stop threshold {stop.threshold:.2f}", flush=True)
        for k0, L in outage_windows(d):
            T = int(round(L * DT)); view = PhoneView(d, k0); nsec = int(np.ceil(L * DT))
            r_ = np.minimum(k0 // STRIDE + np.arange(nsec), len(Fh) - 1); ctx = context_at(view.gps_speed_before, k0); stopped = stop.predict(Fh[r_])
            b_stop, _ = gyro_from_stops(raw[: k0 + 1], stop_rows, k0); yaw_c = raw[k0:k0 + L] + b_stop
            R = residual_design(Fh[r_], Fh[k0 // STRIDE], ctx, np.arange(nsec, dtype=np.float32))
            sp = {}
            for name in ("E1", "E2"):
                X = R if name == "E1" else np.column_stack([R, physics_col(d, k0, k0 + np.arange(nsec) * 10)])
                mu, _ = models[name].predict(X); sp[name] = hold_1s(np.where(stopped, 0.0, np.maximum(ctx[0] + mu, 0)), L)
            v_last = float(view.gps_speed_before[-1])
            rows.append(dict(method="B1 hold speed, raw yaw", drive=sk, T=T, **integrate(d, k0, L, np.full(L, v_last), hs, yaw_rate=raw[k0:k0 + L])))
            rows.append(dict(method="B2 true speed, stop-bias yaw", drive=sk, T=T, **integrate(d, k0, L, d["tr"]["speed"][k0:k0 + L].copy(), hs, yaw_rate=yaw_c)))
            for name in ("E1", "E2"):
                rows.append(dict(method=f"{name} learned speed, stop-bias yaw", drive=sk, T=T, **integrate(d, k0, L, sp[name], hs, yaw_rate=yaw_c)))
            rows.append(dict(method="E2 learned speed, raw yaw", drive=sk, T=T, k0=k0, **integrate(d, k0, L, sp["E2"], hs, yaw_rate=raw[k0:k0 + L])))
            TRK.append(dict(drive=sk, T=T, k0=k0, speed=sp["E2"], yaw=raw[k0:k0 + L].copy(), hold=np.full(L, v_last), hs=hs))
    df = pd.DataFrame(rows); df.to_csv(out / "edge_results.csv", index=False)
    import pickle; pickle.dump(dict(TRK=TRK, drives={k: dict(tr=drives[k]["tr"], n=drives[k]["n"]) for k in TEST if k in drives}), open(out / "edge_tracks.pkl", "wb"))
    g = df.groupby(["method", "T"])
    S = pd.DataFrame(dict(n=g.size(), med=g.drift_pct.median(), p90=g.drift_pct.quantile(.9), u10=g.drift_pct.apply(lambda s: 100 * (s < 10).mean()),
                          end=g.end_m.median(), head=g.head_err_deg.median(), mae=g.speed_mae.median()))
    txt = ["# Edge engine on the external IMU (IO-VNBD vehicle unit; no wheel speed / speedometer)", "",
           "| method | T (s) | n | drift median % | p90 % | % under 10 % | endpoint median m | heading err deg | speed MAE m/s |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    txt += [f"| {m} | {T} | {int(r.n)} | {r.med:.2f} | {r.p90:.2f} | {r.u10:.1f} | {r.end:.1f} | {r['head']:.1f} | {r.mae:.2f} |" for (m, T), r in S.iterrows()]
    (out / "EDGE_RESULTS.md").write_text("\n".join(txt), encoding="utf-8"); print("\n".join(txt))


if __name__ == "__main__":
    main()
