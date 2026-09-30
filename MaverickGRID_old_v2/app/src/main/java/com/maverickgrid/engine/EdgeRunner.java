package com.maverickgrid.engine;

import java.io.*;
import java.util.*;

/**
 * MaverickGRID edge engine - command-line runner for an external IMU (any rate, e.g. 200 Hz FOG) with optional GNSS.
 *
 *   java -jar maverick-edge.jar --model models/edge --imu imu.csv [--gnss gnss.csv] [--map roads.mgr] --out track.csv
 *
 * imu.csv : t_s, ax_mps2 (forward), ay_mps2 (left), yaw_rate_rps   (header line required; any constant or variable rate)
 * gnss.csv: t_s, lat, lon, speed_mps, bearing_deg, accuracy_m      (rows simply absent while GNSS is lost)
 * track.csv: one row per IMU sample: t_s, lat, lon, heading_deg, speed_mps, mode
 *
 * The AI models run at 10 Hz on 100 ms averages of the IMU (anti-aliased); between model ticks the position is propagated
 * at the full IMU rate with the high-rate yaw, so the output rate equals the IMU rate.
 * --yaw-sign: +1 if yaw_rate is positive when turning right (clockwise); default -1 (counter-clockwise positive, as in IO-VNBD).
 */
public final class EdgeRunner {
    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>(); for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        if (!a.containsKey("--model") || !a.containsKey("--imu") || !a.containsKey("--out")) { System.err.println("usage: --model DIR --imu imu.csv [--gnss gnss.csv] [--map roads.mgr] --out track.csv [--yaw-sign -1|1]"); System.exit(2); }
        Model model = Model.fromFolder(new File(a.get("--model")));
        double yawSign = Double.parseDouble(a.getOrDefault("--yaw-sign", "-1"));
        Engine.Config c = new Engine.Config(); c.yawAxis = 1; c.yawSign = yawSign; c.stopLearnedGyroBias = false;   // engine convention: clockwise positive after sign
        Engine e = new Engine(model, c); e.setSeed(1);
        double[][] imu = readCsv(a.get("--imu")); double[][] gn = a.containsKey("--gnss") ? readCsv(a.get("--gnss")) : new double[0][];
        String mapFile = a.get("--map"); boolean mapLoaded = false;
        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(a.get("--out"))))) {
            out.println("t_s,lat,lon,heading_deg,speed_mps,mode");
            int gi = 0; double binStart = imu.length > 0 ? imu[0][0] : 0; double sax = 0, say = 0, syaw = 0; int nb = 0; double lax = 0, lay = 0, lyaw = 0;
            double hx = 0, hy = 0, hpsi = 0, hv = 0; boolean haveState = false; double lastT = binStart; long rows = 0, ticks = 0; long t0 = System.nanoTime();
            for (double[] r : imu) {
                double t = r[0];
                // close every finished 100 ms bin -> one 10 Hz model tick each (an IMU gap repeats the last average, so engine time stays aligned)
                while (t >= binStart + 0.1 - 1e-9) {
                    if (nb > 0) { lax = sax / nb; lay = say / nb; lyaw = syaw / nb; }
                    sax = say = syaw = 0; nb = 0; binStart += 0.1;
                    while (gi < gn.length && gn[gi][0] <= binStart) { double[] g = gn[gi++]; e.gnss(g[1], g[2], g[3], g.length > 4 ? g[4] : Double.NaN, g.length > 5 ? g[5] : 5.0); }
                    if (!mapLoaded && mapFile != null && e.frame() != null) {
                        try (InputStream in = new FileInputStream(mapFile)) { e.setRoads(new Roads(in, e.frame(), new double[]{-30000, 30000, -30000, 30000}, false, 25.0)); }
                        mapLoaded = true;
                    }
                    e.imu(new double[]{lax, lay, 9.80665}, new double[]{0, 0, 9.80665}, new double[]{0, lyaw, 0}); ticks++;
                    Engine.State s = e.state(); hx = s.displayX; hy = s.displayY; hpsi = s.headingRad; hv = s.speed; haveState = s.mode != Engine.Mode.WAITING_FOR_GNSS; lastT = binStart;
                }
                sax += r[1]; say += r[2]; syaw += r[3]; nb++;
                if (haveState) {                                            // high-rate propagation from the last model tick to this sample
                    double dt = t - lastT; hx += hv * Math.sin(hpsi) * dt; hy += hv * Math.cos(hpsi) * dt; hpsi += c.yawSign * r[3] * dt;
                }
                lastT = t;
                if (haveState && e.frame() != null) {
                    out.printf(Locale.US, "%.4f,%.8f,%.8f,%.2f,%.3f,%s%n", t, e.frame().lat(hy), e.frame().lon(hx), ((Math.toDegrees(hpsi) % 360) + 360) % 360, hv, e.mode()); rows++;
                }
            }
            System.err.printf(Locale.US, "%d IMU samples, %d model ticks (10 Hz) -> %d output rows in %.2f s%n", imu.length, ticks, rows, (System.nanoTime() - t0) / 1e9);
        }
    }

    static double[][] readCsv(String path) throws IOException {
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue; String[] p = line.split(","); double[] v = new double[p.length];
                for (int i = 0; i < p.length; i++) { String q = p[i].trim(); v[i] = (q.isEmpty() || q.equalsIgnoreCase("nan")) ? Double.NaN : Double.parseDouble(q); }
                rows.add(v);
            }
        }
        return rows.toArray(new double[0][]);
    }
}
