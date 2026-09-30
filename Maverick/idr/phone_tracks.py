"""Save phone MaverickGRID tracks (S1 learned speed + stop-bias gyro heading) and hold-last-speed tracks for the 4 held-out
USABLE drives, for the map layer (idr.map_eval) and position plots. Leave-one-drive-out, same as idr.run. Drive M never loaded.
    python -m idr.phone_tracks --root "<...>\\Categorised IOVNB Dataset"
"""
import argparse, pickle
from pathlib import Path
import numpy as np, pandas as pd
from .calib import gyro_from_stops
from .data import DT, find_root, prepare_all
from .features import STRIDE, imu_features
from .harness import PhoneView, integrate, outage_windows
from .run import fit_split, heading_sign, speed_methods
ap = argparse.ArgumentParser(); ap.add_argument("--root"); ap.add_argument("--out", default="docs/idr"); A = ap.parse_args()
root = find_root(A.root); Path(A.out).mkdir(parents=True, exist_ok=True)
drives, status = prepare_all(root, log=lambda s: None); status.pop("_gps_late_s"); st = pd.DataFrame(status).T
usable = sorted(st.index[st.role == "USABLE"]); pool = sorted(st.index[st.role.isin(["USABLE", "SPEED-ONLY"])]); print(usable, flush=True)
feats = {k: imu_features(drives[k]["ph"], np.arange(0, drives[k]["n"], STRIDE)) for k in pool}
TRK, res = [], []
for sk in usable:
    M = fit_split(drives, feats, [k for k in pool if k != sk], print); hs = heading_sign(drives, [k for k in usable if k != sk])
    d, F = drives[sk], feats[sk]; stop_rows = M["stop"].predict(F); raw = hs * d["ph"]["gyr"][:, 1]
    for k0, L in outage_windows(d):
        view = PhoneView(d, k0); sp, _ = speed_methods(d, F, k0, L, M, view); b, _ = gyro_from_stops(raw[: k0 + 1], stop_rows, k0)
        yaw = raw[k0:k0 + L] + b; hold = np.full(L, float(view.gps_speed_before[-1]))
        m = integrate(d, k0, L, sp["S1"], hs, yaw_rate=yaw); res.append(dict(drive=sk, T=int(round(L * DT)), **m))
        TRK.append(dict(drive=sk, T=int(round(L * DT)), k0=k0, speed=sp["S1"], yaw=yaw, hold=hold, rawyaw=raw[k0:k0 + L].copy()))
df = pd.DataFrame(res); print(df.groupby("T").drift_pct.median())
pickle.dump(dict(TRK=TRK, drives={k: dict(tr=drives[k]["tr"]) for k in usable}), open(Path(A.out) / "phone_tracks.pkl", "wb"))
