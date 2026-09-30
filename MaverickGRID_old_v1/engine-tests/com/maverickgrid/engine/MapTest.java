package com.maverickgrid.engine;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Runs the Java map layer on the saved outage tracks and prints drift statistics (compare with idr.map_eval). */
public final class MapTest {
    public static void main(String[] args) throws Exception {
        Vectors v = new Vectors(Paths.get(args[0])); String mapFile = args[1];
        double[] drive = v.get("drive"), K0 = v.get("k0"), TT = v.get("T"), LL = v.get("L"), sp = v.get("speed"), yw = v.get("yaw");
        Map<Integer, Roads> roads = new HashMap<>(); long tLoad = 0, tRun = 0; long steps = 0;
        Map<Integer, List<double[]>> res = new TreeMap<>(); int off = 0;
        for (int o = 0; o < K0.length; o++) {
            int d = (int) drive[o], k0 = (int) K0[o], L = (int) LL[o], T = (int) TT[o];
            double[] X = v.get("d" + d + "_x"), Y = v.get("d" + d + "_y"), H = v.get("d" + d + "_heading"), fr = v.get("d" + d + "_frame");
            if (!roads.containsKey(d)) {
                long t0 = System.nanoTime(); double mnx = 1e18, mxx = -1e18, mny = 1e18, mxy = -1e18;
                for (int i = 0; i < X.length; i++) if (!Double.isNaN(X[i])) { mnx = Math.min(mnx, X[i]); mxx = Math.max(mxx, X[i]); mny = Math.min(mny, Y[i]); mxy = Math.max(mxy, Y[i]); }
                roads.put(d, new Roads(new FileInputStream(mapFile), new Geo(fr[0], fr[1]), new double[]{mnx - 3000, mxx + 3000, mny - 3000, mxy + 3000}, false, 25.0));
                tLoad += System.nanoTime() - t0;
            }
            MapFilter mf = new MapFilter(roads.get(d), new MapFilter.Params());
            long t0 = System.nanoTime(); mf.start(X[k0], Y[k0], H[k0], o);
            for (int i = 0; i < L - 1; i++) { mf.step(sp[off + i], yw[off + i]); steps++; }
            tRun += System.nanoTime() - t0;
            double dist = 0; for (int i = k0; i < k0 + L - 1; i++) dist += Math.hypot(X[i + 1] - X[i], Y[i + 1] - Y[i]);
            double ex = X[k0 + L - 1], ey = Y[k0 + L - 1];
            res.computeIfAbsent(T, k -> new ArrayList<>()).add(new double[]{100 * Math.hypot(mf.drX() - ex, mf.drY() - ey) / dist,
                    100 * Math.hypot(mf.pfX() - ex, mf.pfY() - ey) / dist, 100 * Math.hypot(mf.x() - ex, mf.y() - ey) / dist});
            off += L;
        }
        String[] names = {"DR, no map", "DR + map (particle filter)", "MaverickGRID fused"};
        for (int m = 0; m < 3; m++) for (Map.Entry<Integer, List<double[]>> e : res.entrySet()) {
            double[] a = e.getValue().stream().mapToDouble(r -> r[0]).toArray(); final int mm = m; a = e.getValue().stream().mapToDouble(r -> r[mm]).toArray(); Arrays.sort(a);
            int u10 = 0; for (double x : a) if (x < 10) u10++;
            System.out.printf("%-28s T=%3d n=%d median %.2f %%  p90 %.2f %%  under10 %.1f %%%n", names[m], e.getKey(), a.length, q(a, .5), q(a, .9), 100.0 * u10 / a.length);
        }
        System.out.printf("map load %.1f s total; filter %.1f us per 0.1 s step (400 particles)%n", tLoad / 1e9, tRun / 1e3 / steps);
    }
    static double q(double[] s, double p) { double pos = p * (s.length - 1); int lo = (int) pos; int hi = Math.min(lo + 1, s.length - 1); return s[lo] + (s[hi] - s[lo]) * (pos - lo); }
}
