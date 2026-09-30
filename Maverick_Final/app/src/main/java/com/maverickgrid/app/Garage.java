package com.maverickgrid.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The user's own vehicles ("Pulsar 150", "Dad's Activa", "Swift"...). Each has a type (car / bike) that picks the AI model,
 * and its own drive statistics and ride recordings, so the behaviour of one particular vehicle can be analysed and,
 * later, a model can be trained for it. Stored in SharedPreferences "garage".
 */
final class Garage {
    private Garage() {}

    static final class Vehicle {
        final long id; final String name, type;
        Vehicle(long id, String name, String type) { this.id = id; this.name = name; this.type = type; }
        String icon() { return "bike".equals(type) ? "🏍" : "🚗"; }
        String typeLabel() { return "bike".equals(type) ? "Bike (beta model)" : "Car"; }
        /** Folder name for this vehicle's recordings, e.g. "pulsar_150_17". */
        String slug() { String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", ""); return (s.isEmpty() ? type : s) + "_" + id; }
    }

    static final class Stats {
        int drives, outages, tests, recordings; double km, drKm, testPctSum, hours, spTruth, spAi; long lastMs;
        double stopThreshold = Double.NaN, speedScale = 1.0, speedTruth, speedAi; int movingS, speedN;
        String summary() {
            if (drives == 0 && recordings == 0) return "No drives yet";
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "%d drive%s · %.1f km · %.1f h", drives, drives == 1 ? "" : "s", km, hours));
            b.append(String.format(Locale.US, "\nGPS lost %d time%s · %.2f km driven without GPS", outages, outages == 1 ? "" : "s", drKm));
            if (tests > 0) b.append(String.format(Locale.US, "\nGPS-loss tests: %d · average drift %.1f %% of distance", tests, testPctSum / tests));
            if (movingS > 0) b.append(String.format(Locale.US, "\nStop detector learned from %d min of GPS driving: threshold %.2f", movingS / 60, stopThreshold));
            if (speedN > 0) b.append(String.format(Locale.US, "\nSpeed pattern (GPS ÷ AI, %d samples): %s", speedN, speedN >= SPEED_MIN_N ? String.format(Locale.US, "×%.2f applied", speedScale) : "still learning"));
            b.append(String.format(Locale.US, "\nRecorded rides (training data): %d", recordings));
            return b.toString();
        }
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("garage", Context.MODE_PRIVATE); }

    static synchronized List<Vehicle> list(Context c) {
        SharedPreferences p = sp(c); String ids = p.getString("ids", null);
        if (ids == null) {                                    // first run: one of each, the user renames them or adds more
            long car = add(c, "My car", "car"); add(c, "My bike", "bike"); setCurrent(c, car); ids = p.getString("ids", "");
        }
        List<Vehicle> out = new ArrayList<>();
        for (String s : ids.split(",")) {
            if (s.isEmpty()) continue; long id = Long.parseLong(s);
            out.add(new Vehicle(id, p.getString("v_" + id + "_name", "Vehicle"), p.getString("v_" + id + "_type", "car")));
        }
        return out;
    }

    static synchronized long add(Context c, String name, String type) {
        SharedPreferences p = sp(c); long id = p.getLong("next", 1);
        String ids = p.getString("ids", ""); ids = ids.isEmpty() ? String.valueOf(id) : ids + "," + id;
        p.edit().putLong("next", id + 1).putString("ids", ids).putString("v_" + id + "_name", clean(name)).putString("v_" + id + "_type", type).commit();
        return id;
    }

    static synchronized void update(Context c, long id, String name, String type) {
        sp(c).edit().putString("v_" + id + "_name", clean(name)).putString("v_" + id + "_type", type).apply();
    }

    /** Removes the vehicle and its statistics (recorded ride files stay on the phone). */
    static synchronized void remove(Context c, long id) {
        SharedPreferences p = sp(c); StringBuilder b = new StringBuilder();
        for (String s : p.getString("ids", "").split(",")) if (!s.isEmpty() && Long.parseLong(s) != id) { if (b.length() > 0) b.append(','); b.append(s); }
        SharedPreferences.Editor e = p.edit().putString("ids", b.toString());
        for (String k : p.getAll().keySet()) if (k.startsWith("v_" + id + "_") || k.startsWith("s_" + id + "_")) e.remove(k);
        if (p.getLong("current", -1) == id) e.remove("current");
        e.apply();
    }

    static synchronized Vehicle current(Context c) {
        List<Vehicle> l = list(c); long cur = sp(c).getLong("current", -1);
        for (Vehicle v : l) if (v.id == cur) return v;
        if (l.isEmpty()) { long id = add(c, "My car", "car"); setCurrent(c, id); return new Vehicle(id, "My car", "car"); }
        setCurrent(c, l.get(0).id); return l.get(0);
    }
    static void setCurrent(Context c, long id) { sp(c).edit().putLong("current", id).apply(); }

    static synchronized Stats stats(Context c, long id) {
        SharedPreferences p = sp(c); String k = "s_" + id + "_"; Stats s = new Stats();
        s.drives = p.getInt(k + "drives", 0); s.outages = p.getInt(k + "outages", 0); s.tests = p.getInt(k + "tests", 0); s.recordings = p.getInt(k + "rec", 0);
        s.spTruth = p.getFloat(k + "sptruth", 0); s.spAi = p.getFloat(k + "spai", 0);
        s.movingS = p.getInt(k + "stopmovn", 0); s.stopThreshold = p.getFloat(k + "stopthr", Float.NaN); s.speedN = p.getInt(k + "spmn", 0);
        double mt = p.getFloat(k + "spmt", 0), md = p.getFloat(k + "spmd", 0);
        s.speedTruth = mt; s.speedAi = md; s.speedScale = speedScale(mt, md, s.speedN);
        s.km = p.getFloat(k + "km", 0); s.drKm = p.getFloat(k + "drkm", 0); s.testPctSum = p.getFloat(k + "testpct", 0); s.hours = p.getFloat(k + "hours", 0); s.lastMs = p.getLong(k + "last", 0);
        return s;
    }

    /** Adds one finished live drive to the vehicle's totals. */
    static synchronized void addDrive(Context c, long id, double km, double drKm, int outages, int tests, double testPctSum, double hours) {
        if (id < 0) return; Stats s = stats(c, id); String k = "s_" + id + "_";
        sp(c).edit().putInt(k + "drives", s.drives + 1).putFloat(k + "km", (float) (s.km + km)).putFloat(k + "drkm", (float) (s.drKm + drKm))
                .putInt(k + "outages", s.outages + outages).putInt(k + "tests", s.tests + tests).putFloat(k + "testpct", (float) (s.testPctSum + testPctSum))
                .putFloat(k + "hours", (float) (s.hours + hours)).putLong(k + "last", System.currentTimeMillis()).apply();
    }

    /** Adds GPS-speed and AI-speed sums from the dead-reckoning tests: their ratio is this vehicle's speed pattern (not applied yet). */
    static synchronized void addSpeedSample(Context c, long id, double truth, double ai) {
        if (id < 0 || !(ai > 0)) return; Stats s = stats(c, id); String k = "s_" + id + "_";
        sp(c).edit().putFloat(k + "sptruth", (float) (s.spTruth + truth)).putFloat(k + "spai", (float) (s.spAi + ai)).apply();
    }

    static final int SPEED_MIN_N = 60;         // seconds of moving dead-reckoning test data before the speed pattern is applied
    static final double SCALE_MIN = 0.7, SCALE_MAX = 1.6;
    /** GPS speed ÷ AI speed from the dead-reckoning tests, limited to a plausible range; 1 until there is enough data. */
    static double speedScale(double truth, double ai, int n) { return n >= SPEED_MIN_N && ai > 0 ? Math.max(SCALE_MIN, Math.min(SCALE_MAX, truth / ai)) : 1.0; }
    static final int STOP_MAX_S = 7200;        // the stop histograms remember about the last 2 hours of driving

    /** Speed pattern from the check engine's GPS-loss tests, moving samples only: truth and AI (divided by the scale that was in use). */
    static synchronized void addSpeedMoving(Context c, long id, double truth, double aiUnscaled, int n) {
        if (id < 0 || n <= 0 || !(aiUnscaled > 0)) return; SharedPreferences p = sp(c); String k = "s_" + id + "_";
        p.edit().putFloat(k + "spmt", (float) (p.getFloat(k + "spmt", 0) + truth)).putFloat(k + "spmd", (float) (p.getFloat(k + "spmd", 0) + aiUnscaled))
                .putInt(k + "spmn", p.getInt(k + "spmn", 0) + n).apply();
    }

    /** Stop-probability histograms (engine's prior + this drive) and the threshold learned from them. */
    static synchronized void saveStopLearning(Context c, long id, int[] moving, int[] still, double threshold) {
        if (id < 0 || moving == null) return; long tot = 0; for (int v : moving) tot += v; for (int v : still) tot += v;
        double f = tot > STOP_MAX_S ? (double) STOP_MAX_S / tot : 1.0; int nm = 0;
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < moving.length; i++) { int m = (int) Math.round(moving[i] * f), st = (int) Math.round(still[i] * f); nm += m; if (i > 0) { a.append(','); b.append(','); } a.append(m); b.append(st); }
        String k = "s_" + id + "_";
        sp(c).edit().putString(k + "stopmov", a.toString()).putString(k + "stopstill", b.toString()).putInt(k + "stopmovn", nm).putFloat(k + "stopthr", (float) threshold).apply();
    }
    static int[] stopHist(Context c, long id, boolean moving) {
        String v = sp(c).getString("s_" + id + "_" + (moving ? "stopmov" : "stopstill"), null); if (v == null) return null;
        String[] p = v.split(","); int[] h = new int[p.length]; try { for (int i = 0; i < p.length; i++) h[i] = Integer.parseInt(p[i]); } catch (NumberFormatException e) { return null; } return h;
    }

    /** How the phone sits in this vehicle: GPS bearing minus the phone compass (radians), learned while moving with GPS. */
    static synchronized void saveMountOffset(Context c, long id, double rad) { if (id >= 0 && !Double.isNaN(rad)) sp(c).edit().putFloat("s_" + id + "_mount", (float) rad).apply(); }
    static double mountOffset(Context c, long id) { float v = sp(c).getFloat("s_" + id + "_mount", Float.NaN); return Float.isNaN(v) ? Double.NaN : v; }

    /** Forgets what was learned for this vehicle (stop detector, speed pattern). */
    static synchronized void resetLearning(Context c, long id) {
        String k = "s_" + id + "_"; sp(c).edit().remove(k + "stopmov").remove(k + "stopstill").remove(k + "stopmovn").remove(k + "stopthr").remove(k + "spmt").remove(k + "spmd").remove(k + "spmn").remove(k + "mount").apply();
    }

    static synchronized void addRecording(Context c, long id) {
        if (id < 0) return; String k = "s_" + id + "_rec"; sp(c).edit().putInt(k, sp(c).getInt(k, 0) + 1).apply();
    }

    private static String clean(String n) { n = n == null ? "" : n.trim().replaceAll("\\s+", " "); if (n.length() > 30) n = n.substring(0, 30); return n.isEmpty() ? "Vehicle" : n; }
}
