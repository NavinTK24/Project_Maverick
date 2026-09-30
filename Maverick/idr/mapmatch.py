"""Map-aided dead reckoning (the MaverickGRID map-matching layer).

Particle filter over (x, y, heading, speed scale, gyro bias). Propagation at 10 Hz from the engine's learned speed and
yaw rate (non-holonomic: motion only along the heading). Once per second while moving, every particle is weighted by
how well it sits on an OSM road: distance to the road and alignment with the road direction (one-way aware).
Output = weighted mean, then a consistency check against plain dead reckoning: if the two diverge far beyond
normal DR drift, the output is pulled back toward DR (guards against roads missing from the map / wrong-road lock).

Settings: DEF was fixed a priori, then speed freedom (s_sd, s_rw), road tolerance (sig_d) and the consistency
threshold (FUSE_K) were chosen on tuning drives S3b, S4, Y1 only - never on the test drives S1, S2, S3a, S3c.
Cost: ~0.2-0.3 s of CPU per 120 s outage with 400 particles (desktop Python).
"""
from __future__ import annotations

import numpy as np

DT = 0.1
DEF = dict(N=400, sig_d=12.0, sig_h=np.radians(15), s_sd=0.15, s_rw=0.03, b_sd=np.radians(0.3), b_rw=np.radians(0.01),
           h_rw=np.radians(0.5), floor=0.02, k=8, reach=60.0)
FUSE_K, FUSE_A, FUSE_B = 4.0, 0.12, 10.0


def dead_reckon(x0, y0, psi0, speed, yaw):
    psi = psi0 + np.concatenate([[0.0], np.cumsum(yaw[:-1]) * DT])
    return np.column_stack([x0 + np.concatenate([[0.0], np.cumsum(speed[:-1] * np.sin(psi[:-1])) * DT]),
                            y0 + np.concatenate([[0.0], np.cumsum(speed[:-1] * np.cos(psi[:-1])) * DT])])


def particle_filter(R, x0, y0, psi0, speed, yaw, seed=0, **kw):
    p = {**DEF, **kw}; N = p["N"]; K = p["k"]; rng = np.random.default_rng(seed); L = len(speed); nP = len(R.P)
    x = np.full(N, x0); y = np.full(N, y0); psi = np.full(N, psi0) + rng.normal(0, np.radians(1), N)
    s = rng.normal(1, p["s_sd"], N); b = rng.normal(0, p["b_sd"], N); w = np.full(N, 1 / N)
    out = np.zeros((L, 2)); out[0] = x0, y0
    for i in range(L - 1):
        v = np.maximum(speed[i] * s, 0)
        x += v * np.sin(psi) * DT; y += v * np.cos(psi) * DT
        psi += (yaw[i] + b) * DT + rng.normal(0, p["h_rw"] * np.sqrt(DT), N)
        if (i + 1) % 10 == 0:
            s += rng.normal(0, p["s_rw"], N); b += rng.normal(0, p["b_rw"], N)
            if speed[i] > 0.5:
                pts = np.column_stack([x, y]); _, ii = R.tree.query(pts, k=K, distance_upper_bound=p["reach"])
                valid = ii < nP; sid = R.sid[np.where(valid, ii, 0)]
                _, d = R.project(sid.ravel(), np.repeat(pts, K, 0)); d = d.reshape(N, K)
                dh = np.abs(np.angle(np.exp(1j * (psi[:, None] - R.dir[sid]))))
                dh = np.where(R.oneway[sid], dh, np.minimum(dh, np.pi - dh))
                c = np.where(valid, (d / p["sig_d"]) ** 2 + (dh / p["sig_h"]) ** 2, np.inf)
                w = w * (np.exp(-0.5 * c.min(1)) + p["floor"]); w /= w.sum()
                if 1 / np.sum(w ** 2) < N / 2:                      # systematic resampling
                    u = (rng.random() + np.arange(N)) / N; j = np.minimum(np.searchsorted(np.cumsum(w), u), N - 1)
                    x, y, psi, s, b = x[j], y[j], psi[j], s[j], b[j]; w = np.full(N, 1 / N)
        out[i + 1] = np.sum(w * x), np.sum(w * y)
    return out


def consistency_fuse(pf, dr, speed, k=FUSE_K, a=FUSE_A, b=FUSE_B):
    """Blend toward plain DR where |map - DR| exceeds k x the expected DR drift (a x distance + b)."""
    dist = np.concatenate([[0.0], np.cumsum(speed[:-1]) * DT])
    z = np.hypot(*(pf - dr).T) / (a * dist + b); lam = 1 / (1 + np.exp(-(z - k) * 3))
    return pf + lam[:, None] * (dr - pf)


def map_aided(R, x0, y0, psi0, speed, yaw, seed=0, **kw):
    """Full map layer: returns (fused, particle-filter-only, plain-DR) tracks, each (L, 2)."""
    dr = dead_reckon(x0, y0, psi0, speed, yaw); pf = particle_filter(R, x0, y0, psi0, speed, yaw, seed=seed, **kw)
    return consistency_fuse(pf, dr, speed), pf, dr
