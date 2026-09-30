package com.maverickgrid.app;

/** Fixed-size ring of (x, y) points in the local metric frame, for drawing tracks. */
final class Trail {
    private final float[] xs, ys; private final boolean[] dr; private int head = 0, size = 0; private float lx = Float.NaN, ly; private boolean ldr;
    Trail(int capacity) { xs = new float[capacity]; ys = new float[capacity]; dr = new boolean[capacity]; }
    synchronized void add(double x, double y) { add(x, y, false); }
    /** A point (skipped if within 1 m of the last one, unless the mode changes); deadReckoned points are drawn in amber. */
    synchronized void add(double x, double y, boolean deadReckoned) {
        if (!Float.isNaN(lx) && ldr == deadReckoned && Math.hypot(x - lx, y - ly) < 1.0) return;
        xs[head] = (float) x; ys[head] = (float) y; dr[head] = deadReckoned; head = (head + 1) % xs.length; if (size < xs.length) size++;
        lx = (float) x; ly = (float) y; ldr = deadReckoned;
    }
    /** Copies the dead-reckoning flags oldest-first (same order as copy). */
    synchronized int copyFlags(boolean[] out) { int start = (head - size + xs.length) % xs.length; for (int i = 0; i < size; i++) out[i] = dr[(start + i) % xs.length]; return size; }
    synchronized void clear() { head = 0; size = 0; lx = Float.NaN; }
    /** Copies points oldest-first into out (length >= 2*size); returns the number of points. */
    synchronized int copy(float[] out) {
        int start = (head - size + xs.length) % xs.length;
        for (int i = 0; i < size; i++) { int j = (start + i) % xs.length; out[2 * i] = xs[j]; out[2 * i + 1] = ys[j]; }
        return size;
    }
    int capacity() { return xs.length; }
}
