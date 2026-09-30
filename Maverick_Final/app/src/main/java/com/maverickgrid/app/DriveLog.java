package com.maverickgrid.app;

import com.maverickgrid.engine.Geo;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything needed for one drive's report (plain Java, no Android): the 10 Hz track of Maverick's navigation output and of the
 * dead-reckoning check, the "truth" samples (GNSS fixes on a real drive, the reference trajectory in the demo), the metrics
 * and the CSV / GeoJSON / GPX / JSON writers.
 *
 * Dead-reckoning check: during a drive a second engine runs in parallel and is denied GNSS for a test window at regular
 * intervals (the navigation the user sees keeps GNSS). Its position is compared with the GNSS fixes it did not get, which
 * gives honest dead-reckoning errors on every drive without disturbing navigation.
 */
final class DriveLog {
    static final int MIN_WINDOW_DIST_M = 100;          // test windows shorter than this (standing still) are not scored

    final Geo geo; final String title, vehicleName, vehicleType, startedAt; final boolean demo;
    // 10 Hz ticks
    int n; float[] t = new float[4096], fx = new float[4096], fy = new float[4096], fs = new float[4096], fh = new float[4096],
            dx = new float[4096], dy = new float[4096], ds = new float[4096], dh = new float[4096];
    byte[] fm = new byte[4096]; short[] win = new short[4096];   // fused mode 0 wait / 1 GNSS / 2 DR; test window id or -1
    // truth samples
    int m; float[] tt = new float[1024], tx = new float[1024], ty = new float[1024], tsp = new float[1024], tbr = new float[1024], tacc = new float[1024];

    DriveLog(Geo geo, String title, String vehicleName, String vehicleType, String startedAt, boolean demo) {
        this.geo = geo; this.title = title; this.vehicleName = vehicleName; this.vehicleType = vehicleType; this.startedAt = startedAt; this.demo = demo;
    }

    /** One 10 Hz tick. mode: 0 waiting, 1 GNSS, 2 dead reckoning. window: test-window id while the check engine is dead reckoning in a window, else -1. */
    void tick(double tS, int mode, double x, double y, double speed, double headRad, int window, double drX, double drY, double drSpeed, double drHead) {
        if (n == t.length) grow();
        t[n] = (float) tS; fm[n] = (byte) mode; fx[n] = (float) x; fy[n] = (float) y; fs[n] = (float) speed; fh[n] = (float) headRad;
        win[n] = (short) window; dx[n] = (float) drX; dy[n] = (float) drY; ds[n] = (float) drSpeed; dh[n] = (float) drHead; n++;
    }

    /** A truth sample (GNSS fix): time (s), position (m, map frame), speed (m/s, NaN unknown), bearing (deg, NaN unknown), accuracy (m). */
    void truth(double tS, double x, double y, double speed, double bearingDeg, double acc) {
        if (m == tt.length) growT();
        tt[m] = (float) tS; tx[m] = (float) x; ty[m] = (float) y; tsp[m] = (float) speed; tbr[m] = (float) bearingDeg; tacc[m] = (float) acc; m++;
    }

    private void grow() {
        int c = t.length * 2;
        t = java.util.Arrays.copyOf(t, c); fx = java.util.Arrays.copyOf(fx, c); fy = java.util.Arrays.copyOf(fy, c); fs = java.util.Arrays.copyOf(fs, c); fh = java.util.Arrays.copyOf(fh, c);
        dx = java.util.Arrays.copyOf(dx, c); dy = java.util.Arrays.copyOf(dy, c); ds = java.util.Arrays.copyOf(ds, c); dh = java.util.Arrays.copyOf(dh, c);
        fm = java.util.Arrays.copyOf(fm, c); win = java.util.Arrays.copyOf(win, c);
    }
    private void growT() {
        int c = tt.length * 2;
        tt = java.util.Arrays.copyOf(tt, c); tx = java.util.Arrays.copyOf(tx, c); ty = java.util.Arrays.copyOf(ty, c); tsp = java.util.Arrays.copyOf(tsp, c); tbr = java.util.Arrays.copyOf(tbr, c); tacc = java.util.Arrays.copyOf(tacc, c);
    }

