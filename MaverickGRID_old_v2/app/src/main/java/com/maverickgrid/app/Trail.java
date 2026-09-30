package com.maverickgrid.app;

/** Fixed-size ring of (x, y) points in the local metric frame, for drawing tracks. */
final class Trail {
    private final float[] xs, ys; private int head = 0, size = 0;
    Trail(int capacity) { xs = new float[capacity]; ys = new float[capacity]; }
    synchronized void add(double x, double y) { xs[head] = (float) x; ys[head] = (float) y; head = (head + 1) % xs.length; if (size < xs.length) size++; }
    synchronized void clear() { head = 0; size = 0; }
    /** Copies points oldest-first into out (length >= 2*size); returns the number of points. */
    synchronized int copy(float[] out) {
        int start = (head - size + xs.length) % xs.length;
        for (int i = 0; i < size; i++) { int j = (start + i) % xs.length; out[2 * i] = xs[j]; out[2 * i + 1] = ys[j]; }
        return size;
    }
    int capacity() { return xs.length; }
}
