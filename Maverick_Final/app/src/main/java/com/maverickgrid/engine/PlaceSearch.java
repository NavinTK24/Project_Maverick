package com.maverickgrid.engine;

import java.text.Normalizer;
import java.util.*;

/** Offline search over named places and street names in a {@link Roads} map. */
public final class PlaceSearch {
    public static final String[] KIND = {"Street", "Food", "Fuel", "Health", "Shop", "Education", "Transport", "Area", "Place"};

    public final Roads roads;

    public static final class Result {
        public final String name, kind; public final double x, y, distM;
        Result(String n, String k, double x, double y, double d) { name = n; kind = k; this.x = x; this.y = y; distM = d; }
    }

    private final Roads r; private final String[] lower; private int[][] segsByName;

    public PlaceSearch(Roads roads) {
        r = roads; this.roads = roads; lower = new String[roads.names.length];
        for (int i = 0; i < lower.length; i++) lower[i] = norm(roads.names[i]);
    }

    private synchronized int[][] streets() {
        if (segsByName != null) return segsByName;
        int[] cnt = new int[lower.length]; for (int i = 0; i < r.n; i++) if (r.nameIdx[i] >= 0 && r.nameIdx[i] < cnt.length) cnt[r.nameIdx[i]]++;
        int[][] s = new int[lower.length][]; for (int k = 0; k < s.length; k++) s[k] = new int[cnt[k]]; int[] f = new int[lower.length];
        for (int i = 0; i < r.n; i++) { int k = r.nameIdx[i]; if (k >= 0 && k < s.length) s[k][f[k]++] = i; }
        return segsByName = s;
    }

    /** Places and streets whose name contains the query (case-insensitive); best matches first, then nearest to (fromX, fromY). */
    public List<Result> search(String query, double fromX, double fromY, int limit) {
        String q = norm(query).trim().replaceAll("\\s+", " "); List<Result> out = new ArrayList<>(); if (q.length() < 2) return out;
        List<double[]> scored = new ArrayList<>();                     // {rank, dist, type(0 place,1 street), index, x, y}
        for (int p = 0; p < r.np; p++) {
            int k = r.pname[p]; if (k < 0 || k >= lower.length) continue; int rank = rank(lower[k], q); if (rank < 0) continue;
            scored.add(new double[]{rank, Math.hypot(r.px[p] - fromX, r.py[p] - fromY), 0, p, r.px[p], r.py[p]});
        }
        int[][] st = streets();
        for (int k = 0; k < lower.length; k++) {
            if (st[k].length == 0) continue; int rank = rank(lower[k], q); if (rank < 0) continue;
            // a name can belong to several separate streets: report up to 3 places at least 1 km apart, nearest first
            int m = st[k].length; double[][] c = new double[m][]; for (int j = 0; j < m; j++) { int s = st[k][j]; double mx = (r.ax[s] + r.bx[s]) / 2, my = (r.ay[s] + r.by[s]) / 2; c[j] = new double[]{Math.hypot(mx - fromX, my - fromY), mx, my}; }
            Arrays.sort(c, Comparator.comparingDouble(z -> z[0])); List<double[]> picked = new ArrayList<>();
            for (double[] z : c) { boolean far = true; for (double[] pk : picked) if (Math.hypot(pk[1] - z[1], pk[2] - z[2]) < 1000) { far = false; break; } if (far) { picked.add(z); scored.add(new double[]{rank, z[0], 1, k, z[1], z[2]}); if (picked.size() == 3) break; } }
        }
        // exact name first; then word matches with places before streets ("hospital" lists hospitals before "Hospital Lane"); then partial matches
        for (double[] s : scored) { String nm = s[2] == 0 ? lower[r.pname[(int) s[3]]] : lower[(int) s[3]]; s[0] = nm.equals(q) ? -1 : s[0] <= 1 ? s[2] : 2 + s[2]; }
        scored.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        for (double[] s : scored) {
            if (out.size() >= limit) break;
            String name = s[2] == 0 ? r.placeName((int) s[3]) : r.names[(int) s[3]];
            boolean dup = false;                                           // the same name mapped twice close together (e.g. two bus-stand nodes)
            for (Result o : out) if (o.name != null && o.name.equalsIgnoreCase(name) && Math.hypot(o.x - s[4], o.y - s[5]) < 300) { dup = true; break; }
            if (dup) continue;
            String kind = s[2] == 0 ? KIND[Math.max(0, Math.min(8, r.pkind[(int) s[3]]))] : KIND[0];
            out.add(new Result(name, kind, s[4], s[5], s[1]));
        }
        return out;
    }

    /** 0 = name starts with the query, 1 = every query word starts a word of the name, 2 = every query word occurs, -1 = no match. */
    static int rank(String name, String q) {
        if (name.startsWith(q)) return 0;
        int worst = 1;
        for (String w : q.split(" ")) {
            if (w.isEmpty()) continue; int i = name.indexOf(w); if (i < 0) return -1; boolean wordStart = false;
            while (i >= 0) { if (i == 0 || !Character.isLetterOrDigit(name.charAt(i - 1))) { wordStart = true; break; } i = name.indexOf(w, i + 1); }
            if (!wordStart) worst = 2;
        }
        return worst;
    }

    /** Lower case, accents removed, typographic apostrophes/quotes/dashes folded. */
    static String norm(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201C', '"').replace('\u201D', '"').replace('\u2013', '-').replace('\u2014', '-').toLowerCase(Locale.ROOT);
    }

    /** Nearest named place within maxDist of (x, y), or null (tap-to-select on the map). Self-contained: no index into other arrays. */
    public Result placeNear(double x, double y, double maxDist) {
        int best = -1; double bd = maxDist; for (int p = 0; p < r.np; p++) { double d = Math.hypot(r.px[p] - x, r.py[p] - y); if (d < bd) { bd = d; best = p; } }
        if (best < 0) return null; String nm = r.placeName(best);
        return new Result(nm != null ? nm : "Place", KIND[Math.max(0, Math.min(8, r.pkind[best]))], r.px[best], r.py[best], bd);
    }
}
