package com.maverickgrid.engine;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RouteTest {
    public static void main(String[] a) throws Exception {
        ArrayStore v = ArrayStore.fromFolder(new File("/tmp/idrpkg/export/phone/vectors_S1"));
        double[] X = v.get("tr_x"), Y = v.get("tr_y"); Geo geo = ArrayStore.frameOf(v.get("tr_lat"), v.get("tr_lon"), X, Y);
        long t0 = System.nanoTime(); Roads r = new Roads(new FileInputStream(a[0]), geo, new double[]{-20000, 20000, -20000, 20000}, false, 25.0);
        long t1 = System.nanoTime(); Router rt = new Router(r); long t2 = System.nanoTime(); PlaceSearch ps = new PlaceSearch(r);
        System.out.printf("roads %d, names %d, places %d; load %.2f s, router build %.2f s%n", r.n, r.names.length, r.np, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
        for (String q : new String[]{"foleshill", "london road", "earls", "a45", "xy"}) {
            long s = System.nanoTime(); List<PlaceSearch.Result> res = ps.search(q, X[0], Y[0], 5);
            System.out.printf("search '%s' (%.1f ms):", q, (System.nanoTime() - s) / 1e6); for (PlaceSearch.Result x : res) System.out.printf("  [%s %s %.1f km]", x.kind, x.name, x.distM / 1000); System.out.println();
        }
        // routes between points of the real drive: compare with the distance actually driven
        int n = X.length; Random rnd = new Random(1); double tot = 0; int ok = 0, cnt = 0; double worst = 0;
        for (int k = 0; k < 40; k++) {
            int i = rnd.nextInt(n - 3000), j = i + 600 + rnd.nextInt(2400); if (Double.isNaN(X[i]) || Double.isNaN(X[j])) continue;
            double driven = 0; for (int q = i; q < j; q++) if (!Double.isNaN(X[q + 1]) && !Double.isNaN(X[q])) driven += Math.hypot(X[q + 1] - X[q], Y[q + 1] - Y[q]);
            long s = System.nanoTime(); Router.Route R = rt.route(X[i], Y[i], X[j], Y[j]); double ms = (System.nanoTime() - s) / 1e6; worst = Math.max(worst, ms); cnt++;
            if (R == null) { System.out.println("no route " + i + "->" + j); continue; }
            ok++; tot += ms;
            if (k < 4) { System.out.printf("route %d->%d: %.0f m (driven %.0f m, straight %.0f m), %.1f min, %d instructions, %.1f ms%n", i, j, R.lengthM, driven, Math.hypot(X[j] - X[i], Y[j] - Y[i]), R.timeS / 60, R.maneuvers.size(), ms);
                for (int m = 0; m < Math.min(6, R.maneuvers.size()); m++) System.out.printf("    at %5.0f m: %s%n", R.maneuvers.get(m).atM, R.maneuvers.get(m).text); }
        }
        System.out.printf("%d/%d routes found, mean %.1f ms, worst %.1f ms%n", ok, cnt, tot / ok, worst);
        // navigator along a route using the real driven track
        Router.Route R = rt.route(X[5000], Y[5000], X[7000], Y[7000]); Navigator nav = new Navigator(R); int off = 0;
        for (int q = 5000; q <= 7000; q++) { if (Double.isNaN(X[q])) continue; nav.update(X[q], Y[q]); if (nav.offRoute) off++; }
        System.out.printf("navigator on driven track: final remaining %.0f m, arrived %b, off-route ticks %d%n", nav.remainingM, nav.arrived, off);
    }
}
