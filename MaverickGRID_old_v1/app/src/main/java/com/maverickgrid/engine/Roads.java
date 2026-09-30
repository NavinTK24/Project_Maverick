package com.maverickgrid.engine;

import java.io.*;
import java.util.*;

/** Offline road network (roads.mgr from idr.osm_pack) in a local metric frame, with a uniform-grid spatial index. */
public final class Roads {
    public final Geo geo;
    public final double[] ax, ay, bx, by, len, dir; public final boolean[] oneway; public final long[] u, v;
    public final int n;
    private final double cell; private final HashMap<Long, int[]> grid = new HashMap<>();

    /** Loads segments whose bounding box meets [minX-margin, maxX+margin] x [minY-margin, maxY+margin] (local metres); null bbox = all. */
    public Roads(InputStream in, Geo geo, double[] bbox, boolean includeService, double cell) throws IOException {
        this.geo = geo; this.cell = cell;
        DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        byte[] magic = new byte[4]; d.readFully(magic); if (!new String(magic, "US-ASCII").equals("MGR1")) throw new IOException("not a MaverickGRID road file");
        int total = d.readInt();
        double[] AX = new double[total], AY = new double[total], BX = new double[total], BY = new double[total]; boolean[] OW = new boolean[total];
        long[] U = new long[total], V = new long[total]; int m = 0;
        for (int i = 0; i < total; i++) {
            double la1 = d.readInt() / 1e7, lo1 = d.readInt() / 1e7, la2 = d.readInt() / 1e7, lo2 = d.readInt() / 1e7; long n1 = d.readLong(), n2 = d.readLong(); byte fl = d.readByte();
            if (!includeService && (fl & 2) != 0) continue;
            double x1 = geo.x(lo1), y1 = geo.y(la1), x2 = geo.x(lo2), y2 = geo.y(la2);
            if (bbox != null && (Math.max(x1, x2) < bbox[0] || Math.min(x1, x2) > bbox[1] || Math.max(y1, y2) < bbox[2] || Math.min(y1, y2) > bbox[3])) continue;
            AX[m] = x1; AY[m] = y1; BX[m] = x2; BY[m] = y2; OW[m] = (fl & 1) != 0; U[m] = n1; V[m] = n2; m++;
        }
        n = m; ax = Arrays.copyOf(AX, m); ay = Arrays.copyOf(AY, m); bx = Arrays.copyOf(BX, m); by = Arrays.copyOf(BY, m); oneway = Arrays.copyOf(OW, m); u = Arrays.copyOf(U, m); v = Arrays.copyOf(V, m);
        len = new double[m]; dir = new double[m];
        HashMap<Long, ArrayList<Integer>> g = new HashMap<>();
        for (int i = 0; i < m; i++) {
            double dx = bx[i] - ax[i], dy = by[i] - ay[i]; len[i] = Math.max(Math.hypot(dx, dy), 1e-6); dir[i] = Math.atan2(dx, dy);
            int cx0 = (int) Math.floor(Math.min(ax[i], bx[i]) / cell), cx1 = (int) Math.floor(Math.max(ax[i], bx[i]) / cell);
            int cy0 = (int) Math.floor(Math.min(ay[i], by[i]) / cell), cy1 = (int) Math.floor(Math.max(ay[i], by[i]) / cell);
            for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) {
                // keep only cells the segment actually passes near
                double px = (cx + 0.5) * cell, py = (cy + 0.5) * cell;
                if (dist(i, px, py) > cell * 0.7072) continue;
                g.computeIfAbsent(key(cx, cy), kk -> new ArrayList<>()).add(i);
            }
        }
        for (Map.Entry<Long, ArrayList<Integer>> e : g.entrySet()) { int[] a = new int[e.getValue().size()]; for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i); grid.put(e.getKey(), a); }
    }

    private static long key(int cx, int cy) { return (((long) cx) << 32) ^ (cy & 0xffffffffL); }

    /** Distance from (px, py) to segment i. */
    public double dist(int i, double px, double py) {
        double dx = bx[i] - ax[i], dy = by[i] - ay[i]; double t = ((px - ax[i]) * dx + (py - ay[i]) * dy) / (len[i] * len[i]);
        t = Math.max(0, Math.min(1, t)); double qx = ax[i] + dx * t - px, qy = ay[i] + dy * t - py; return Math.hypot(qx, qy);
    }

    /** Calls visitor for every segment within `reach` metres of (px, py) (each segment once per call). */
    private int[] stamp = new int[0]; private int stampId = 0;
    public void near(double px, double py, double reach, java.util.function.IntConsumer visitor) {
        if (stamp.length != n) stamp = new int[n];
        stampId++; if (stampId == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); stampId = 1; }
        int cx0 = (int) Math.floor((px - reach) / cell), cx1 = (int) Math.floor((px + reach) / cell);
        int cy0 = (int) Math.floor((py - reach) / cell), cy1 = (int) Math.floor((py + reach) / cell);
        for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) {
            int[] s = grid.get(key(cx, cy)); if (s == null) continue;
            for (int i : s) if (stamp[i] != stampId) { stamp[i] = stampId; visitor.accept(i); }
        }
    }
}
