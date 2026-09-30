"""Offline OSM road network (Overpass JSON with `out geom`) in a drive's local metric frame: segments, direction,
one-way flags, KD-tree of densified points, and the road graph (node connectivity)."""
from __future__ import annotations

import collections
import heapq
import json

import numpy as np
from scipy.spatial import cKDTree

R_EARTH = 6378137.0
ONEWAY_T = {"motorway", "motorway_link"}


def load_ways(path):
    return [e for e in json.load(open(path, encoding="utf-8"))["elements"] if e["type"] == "way" and "geometry" in e]


def frame_of(tr):
    """Recover the (lat0, lon0, metres-per-radian-lon) local frame used by data.align from a truth track."""
    lat0 = float(np.nanmedian(tr["lat"] - np.degrees(tr["y"] / R_EARTH)))
    k = np.cos(np.radians(lat0)) * R_EARTH
    lon0 = float(np.nanmedian(tr["lon"] - np.degrees(tr["x"] / k)))
    return lat0, lon0, k


class Roads:
    def __init__(self, ways, lat0, lon0, k, bbox=None, step=2.0, exclude=("service",)):
        A, B, W, ON, U, V = [], [], [], [], [], []
        for wi, e in enumerate(ways):
            hw = e["tags"].get("highway", "")
            if hw in exclude:
                continue
            g = e["geometry"]
            x = np.radians(np.array([p["lon"] for p in g]) - lon0) * k
            y = np.radians(np.array([p["lat"] for p in g]) - lat0) * R_EARTH
            if bbox is not None and (x.max() < bbox[0] or x.min() > bbox[1] or y.max() < bbox[2] or y.min() > bbox[3]):
                continue
            ow = e["tags"].get("oneway") in ("yes", "1", "true") or hw in ONEWAY_T or e["tags"].get("junction") == "roundabout"
            for i in range(len(x) - 1):
                A.append((x[i], y[i])); B.append((x[i + 1], y[i + 1])); W.append(wi); ON.append(ow)
                U.append(e["nodes"][i]); V.append(e["nodes"][i + 1])
        self.A = np.array(A); self.B = np.array(B); self.way = np.array(W); self.oneway = np.array(ON)
        self.U = np.array(U); self.V = np.array(V)
        d = self.B - self.A; self.len = np.hypot(d[:, 0], d[:, 1]); self.len[self.len == 0] = 1e-6
        self.dir = np.arctan2(d[:, 0], d[:, 1])                     # clockwise from north, same convention as truth heading
        n = np.maximum(1, np.ceil(self.len / step).astype(int)); sid = np.repeat(np.arange(len(n)), n + 1)
        t = np.concatenate([np.linspace(0, 1, m + 1) for m in n])
        self.P = self.A[sid] + (self.B - self.A)[sid] * t[:, None]; self.sid = sid; self.tree = cKDTree(self.P)
        self.adj = collections.defaultdict(list)
        for i, (u, v) in enumerate(zip(self.U, self.V)):
            self.adj[u].append((i, v)); self.adj[v].append((i, u))
        self._cache = {}

    @classmethod
    def around(cls, ways, tr, margin=3000.0, **kw):
        lat0, lon0, k = frame_of(tr)
        bb = (np.nanmin(tr["x"]) - margin, np.nanmax(tr["x"]) + margin, np.nanmin(tr["y"]) - margin, np.nanmax(tr["y"]) + margin)
        return cls(ways, lat0, lon0, k, bb, **kw)

    def project(self, s, p):
        a = self.A[s]; d = self.B[s] - a; t = np.clip(np.sum((p - a) * d, -1) / self.len[s] ** 2, 0, 1)
        q = a + d * t[..., None]
        return q, (np.hypot(*(p - q).T) if q.ndim == 2 else float(np.hypot(*(p - q))))

    def nearest(self, pts):
        _, i = self.tree.query(pts); s = self.sid[i]; q, d = self.project(s, pts); return s, q, d

    def reachable(self, s, D):
        """Segments reachable from segment s within road distance D (m), direction ignored. Cached."""
        key = (int(s), int(D))
        if key in self._cache:
            return self._cache[key]
        dist = {self.U[s]: 0.0, self.V[s]: 0.0}; h = [(0.0, self.U[s]), (0.0, self.V[s])]; segs = {int(s)}
        while h:
            d, n = heapq.heappop(h)
            if d > dist.get(n, 1e18) or d > D:
                continue
            for sg, m in self.adj[n]:
                segs.add(sg); nd = d + self.len[sg]
                if nd < dist.get(m, 1e18) and nd <= D:
                    dist[m] = nd; heapq.heappush(h, (nd, m))
        r = np.fromiter(segs, int); self._cache[key] = r; return r
