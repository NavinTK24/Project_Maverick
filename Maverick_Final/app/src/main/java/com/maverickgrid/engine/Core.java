package com.maverickgrid.engine;

/** Pure functions shared by the phone and edge engines (ports of idr.features / idr.calib / idr.harness pieces). */
public final class Core {
    public static final double DT = 0.1;
    public static final int STRIDE = 10;
    private Core() {}

    /** idr.features.context_at: [v_last, mean30, mean60, trend60, gps_age_s] from the 10 Hz held GNSS speed, samples 0..k0. */
    public static float[] context(double[] gps, int k0) {
        int len = k0 + 1; double last = gps[k0];
        int lastChange = -1; for (int i = 0; i < k0; i++) if (gps[i + 1] != gps[i]) lastChange = i;
        double age = lastChange >= 0 ? (len - 1 - (lastChange + 1)) * DT : len * DT;
        int n30 = Math.min(300, len), n60 = Math.min(600, len);
        double m30 = ImuFeatures.psum(gps, len - n30, n30) / n30, m60 = ImuFeatures.psum(gps, len - n60, n60) / n60;
        double trend = 0.0;
        if (n60 > 10) {
            double sx = 0, sy = 0; for (int i = 0; i < n60; i++) { sx += i * DT; sy += gps[len - n60 + i]; }
            double mx = sx / n60, my = sy / n60, num = 0, den = 0;
            for (int i = 0; i < n60; i++) { double dx = i * DT - mx; num += dx * (gps[len - n60 + i] - my); den += dx * dx; }
            trend = num / den;
        }
        return new float[]{(float) last, (float) m30, (float) m60, (float) trend, (float) age};
    }

    /** idr.features.residual_design for one row (+ optional physics input for the edge engine). */
    public static double[] residualRow(float[] imuT, float[] imuK0, int[] key, float[] ctx, float tau, Float physics) {
        int n = imuT.length + key.length + ctx.length + 1 + (physics != null ? 1 : 0); double[] x = new double[n]; int c = 0;
        for (float v : imuT) x[c++] = v;
        for (int k : key) x[c++] = (float) (imuT[k] - imuK0[k]);
        for (float v : ctx) x[c++] = v;
        x[c++] = tau;
        if (physics != null) x[c] = physics;
        return x;
    }

    /** idr.calib.gyro_from_stops: bias = -mean(rate) over IMU-detected stops (>= minRun s) in the 15 min before k0. */
    public static double gyroBiasFromStops(double[] rate, boolean[] stopRows, int k0, int minRun) {
        int r0 = Math.max(0, Math.floorDiv(k0 - 9000, STRIDE)), r1 = k0 / STRIDE;
        double[] buf = new double[Math.max(1, (r1 - r0) * STRIDE)]; int nb = 0; int i = 0, len = Math.max(0, Math.min(r1, stopRows.length) - r0);
        while (i < len) {
            if (stopRows[r0 + i]) {
                int j = i; while (j < len && stopRows[r0 + j]) j++;
                if (j - i >= minRun) { int a = (r0 + i + 1) * STRIDE, b = (r0 + j - 1) * STRIDE; for (int k = a; k < b; k++) buf[nb++] = rate[k]; }
                i = j;
            } else i++;
        }
        if (nb == 0) return 0.0;
        return -(ImuFeatures.psum(buf, 0, nb) / nb);
    }
}
