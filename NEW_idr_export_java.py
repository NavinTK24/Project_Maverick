"""Export trained models (LightGBM native text format) and parity test vectors for the Java engine.

    python -m idr.export_java --root "<...>\\Categorised IOVNB Dataset" --out export [--parity S1] [--production]

--parity K : models trained WITHOUT drive K (leave-one-out, as in the results) + drive K's inputs and Python outputs.
--production: models trained on ALL training drives (what the app ships). Drive M is never loaded.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

from .calib import gyro_from_stops
from .data import DT, find_root, list_drives, prepare_all
from .edge import load_edge, physics_col, rows_for
from .features import IMU_NAMES, KEY_ENERGY, RESIDUAL_NAMES, STRIDE, context_at, imu_features, residual_design
from .harness import PhoneView, hold_1s, outage_windows
from .models import SpeedModel, StopDetector
from .run import fit_split, heading_sign


def save_arrays(folder: Path, arrays: dict):
    folder.mkdir(parents=True, exist_ok=True); man = {}
    for k, v in arrays.items():
        v = np.asarray(v, dtype=">f8"); v.tofile(folder / f"{k}.bin"); man[k] = list(v.shape)
    (folder / "manifest.json").write_text(json.dumps(man, indent=1))


def save_models(folder: Path, R, stop, extra: dict):
    folder.mkdir(parents=True, exist_ok=True)
    R.mean.booster_.save_model(str(folder / "speed_mean.txt"))
    R.q16.booster_.save_model(str(folder / "speed_q16.txt")); R.q84.booster_.save_model(str(folder / "speed_q84.txt"))
    stop.model.booster_.save_model(str(folder / "stop.txt"))
    meta = dict(stop_threshold=stop.threshold, n_imu=len(IMU_NAMES), key_energy=[IMU_NAMES.index(n) for n in KEY_ENERGY],
                n_inputs=len(RESIDUAL_NAMES) + extra.pop("n_extra", 0), stride=STRIDE, dt=DT, **extra)
    (folder / "meta.json").write_text(json.dumps(meta, indent=1))


def phone(root, out, parity, production):
    drives, status = prepare_all(root, log=lambda s: None); status.pop("_gps_late_s"); st = pd.DataFrame(status).T
    usable = sorted(st.index[st.role == "USABLE"]); pool = sorted(st.index[st.role.isin(["USABLE", "SPEED-ONLY"])])
    F = {k: imu_features(drives[k]["ph"], np.arange(0, drives[k]["n"], STRIDE)) for k in pool}
    todo = ([("parity_" + parity, [k for k in pool if k != parity], [k for k in usable if k != parity])] if parity else []) + \
           ([("production", pool, usable)] if production else [])
    for name, tk, hk in todo:
        M = fit_split(drives, F, tk, print); hs = heading_sign(drives, hk)
        save_models(out / "phone" / name, M["R"], M["stop"], dict(heading_sign=hs, yaw_axis=1, engine="phone", train_drives=tk))
    if parity:
        d, Fk = drives[parity], F[parity]; M_stop_rows = None
        from lightgbm import Booster
        mdir = out / "phone" / ("parity_" + parity); meta = json.loads((mdir / "meta.json").read_text())
        mean = Booster(model_file=str(mdir / "speed_mean.txt")); stopm = Booster(model_file=str(mdir / "stop.txt"))
        stop_rows = stopm.predict(Fk) >= meta["stop_threshold"]; raw = meta["heading_sign"] * d["ph"]["gyr"][:, 1]
        K0, LL, SP, YW = [], [], [], []
        for k0, L in outage_windows(d):
            view = PhoneView(d, k0); nsec = int(np.ceil(L * DT)); rr = np.minimum(k0 // STRIDE + np.arange(nsec), len(Fk) - 1)
            ctx = context_at(view.gps_speed_before, k0); r = mean.predict(residual_design(Fk[rr], Fk[k0 // STRIDE], ctx, np.arange(nsec, dtype=np.float32)))
            sp = hold_1s(np.where(stop_rows[rr], 0.0, np.maximum(ctx[0] + r, 0)), L); b, _ = gyro_from_stops(raw[: k0 + 1], stop_rows, k0)
            K0.append(k0); LL.append(L); SP.append(sp); YW.append(raw[k0:k0 + L] + b)
        tr = d["tr"]
        save_arrays(out / "phone" / ("vectors_" + parity), dict(acc=d["ph"]["acc"], grav=d["ph"]["grav"], gyr=d["ph"]["gyr"], gps_speed=d["ph"]["gps_speed"],
                    features=Fk, stop_rows=stop_rows.astype(float), k0=K0, L=LL, speed=np.concatenate(SP), yaw=np.concatenate(YW),
                    tr_x=tr["x"], tr_y=tr["y"], tr_heading=tr["heading"], tr_speed=tr["speed"], tr_lat=tr["lat"], tr_lon=tr["lon"]))
        print("phone vectors:", len(K0), "outages")


def edge(root, out, parity, production):
    drives = {}
    for d in list_drives(root):
        try:
            e = load_edge(d)
            if np.sum(e["tr"]["speed"] > 5 / 3.6) * DT > 120: drives[d["key"]] = e
        except (KeyError, ValueError):
            pass
    F = {k: imu_features(d["ph"], np.arange(0, d["n"], STRIDE)) for k, d in drives.items()}
    todo = ([("parity_" + parity, [k for k in drives if k != parity])] if parity else []) + ([("production", list(drives))] if production else [])
    for name, tk in todo:
        XA = np.vstack([F[k] for k in tk]); yA = np.concatenate([drives[k]["tr"]["speed"][::STRIDE][: len(F[k])] for k in tk])
        grp = np.concatenate([[k] * len(F[k]) for k in tk]); stop = StopDetector().fit(XA, yA < 1 / 3.6, yA > 5 / 3.6, grp)
        rr = [r for r in (rows_for(drives[k], F[k], True) for k in tk) if r is not None]
        R = SpeedModel().fit(np.vstack([r[0] for r in rr]), np.concatenate([r[1] for r in rr]))
        hs = float(np.sign(sum(np.nansum((drives[k]["ph"]["gyr"][:, 1] * drives[k]["tr"]["heading_rate"])[drives[k]["tr"]["speed"] > 2]) for k in tk)))
        save_models(out / "edge" / name, R, stop, dict(heading_sign=hs, yaw_axis=1, engine="edge", physics_input=True, n_extra=1, train_drives=tk))
        print("edge", name, "stop thr", stop.threshold, flush=True)
    if parity:
        from lightgbm import Booster
        d, Fk = drives[parity], F[parity]; mdir = out / "edge" / ("parity_" + parity); meta = json.loads((mdir / "meta.json").read_text())
        mean = Booster(model_file=str(mdir / "speed_mean.txt")); stopm = Booster(model_file=str(mdir / "stop.txt"))
        stop_rows = stopm.predict(Fk) >= meta["stop_threshold"]; raw = meta["heading_sign"] * d["ph"]["gyr"][:, 1]
        K0, LL, SP = [], [], []
        for k0, L in outage_windows(d):
            view = PhoneView(d, k0); nsec = int(np.ceil(L * DT)); r_ = np.minimum(k0 // STRIDE + np.arange(nsec), len(Fk) - 1)
            ctx = context_at(view.gps_speed_before, k0)
            X = np.column_stack([residual_design(Fk[r_], Fk[k0 // STRIDE], ctx, np.arange(nsec, dtype=np.float32)), physics_col(d, k0, k0 + np.arange(nsec) * 10)])
            sp = hold_1s(np.where(stop_rows[r_], 0.0, np.maximum(ctx[0] + mean.predict(X), 0)), L); K0.append(k0); LL.append(L); SP.append(sp)
        tr = d["tr"]
        save_arrays(out / "edge" / ("vectors_" + parity), dict(acc=d["ph"]["acc"], grav=d["ph"]["grav"], gyr=d["ph"]["gyr"], gps_speed=d["ph"]["gps_speed"], ax=d["ax"],
                    features=Fk, stop_rows=stop_rows.astype(float), k0=K0, L=LL, speed=np.concatenate(SP), yaw=raw,
                    tr_x=tr["x"], tr_y=tr["y"], tr_heading=tr["heading"], tr_speed=tr["speed"], tr_lat=tr["lat"], tr_lon=tr["lon"]))
        print("edge vectors:", len(K0), "outages")


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--root"); ap.add_argument("--out", default="export"); ap.add_argument("--parity", default="")
    ap.add_argument("--production", action="store_true"); ap.add_argument("--which", default="phone,edge")
    a = ap.parse_args(); root = find_root(a.root); out = Path(a.out)
    if "phone" in a.which: phone(root, out, a.parity, a.production)
    if "edge" in a.which: edge(root, out, a.parity, a.production)


if __name__ == "__main__":
    main()