    // ------------------------------------------------------------------ metrics
    static final class Window { int id; float t0, t1; double distM, finalErrM, maxErrM; int samples; boolean scored; float endX, endY, truthEndX, truthEndY; }

    static final class Metrics {
        double durationS, distanceKm, avgSpeedKmh, maxSpeedKmh;
        int windows, scoredWindows; double windowDistKm;
        double posMae = Double.NaN, posRmse = Double.NaN, posMax = Double.NaN, posR2 = Double.NaN;
        double driftMeanPct = Double.NaN, driftMedianPct = Double.NaN, driftMaxPct = Double.NaN, driftOverallPct = Double.NaN;
        double spdMae = Double.NaN, spdRmse = Double.NaN, spdR2 = Double.NaN, headMae = Double.NaN, speedScale = Double.NaN;
        double sumTruthMov, sumDrMov; int nMov;          // moving samples where dead reckoning did not think it stopped (speed learning)
        double sumTruthSpeed, sumDrSpeed;                 // for the vehicle's speed calibration estimate
        int realOutages; double realOutageKm, realOutageS;
        final List<Window> list = new ArrayList<>();
        // per-sample errors for plotting: time, error (m)
        float[] errT = new float[0], errE = new float[0];
    }

    /** Dead-reckoning position (and speed, heading) of the check engine interpolated at time tS; null if not inside window w there. */
    private double[] drAt(double tS, int w) {
        int i = lower(tS); if (i < 0) return null;
        if (win[i] == w && (t[i] == tS || i + 1 >= n)) return new double[]{dx[i], dy[i], ds[i], dh[i]};
        if (i + 1 >= n || win[i] != w || win[i + 1] != w) return null;
        double a = (tS - t[i]) / Math.max(1e-6, t[i + 1] - t[i]); a = Math.max(0, Math.min(1, a));
        return new double[]{dx[i] + a * (dx[i + 1] - dx[i]), dy[i] + a * (dy[i + 1] - dy[i]), ds[i], dh[i]};
    }
    private int windowAt(double tS) { int i = lower(tS); return i < 0 || i + 1 >= n ? -1 : win[i] == win[i + 1] ? win[i] : -1; }
    /** Last tick index with t <= tS, or -1. */
    private int lower(double tS) { int lo = 0, hi = n - 1, r = -1; while (lo <= hi) { int mid = (lo + hi) >>> 1; if (t[mid] <= tS) { r = mid; lo = mid + 1; } else hi = mid - 1; } return r; }

    /** Distance between truth samples k-1 and k: GNSS Doppler speed integrated over time when available (not inflated by
     *  position noise, which zig-zags a 1 Hz track), else the straight-line step; implausible jumps are ignored. */
    private double step(int k) {
        double dt = tt[k] - tt[k - 1]; if (!(dt > 0) || dt > 10) return 0;
        double d = Math.hypot(tx[k] - tx[k - 1], ty[k] - ty[k - 1]); if (d / dt > 70) return 0;
        if (!Float.isNaN(tsp[k]) && !Float.isNaN(tsp[k - 1])) return 0.5 * (tsp[k] + tsp[k - 1]) * dt;
        return d;
    }

