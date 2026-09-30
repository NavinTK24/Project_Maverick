package com.maverickgrid.engine;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Checks the Java engine core against Python outputs: features, stop detector, speed and yaw on every outage. */
public final class ParityTest {
    public static void main(String[] args) throws Exception {
        String engine = args[0]; Path root = Paths.get(args[1]); String drive = args[2];
        Path mdir = root.resolve(engine).resolve("parity_" + drive); Vectors v = new Vectors(root.resolve(engine).resolve("vectors_" + drive));
        String meta = new String(Files.readAllBytes(mdir.resolve("meta.json")));
        double thr = num(meta, "stop_threshold"), hs = num(meta, "heading_sign"); boolean phys = meta.contains("\"physics_input\": true");
        int[] key = ints(meta, "key_energy");
        Gbdt mean = new Gbdt(new FileReader(mdir.resolve("speed_mean.txt").toFile())), stop = new Gbdt(new FileReader(mdir.resolve("stop.txt").toFile()));
        int n = v.rows("acc"), nr = v.rows("features");
        // 1. features, streamed sample by sample
        ImuFeatures fx = new ImuFeatures(); float[][] F = new float[nr][]; double maxRel = 0; long diff = 0, tot = 0;
        for (int i = 0; i < n; i++) {
            fx.push(v.row("acc", i), v.row("grav", i), v.row("gyr", i));
            if (i % Core.STRIDE == 0 && i / Core.STRIDE < nr) {
                int r = i / Core.STRIDE; F[r] = fx.features(); double[] p = v.row("features", r);
                for (int j = 0; j < ImuFeatures.N; j++) { tot++; if ((float) p[j] != F[r][j]) { diff++; maxRel = Math.max(maxRel, Math.abs(p[j] - F[r][j]) / Math.max(1e-9, Math.abs(p[j]))); } }
            }
        }
        System.out.printf("features: %d rows x %d, %d of %d values differ (%.4f %%), max relative diff %.2e%n", nr, ImuFeatures.N, diff, tot, 100.0 * diff / tot, maxRel);
        // 2. stop detector
        boolean[] st = new boolean[nr]; int sd = 0;
        for (int r = 0; r < nr; r++) { st[r] = stop.predict(toD(F[r])) >= thr; if (st[r] != (v.get("stop_rows")[r] > 0.5)) sd++; }
        System.out.printf("stop detector: %d of %d rows differ%n", sd, nr);
        // 3. speed + yaw per outage
        double[] gps = v.get("gps_speed"), gyr = v.get("gyr"), K0 = v.get("k0"), LL = v.get("L"), spPy = v.get("speed"), yawPy = v.get("yaw");
        double[] raw = new double[n]; for (int i = 0; i < n; i++) raw[i] = hs * gyr[i * 3 + 1];
        double[] cum = null; if (phys) { double[] ax = v.get("ax"); cum = new double[n + 1]; for (int i = 0; i < n; i++) cum[i + 1] = cum[i] + ax[i]; for (int i = 0; i <= n; i++) cum[i] *= Core.DT; }
        int off = 0, yoff = 0; double maxSp = 0, maxYaw = 0; int spBad = 0;
        for (int o = 0; o < K0.length; o++) {
            int k0 = (int) K0[o], L = (int) LL[o], nsec = (int) Math.ceil(L * Core.DT); float[] ctx = Core.context(gps, k0);
            double[] sp = new double[L];
            for (int j = 0; j < nsec; j++) {
                int r = Math.min(k0 / Core.STRIDE + j, nr - 1);
                Float ph = phys ? (float) (cum[Math.min(k0 + j * 10, n - 1)] - cum[k0]) : null;
                double s = st[r] ? 0.0 : Math.max(ctx[0] + mean.predict(Core.residualRow(F[r], F[k0 / Core.STRIDE], key, ctx, (float) j, ph)), 0);
                for (int q = j * 10; q < Math.min(L, j * 10 + 10); q++) sp[q] = s;
            }
            double e = 0; for (int q = 0; q < L; q++) e = Math.max(e, Math.abs(sp[q] - spPy[off + q])); maxSp = Math.max(maxSp, e); if (e > 1e-6) spBad++;
            if (!phys) {
                double b = Core.gyroBiasFromStops(Arrays.copyOf(raw, k0 + 1), st, k0, 3);
                for (int q = 0; q < L; q++) maxYaw = Math.max(maxYaw, Math.abs(raw[k0 + q] + b - yawPy[yoff + q]));
                yoff += L;
            }
            off += L;
        }
        System.out.printf("speed: %d outages, %d differ by > 1e-6 m/s, max diff %.2e m/s%n", K0.length, spBad, maxSp);
        if (!phys) System.out.printf("yaw (stop-learned bias): max diff %.2e rad/s%n", maxYaw);
    }
    static double[] toD(float[] f) { double[] d = new double[f.length]; for (int i = 0; i < f.length; i++) d[i] = f[i]; return d; }
    static double num(String json, String k) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + k + "\":\\s*([-0-9.eE+]+)").matcher(json); m.find(); return Double.parseDouble(m.group(1)); }
    static int[] ints(String json, String k) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + k + "\":\\s*\\[([^\\]]*)\\]").matcher(json); m.find(); String[] p = m.group(1).replaceAll("\\s", "").split(","); int[] a = new int[p.length]; for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i]); return a; }
}
