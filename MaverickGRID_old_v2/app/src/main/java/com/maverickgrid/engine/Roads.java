package com.maverickgrid.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Offline road network + street names + named places (from idr.osm_pack, format MGR1 or MGR2) in a local metric frame,
 * with a uniform-grid spatial index. Immutable after construction; safe to share between threads (queries take a
 * caller-owned {@link Stamp}).
 */
public final class Roads {
    public final Geo geo;
    public final double[] ax, ay, bx, by, len, dir; public final boolean[] oneway, service; public final byte[] roadClass; public final int[] nameIdx;
    public final long[] u, v; public final int n;
    public final String[] names;
    /** Places: x, y (local m), kind (1 food, 2 fuel, 3 health, 4 shop, 5 education, 6 transport, 7 locality, 8 other), name index. */
    public final double[] px, py; public final byte[] pkind; public final int[] pname; public final int np;
    public final double cell; private final LongIntArrayMap grid;

    /** Per-caller scratch for de-duplicating segments in {@link #near}. */
    public static final class Stamp { int[] s = new int[0]; int id = 0; }

    public Roads(InputStream in, Geo geo, double[] bbox, boolean includeService, double cell) throws IOException {
        this.geo = geo; this.cell = cell;
        DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        byte[] magic = new byte[4]; d.readFully(magic); String mg = new String(magic, StandardCharsets.US_ASCII);
        boolean v2 = mg.equals("MGR2"); if (!v2 && !mg.equals("MGR1")) throw new IOException("not a MaverickGRID map file");
        int total = d.readInt();
        double[] AX = new double[total], AY = new double[total], BX = new double[total], BY = new double[total];
        boolean[] OW = new boolean[total], SV = new boolean[total]; byte[] CL = new byte[total]; int[] NI = new int[total]; long[] U = new long[total], V = new long[total]; int m = 0;
        for (int i = 0; i < total; i++) {
            double la1 = d.readInt() / 1e7, lo1 = d.readInt() / 1e7, la2 = d.readInt() / 1e7, lo2 = d.readInt() / 1e7; long n1 = d.readLong(), n2 = d.readLong(); byte fl = d.readByte();
            byte cls = v2 ? d.readByte() : (byte) ((fl & 2) != 0 ? 6 : 5); int ni = v2 ? d.readInt() : -1;
            if (!includeService && (fl & 2) != 0) continue;
            double x1 = geo.x(lo1), y1 = geo.y(la1), x2 = geo.x(lo2), y2 = geo.y(la2);
            if (bbox != null && (Math.max(x1, x2) < bbox[0] || Math.min(x1, x2) > bbox[1] || Math.max(y1, y2) < bbox[2] || Math.min(y1, y2) > bbox[3])) continue;
            AX[m] = x1; AY[m] = y1; BX[m] = x2; BY[m] = y2; OW[m] = (fl & 1) != 0; SV[m] = (fl & 2) != 0; CL[m] = cls; NI[m] = ni; U[m] = n1; V[m] = n2; m++;
        }
        n = m; ax = Arrays.copyOf(AX, m); ay = Arrays.copyOf(AY, m); bx = Arrays.copyOf(BX, m); by = Arrays.copyOf(BY, m);
        oneway = Arrays.copyOf(OW, m); service = Arrays.copyOf(SV, m); roadClass = Arrays.copyOf(CL, m); nameIdx = Arrays.copyOf(NI, m); u = Arrays.copyOf(U, m); v = Arrays.copyOf(V, m);
        String[] nm = new String[0]; double[] PX = new double[0], PY = new double[0]; byte[] PK = new byte[0]; int[] PN = new int[0]; int pn = 0;
        if (v2) {
            int nn = d.readInt(); nm = new String[nn];
            for (int i = 0; i < nn; i++) { int l = d.readUnsignedShort(); byte[] b = new byte[l]; d.readFully(b); nm[i] = new String(b, StandardCharsets.UTF_8); }
            int npl = d.readInt(); PX = new double[npl]; PY = new double[npl]; PK = new byte[npl]; PN = new int[npl];
            for (int i = 0; i < npl; i++) {
                double la = d.readInt() / 1e7, lo = d.readInt() / 1e7; byte k = d.readByte(); int ni = d.readInt(); double x = geo.x(lo), y = geo.y(la);
                if (bbox != null && (x < bbox[0] || x > bbox[1] || y < bbox[2] || y > bbox[3])) continue;
                PX[pn] = x; PY[pn] = y; PK[pn] = k; PN[pn] = ni; pn++;
            }
        }
        names = nm; np = pn; px = Arrays.copyOf(PX, pn); py = Arrays.copyOf(PY, pn); pkind = Arrays.copyOf(PK, pn); pname = Arrays.copyOf(PN, pn);
        len = new double[m]; dir = new double[m];
        HashMap<Long, int[]> tmp = new HashMap<>(); HashMap<Long, Integer> cnt = new HashMap<>();
        ArrayList<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            double dx = bx[i] - ax[i], dy = by[i] - ay[i]; len[i] = Math.max(Math.hypot(dx, dy), 1e-6); dir[i] = Math.atan2(dx, dy);
            int cx0 = (int) Math.floor(Math.min(ax[i], bx[i]) / cell), cx1 = (int) Math.floor(Math.max(ax[i], bx[i]) / cell);
            int cy0 = (int) Math.floor(Math.min(ay[i], by[i]) / cell), cy1 = (int) Math.floor(Math.max(ay[i], by[i]) / cell);
            for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) {
                if (dist(i, (cx + 0.5) * cell, (cy + 0.5) * cell) > cell * 0.7072) continue;
                pairs.add(new long[]{key(cx, cy), i});
            }
        }
        pairs.sort((p, q) -> Long.compare(p[0], q[0]));
        grid = new LongIntArrayMap(Math.max(16, pairs.size() / 4));
        for (int s = 0; s < pairs.size(); ) {
            int e = s; while (e < pairs.size() && pairs.get(e)[0] == pairs.get(s)[0]) e++;
            int[] a = new int[e - s]; for (int j = s; j < e; j++) a[j - s] = (int) pairs.get(j)[1]; grid.put(pairs.get(s)[0], a); s = e;
        }
    }

    static long key(int cx, int cy) { return (((long) cx) << 32) ^ (cy & 0xffffffffL); }

    /** Segments registered in grid cell (cx, cy), or null. */
    public int[] cellSegments(int cx, int cy) { return grid.get(key(cx, cy)); }

    public String name(int i) { int k = nameIdx[i]; return k >= 0 && k < names.length ? names[k] : null; }
    public String placeName(int p) { int k = pname[p]; return k >= 0 && k < names.length ? names[k] : null; }

    /** Distance from (px, py) to segment i. */
    public double dist(int i, double qx, double qy) {
        double dx = bx[i] - ax[i], dy = by[i] - ay[i]; double t = ((qx - ax[i]) * dx + (qy - ay[i]) * dy) / (len[i] * len[i]);
        t = Math.max(0, Math.min(1, t)); double ex = ax[i] + dx * t - qx, ey = ay[i] + dy * t - qy; return Math.hypot(ex, ey);
    }

    /** Fraction along segment i of the closest point to (qx, qy). */
    public double along(int i, double qx, double qy) {
        double dx = bx[i] - ax[i], dy = by[i] - ay[i]; return Math.max(0, Math.min(1, ((qx - ax[i]) * dx + (qy - ay[i]) * dy) / (len[i] * len[i])));
    }

    /** Calls visitor once for every segment registered in the cells covering the square of half-size `reach` around (qx, qy). */
    public void near(double qx, double qy, double reach, Stamp st, java.util.function.IntConsumer visitor) {
        if (st.s.length != n) { st.s = new int[n]; st.id = 0; }
        if (++st.id == Integer.MAX_VALUE) { Arrays.fill(st.s, 0); st.id = 1; }
        int cx0 = (int) Math.floor((qx - reach) / cell), cx1 = (int) Math.floor((qx + reach) / cell);
        int cy0 = (int) Math.floor((qy - reach) / cell), cy1 = (int) Math.floor((qy + reach) / cell);
        for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) {
            int[] s = grid.get(key(cx, cy)); if (s == null) continue;
            for (int i : s) if (st.s[i] != st.id) { st.s[i] = st.id; visitor.accept(i); }
        }
    }

    /** Nearest segment to (qx, qy) within maxDist, or -1. */
    public int nearest(double qx, double qy, double maxDist, Stamp st) {
        final int[] best = {-1}; final double[] bd = {maxDist};
        near(qx, qy, maxDist, st, i -> { double dd = dist(i, qx, qy); if (dd < bd[0]) { bd[0] = dd; best[0] = i; } });
        return best[0];
    }
}