    Metrics compute() {
        Metrics M = new Metrics();
        M.durationS = n > 1 ? t[n - 1] - t[0] : 0;
        double dist = 0; double maxSp = 0;
        for (int k = 1; k < m; k++) dist += step(k);
        for (int k = 0; k < m; k++) if (!Float.isNaN(tsp[k])) maxSp = Math.max(maxSp, tsp[k]);
        M.distanceKm = dist / 1000; M.avgSpeedKmh = M.durationS > 0 ? dist / M.durationS * 3.6 : 0; M.maxSpeedKmh = maxSp * 3.6;
        // real GNSS outages of the navigation (no truth there): count, time and distance
        for (int i = 1; i < n; i++) {
            if (fm[i] == 2 && fm[i - 1] != 2) M.realOutages++;
            if (fm[i] == 2) { M.realOutageS += t[i] - t[i - 1]; M.realOutageKm += Math.hypot(fx[i] - fx[i - 1], fy[i] - fy[i - 1]) / 1000; }
        }
        // test windows
        java.util.TreeMap<Integer, Window> ws = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) if (win[i] >= 0) { Window w = ws.get((int) win[i]); if (w == null) { w = new Window(); w.id = win[i]; w.t0 = t[i]; ws.put(w.id, w); } w.t1 = t[i]; w.endX = dx[i]; w.endY = dy[i]; }
        M.windows = ws.size();
        List<float[]> errs = new ArrayList<>();
        double sAbs = 0, sSq = 0, cnt = 0, maxE = 0;
        double ssRes = 0; List<double[]> disp = new ArrayList<>();
        double spAbs = 0, spSq = 0, spCnt = 0; List<double[]> spPairs = new ArrayList<>(); double hAbs = 0, hCnt = 0;
        List<Double> drifts = new ArrayList<>(); double sumFinal = 0, sumDist = 0;
        for (Window w : ws.values()) {
            // truth samples inside this window
            double wd = 0, px = Double.NaN, py = Double.NaN, x0t = 0, y0t = 0, x0d = 0, y0d = 0; boolean first = true; double lastErr = Double.NaN;
            List<double[]> wErr = new ArrayList<>(); List<double[]> wDisp = new ArrayList<>(); List<double[]> wSp = new ArrayList<>(); List<Double> wHead = new ArrayList<>();
            for (int k = 0; k < m; k++) {
                if (tt[k] < w.t0 || tt[k] > w.t1) continue;
                double[] d = drAt(tt[k], w.id); if (d == null) continue;
                if (!Double.isNaN(px)) wd += step(k); px = tx[k]; py = ty[k];
                double e = Math.hypot(d[0] - tx[k], d[1] - ty[k]); lastErr = e; w.truthEndX = tx[k]; w.truthEndY = ty[k];
                wErr.add(new double[]{tt[k], e});
                if (first) { x0t = tx[k]; y0t = ty[k]; x0d = d[0]; y0d = d[1]; first = false; }
                wDisp.add(new double[]{tx[k] - x0t, ty[k] - y0t, d[0] - x0d, d[1] - y0d});
                if (!Float.isNaN(tsp[k])) wSp.add(new double[]{tsp[k], d[2]});
                if (!Float.isNaN(tbr[k]) && !Float.isNaN(tsp[k]) && tsp[k] > 3) wHead.add(Math.abs(Math.IEEEremainder(Math.toDegrees(d[3]) - tbr[k], 360)));
            }
            w.distM = wd; w.samples = wErr.size(); w.finalErrM = lastErr;
            for (double[] e : wErr) w.maxErrM = Math.max(w.maxErrM, e[1]);
            w.scored = wd >= MIN_WINDOW_DIST_M && !Double.isNaN(lastErr);
            M.list.add(w);
            if (!w.scored) continue;
            M.scoredWindows++; M.windowDistKm += wd / 1000;
            for (double[] e : wErr) { sAbs += e[1]; sSq += e[1] * e[1]; cnt++; maxE = Math.max(maxE, e[1]); errs.add(new float[]{(float) e[0], (float) e[1]}); }
            disp.addAll(wDisp);
            for (double[] p : wSp) { double r = p[1] - p[0]; spAbs += Math.abs(r); spSq += r * r; spCnt++; spPairs.add(p); M.sumTruthSpeed += p[0] > 3 ? p[0] : 0; M.sumDrSpeed += p[0] > 3 ? p[1] : 0; if (p[0] > 3 && p[1] > 0.5) { M.sumTruthMov += p[0]; M.sumDrMov += p[1]; M.nMov++; } }
            for (double h : wHead) { hAbs += h; hCnt++; }
            drifts.add(100 * lastErr / wd); sumFinal += lastErr; sumDist += wd;
        }
        if (cnt > 0) { M.posMae = sAbs / cnt; M.posRmse = Math.sqrt(sSq / cnt); M.posMax = maxE; }
        // R² of the displacement since GNSS was lost (east and north together): 1 = dead reckoning follows the true path exactly
        if (disp.size() > 2) {
            double mx = 0, my = 0; for (double[] p : disp) { mx += p[0]; my += p[1]; } mx /= disp.size(); my /= disp.size();
            double tot = 0; for (double[] p : disp) { tot += (p[0] - mx) * (p[0] - mx) + (p[1] - my) * (p[1] - my); ssRes += (p[0] - p[2]) * (p[0] - p[2]) + (p[1] - p[3]) * (p[1] - p[3]); }
            if (tot > 0) M.posR2 = 1 - ssRes / tot;
        }
        if (spCnt > 0) {
            M.spdMae = spAbs / spCnt; M.spdRmse = Math.sqrt(spSq / spCnt);
            double mean = 0; for (double[] p : spPairs) mean += p[0]; mean /= spPairs.size();
            double tot = 0, res = 0; for (double[] p : spPairs) { tot += (p[0] - mean) * (p[0] - mean); res += (p[0] - p[1]) * (p[0] - p[1]); }
            if (tot > 0) M.spdR2 = 1 - res / tot;
        }
        if (hCnt > 0) M.headMae = hAbs / hCnt;
        if (M.sumDrSpeed > 0) M.speedScale = M.sumTruthSpeed / M.sumDrSpeed;
        if (!drifts.isEmpty()) {
            double s = 0, mx = 0; for (double d : drifts) { s += d; mx = Math.max(mx, d); }
            List<Double> sorted = new ArrayList<>(drifts); java.util.Collections.sort(sorted); int q = sorted.size();
            M.driftMeanPct = s / q; M.driftMaxPct = mx; M.driftMedianPct = q % 2 == 1 ? sorted.get(q / 2) : 0.5 * (sorted.get(q / 2 - 1) + sorted.get(q / 2));
            M.driftOverallPct = 100 * sumFinal / sumDist;
        }
        M.errT = new float[errs.size()]; M.errE = new float[errs.size()];
        for (int i = 0; i < errs.size(); i++) { M.errT[i] = errs.get(i)[0]; M.errE[i] = errs.get(i)[1]; }
        return M;
    }

    // ------------------------------------------------------------------ files
    private static String f(double v, int dec) { return Double.isNaN(v) ? "" : String.format(Locale.US, "%." + dec + "f", v); }
    private static String j(double v, int dec) { return Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(Locale.US, "%." + dec + "f", v); }
    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    /** track.csv: one row per 10 Hz tick with Maverick's output, the check engine and the nearest-in-time truth. */
    void writeTrackCsv(Writer w) throws IOException {
        w.write("t_s,mode,lat,lon,speed_mps,heading_deg,test_window,dr_lat,dr_lon,dr_speed_mps,dr_heading_deg,gnss_lat,gnss_lon,gnss_speed_mps,gnss_age_s,dr_error_m\n");
        int k = 0; StringBuilder b = new StringBuilder(256);
        for (int i = 0; i < n; i++) {
            while (k + 1 < m && tt[k + 1] <= t[i]) k++;
            boolean hasT = m > 0 && tt[k] <= t[i]; double age = hasT ? t[i] - tt[k] : Double.NaN;
            b.setLength(0);
            b.append(f(t[i], 2)).append(',').append(fm[i] == 0 ? "wait" : fm[i] == 1 ? "gnss" : "dr").append(',');
            if (fm[i] == 0) b.append(",,,,"); else b.append(f(geo.lat(fy[i]), 7)).append(',').append(f(geo.lon(fx[i]), 7)).append(',').append(f(fs[i], 2)).append(',').append(f(norm(Math.toDegrees(fh[i])), 1)).append(',');
            b.append(win[i] >= 0 ? String.valueOf(win[i] + 1) : "").append(',');
            if (win[i] >= 0) b.append(f(geo.lat(dy[i]), 7)).append(',').append(f(geo.lon(dx[i]), 7)).append(',').append(f(ds[i], 2)).append(',').append(f(norm(Math.toDegrees(dh[i])), 1)).append(','); else b.append(",,,,");
            if (hasT) b.append(f(geo.lat(ty[k]), 7)).append(',').append(f(geo.lon(tx[k]), 7)).append(',').append(f(tsp[k], 2)).append(',').append(f(age, 2)).append(','); else b.append(",,,,");
            b.append(win[i] >= 0 && hasT && age < 1.5 ? f(Math.hypot(dx[i] - tx[k], dy[i] - ty[k]), 1) : "");
            b.append('\n'); w.write(b.toString());
        }
    }
    private static double norm(double deg) { return ((deg % 360) + 360) % 360; }

    /** track.geojson: GNSS track, Maverick's track, the dead-reckoning test windows (with their errors) and real GNSS outages. */
    void writeGeoJson(Writer w, Metrics M) throws IOException {
        w.write("{\"type\":\"FeatureCollection\",\"properties\":{\"title\":\"" + esc(title) + "\",\"vehicle\":\"" + esc(vehicleName) + "\",\"started\":\"" + esc(startedAt) + "\"},\"features\":[");
        StringBuilder b = new StringBuilder();
        b.append("{\"type\":\"Feature\",\"properties\":{\"name\":\"").append(demo ? "reference trajectory" : "GNSS").append("\",\"distance_km\":").append(j(M.distanceKm, 3)).append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        float last = -1e9f; boolean firstPt = true;
        for (int k = 0; k < m; k++) { if (tt[k] - last < 0.99f) continue; last = tt[k]; if (!firstPt) b.append(','); firstPt = false; b.append('[').append(f(geo.lon(tx[k]), 7)).append(',').append(f(geo.lat(ty[k]), 7)).append(']'); }
        b.append("]}}"); w.write(b.toString()); b.setLength(0);
        b.append(",{\"type\":\"Feature\",\"properties\":{\"name\":\"Maverick (navigation output)\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        boolean any = false; for (int i = 0; i < n; i += 5) { if (fm[i] == 0) continue; if (any) b.append(','); any = true; b.append('[').append(f(geo.lon(fx[i]), 7)).append(',').append(f(geo.lat(fy[i]), 7)).append(']'); }
        b.append("]}}"); w.write(b.toString()); b.setLength(0);
        for (Window win1 : M.list) {
            b.append(",{\"type\":\"Feature\",\"properties\":{\"name\":\"dead-reckoning test ").append(win1.id + 1).append("\",\"start_s\":").append(j(win1.t0, 1)).append(",\"end_s\":").append(j(win1.t1, 1))
             .append(",\"distance_m\":").append(j(win1.distM, 1)).append(",\"final_error_m\":").append(j(win1.finalErrM, 1)).append(",\"drift_pct\":").append(win1.scored ? j(100 * win1.finalErrM / win1.distM, 2) : "null")
             .append(",\"scored\":").append(win1.scored).append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
            boolean a2 = false; for (int i = 0; i < n; i += 2) if (win[i] == win1.id) { if (a2) b.append(','); a2 = true; b.append('[').append(f(geo.lon(dx[i]), 7)).append(',').append(f(geo.lat(dy[i]), 7)).append(']'); }
            b.append("]}}"); w.write(b.toString()); b.setLength(0);
        }
        int i = 0;
        while (i < n) {
            if (fm[i] != 2) { i++; continue; }
            int s0 = i; while (i < n && fm[i] == 2) i++;
            b.append(",{\"type\":\"Feature\",\"properties\":{\"name\":\"real GNSS outage (dead reckoning)\",\"start_s\":").append(j(t[s0], 1)).append(",\"duration_s\":").append(j(t[i - 1] - t[s0], 1)).append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
            for (int q = s0; q < i; q += 2) { if (q > s0) b.append(','); b.append('[').append(f(geo.lon(fx[q]), 7)).append(',').append(f(geo.lat(fy[q]), 7)).append(']'); }
            b.append("]}}"); w.write(b.toString()); b.setLength(0);
        }
        w.write("]}\n");
    }

    /** track.gpx: the GNSS track (opens in Google Earth, OsmAnd, QGIS...). */
    void writeGpx(Writer w, long startEpochMs) throws IOException {
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Maverick\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>" + title.replace("&", "&amp;").replace("<", "&lt;") + "</name><trkseg>\n");
        float last = -1e9f;
        for (int k = 0; k < m; k++) if (tt[k] - last >= 0.99f) { last = tt[k]; w.write("<trkpt lat=\"" + f(geo.lat(ty[k]), 7) + "\" lon=\"" + f(geo.lon(tx[k]), 7) + "\"><time>" + iso.format(new java.util.Date(startEpochMs + Math.round(tt[k] * 1000.0))) + "</time></trkpt>\n"); }
        w.write("</trkseg></trk></gpx>\n");
    }

    /** summary.json: all metrics and every test window. */
    void writeSummary(Writer w, Metrics M) throws IOException {
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"title\": \"").append(esc(title)).append("\",\n  \"vehicle_name\": \"").append(esc(vehicleName)).append("\",\n  \"vehicle_type\": \"").append(vehicleType).append("\",\n");
        b.append("  \"started\": \"").append(esc(startedAt)).append("\",\n  \"demo\": ").append(demo).append(",\n");
        b.append("  \"duration_s\": ").append(j(M.durationS, 1)).append(",\n  \"distance_km\": ").append(j(M.distanceKm, 3)).append(",\n  \"avg_speed_kmh\": ").append(j(M.avgSpeedKmh, 1)).append(",\n  \"max_speed_kmh\": ").append(j(M.maxSpeedKmh, 1)).append(",\n");
        b.append("  \"test_windows\": ").append(M.windows).append(",\n  \"scored_windows\": ").append(M.scoredWindows).append(",\n  \"window_distance_km\": ").append(j(M.windowDistKm, 3)).append(",\n");
        b.append("  \"position_mae_m\": ").append(j(M.posMae, 2)).append(",\n  \"position_rmse_m\": ").append(j(M.posRmse, 2)).append(",\n  \"position_max_m\": ").append(j(M.posMax, 2)).append(",\n  \"position_r2_displacement\": ").append(j(M.posR2, 4)).append(",\n");
        b.append("  \"drift_mean_pct\": ").append(j(M.driftMeanPct, 2)).append(",\n  \"drift_median_pct\": ").append(j(M.driftMedianPct, 2)).append(",\n  \"drift_max_pct\": ").append(j(M.driftMaxPct, 2)).append(",\n  \"drift_overall_pct\": ").append(j(M.driftOverallPct, 2)).append(",\n");
        b.append("  \"speed_mae_mps\": ").append(j(M.spdMae, 3)).append(",\n  \"speed_rmse_mps\": ").append(j(M.spdRmse, 3)).append(",\n  \"speed_r2\": ").append(j(M.spdR2, 4)).append(",\n  \"heading_mae_deg\": ").append(j(M.headMae, 2)).append(",\n");
        b.append("  \"speed_scale_gnss_over_ai\": ").append(j(M.speedScale, 4)).append(",\n");
        b.append("  \"real_gnss_outages\": ").append(M.realOutages).append(",\n  \"real_outage_s\": ").append(j(M.realOutageS, 1)).append(",\n  \"real_outage_km\": ").append(j(M.realOutageKm, 3)).append(",\n");
        b.append("  \"windows\": [");
        for (int i = 0; i < M.list.size(); i++) {
            Window x = M.list.get(i); if (i > 0) b.append(',');
            b.append("\n    {\"id\": ").append(x.id + 1).append(", \"start_s\": ").append(j(x.t0, 1)).append(", \"end_s\": ").append(j(x.t1, 1)).append(", \"distance_m\": ").append(j(x.distM, 1))
             .append(", \"final_error_m\": ").append(j(x.finalErrM, 1)).append(", \"max_error_m\": ").append(j(x.maxErrM, 1)).append(", \"drift_pct\": ").append(x.scored ? j(100 * x.finalErrM / x.distM, 2) : "null").append(", \"scored\": ").append(x.scored).append("}");
        }
        b.append("\n  ],\n  \"method\": \"").append(demo
                ? "Demo: the navigation engine is denied GNSS in the test windows; truth = the dataset's reference trajectory."
                : "A second engine runs alongside navigation and is denied GNSS in test windows (60 s every 3 min after a 2 min warm-up); its position is compared with the GNSS fixes it did not receive. Windows under 100 m are not scored.").append("\"\n}\n");
        w.write(b.toString());
    }
}
