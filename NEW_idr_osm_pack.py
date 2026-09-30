"""Pack drivable OSM roads into the compact file the Java/Android engine loads (roads.mgr).

    python -m idr.osm_pack --in data/osm/coventry_roads.json --out export/maps/coventry.mgr
    python -m idr.osm_pack --in southern-zone-latest.osm.pbf --bbox 10.90,76.85,11.15,77.10 --out export/maps/coimbatore.mgr
      (bbox = south,west,north,east; .pbf needs:  pip install osmium)

Format (big-endian): magic 'MGR1', int32 nSeg, then per segment: int32 lat1e7, int32 lon1e7, int32 lat2e7, int32 lon2e7,
int64 node1, int64 node2, byte flags (bit0 one-way, bit1 service road).  ~33 bytes per segment.
"""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

DRIVABLE = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link", "road"}
ONEWAY_T = {"motorway", "motorway_link"}


def ways_from_json(path):
    for e in json.load(open(path, encoding="utf-8"))["elements"]:
        if e["type"] == "way" and "geometry" in e:
            yield e["tags"], e["nodes"], [(p["lat"], p["lon"]) for p in e["geometry"]]


def ways_from_pbf(path, bbox):
    import osmium
    s, w, n, e = bbox
    class H(osmium.SimpleHandler):
        def __init__(self):
            super().__init__(); self.out = []
        def way(self, wy):
            hw = wy.tags.get("highway")
            if hw not in DRIVABLE: return
            try:
                pts = [(nd.lat, nd.lon) for nd in wy.nodes]; ids = [nd.ref for nd in wy.nodes]
            except osmium.InvalidLocationError:
                return
            if not any(s <= la <= n and w <= lo <= e for la, lo in pts): return
            self.out.append(({k.k: k.v for k in wy.tags}, ids, pts))
    h = H(); h.apply_file(str(path), locations=True); return h.out


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--in", dest="src", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--bbox", default=""); a = ap.parse_args()
    ways = ways_from_pbf(a.src, [float(x) for x in a.bbox.split(",")]) if a.src.endswith(".pbf") else ways_from_json(a.src)
    segs = []
    for tags, ids, pts in ways:
        hw = tags.get("highway", "")
        if hw not in DRIVABLE: continue
        ow = tags.get("oneway") in ("yes", "1", "true") or hw in ONEWAY_T or tags.get("junction") == "roundabout"
        flags = (1 if ow else 0) | (2 if hw == "service" else 0)
        for i in range(len(pts) - 1):
            segs.append((round(pts[i][0] * 1e7), round(pts[i][1] * 1e7), round(pts[i + 1][0] * 1e7), round(pts[i + 1][1] * 1e7), ids[i], ids[i + 1], flags))
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "wb") as f:
        f.write(b"MGR1"); f.write(struct.pack(">i", len(segs)))
        for s in segs: f.write(struct.pack(">iiiiqqb", *s))
    print(f"{len(segs)} segments -> {a.out} ({Path(a.out).stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
