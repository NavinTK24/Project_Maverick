package com.maverickgrid.engine;

/** Minimal open-addressing map long -> int[] (no boxing on lookup). */
final class LongIntArrayMap {
    private long[] keys; private int[][] vals; private int size;
    LongIntArrayMap(int expected) { int cap = Integer.highestOneBit(Math.max(4, expected * 2)) << 1; keys = new long[cap]; vals = new int[cap][]; }
    private static int mix(long k) { long h = k * 0x9E3779B97F4A7C15L; return (int) (h ^ (h >>> 32)); }
    int[] get(long k) { int m = keys.length - 1, i = mix(k) & m; while (vals[i] != null) { if (keys[i] == k) return vals[i]; i = (i + 1) & m; } return null; }
    void put(long k, int[] v) {
        if (size * 2 >= keys.length) grow();
        int m = keys.length - 1, i = mix(k) & m; while (vals[i] != null && keys[i] != k) i = (i + 1) & m;
        if (vals[i] == null) size++; keys[i] = k; vals[i] = v;
    }
    private void grow() { long[] ok = keys; int[][] ov = vals; keys = new long[ok.length * 2]; vals = new int[ok.length * 2][]; size = 0; for (int i = 0; i < ok.length; i++) if (ov[i] != null) put(ok[i], ov[i]); }
}
