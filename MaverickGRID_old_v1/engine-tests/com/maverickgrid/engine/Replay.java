package com.maverickgrid.engine;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * End-to-end replay: the full Engine (EKF with noisy 1 Hz GNSS, automatic outage detection, AI dead reckoning, map layer)
 * on every simulated outage of the held-out drives. Nothing starts from the truth: the engine only sees IMU + noisy fixes.
 *   java Replay <engine phone|edge> <export dir> <map.mgr> <drives comma> [threads]
 */
public final class Replay {
    public static void main(String[] a) throws Exception {
        String eng = a[0]; Path root = Paths.get(a[1]); String mapFile = a[2]; String[] drives = a[3].split(","); int threads = a.length > 4 ? Integer.parseInt(a[4]) : 2;
        List<double[]> rows = Collections.synchronizedList(new ArrayList<>()); long t0 = System.nanoTime();
        for (String dname : drives) {
            Vectors v = new Vectors(root.resolve(eng).resolve("vectors_" + dname)); File mdir = root.resolve(eng).resolve("parity_" + dname).toFile();
            double[] X = v.get("tr_x"), Y = v.get("tr_y"), H = v.get("tr_heading"), S = v.get("tr_speed"), LAT = v.get("tr_lat"), LON = v.get("tr_lon");
            int n = v.rows("acc");
            double[] la = new double[n], lo = new double[n]; int m = 0;
            for (int i = 0; i < n; i++) if (!Double.isNaN(LAT[i]) && !Double.isNaN(X[i])) { la[m] = LAT[i] - Math.toDegrees(Y[i] / Geo.R_EARTH); m++; }
            double lat0 = median(Arrays.copyOf(la, m)); double kk = Math.cos(Math.toRadians(lat0)) * Geo.R_EARTH; m = 0;
            for (int i = 0; i < n; i++) if (!Double.isNaN(LON[i]) && !Double.isNaN(X[i])) { lo[m] = LON[i] - Math.toDegrees(X[i] / kk); m++; }
            Geo geo = new Geo(lat0, median(Arrays.copyOf(lo, m)));
            double mnx = 1e18, mxx = -1e18, mny = 1e18, mxy = -1e18; for (int i = 0; i < n; i++) if (!Double.isNaN(X[i])) { mnx = Math.min(mnx, X[i]); mxx = Math.max(mxx, X[i]); mny = Math.min(mny, Y[i]); mxy = Math.max(mxy, Y[i]); }
            Roads roads = mapFile.equals("none") ? null : new Roads(new FileInputStream(mapFile), geo, new double[]{mnx - 3000, mxx + 3000, mny - 3000, mxy + 3000}, false, 25.0);
            Model model = Model.fromFolder(mdir);
            double[] acc = v.get("acc"), grav = v.get("grav"), gyr = v.get("gyr"), K0 = v.get("k0"), LL = v.get("L");
            ExecutorService ex = Executors.newFixedThreadPool(threads); List<Future<?>> fs = new ArrayList<>();
            for (int o = 0; o < K0.length; o++) {
                final int k0 = (int) K0[o], L = (int) LL[o], oo = o;
                fs.add(ex.submit(() -> {
                    Engine.Config c = new Engine.Config(); c.yawAxis = model.yawAxis; c.yawSign = model.headingSign; c.stopLearnedGyroBias = eng.equals("phone");
                    Engine e = new Engine(model, c); e.setFrame(geo); e.setRoads(roads); e.setSeed(oo * 7919L + 1);
                    Random rnd = new Random(1000 + oo); int ks = Math.max(0, k0 - 9000); boolean enteredDR = false; long detect = -1;
                    for (int i = ks; i < k0 + L; i++) {
                        e.imu(new double[]{acc[3 * i], acc[3 * i + 1], acc[3 * i + 2]}, new double[]{grav[3 * i], grav[3 * i + 1], grav[3 * i + 2]}, new double[]{gyr[3 * i], gyr[3 * i + 1], gyr[3 * i + 2]});
                        if (e.mode() == Engine.Mode.DEAD_RECKONING && detect < 0) detect = i;
                        boolean inOutage = i >= k0 && i < k0 + L;
                        if (i % 10 == 0 && !inOutage && !Double.isNaN(X[i])) {
                            double sp = Math.max(0, S[i] + rnd.nextGaussian() * 0.2); double br = sp > 2 ? Math.toDegrees(H[i]) + rnd.nextGaussian() * 2.0 : Double.NaN;
                            double fx = X[i] + rnd.nextGaussian() * 3.0, fy = Y[i] + rnd.nextGaussian() * 3.0;
                            e.gnss(geo.lat(fy), geo.lon(fx), sp, br, 5.0);
                        }
                    }
                    int end = k0 + L - 1; double dist = 0; for (int i = k0; i < end; i++) dist += Math.hypot(X[i + 1] - X[i], Y[i + 1] - Y[i]);
                    Engine.State st = e.state(); double err = Math.hypot(st.x - X[end], st.y - Y[end]);
                    rows.add(new double[]{Math.round(L * Core.DT), 100 * err / dist, err, (detect - k0) * Core.DT, e.mode() == Engine.Mode.DEAD_RECKONING ? 1 : 0});
                }));
            }
            for (Future<?> f : fs) f.get(); ex.shutdown();
            System.out.printf("%s done (%d outages) [%.0f s]%n", dname, K0.length, (System.nanoTime() - t0) / 1e9);
        }
        for (int T : new int[]{30, 60, 120}) {
            double[] d = rows.stream().filter(r -> r[0] == T).mapToDouble(r -> r[1]).sorted().toArray(); int u = 0; for (double x : d) if (x < 10) u++;
            double det = rows.stream().filter(r -> r[0] == T).mapToDouble(r -> r[3]).average().orElse(0); long dr = rows.stream().filter(r -> r[0] == T && r[4] == 1).count();
            System.out.printf("T=%3d n=%d  drift median %.2f %%  p90 %.2f %%  under10 %.1f %%  | GNSS loss detected after %.2f s on average, DR active at end %d/%d%n", T, d.length, q(d, .5), q(d, .9), 100.0 * u / d.length, det, dr, d.length);
        }
    }
    static double median(double[] a) { double[] s = a.clone(); Arrays.sort(s); int n = s.length; return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]); }
    static double q(double[] s, double p) { double pos = p * (s.length - 1); int lo = (int) pos; int hi = Math.min(lo + 1, s.length - 1); return s[lo] + (s[hi] - s[lo]) * (pos - lo); }
}
