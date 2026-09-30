"""Score the map layer on saved engine tracks (from idr.edge -> docs/edge/edge_tracks.pkl, or idr.phone_tracks ->
docs/idr/phone_tracks.pkl). Same outages, same truth, same start state as the engine results.
    python -m idr.map_eval --tracks docs/edge/edge_tracks.pkl --map data/osm/coventry_roads.json --out docs/map_edge
"""
from __future__ import annotations

import argparse
import pickle
import time
from pathlib import Path

import numpy as np
import pandas as pd

from .mapmatch import map_aided
from .roads import Roads, load_ways


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--tracks", required=True); ap.add_argument("--map", required=True)
    ap.add_argument("--out", required=True); a = ap.parse_args(); out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    ways = load_ways(a.map); P = pickle.load(open(a.tracks, "rb"))
    roads = {k: Roads.around(ways, v["tr"]) for k, v in P["drives"].items()}
    rows, keep = [], []; t0 = time.time()
    for n, t in enumerate(P["TRK"]):
        d = t["drive"]; tr = P["drives"][d]["tr"]; k0 = t["k0"]; L = len(t["speed"])
        T = np.column_stack([tr["x"][k0:k0 + L], tr["y"][k0:k0 + L]]); dist = float(np.sum(np.hypot(*np.diff(T, axis=0).T)))
        fused, pf, dr = map_aided(roads[d], T[0, 0], T[0, 1], tr["heading"][k0], t["speed"], t["yaw"], seed=n)
        for m, e in (("DR, no map", dr), ("DR + map (particle filter)", pf), ("MaverickGRID: DR + map + consistency", fused)):
            rows.append(dict(method=m, drive=d, T=t["T"], k0=k0, dist_m=dist, end_m=float(np.hypot(*(e[-1] - T[-1]))),
                             drift_pct=100 * float(np.hypot(*(e[-1] - T[-1]))) / dist))
        keep.append(dict(drive=d, T=t["T"], k0=k0, truth=T, fused=fused, pf=pf, dr=dr))
        if n % 100 == 0:
            print(f"{n}/{len(P['TRK'])} [{time.time() - t0:.0f} s]", flush=True)
    df = pd.DataFrame(rows); df.to_csv(out / "map_results.csv", index=False); pickle.dump(keep, open(out / "map_tracks.pkl", "wb"))
    g = df.groupby(["method", "T"]).drift_pct
    S = pd.DataFrame(dict(n=g.size(), med=g.median(), p90=g.quantile(.9), u10=g.apply(lambda s: 100 * (s < 10).mean())))
    lines = [f"# Map-aided dead reckoning ({a.tracks})", "", "| method | T (s) | n | drift median % | p90 % | % under 10 % |", "|---|---:|---:|---:|---:|---:|"]
    lines += [f"| {m} | {T} | {int(r.n)} | {r.med:.2f} | {r.p90:.2f} | {r.u10:.1f} |" for (m, T), r in S.iterrows()]
    rng = np.random.default_rng(0); lines += ["", "Paired vs DR, no map (same outages): share better, median difference, 95 % bootstrap CI", ""]
    base = df[df.method == "DR, no map"].drift_pct.to_numpy(); Ts = df[df.method == "DR, no map"]["T"].to_numpy()
    for m in ("DR + map (particle filter)", "MaverickGRID: DR + map + consistency"):
        x = df[df.method == m].drift_pct.to_numpy()
        for T in (30, 60, 120):
            dd = (x - base)[Ts == T]; bs = [np.median(rng.choice(dd, len(dd))) for _ in range(2000)]; lo, hi = np.percentile(bs, [2.5, 97.5])
            lines.append(f"- {m}, T={T}: better in {100 * np.mean(dd < 0):.0f} %, median diff {np.median(dd):+.2f} pts, CI [{lo:+.2f}, {hi:+.2f}]")
    lines += ["", "Per held-out drive (median drift %, all T):", ""]
    lines += [f"- {k}: " + ", ".join(f"{m.split(':')[0]} {v:.2f}" for m, v in grp.groupby('method').drift_pct.median().items()) for k, grp in df.groupby("drive")]
    (out / "MAP_RESULTS.md").write_text("\n".join(lines), encoding="utf-8"); print("\n".join(lines))


if __name__ == "__main__":
    main()
