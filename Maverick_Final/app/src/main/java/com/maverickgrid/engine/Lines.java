package com.maverickgrid.engine;

import java.util.Arrays;

/**
 * A small set of line segments in the local metric frame with a grid index: the planned route while navigating, or the
 * roads this phone has already driven with GPS ("learned roads"). Directed segments only match particles heading the same
 * way (a route is driven in one direction); undirected ones match either way. Immutable after build().
 */
public final class Lines {
    private double[] ax = new double[64], ay = new double[64], bx = new double[64], by = new double[64], dir = new double[64];
    private boolean[] directed = new boolean[64]; private int n; private final double cell;
    private LongIntArrayMap grid; private boolean built;

    public Lines(double cell) { this.cell = cell; }

    public int size() { return n; }

    /** Adds a polyline (points closer than minStepM to the previous kept point are skipped). */
    public Lines addPolyline(double[] xs, double[] ys, int count, boolean isDirected, double minStepM) {
        if (built) throw new IllegalStateException("already built");
        int last = -1;
        for (int i = 0; i < count; i++) {
            if (Double.isNaN(xs[i]) || Double.isNaN(ys[i])) { last = -1; continue; }
            if (last < 0) { last = i; continue; }
            double d = Math.hypot(xs[i] - xs[last], ys[i] - ys[last]);
            if (d < minStepM && i < count - 1) continue;
            if (d > 200) { last = i; continue; }                  // a gap in the recording: do not bridge it
            if (d > 1e-3) add(xs[last], ys[last], xs[i], ys[i], isDirected);
            last = i;
        }
        return this;
    }

    public void add(double x1, double y1, double x2, double y2, boolean isDirected) {
        if (n == ax.length) { int c = n * 2; ax = Arrays.copyOf(ax, c); ay = Arrays.copyOf(ay, c); bx = Arrays.copyOf(bx, c); by = Arrays.copyOf(by, c); dir = Arrays.copyOf(dir, c); directed = Arrays.copyOf(directed, c); }
        ax[n] = x1; ay[n] = y1; bx[n] = x2; by[n] = y2; dir[n] = Math.atan2(x2 - x1, y2 - y1); directed[n] = isDirected; n++;
    }

    public Lines build() {
        java.util.ArrayList<long[]> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            int cx0 = (int) Math.floor(Math.min(ax[i], bx[i]) / cell), cx1 = (int) Math.floor(Math.max(ax[i], bx[i]) / cell);
            int cy0 = (int) Math.floor(Math.min(ay[i], by[i]) / cell), cy1 = (int) Math.floor(Math.max(ay[i], by[i]) / cell);
            for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) pairs.add(new long[]{Roads.key(cx, cy), i});
        }
        pairs.sort((p, q) -> Long.compare(p[0], q[0]));
        grid = new LongIntArrayMap(Math.max(16, pairs.size() / 2));
        for (int s = 0; s < pairs.size(); ) {
            int e = s; while (e < pairs.size() && pairs.get(e)[0] == pairs.get(s)[0]) e++;
            int[] a = new int[e - s]; for (int j = s; j < e; j++) a[j - s] = (int) pairs.get(j)[1]; grid.put(pairs.get(s)[0], a); s = e;
        }
        built = true; return this;
    }

    private double dist(int i, double qx, double qy) {
        double dx = bx[i] - ax[i], dy = by[i] - ay[i], L2 = dx * dx + dy * dy; double t = L2 > 0 ? ((qx - ax[i]) * dx + (qy - ay[i]) * dy) / L2 : 0;
        t = Math.max(0, Math.min(1, t)); return Math.hypot(ax[i] + dx * t - qx, ay[i] + dy * t - qy);
    }

    /** Smallest d²/σd² + dh²/σh² over segments within reach of (qx, qy) with heading psi; +inf if none. */
    public double cost(double qx, double qy, double psi, double reach, double is2, double ih2) {
        if (!built || n == 0) return Double.POSITIVE_INFINITY;
        double best = Double.POSITIVE_INFINITY;
        int cx0 = (int) Math.floor((qx - reach) / cell), cx1 = (int) Math.floor((qx + reach) / cell);
        int cy0 = (int) Math.floor((qy - reach) / cell), cy1 = (int) Math.floor((qy + reach) / cell);
        for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) {
            int[] segs = grid.get(Roads.key(cx, cy)); if (segs == null) continue;
            for (int s : segs) {
                double d = dist(s, qx, qy); if (d > reach) continue;
                double dh = Math.abs(Math.IEEEremainder(psi - dir[s], 2 * Math.PI));
                if (!directed[s]) dh = Math.min(dh, Math.PI - dh);
                double c = d * d * is2 + dh * dh * ih2; if (c < best) best = c;
            }
        }
        return best;
    }
}
