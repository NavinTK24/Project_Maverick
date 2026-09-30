package com.maverickgrid.app;

import android.content.Context;

import com.maverickgrid.engine.Geo;
import com.maverickgrid.engine.Lines;
import com.maverickgrid.engine.Roads;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;

/**
 * Roads this phone has driven with good GPS ("learned roads"), kept only in the app's private storage.
 * During a drive, good fixes (accuracy ≤ 15 m, moving) are collected every ≥ 8 m; when the drive ends, points on
 * places not driven before (≈10 m cells) are added to learned_roads.bin. During a GPS outage the map filter treats
 * these tracks as roads, so a route driven before is followed even where the offline map has no road.
 */
final class LearnedRoads {
    private static final String FILE = "learned_roads.bin"; private static final int MAGIC = 0x4D4C5231;   // "MLR1"
    private static final int BREAK = Integer.MIN_VALUE, MAX_POINTS = 400_000;
    private static final double CELL_DEG = 1e-4;                                              // ≈ 11 m of latitude

    // ------------------------------------------------------------------ collection during a drive (engine thread)
    private int[] lat = new int[1024], lon = new int[1024]; private int n; private double lastLat = Double.NaN, lastLon; private long lastT;

    void clearDrive() { n = 0; lastLat = Double.NaN; }

    void fix(long tNs, double la, double lo, double speed, double acc) {
        if (!(acc <= 15) || !(speed > 2.0)) return;
        if (!Double.isNaN(lastLat)) {
            double dy = (la - lastLat) * 111_320, dx = (lo - lastLon) * 111_320 * Math.cos(Math.toRadians(la)), d = Math.hypot(dx, dy);
            if (tNs - lastT > 5_000_000_000L || d > 150) put(BREAK, BREAK);                 // a gap: do not join across it
            else if (d < 8) return;
        }
        put((int) Math.round(la * 1e7), (int) Math.round(lo * 1e7)); lastLat = la; lastLon = lo; lastT = tNs;
    }
    private void put(int a, int b) { if (n == lat.length) { lat = java.util.Arrays.copyOf(lat, n * 2); lon = java.util.Arrays.copyOf(lon, n * 2); } lat[n] = a; lon[n] = b; n++; }

    /** Snapshot of this drive's points for {@link #merge} (engine thread). */
    int[][] takeDrive() { int[][] r = {java.util.Arrays.copyOf(lat, n), java.util.Arrays.copyOf(lon, n)}; clearDrive(); return r; }

    // ------------------------------------------------------------------ store (background thread)
    private static final Object FILE_LOCK = new Object();

    static int[][] load(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), FILE); if (!f.isFile()) return new int[][]{new int[0], new int[0]};
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
                if (in.readInt() != MAGIC) return new int[][]{new int[0], new int[0]};
                int m = in.readInt(); if (m < 0 || m > MAX_POINTS * 2) return new int[][]{new int[0], new int[0]};
                int[] a = new int[m], b = new int[m]; for (int i = 0; i < m; i++) { a[i] = in.readInt(); b[i] = in.readInt(); }
                return new int[][]{a, b};
            } catch (IOException e) { return new int[][]{new int[0], new int[0]}; }
        }
    }

    /** Adds a drive's points that are not already known; returns the number of new points. */
    static int merge(Context c, int[][] drive) {
        synchronized (FILE_LOCK) {
            int[][] old = load(c); HashSet<Long> seen = new HashSet<>(old[0].length * 2);
            for (int i = 0; i < old[0].length; i++) if (old[0][i] != BREAK) seen.add(cell(old[0][i], old[1][i]));
            int[] a = java.util.Arrays.copyOf(old[0], old[0].length + drive[0].length + 1), b = java.util.Arrays.copyOf(old[1], a.length); int m = old[0].length, added = 0;
            if (m > 0 && a[m - 1] != BREAK) { a[m] = BREAK; b[m] = BREAK; m++; }
            boolean open = false;
            for (int i = 0; i < drive[0].length; i++) {
                int la = drive[0][i], lo = drive[1][i];
                if (la == BREAK || seen.contains(cell(la, lo))) { if (open) { a[m] = BREAK; b[m] = BREAK; m++; open = false; } continue; }
                a[m] = la; b[m] = lo; m++; open = true; added++; seen.add(cell(la, lo));
            }
            int start = Math.max(0, m - MAX_POINTS);                                      // oldest points go first when full
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(c.getFilesDir(), FILE)), 1 << 16))) {
                out.writeInt(MAGIC); out.writeInt(m - start); for (int i = start; i < m; i++) { out.writeInt(a[i]); out.writeInt(b[i]); }
            } catch (IOException e) { return 0; }
            return added;
        }
    }

    static void clear(Context c) { synchronized (FILE_LOCK) { new File(c.getFilesDir(), FILE).delete(); } }

    static int count(Context c) { int[][] p = load(c); int k = 0; for (int v : p[0]) if (v != BREAK) k++; return k; }

    /**
     * The learned roads within 25 km of the frame's origin, as undirected lines in that frame; null if there are none.
     * Where the offline map already has a road (within 15 m) the map is used instead: learned tracks only fill the map's gaps
     * (tested on IO-VNBD: on fully mapped roads extra GPS tracks do not help, without a map they cut the drift by a quarter to a half).
     */
    static Lines linesFor(Context c, Geo g, Roads roads) {
        Roads.Stamp st = new Roads.Stamp();
        int[][] p = load(c); int m = p[0].length; if (m < 2) return null;
        double[] xs = new double[m], ys = new double[m]; int k = 0;
        for (int i = 0; i < m; i++) {
            if (p[0][i] == BREAK) { xs[i] = ys[i] = Double.NaN; continue; }
            double x = g.x(p[1][i] / 1e7), y = g.y(p[0][i] / 1e7);
            if (Math.abs(x) > 25_000 || Math.abs(y) > 25_000 || (roads != null && roads.nearest(x, y, 15.0, st) >= 0)) { xs[i] = ys[i] = Double.NaN; continue; }
            xs[i] = x; ys[i] = y; k++;
        }
        if (k < 2) return null;
        Lines l = new Lines(25.0).addPolyline(xs, ys, m, false, 0.0).build();
        return l.size() > 0 ? l : null;
    }

    private static long cell(int la, int lo) { long a = Math.round(la / 1e7 / CELL_DEG), b = Math.round(lo / 1e7 / CELL_DEG); return (a << 32) ^ (b & 0xffffffffL); }
}
