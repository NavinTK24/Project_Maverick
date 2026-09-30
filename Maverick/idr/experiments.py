"""Step-2/3 experiments on IO-VNBD: online self-calibration (speed bands, gyro bias/scale from GPS course and from stops),
a sensor-trust check for heading, and shrinking the learned speed correction towards hold-last-speed.
Same held-out drives, same outage windows, same integrator as idr.run. Drive M is never loaded.

    python -m idr.experiments --root "<...>\\Categorised IOVNB Dataset"
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from .calib import apply_band, gyro_from_course, gyro_from_stops, heading_trust, speed_band_correction
from .data import DT, find_root, prepare_all
from .features import STRIDE, context_at, imu_features, residual_design
from .harness import LENGTHS_S, PhoneView, hold_1s, integrate, outage_windows
from .run import fit_split, heading_sign


def speeds(d, F, k0, L, M, view, a_all, late):
    nsec = int(np.ceil(L * DT)); rows = np.minimum(k0 // STRIDE + np.arange(nsec), len(F) - 1); Ft = F[rows]
    stopped = M["stop"].predict(Ft); a_mu, a_sd = a_all[0][rows], a_all[1][rows]
    ctx = context_at(view.gps_speed_before, k0); v_last = float(ctx[0])
    r_mu, r_sd = M["R"].predict(residual_design(Ft, F[k0 // STRIDE], ctx, np.arange(nsec, dtype=np.float32)))
    corr, npairs = speed_band_correction(a_all[0][: k0 // STRIDE + 1], view.gps_speed_before, view.gps_x_before, k0, late)
    a_cal = apply_band(a_mu, corr)
    prior = np.interp(np.arange(nsec), M["prior_tau"], M["prior_var"])
    alpha = prior / (prior + r_sd ** 2)                                   # shrink the learned correction when it is unsure
    s1 = np.maximum(v_last + r_mu, 0); s1g = np.maximum(v_last + alpha * r_mu, 0)
    w1, w0 = 1 / r_sd ** 2, 1 / a_sd ** 2                                  # inverse-variance blend of S1 and calibrated S0
    s4 = (w1 * s1 + w0 * a_cal) / (w1 + w0)
    out = {"S1": s1, "S0c": a_cal, "S1g": s1g, "S4": s4}
    return {k: hold_1s(np.where(stopped, 0.0, v), L) for k, v in out.items()}, npairs


def headings(d, k0, L, hs, stopped_rows, view, late):
    raw = hs * d["ph"]["gyr"][:, 1]
    fit = gyro_from_course(raw[: k0 + 1], view.gps_x_before, view.gps_y_before, view.gps_speed_before, k0, late)
    b_stop, t_stop = gyro_from_stops(raw[: k0 + 1], stopped_rows, k0)
    seg = raw[k0:k0 + L]
    H = {"H0 raw gyro": seg,
         "Hs stop bias": seg + b_stop,
         "Hb course bias": seg + fit["bias"],
         "Hc course bias+scale": fit["scale"] * seg + fit["bias"]}
    trusted = heading_trust(fit)
    H["Ht trust (course fit, else hold course)"] = H["Hc course bias+scale"] if trusted else np.zeros(L)
    return H, fit, b_stop, t_stop, trusted


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--root"); ap.add_argument("--out", default="docs/idr")
    a = ap.parse_args(); out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    root = find_root(a.root); drives, status = prepare_all(root, log=lambda s: None); status.pop("_gps_late_s")
    st = pd.DataFrame(status).T
    usable = sorted(st.index[st.role == "USABLE"]); pool = sorted(st.index[st.role.isin(["USABLE", "SPEED-ONLY"])])
    feats = {k: imu_features(drives[k]["ph"], np.arange(0, drives[k]["n"], STRIDE)) for k in pool}
    rows, cal = [], []
    for sk in usable:
        tk = [k for k in pool if k != sk]; ou = [k for k in usable if k != sk]
        M = fit_split(drives, feats, tk, print); hs = heading_sign(drives, ou)
        late = int(round(np.median([(st.loc[k, "speed_lag_s"] - st.loc[k, "gyro_lag_s"]) for k in ou]) / DT))
        d, F = drives[sk], feats[sk]; a_all = M["A"].predict(F); stopped_rows = M["stop"].predict(F)
        for k0, L in outage_windows(d):
            T = int(round(L * DT)); view = PhoneView(d, k0)
            S, npairs = speeds(d, F, k0, L, M, view, a_all, late)
            S["B1"] = np.full(L, float(view.gps_speed_before[-1])); orac = d["tr"]["speed"][k0:k0 + L].copy()
            H, fit, b_stop, t_stop, trusted = headings(d, k0, L, hs, stopped_rows, view, late)
            cal.append(dict(drive=sk, T=T, k0=k0, course_r=fit["r"], course_n=fit["n"], scale=fit["scale"], bias_dps=np.degrees(fit["bias"]),
                            stop_bias_dps=np.degrees(b_stop), stop_s=t_stop, trusted=trusted, band_pairs=npairs))
            for sn, sp in S.items():                                   # speed variants with raw gyro heading
                rows.append(dict(part="speed", method=sn, drive=sk, T=T, **integrate(d, k0, L, sp, hs, yaw_rate=H["H0 raw gyro"])))
            for hn, hr in H.items():                                   # heading variants with oracle speed, then with S1
                rows.append(dict(part="heading|oracle speed", method=hn, drive=sk, T=T, **integrate(d, k0, L, orac, hs, yaw_rate=hr)))
                rows.append(dict(part="heading|S1 speed", method=hn, drive=sk, T=T, **integrate(d, k0, L, S["S1"], hs, yaw_rate=hr)))
        print(f"{sk} scored", flush=True)
    # trust check on drives whose gyro is known to be unusable: heading only, oracle speed, no model needed
    for k in [k for k in pool if k not in usable and drives[k]["n"] * DT > 900][:12]:
        d = drives[k]; hs = -1.0
        late = int(round(np.median([(st.loc[u, "speed_lag_s"] - st.loc[u, "gyro_lag_s"]) for u in usable]) / DT))
        dummy = np.zeros(len(d["tr"]["speed"][::STRIDE]), bool)
        for k0, L in outage_windows(d):
            view = PhoneView(d, k0); H, fit, *_ , trusted = headings(d, k0, L, hs, dummy, view, late); orac = d["tr"]["speed"][k0:k0 + L].copy()
            for hn in ("H0 raw gyro", "Ht trust (course fit, else hold course)"):
                rows.append(dict(part="heading|oracle speed|UNUSABLE-gyro drives", method=hn, drive=k, T=int(round(L * DT)), trusted=trusted, **integrate(d, k0, L, orac, hs, yaw_rate=H[hn])))
            rows.append(dict(part="heading|oracle speed|UNUSABLE-gyro drives", method="hold last GPS course", drive=k, T=int(round(L * DT)), **integrate(d, k0, L, orac, hs, yaw_rate=np.zeros(L))))
    df = pd.DataFrame(rows); df.to_csv(out / "experiments.csv", index=False); C = pd.DataFrame(cal); C.to_csv(out / "calibration_log.csv", index=False)
    write(out, df, C)


def write(out, df, C):
    L = ["# Online self-calibration experiments (IO-VNBD, held-out USABLE drives)", "",
         "All numbers computed by `idr/experiments.py`. Calibration uses only phone data from before each outage.", ""]
    for part in df.part.unique():
        g = df[df.part == part].groupby(["method", "T"])
        S = pd.DataFrame(dict(n=g.size(), drift_med=g.drift_pct.median(), drift_p90=g.drift_pct.quantile(.9),
                              under10=g.drift_pct.apply(lambda s: 100 * (s < 10).mean()), head_med=g.head_err_deg.median(), speed_mae=g.speed_mae.median())).round(2)
        L += [f"## {part}", "", "| method | T (s) | n | drift median % | p90 % | % under 10 % | heading err median deg | speed MAE m/s |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
        L += [f"| {m} | {T} | {int(r.n)} | {r.drift_med:.2f} | {r.drift_p90:.2f} | {r.under10:.1f} | {r.head_med:.1f} | {r.speed_mae:.2f} |" for (m, T), r in S.iterrows()]
        L.append("")
    L += ["## Calibration estimates (held-out USABLE drives)", "",
          f"- course-fit pairs per outage: median {C.course_n.median():.0f}; correlation r median {C.course_r.median():.3f}; scale median {C.scale.median():.3f}; bias median {C.bias_dps.median():.3f} deg/s",
          f"- stop-based bias available on {100 * (C.stop_s > 0).mean():.0f} % of outages; median {C.stop_bias_dps[C.stop_s > 0].median():.3f} deg/s",
          f"- gyro trusted on {100 * C.trusted.mean():.1f} % of outages; speed-band calibration pairs median {C.band_pairs.median():.0f}", ""]
    (out / "EXPERIMENTS.md").write_text("\n".join(L), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
