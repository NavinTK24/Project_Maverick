package com.maverickgrid.engine;

import java.io.*;
import java.util.*;

/** Desktop run of exactly what the app's "Replay IO-VNBD" does (same assets, same outage schedule), to check it end to end. */
public final class AppReplaySim {
    public static void main(String[] a) throws Exception {
        File assets = new File(a[0]);
        Model model = Model.fromFolder(new File(assets, "models/car_heldout_S1")); ArrayStore rep = ArrayStore.fromFolder(new File(assets, "replay/S1"));
        double[] acc = rep.get("acc"), grav = rep.get("grav"), gyr = rep.get("gyr"), X = rep.get("tr_x"), Y = rep.get("tr_y"), H = rep.get("tr_heading"), S = rep.get("tr_speed");
        int n = rep.rows("acc"); Geo geo = ArrayStore.frameOf(rep.get("tr_lat"), rep.get("tr_lon"), X, Y);
        Engine.Config c = new Engine.Config(); c.yawAxis = model.yawAxis; c.yawSign = model.headingSign; c.stopLearnedGyroBias = true;
        Engine e = new Engine(model, c); e.setFrame(geo);
        long t0 = System.nanoTime(); Roads roads = new Roads(new FileInputStream(new File(assets, "maps/coventry.mgr")), geo, new double[]{-20000, 20000, -20000, 20000}, false, 25.0);
        System.out.printf("map: %d segments loaded in %.1f s%n", roads.n, (System.nanoTime() - t0) / 1e9); e.setRoads(roads);
        Random rnd = new Random(7); int WARM = 1800, EVERY = 1800, OUT = 600; double lossDist = 0; List<Double> drift = new ArrayList<>(); long tt = System.nanoTime(); double worstTick = 0;
        for (int i = 0; i < n; i++) {
            long ts = System.nanoTime();
            e.imu(new double[]{acc[3 * i], acc[3 * i + 1], acc[3 * i + 2]}, new double[]{grav[3 * i], grav[3 * i + 1], grav[3 * i + 2]}, new double[]{gyr[3 * i], gyr[3 * i + 1], gyr[3 * i + 2]});
            boolean outage = i >= WARM && ((i - WARM) % EVERY) < OUT; int phase = i >= WARM ? (i - WARM) % EVERY : -1;
            if (!Double.isNaN(X[i])) {
                if (phase == 0) lossDist = 0;
                if (outage && i > 0 && !Double.isNaN(X[i - 1])) lossDist += Math.hypot(X[i] - X[i - 1], Y[i] - Y[i - 1]);
                if (i % 10 == 0 && !outage) { double sp = Math.max(0, S[i] + rnd.nextGaussian() * 0.2), br = sp > 2 ? Math.toDegrees(H[i]) + rnd.nextGaussian() * 2 : Double.NaN;
                    e.gnss(geo.lat(Y[i] + rnd.nextGaussian() * 3), geo.lon(X[i] + rnd.nextGaussian() * 3), sp, br, 5.0); }
            }
            worstTick = Math.max(worstTick, (System.nanoTime() - ts) / 1e6);
            if (phase == OUT - 1 && lossDist > 50 && !Double.isNaN(X[i])) { Engine.State s = e.state(); drift.add(100 * Math.hypot(s.x - X[i], s.y - Y[i]) / lossDist); }
        }
        double[] d = drift.stream().mapToDouble(x -> x).sorted().toArray(); int u = 0; for (double x : d) if (x < 10) u++;
        System.out.printf("%d outages of 60 s: median drift %.2f %%, mean %.2f %%, under 10 %%: %d/%d; %.1f h of data replayed in %.0f s; slowest tick %.1f ms%n",
                d.length, d[d.length / 2], Arrays.stream(d).average().orElse(0), u, d.length, n * 0.1 / 3600, (System.nanoTime() - tt) / 1e9, worstTick);
    }
}
