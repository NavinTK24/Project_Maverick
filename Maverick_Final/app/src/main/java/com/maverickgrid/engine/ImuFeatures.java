package com.maverickgrid.engine;

import java.util.Arrays;

/**
 * Causal vibration features from a 10 Hz IMU stream (port of idr.features.imu_features, same order and maths).
 * Push one sample per 100 ms; features() describes the windows ending at the latest sample. Before 100 samples exist,
 * the history is padded with the first sample, exactly like the Python version.
 */
public final class ImuFeatures {
    public static final int[] WINDOWS = {10, 50, 100};
    public static final double[][] BANDS = {{0.3, 1.0}, {1.0, 2.0}, {2.0, 3.5}, {3.5, 5.0}};
    public static final double DT = 0.1;
    private static final int W = 100, NSIG = 7;           // amag, vert, horiz, g0, g1, g2, gmag
    public static final int N = 3 * NSIG * 3 + 2 * 2 * 4;   // 79
    private final double[][] buf = new double[NSIG][W];
    private int count = 0, head = 0;                          // head = index of the next write
    private final double[] first = new double[NSIG];
    private final double[][] cos = new double[W + 1][], sin = new double[W + 1][];

    public ImuFeatures() {
        for (int w : new int[]{50, 100}) {
            cos[w] = new double[w * w]; sin[w] = new double[w * w];
            for (int k = 0; k < w; k++) for (int n = 0; n < w; n++) { double a = -2 * Math.PI * k * n / w; cos[w][k * w + n] = Math.cos(a); sin[w][k * w + n] = Math.sin(a); }
        }
    }

    /** acc, grav in m/s^2 and gyr in rad/s, all in the same sensor frame. */
    public void push(double[] acc, double[] grav, double[] gyr) {
        double gn = Math.max(Math.sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2]), 1e-6);
        double gu0 = grav[0] / gn, gu1 = grav[1] / gn, gu2 = grav[2] / gn;
        double l0 = acc[0] - grav[0], l1 = acc[1] - grav[1], l2 = acc[2] - grav[2];
        double vert = l0 * gu0 + l1 * gu1 + l2 * gu2;
        double h0 = l0 - vert * gu0, h1 = l1 - vert * gu1, h2 = l2 - vert * gu2;
        double[] s = {Math.sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2]), vert, Math.sqrt(h0 * h0 + h1 * h1 + h2 * h2),
                gyr[0], gyr[1], gyr[2], Math.sqrt(gyr[0] * gyr[0] + gyr[1] * gyr[1] + gyr[2] * gyr[2])};
        if (count == 0) System.arraycopy(s, 0, first, 0, NSIG);
        for (int i = 0; i < NSIG; i++) buf[i][head] = s[i];
        head = (head + 1) % W; count++;
    }

    public int count() { return count; }

    /** Last w samples of signal i, oldest first, padded with the first sample. */
    private double[] window(int i, int w) {
        double[] out = new double[w];
        for (int j = 0; j < w; j++) {
            int age = w - 1 - j;                               // 0 = newest
            out[j] = age < count ? buf[i][((head - 1 - age) % W + W) % W] : first[i];
        }
        return out;
    }

    public float[] features() {
        float[] f = new float[N]; int c = 0;
        for (int w : WINDOWS) {
            for (int i = 0; i < NSIG; i++) {
                double[] x = window(i, w); double m = psum(x) / w;
                double[] d2 = new double[w], x2 = new double[w], ad = new double[w];
                for (int j = 0; j < w; j++) { double d = x[j] - m; d2[j] = d * d; x2[j] = x[j] * x[j]; ad[j] = Math.abs(d); }
                f[c++] = (float) Math.sqrt(psum(d2) / w); f[c++] = (float) Math.sqrt(psum(x2) / w); f[c++] = (float) percentile(ad, 90);
            }
            if (w >= 50) {
                for (int i = 0; i < 2; i++) {
                    double[] x = window(i, w); double m = psum(x) / w;
                    double[] P = new double[w / 2 + 1];
                    for (int k = 0; k <= w / 2; k++) {
                        double re = 0, im = 0;
                        for (int n = 0; n < w; n++) { double d = x[n] - m; re += d * cos[w][k * w + n]; im += d * sin[w][k * w + n]; }
                        P[k] = (re * re + im * im) / w;
                    }
                    for (double[] b : BANDS) {
                        double s = 0;
                        for (int k = 0; k <= w / 2; k++) { double fr = k / (w * DT); if (fr >= b[0] && fr < b[1]) s += P[k]; }
                        f[c++] = (float) Math.log(s + 1e-6);
                    }
                }
            }
        }
        return f;
    }

    /** numpy.percentile default ('linear' / _lerp). */
    static double percentile(double[] a, double q) {
        double[] s = a.clone(); Arrays.sort(s); double pos = (q / 100.0) * (s.length - 1);
        int lo = (int) Math.floor(pos); int hi = Math.min(lo + 1, s.length - 1); double t = pos - lo, d = s[hi] - s[lo];
        return t >= 0.5 ? s[hi] - d * (1 - t) : s[lo] + d * t;
    }

    /** numpy's pairwise summation (same rounding as numpy sum/mean on contiguous data). */
    static double psum(double[] a) { return psum(a, 0, a.length); }
    static double psum(double[] a, int off, int n) {
        if (n < 8) { double r = 0.0; for (int i = 0; i < n; i++) r += a[off + i]; return r; }
        if (n <= 128) {
            double[] r = new double[8]; for (int j = 0; j < 8; j++) r[j] = a[off + j];
            int i; for (i = 8; i < n - (n % 8); i += 8) for (int j = 0; j < 8; j++) r[j] += a[off + i + j];
            double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
            for (; i < n; i++) res += a[off + i];
            return res;
        }
        int n2 = n / 2; n2 -= n2 % 8; return psum(a, off, n2) + psum(a, off + n2, n - n2);
    }
}
