package com.maverickgrid.engine;

import java.util.*;

/**
 * Offline car routing on the road network (A*, travel-time cost by road class, one-way aware), plus turn instructions.
 * Build once per Roads (≈0.2 s for 100k segments); each route takes a few to tens of milliseconds.
 */
public final class Router {
    /** Typical speeds (m/s) by road class 0 motorway .. 6 service. */
    private static final double[] SPEED = {25, 20, 15, 13, 11, 8, 5};
    private final Roads r; private final int nNodes; private final double[] nx, ny;
    private final int[] start, adjTo, adjSeg; private final Roads.Stamp stamp = new Roads.Stamp();

    public static final class Maneuver {
        public final int pointIndex; public final double atM; public final String text; public final int turn;   // turn: -3..3 (left sharp..right sharp), 0 straight, 9 arrive
        Maneuver(int pi, double at, String t, int turn) { pointIndex = pi; atM = at; text = t; this.turn = turn; }
    }

    public static final class Route {
        public final double[] xs, ys, cum; public final double lengthM, timeS; public final List<Maneuver> maneuvers;
        Route(double[] xs, double[] ys, double timeS, List<Maneuver> m) {
            this.xs = xs; this.ys = ys; cum = new double[xs.length];
            for (int i = 1; i < xs.length; i++) cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
            lengthM = cum[xs.length - 1]; this.timeS = timeS; maneuvers = m;
        }
    }

