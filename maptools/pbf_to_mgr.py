"""Fast OSM .pbf -> Maverick map file (.mgr) for one area: drivable roads, street names and every named place (shops, crafts,\nnamed buildings, colonies / "Nagar" areas, campuses mapped as multipolygons, ...).

Windows PowerShell example (Coimbatore):
    py -m pip install osmium
    py pbf_to_mgr.py --in D:\\Downloads\\southern-zone-260925.osm.pbf --bbox 10.85,76.80,11.20,77.15 --out coimbatore.mgr

bbox = south,west,north,east (degrees). Uses osm_pack.py from the same folder for the tag rules and the file format.
Add --disk-index if the computer runs out of memory (slower, keeps the node index in a temporary file).
"""
import argparse, os, struct, sys, tempfile, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import osm_pack as P
import osmium

KEYS = ["highway", "amenity", "shop", "tourism", "place", "leisure", "office", "historic", "railway", "public_transport", "aeroway", "healthcare",
        "building", "natural", "landuse", "waterway", "water", "craft", "man_made", "club", "emergency"]


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--in", dest="src", required=True); ap.add_argument("--bbox", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--disk-index", action="store_true")
    ap.add_argument("--no-buildings", action="store_true", help="skip building outlines (smaller file)"); a = ap.parse_args()
    s, w, n, e = [float(x) for x in a.bbox.split(",")]; pad = 0.02
    inside = lambda la, lo: s <= la <= n and w <= lo <= e
    near = lambda la, lo: s - pad <= la <= n + pad and w - pad <= lo <= e + pad
    tmp = None
    if a.disk_index:
        tmp = os.path.join(tempfile.gettempdir(), "mgr_nodes.idx"); index = osmium.index.create_map("sparse_file_array," + tmp)
    else:
        index = osmium.index.create_map("flex_mem")
    fp = osmium.FileProcessor(a.src).with_locations(index).with_areas().with_filter(osmium.filter.KeyFilter(*KEYS))
    items, cnt, t0 = [], 0, time.time()
    print("reading", a.src, "(this takes a few minutes for a large file)", flush=True)
    for o in fp:
        cnt += 1
        if cnt % 2000000 == 0:
            print(f"  {cnt / 1e6:.0f} M tagged objects scanned, {len(items)} kept, {time.time() - t0:.0f} s", flush=True)
        if o.is_area():                                          # closed ways and multipolygon relations
            if o.from_way() and "highway" in o.tags:
                continue
            tags = {t.k: t.v for t in o.tags}; ak = P.area_kind(tags)
            if not o.from_way():                                 # a named multipolygon (college campus, park, big building): keep its name as a place
                k = P.place_kind(tags)
                if k is not None:
                    ring0 = next(iter(o.outer_rings()), None)
                    pts0 = [(nr.lat, nr.lon) for nr in ring0 if nr.location.valid()] if ring0 is not None else []
                    if pts0:
                        c0 = P.centroid(pts0)
                        if inside(*c0): items.append(("place", k, P.place_name(tags), c0))
            if ak is None or (ak == P.AREA_BUILDING and a.no_buildings):
                continue
            for ring in o.outer_rings():
                pts = [(nr.lat, nr.lon) for nr in ring if nr.location.valid()]
                if len(pts) >= 3 and any(near(la, lo) for la, lo in pts[:: max(1, len(pts) // 8)]):
                    items.append(("area", ak, pts))
            continue
        if o.is_node():
            loc = o.location
            if not loc.valid() or not inside(loc.lat, loc.lon):
                continue
            tags = {t.k: t.v for t in o.tags}; k = P.place_kind(tags)
            if k is not None:
                items.append(("place", k, P.place_name(tags), (loc.lat, loc.lon)))
        elif o.is_way():
            nds = o.nodes
            if len(nds) < 2:
                continue
            probe = [nds[0].location, nds[len(nds) // 2].location, nds[len(nds) - 1].location]
            if not any(p.valid() and near(p.lat, p.lon) for p in probe):
                continue
            tags = {t.k: t.v for t in o.tags}; hw = tags.get("highway"); k = P.place_kind(tags)
            if hw not in P.DRIVABLE and k is None:
                continue                                         # (area outlines come from the area objects above)
            pts, ids = [], []
            for nd in nds:
                if nd.location.valid():
                    pts.append((nd.location.lat, nd.location.lon)); ids.append(nd.ref)
            if len(pts) < 2 or not any(inside(la, lo) for la, lo in pts):
                continue
            items.append(("way", tags, ids, pts) if hw in P.DRIVABLE else ("place", k, P.place_name(tags), P.centroid(pts)))
    print(f"scan done: {cnt} tagged objects, {len(items)} kept, {time.time() - t0:.0f} s", flush=True)
    nseg, nnames, nplaces = P.write_mgr(items, a.out); nareas = sum(1 for it in items if it[0] == "area")
    print(f"DONE: {nseg} road segments, {nnames} names, {nplaces} places, {nareas} buildings/water/parks -> {a.out} ({os.path.getsize(a.out) / 1e6:.1f} MB)", flush=True)
    if tmp and os.path.exists(tmp):
        os.remove(tmp)


if __name__ == "__main__":
    main()
