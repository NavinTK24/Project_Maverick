"""Pack an OSM extract into the offline map file used by the Java/Android engine (.mgr): drivable roads for map matching
and routing, street names, and named places for search.

    python -m idr.osm_pack --in data/osm/coventry_roads.json --out export/maps/coventry.mgr [--places places.json]
    python -m idr.osm_pack --in southern-zone-latest.osm.pbf --bbox 10.85,76.80,11.20,77.15 --out coimbatore.mgr
      (bbox = south,west,north,east; .pbf needs:  pip install osmium ; a .pbf gives roads AND places in one pass;
       places mapped only as multipolygon relations are not included)

Format MGR2 (big-endian):
  'MGR2', int nSeg, per segment: int lat1e7, int lon1e7, int lat2e7, int lon2e7, long node1, long node2,
          byte flags (bit0 one-way, bit1 service), byte roadClass (0 motorway .. 6 service), int nameIdx (-1 = none)
  int nNames, per name: UTF-8 (unsigned short length + bytes)
  int nPlaces, per place: int lat1e7, int lon1e7, byte kind, int nameIdx
"""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

DRIVABLE = {"motorway": 0, "motorway_link": 0, "trunk": 1, "trunk_link": 1, "primary": 2, "primary_link": 2, "secondary": 3,
            "secondary_link": 3, "tertiary": 4, "tertiary_link": 4, "unclassified": 5, "residential": 5, "living_street": 5,
            "road": 5, "service": 6}
ONEWAY_T = {"motorway", "motorway_link"}
# place kinds: 1 food, 2 fuel, 3 health, 4 shop, 5 education, 6 transport, 7 locality, 8 other
KIND_RULES = [("amenity", {"restaurant", "cafe", "fast_food", "food_court", "bar", "pub", "ice_cream"}, 1),
              ("amenity", {"fuel", "charging_station"}, 2),
              ("amenity", {"hospital", "clinic", "doctors", "pharmacy", "dentist"}, 3), ("healthcare", None, 3),
              ("shop", None, 4),
              ("amenity", {"school", "college", "university", "kindergarten", "library"}, 5),
              ("amenity", {"bus_station", "taxi", "parking", "ferry_terminal"}, 6), ("railway", {"station", "halt"}, 6),
              ("public_transport", {"station"}, 6), ("aeroway", {"aerodrome", "terminal"}, 6),
              ("place", {"city", "town", "suburb", "village", "neighbourhood", "quarter", "hamlet", "locality"}, 7),
              ("amenity", None, 8), ("tourism", None, 8), ("leisure", {"park", "stadium", "sports_centre"}, 8),
              ("office", None, 8), ("historic", None, 8), ("building", {"temple", "church", "mosque", "hospital", "university", "college"}, 8)]


def place_kind(tags):
    if "name" not in tags:
        return None
    for k, vals, kind in KIND_RULES:
        v = tags.get(k)
        if v is not None and (vals is None or v in vals):
            return kind
    return None


def centroid(pts):
    """Mean of the vertices, counting a closed ring's repeated first/last vertex once."""
    if len(pts) > 1 and pts[0] == pts[-1]:
        pts = pts[:-1]
    return (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))


def ways_from_json(path):
    places = []
    for e in json.load(open(path, encoding="utf-8"))["elements"]:
        tags = e.get("tags", {})
        if e["type"] == "way" and "geometry" in e and tags.get("highway") in DRIVABLE:
            yield ("way", tags, e["nodes"], [(p["lat"], p["lon"]) for p in e["geometry"]])
        kind = place_kind(tags)
        if kind is not None:
            if e["type"] == "node" and "lat" in e:
                yield ("place", kind, tags["name"], (e["lat"], e["lon"]))
            elif "center" in e:
                yield ("place", kind, tags["name"], (e["center"]["lat"], e["center"]["lon"]))
            elif "geometry" in e:
                g = [(p["lat"], p["lon"]) for p in e["geometry"]]; yield ("place", kind, tags["name"], centroid(g))


def items_from_pbf(path, bbox):
    import osmium
    s, w, n, e = bbox
    inside = lambda la, lo: s <= la <= n and w <= lo <= e
    out = []

    class H(osmium.SimpleHandler):
        def node(self, nd):
            tags = {t.k: t.v for t in nd.tags}; kind = place_kind(tags)
            if kind is not None and nd.location.valid() and inside(nd.location.lat, nd.location.lon):
                out.append(("place", kind, tags["name"], (nd.location.lat, nd.location.lon)))

        def way(self, wy):
            tags = {t.k: t.v for t in wy.tags}; hw = tags.get("highway"); kind = place_kind(tags)
            if hw not in DRIVABLE and kind is None:
                return
            try:
                pts = [(nd.lat, nd.lon) for nd in wy.nodes]; ids = [nd.ref for nd in wy.nodes]
            except osmium.InvalidLocationError:
                return
            if not pts or not any(inside(la, lo) for la, lo in pts):
                return
            if hw in DRIVABLE:
                out.append(("way", tags, ids, pts))
            elif kind is not None:
                out.append(("place", kind, tags["name"], centroid(pts)))

    H().apply_file(str(path), locations=True)
    return out


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--in", dest="src", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--bbox", default=""); ap.add_argument("--places", default="", help="optional extra Overpass JSON with named places")
    a = ap.parse_args()
    if a.src.endswith(".pbf"):
        if not a.bbox:
            raise SystemExit("--bbox south,west,north,east is required for .pbf input")
        items = items_from_pbf(a.src, [float(x) for x in a.bbox.split(",")])
    else:
        items = list(ways_from_json(a.src))
    if a.places:
        items += [it for it in ways_from_json(a.places) if it[0] == "place"]
    names, name_idx = [], {}

    def nid(name):
        if not name:
            return -1
        if name not in name_idx:
            name_idx[name] = len(names); names.append(name)
        return name_idx[name]

    segs, places, seen = [], [], set()
    for it in items:
        if it[0] == "way":
            _, tags, ids, pts = it; hw = tags.get("highway", "")
            ow = tags.get("oneway") in ("yes", "1", "true") or hw in ONEWAY_T or tags.get("junction") == "roundabout"
            rev = tags.get("oneway") == "-1"
            if rev:
                ids, pts, ow = ids[::-1], pts[::-1], True
            flags = (1 if ow else 0) | (2 if hw == "service" else 0); cls = DRIVABLE[hw]; ni = nid(tags.get("name") or tags.get("ref"))
            for i in range(len(pts) - 1):
                segs.append((round(pts[i][0] * 1e7), round(pts[i][1] * 1e7), round(pts[i + 1][0] * 1e7), round(pts[i + 1][1] * 1e7), ids[i], ids[i + 1], flags, cls, ni))
        else:
            _, kind, name, (la, lo) = it; key = (name, round(la, 4), round(lo, 4))
            if key in seen:
                continue
            seen.add(key); places.append((round(la * 1e7), round(lo * 1e7), kind, nid(name)))
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "wb") as f:
        f.write(b"MGR2"); f.write(struct.pack(">i", len(segs)))
        for s in segs:
            f.write(struct.pack(">iiiiqqbbi", *s))
        f.write(struct.pack(">i", len(names)))
        for n in names:
            b = n.encode("utf-8")[:65535].decode("utf-8", "ignore").encode("utf-8")   # never cut a character in half
            f.write(struct.pack(">H", len(b))); f.write(b)
        f.write(struct.pack(">i", len(places)))
        for p in places:
            f.write(struct.pack(">iibi", *p))
    print(f"{len(segs)} road segments, {len(names)} names, {len(places)} places -> {a.out} ({Path(a.out).stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