    public Router(Roads roads) {
        r = roads; HashMap<Long, Integer> id = new HashMap<>(roads.n * 2);
        int[] su = new int[roads.n], sv = new int[roads.n];
        for (int i = 0; i < roads.n; i++) {
            Integer a = id.get(roads.u[i]); if (a == null) { a = id.size(); id.put(roads.u[i], a); } su[i] = a;
            Integer b = id.get(roads.v[i]); if (b == null) { b = id.size(); id.put(roads.v[i], b); } sv[i] = b;
        }
        nNodes = id.size(); nx = new double[nNodes]; ny = new double[nNodes]; int[] deg = new int[nNodes + 1];
        for (int i = 0; i < roads.n; i++) { nx[su[i]] = roads.ax[i]; ny[su[i]] = roads.ay[i]; nx[sv[i]] = roads.bx[i]; ny[sv[i]] = roads.by[i]; deg[su[i]]++; if (!roads.oneway[i]) deg[sv[i]]++; }
        start = new int[nNodes + 1]; for (int i = 0; i < nNodes; i++) start[i + 1] = start[i] + deg[i];
        adjTo = new int[start[nNodes]]; adjSeg = new int[start[nNodes]]; int[] fill = Arrays.copyOf(start, nNodes);
        for (int i = 0; i < roads.n; i++) {
            adjTo[fill[su[i]]] = sv[i]; adjSeg[fill[su[i]]++] = i;
            if (!roads.oneway[i]) { adjTo[fill[sv[i]]] = su[i]; adjSeg[fill[sv[i]]++] = i; }
        }
        segU = su; segV = sv; udeg = new int[nNodes]; for (int i = 0; i < roads.n; i++) { udeg[su[i]]++; udeg[sv[i]]++; }
        // largest connected piece of the network (undirected); isolated fragments are never used as start/target
        int[] par = new int[nNodes]; for (int i = 0; i < nNodes; i++) par[i] = i;
        for (int i = 0; i < roads.n; i++) { int a = find(par, su[i]), b = find(par, sv[i]); if (a != b) par[a] = b; }
        int[] size = new int[nNodes]; int bestRoot = 0; for (int i = 0; i < nNodes; i++) { int rr = find(par, i); if (++size[rr] > size[bestRoot]) bestRoot = rr; }
        mainSeg = new boolean[roads.n]; for (int i = 0; i < roads.n; i++) mainSeg[i] = find(par, su[i]) == bestRoot;
    }
    private final int[] segU, segV, udeg; private final boolean[] mainSeg;
    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }

    /** Up to k nearest main-network segments within maxDist of (x, y), nearest first. */
    private int[] candidates(double x, double y, double maxDist, int k) {
        final ArrayList<double[]> c = new ArrayList<>();
        r.near(x, y, maxDist, stamp, i -> { if (!mainSeg[i]) return; double d = r.dist(i, x, y); if (d <= maxDist) c.add(new double[]{d, i}); });
        c.sort(Comparator.comparingDouble(a -> a[0])); int n = Math.min(k, c.size()); int[] out = new int[n]; for (int i = 0; i < n; i++) out[i] = (int) c.get(i)[1]; return out;
    }

    /** Why the last route() returned null. */
    public volatile String lastError = "";

    private double cost(int seg, double frac) { return frac * r.len[seg] / SPEED[Math.min(6, Math.max(0, r.roadClass[seg]))]; }

    /** Fastest route from (sx, sy) to (tx, ty) in local metres, or null if either end is far from a road or unreachable. */
    public synchronized Route route(double sx, double sy, double tx, double ty) {
        int[] sc = candidates(sx, sy, 300, 3), tc = candidates(tx, ty, 1000, 4);
        if (sc.length == 0) { lastError = "your position is more than 300 m from the connected road network"; return null; }
        if (tc.length == 0) { lastError = "the destination is more than 1 km from a mapped road"; return null; }
        for (int s0 : sc) for (int t0 : tc) { Route rt = routeBetween(s0, t0, sx, sy, tx, ty); if (rt != null) { lastError = ""; return rt; } }
        lastError = "no drivable connection found (one-way streets or map gap)"; return null;
    }

    private Route routeBetween(int s0, int t0, double sx, double sy, double tx, double ty) {
        double fs = r.along(s0, sx, sy), ft = r.along(t0, tx, ty);
        double[] g = new double[nNodes]; Arrays.fill(g, Double.POSITIVE_INFINITY); int[] prevNode = new int[nNodes], prevSeg = new int[nNodes];
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        double h = 1.0 / SPEED[0];
        // leave the start segment forwards (to V) and, if two-way, backwards (to U)
        relax(g, prevNode, prevSeg, pq, segV[s0], cost(s0, 1 - fs), -1, s0, tx, ty, h);
        if (!r.oneway[s0]) relax(g, prevNode, prevSeg, pq, segU[s0], cost(s0, fs), -1, s0, tx, ty, h);
        double best = Double.POSITIVE_INFINITY; int bestNode = -2; boolean direct = false;
        if (s0 == t0 && (ft >= fs || !r.oneway[s0])) { best = cost(s0, Math.abs(ft - fs)); direct = true; }
        double tU = cost(t0, ft), tV = r.oneway[t0] ? Double.POSITIVE_INFINITY : cost(t0, 1 - ft);
        while (!pq.isEmpty()) {
            double[] top = pq.poll(); int u = (int) top[1]; double gu = top[2];
            if (gu > g[u]) continue;
            if (gu >= best) break;
            if (u == segU[t0] && gu + tU < best) { best = gu + tU; bestNode = u; direct = false; }
            if (u == segV[t0] && gu + tV < best) { best = gu + tV; bestNode = u; direct = false; }
            for (int e = start[u]; e < start[u + 1]; e++) relax(g, prevNode, prevSeg, pq, adjTo[e], gu + cost(adjSeg[e], 1), u, adjSeg[e], tx, ty, h);
        }
        if (Double.isInfinite(best)) return null;
        ArrayList<double[]> pts = new ArrayList<>();
        pts.add(new double[]{r.ax[s0] + (r.bx[s0] - r.ax[s0]) * fs, r.ay[s0] + (r.by[s0] - r.ay[s0]) * fs});
        ArrayList<Integer> segSeq = new ArrayList<>(); ArrayList<Integer> nodeSeq = new ArrayList<>(); nodeSeq.add(-1);
        if (!direct) {
            ArrayDeque<Integer> nodes = new ArrayDeque<>(); int c = bestNode;
            while (c >= 0) { nodes.addFirst(c); segSeq.add(0, prevSeg[c]); c = prevNode[c]; }
            for (int nd : nodes) { pts.add(new double[]{nx[nd], ny[nd]}); nodeSeq.add(nd); }
        } else segSeq.add(s0);
        segSeq.add(t0);
        pts.add(new double[]{r.ax[t0] + (r.bx[t0] - r.ax[t0]) * ft, r.ay[t0] + (r.by[t0] - r.ay[t0]) * ft}); nodeSeq.add(-1);
        double[] xs = new double[pts.size()], ys = new double[pts.size()]; for (int i = 0; i < xs.length; i++) { xs[i] = pts.get(i)[0]; ys[i] = pts.get(i)[1]; }
        // road name for each polyline point (name of the road leaving that point)
        String[] roadAt = new String[xs.length];
        for (int i = 0; i < xs.length; i++) { int si = Math.min(i, segSeq.size() - 1); roadAt[i] = r.name(segSeq.get(si)); }
        Route rt = new Route(xs, ys, best, new ArrayList<>());
        boolean[] junction = new boolean[xs.length]; for (int i = 0; i < xs.length; i++) { int nd = nodeSeq.get(i); junction[i] = nd >= 0 && udeg[nd] >= 3; }
        rt.maneuvers.addAll(instructions(rt, roadAt, junction));
        return rt;
    }

    private void relax(double[] g, int[] pn, int[] ps, PriorityQueue<double[]> pq, int v, double gv, int from, int seg, double tx, double ty, double h) {
        if (gv < g[v]) { g[v] = gv; pn[v] = from; ps[v] = seg; pq.add(new double[]{gv + Math.hypot(nx[v] - tx, ny[v] - ty) * h, v, gv}); }
    }

    /** Turn instructions from the route geometry: bearing change measured 20 m before and after each vertex. */
    static List<Maneuver> instructions(Route rt, String[] roadAt, boolean[] junction) {
        List<Maneuver> cand = new ArrayList<>(); int n = rt.xs.length;
        for (int i = 1; i < n - 1; i++) {
            double at = rt.cum[i]; if (rt.lengthM - at < 10) continue;
            double[] a = pointAt(rt, at - 20), b = pointAt(rt, at + 20);
            double bin = Math.atan2(rt.xs[i] - a[0], rt.ys[i] - a[1]), bout = Math.atan2(b[0] - rt.xs[i], b[1] - rt.ys[i]);
            double d = Math.toDegrees(Math.IEEEremainder(bout - bin, 2 * Math.PI)), ad = Math.abs(d);
            String onto = roadAt[i] != null ? " onto " + roadAt[i] : "";
            boolean nameChange = roadAt[i] != null && !roadAt[i].equals(roadAt[i - 1]) && roadAt[i - 1] != null;
            if (!junction[i] && !nameChange) continue;                 // a bend in the same road is not an instruction
            if (ad < 30 && !(nameChange && ad > 15)) continue;
            // keep the vertex with the largest turn within the next 20 m
            int turn; String verb;
            if (ad < 30) { turn = 0; verb = "Continue"; }
            else if (ad < 60) { turn = d > 0 ? 1 : -1; verb = d > 0 ? "Keep right" : "Keep left"; }
            else if (ad < 135) { turn = d > 0 ? 2 : -2; verb = d > 0 ? "Turn right" : "Turn left"; }
            else { turn = d > 0 ? 3 : -3; verb = d > 0 ? "Sharp right" : "Sharp left"; }
            cand.add(new Maneuver(i, at, verb + onto, turn));
        }
        // within 25 m keep only the strongest turn (staggered junctions, curved junction geometry)
        List<Maneuver> out = new ArrayList<>();
        String first = roadAt.length > 0 && roadAt[0] != null ? " on " + roadAt[0] : "";
        double b0 = n > 1 ? Math.toDegrees(Math.atan2(rt.xs[Math.min(n - 1, 1)] - rt.xs[0], rt.ys[Math.min(n - 1, 1)] - rt.ys[0])) : 0;
        String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        out.add(new Maneuver(0, 0, "Head " + dirs[(int) Math.round(((b0 % 360) + 360) % 360 / 45.0) % 8] + first, 0));
        for (int a = 0; a < cand.size(); ) {
            int b = a, best = a;
            while (b < cand.size() && cand.get(b).atM - cand.get(a).atM < 25) { if (Math.abs(cand.get(b).turn) > Math.abs(cand.get(best).turn)) best = b; b++; }
            out.add(cand.get(best)); a = b;
        }
        out.add(new Maneuver(n - 1, rt.lengthM, "Arrive at destination", 9));
        return out;
    }

    static double[] pointAt(Route rt, double at) {
        at = Math.max(0, Math.min(rt.lengthM, at)); int i = Arrays.binarySearch(rt.cum, at); if (i >= 0) return new double[]{rt.xs[i], rt.ys[i]};
        i = -i - 1; if (i <= 0) return new double[]{rt.xs[0], rt.ys[0]};
        double f = (at - rt.cum[i - 1]) / Math.max(1e-9, rt.cum[i] - rt.cum[i - 1]);
        return new double[]{rt.xs[i - 1] + (rt.xs[i] - rt.xs[i - 1]) * f, rt.ys[i - 1] + (rt.ys[i] - rt.ys[i - 1]) * f};
    }
}
