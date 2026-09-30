"""Train and score the phone-only speed methods on IO-VNBD, leave-one-drive-out, through one shared integrator.

    python -m idr.run --root "D:\\MaverickGRID\\IO-VNBD\\Synchronised V abd S datasets\\Categorised IOVNB Dataset"

Writes docs/idr/results.csv, docs/idr/outages.csv, docs/idr/drive_status.csv and docs/idr/RESULTS.md.
Every number and every conclusion sentence in RESULTS.md is computed in this file from this run.
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

import numpy as np
import pandas as pd

from .data import DT, find_root, prepare_all
from .features import IMU_NAMES, RESIDUAL_NAMES, STRIDE, context_at, imu_features, residual_design
from .harness import LENGTHS_S, PhoneView, hold_1s, integrate, outage_windows, summarise
from .models import SpeedModel, StopDetector

TAU_STEP_S, R_START_EVERY_S, R_MAX_TAU_S = 5, 20, 120


def heading_sign(drives: dict, keys: list[str]) -> float:
    s = 0.0
    for k in keys:
        d = drives[k]; mv = d["tr"]["speed"] > 5 / 3.6
        s += float(np.nansum((d["ph"]["gyr"][:, 1] * d["tr"]["heading_rate"])[mv]))
    return float(np.sign(s)) or 1.0


def forward_axis(drives: dict, keys: list[str]) -> np.ndarray:
    A, b = [], []
    for k in keys:
        d = drives[k]; ph = d["ph"]; mv = d["tr"]["speed"] > 5 / 3.6
        g = ph["grav"] / np.linalg.norm(ph["grav"], axis=1, keepdims=True); lin = ph["acc"] - ph["grav"]
        h = lin - np.sum(lin * g, axis=1, keepdims=True) * g
        dv = np.gradient(pd.Series(d["tr"]["speed"]).rolling(5, center=True, min_periods=1).mean().to_numpy(), DT)
        A.append(h[mv]); b.append(dv[mv])
    return np.linalg.lstsq(np.vstack(A), np.concatenate(b), rcond=None)[0]


def residual_rows(d: dict, F: np.ndarray):
    """Training rows for the residual model: simulated outages every 20 s, tau = 0..120 s every 5 s."""
    X, y, n, v = [], [], d["n"], d["tr"]["speed"]
    for k0 in range(int(60 / DT), n - 10, int(R_START_EVERY_S / DT)):
        if v[k0] <= 5 / 3.6:
            continue
        ctx = context_at(d["ph"]["gps_speed"], k0)
        taus = np.arange(0, R_MAX_TAU_S + 1, TAU_STEP_S)
        ks = k0 + (taus / DT).astype(int); ok = ks < n; taus, ks = taus[ok], ks[ok]
        X.append(residual_design(F[ks // STRIDE], F[k0 // STRIDE], ctx, taus.astype(np.float32)))
        y.append(v[ks] - ctx[0])
    return (np.vstack(X), np.concatenate(y)) if X else (np.empty((0, len(RESIDUAL_NAMES))), np.empty(0))


def fit_split(drives, feats, train_keys, log):
    t0 = time.time()
    XA = np.vstack([feats[k] for k in train_keys])
    yA = np.concatenate([drives[k]["tr"]["speed"][::STRIDE][: len(feats[k])] for k in train_keys])
    grp = np.concatenate([[k] * len(feats[k]) for k in train_keys])
    A = SpeedModel().fit(XA, yA)
    rr = [residual_rows(drives[k], feats[k]) for k in train_keys]
    XR = np.vstack([r[0] for r in rr]); yR = np.concatenate([r[1] for r in rr])
    R = SpeedModel().fit(XR, yR)
    stop = StopDetector().fit(XA, yA < 1 / 3.6, yA > 5 / 3.6, grp)
    # Kalman constants from training drives only
    p0 = float(np.var([r for rows in rr for r in rows[1][rows[0][:, -1] == 0]]) if len(XR) else 1.0)
    dv = np.concatenate([np.diff(drives[k]["tr"]["speed"][::STRIDE]) for k in train_keys])
    q = float(np.var(dv))
    log(f"  trained on {len(train_keys)} drives: A rows {len(XA)}, R rows {len(XR)}, stop thr {stop.threshold:.2f} "
        f"(OOF false {100 * stop.oof_false:.2f} %, capture {100 * stop.oof_capture:.1f} %), P0 {p0:.3f}, q {q:.3f} [{time.time() - t0:.0f} s]")
    taus = np.unique(XR[:, -1]); prior_var = np.array([float(np.var(yR[XR[:, -1] == t])) for t in taus])
    return dict(A=A, R=R, stop=stop, p0=p0, q=q, n_A=len(XA), n_R=len(XR), prior_tau=taus, prior_var=prior_var)


def speed_methods(d, F, k0, L, M, view: PhoneView):
    """10 Hz speed series for S0/S1/S2. Uses IMU features up to each instant and phone GPS up to k0 only."""
    nsec = int(np.ceil(L * DT)); rows = k0 // STRIDE + np.arange(nsec)
    rows = np.minimum(rows, len(F) - 1)
    Ft = F[rows]
    stopped = M["stop"].predict(Ft)
    a_mu, a_sd = M["A"].predict(Ft)
    ctx = context_at(view.gps_speed_before, k0)
    r_mu, r_sd = M["R"].predict(residual_design(Ft, F[k0 // STRIDE], ctx, np.arange(nsec, dtype=np.float32)))
    s0 = np.where(stopped, 0.0, np.maximum(a_mu, 0))
    s1 = np.where(stopped, 0.0, np.maximum(ctx[0] + r_mu, 0))
    v, P, s2 = float(ctx[0]), M["p0"], []
    for j in range(nsec):                               # 1-D Kalman: prior = last GPS speed, measurements = IMU-only model
        P += M["q"]
        z, Rm = (0.0, 0.1 ** 2) if stopped[j] else (float(a_mu[j]), float(a_sd[j]) ** 2)
        K = P / (P + Rm); v += K * (z - v); P *= 1 - K; s2.append(max(v, 0.0))
    return {m: hold_1s(np.asarray(s), L) for m, s in (("S0", s0), ("S1", s1), ("S2", s2))}, dict(a_mu=a_mu, a_sd=a_sd, r_sd=r_sd, stopped=stopped, rows=rows)


def main() -> None:
    ap = argparse.ArgumentParser(); ap.add_argument("--root"); ap.add_argument("--out", default="docs/idr")
    a = ap.parse_args(); out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    lines_log = []
    def log(s): print(s, flush=True); lines_log.append(s)
    root = find_root(a.root); log(f"DATA_ROOT={root}")
    drives, status = prepare_all(root, log)
    gps_late = status.pop("_gps_late_s")
    st = pd.DataFrame(status).T; st.index.name = "drive"; st.to_csv(out / "drive_status.csv")
    usable = sorted(st.index[st.role == "USABLE"]); train_pool = sorted(st.index[st.role.isin(["USABLE", "SPEED-ONLY"])])
    log(f"USABLE={','.join(usable)}  SPEED-ONLY={int((st.role == 'SPEED-ONLY').sum())}  EXCLUDED={int((st.role == 'EXCLUDED').sum())}  phone GPS speed late by {gps_late:.1f} s")
    log("feature extraction ...")
    feats = {k: imu_features(drives[k]["ph"], np.arange(0, drives[k]["n"], STRIDE)) for k in train_pool}
    rows, cal, stops, splits, outs = [], [], [], [], []
    for sk in usable:
        log(f"SPLIT held-out {sk}")
        tk = [k for k in train_pool if k != sk]
        M = fit_split(drives, feats, tk, log)
        hs = heading_sign(drives, [k for k in usable if k != sk]); u = forward_axis(drives, [k for k in usable if k != sk])
        d, F = drives[sk], feats[sk]
        # held-out stop-detector and sigma calibration on every 1 s row of the held-out drive
        vt = d["tr"]["speed"][::STRIDE][: len(F)]; fire = M["stop"].predict(F); mu, sd = M["A"].predict(F)
        stops.append(dict(drive=sk, false_stop_pct=100 * float(fire[vt > 5 / 3.6].mean()), capture_pct=100 * float(fire[vt < 1 / 3.6].mean()) if (vt < 1 / 3.6).any() else np.nan))
        cal.append(dict(drive=sk, model="A", within_1sigma_pct=100 * float((np.abs(vt - mu) < sd).mean()), mae=float(np.mean(np.abs(vt - mu)))))
        splits.append(dict(drive=sk, train_drives=len(tk), rows_A=M["n_A"], rows_R=M["n_R"], heading_sign=hs))
        ph = d["ph"]; g = ph["grav"] / np.linalg.norm(ph["grav"], axis=1, keepdims=True); lin = ph["acc"] - ph["grav"]
        afwd = (lin - np.sum(lin * g, axis=1, keepdims=True) * g) @ u
        for k0, L in outage_windows(d):
            T = int(round(L * DT)); outs.append(dict(drive=sk, T=T, k0=k0, start_s=k0 * DT, end_s=(k0 + L) * DT))
            view = PhoneView(d, k0)
            v_last = float(view.gps_speed_before[-1])
            series = {"B1": np.full(L, v_last), "B2": d["tr"]["speed"][k0:k0 + L].copy(),
                      "B4": np.maximum(d["tr"]["speed"][k0] + np.concatenate([[0], np.cumsum(afwd[k0:k0 + L - 1]) * DT]), 0)}
            sm, aux = speed_methods(d, F, k0, L, M, view); series.update(sm)
            for m, s in series.items():
                rows.append(dict(method=m, drive=sk, T=T, k0=k0, **integrate(d, k0, L, s, hs)))
            rows.append(dict(method="B3", drive=sk, T=T, k0=k0, **integrate(d, k0, L, np.full(L, v_last), hs, oracle_heading=True)))
    df = pd.DataFrame(rows); df.to_csv(out / "results.csv", index=False); pd.DataFrame(outs).to_csv(out / "outages.csv", index=False)
    write_report(out, df, st, gps_late, pd.DataFrame(stops), pd.DataFrame(cal), pd.DataFrame(splits), lines_log)
    log(f"Wrote {out / 'RESULTS.md'}")


def _fmt(x, nd=2):
    return "n/a" if pd.isna(x) else f"{x:.{nd}f}"


def write_report(out, df, st, gps_late, stops, cal, splits, console):
    order = ["B1", "B2", "B3", "B4", "S0", "S1", "S2"]
    desc = {"B1": "gyro heading + hold last phone-GPS speed (floor)", "B2": "gyro heading + VBOX speed (oracle)",
            "B3": "VBOX heading (oracle) + hold last phone-GPS speed", "B4": "gyro heading + accelerometer-integrated speed",
            "S0": "gyro heading + IMU-only learned speed (model A) + stop detector",
            "S1": "gyro heading + last GPS speed + learned correction from IMU and pre-outage context (model R) + stop detector",
            "S2": "gyro heading + Kalman fusion of last GPS speed (prior) with model A speed and its per-sample sigma"}
    S = summarise(df); L = []
    L += ["# idr — phone-only speed methods on IO-VNBD", "",
          "All numbers below are computed by `idr/run.py` in this run. Drive M is never loaded. Every method is scored by the same "
          "integrator (`idr/harness.py:integrate`) on the same outage windows (`outages.csv`).", "",
          "## Data checks", "",
          f"- Phone GPS speed used as m/s. Median VBOX/phone speed ratio while moving, USABLE drives: "
          + ", ".join(f"{k} {_fmt(st.loc[k, 'speed_ratio'], 3)}" for k in st.index[st.role == 'USABLE']) + " (1.0 expected; 3.6 would mean a double conversion).",
          f"- Phone GPS speed runs {gps_late:.1f} s late relative to the gyro-based alignment (median over USABLE drives).",
          f"- Drives: USABLE {int((st.role == 'USABLE').sum())}, SPEED-ONLY {int((st.role == 'SPEED-ONLY').sum())}, EXCLUDED {int((st.role == 'EXCLUDED').sum())}. Full table: `drive_status.csv`.", "",
          "| drive | role | sync | lag used (s) | gyro corr | speed corr | hours | km |", "|---|---|---|---:|---:|---:|---:|---:|"]
    for k in st.sort_values(["role", "km"], ascending=[False, False]).index[:12]:
        r = st.loc[k]; L.append(f"| {k} | {r.role} | {r.sync} | {_fmt(r.lag_used_s, 1)} | {_fmt(r.gyro_corr, 3)} | {_fmt(r.speed_corr, 3)} | {_fmt(r.hours)} | {_fmt(r.km, 1)} |")
    L += ["", "(first 12 rows; see `drive_status.csv` for all)", "", "## Methods", ""] + [f"- **{m}**: {desc[m]}" for m in order]
    L += ["", "## Training (leave-one-drive-out over the USABLE drives)", "", "| held-out | training drives | rows model A | rows model R | heading sign |", "|---|---:|---:|---:|---:|"]
    L += [f"| {r.drive} | {r.train_drives} | {r.rows_A} | {r.rows_R} | {int(r.heading_sign)} |" for r in splits.itertuples()]
    L += ["", "## Results — all outages", "", "| method | T (s) | n | drift median % | p90 % | worst % | % under 10 % | endpoint median m | speed MAE median m/s | speed bias m/s | heading err median deg |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for m in order:
        for T in LENGTHS_S:
            if (m, T) in S.index:
                r = S.loc[(m, T)]
                L.append(f"| {m} | {T} | {int(r.n)} | {_fmt(r.drift_med)} | {_fmt(r.drift_p90)} | {_fmt(r.drift_worst)} | {_fmt(r.under10_pct, 1)} | {_fmt(r.end_med_m, 1)} | {_fmt(r.speed_mae_med)} | {_fmt(r.speed_bias_med)} | {_fmt(r.head_med_deg, 1)} |")
    df2 = df.assign(kind=np.where(df.changed, "changed", "steady"))
    S2 = summarise(df2, by=("method", "T", "kind"))
    L += ["", "## Results — changed (> 10 km/h speed range) vs steady", "", "| method | T (s) | kind | n | drift median % | speed MAE median m/s |", "|---|---:|---|---:|---:|---:|"]
    for m in order:
        for T in LENGTHS_S:
            for kd in ("changed", "steady"):
                if (m, T, kd) in S2.index:
                    r = S2.loc[(m, T, kd)]; L.append(f"| {m} | {T} | {kd} | {int(r.n)} | {_fmt(r.drift_med)} | {_fmt(r.speed_mae_med)} |")
    L += ["", "## Computed comparisons", ""]
    for T in LENGTHS_S:
        for kd in ("changed", "steady", None):
            def med(m, col="drift_med"):
                key = (m, T, kd) if kd else (m, T)
                src = S2 if kd else S
                return src.loc[key, col] if key in src.index else np.nan
            tag = kd or "all"
            b1, s0, s1, s2 = med("B1"), med("S0"), med("S1"), med("S2")
            if np.isnan(b1):
                continue
            best = min(("S0", s0), ("S1", s1), ("S2", s2), key=lambda t: np.inf if np.isnan(t[1]) else t[1])
            L.append(f"- T={T} s, {tag}: B1 {_fmt(b1)} %, S0 {_fmt(s0)} %, S1 {_fmt(s1)} %, S2 {_fmt(s2)} %. "
                     f"S1 (last GPS speed + learned correction) is {'better' if s1 < s0 else 'worse'} than S0 (IMU only) by {_fmt(abs(s0 - s1))} points; "
                     f"best learned method {best[0]} is {'better' if best[1] < b1 else 'worse'} than B1 by {_fmt(abs(b1 - best[1]))} points.")
    L += ["", "## Stop detector (IMU-only inputs), held-out drives", "", "| drive | false stops while moving % | capture of true stops % |", "|---|---:|---:|"]
    L += [f"| {r.drive} | {_fmt(r.false_stop_pct)} | {_fmt(r.capture_pct, 1)} |" for r in stops.itertuples()]
    L += ["", "## Uncertainty calibration of model A, held-out drives (target 60–75 % within ±1 sigma)", "", "| drive | within ±1 sigma % | MAE m/s |", "|---|---:|---:|"]
    L += [f"| {r.drive} | {_fmt(r.within_1sigma_pct, 1)} | {_fmt(r.mae)} |" for r in cal.itertuples()]
    L += ["", "## Reproduce", "", "```", "python -m idr.run --root \"<...>\\Synchronised V abd S datasets\\Categorised IOVNB Dataset\"", "```", "",
          "## Console output", "", "```text", *console, "```", ""]
    (out / "RESULTS.md").write_text("\n".join(L), encoding="utf-8")


if __name__ == "__main__":
    main()
