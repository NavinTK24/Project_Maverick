package com.maverickgrid.engine;

import java.util.SplittableRandom;

/**
 * Map-aided dead reckoning during a GNSS outage (port of idr.mapmatch): particle filter over position, heading, speed scale
 * and gyro bias, weighted once per second by road distance and alignment; plus the consistency check against plain DR.
 * Streaming: start() at the outage, then step(speed, yawRate) every 0.1 s; position() gives the fused estimate.
 */
public final class MapFilter {
    public static final class Params {
        public int N = 400; public double sigD = 12.0, sigH = Math.toRadians(15), sSd = 0.15, sRw = 0.03, bSd = Math.toRadians(0.3), bRw = Math.toRadians(0.01),
                hRw = Math.toRadians(0.5), floor = 0.02, reach = 40.0, fuseK = 4.0, fuseA = 0.12, fuseB = 10.0;
    }
    private final Roads roads; private final Params p; private SplittableRandom rng;
    private double[] x, y, psi, s, b, w; private int steps;
    private double drX, drY, drPsi, dist; private double outX, outY, pfX, pfY, lam, outPsi;
    private double[] tx, ty, tpsi, ts, tb;

    public MapFilter(Roads roads, Params p) { this.roads = roads; this.p = p; }

    public void start(double x0, double y0, double psi0, long seed) {
        int N = p.N; rng = new SplittableRandom(seed);
        x = new double[N]; y = new double[N]; psi = new double[N]; s = new double[N]; b = new double[N]; w = new double[N];
        tx = new double[N]; ty = new double[N]; tpsi = new double[N]; ts = new double[N]; tb = new double[N];
        for (int i = 0; i < N; i++) { x[i] = x0; y[i] = y0; psi[i] = psi0 + gauss() * Math.toRadians(1); s[i] = 1 + gauss() * p.sSd; b[i] = gauss() * p.bSd; w[i] = 1.0 / N; }
        drX = x0; drY = y0; drPsi = psi0; dist = 0; steps = 0; outX = pfX = x0; outY = pfY = y0; lam = 0; outPsi = psi0;
    }

    private double gauss() { // Box-Muller on SplittableRandom (deterministic per seed)
        double u1 = rng.nextDouble(), u2 = rng.nextDouble(); return Math.sqrt(-2 * Math.log(Math.max(u1, 1e-300))) * Math.cos(2 * Math.PI * u2);
    }

    /** Advance 0.1 s with the engine's speed (m/s) and bias-corrected yaw rate (rad/s, clockwise positive). */
    public void step(double speed, double yaw) {
        final double DT = Core.DT; int N = p.N;
        // plain dead reckoning (for the consistency check)
        drX += speed * Math.sin(drPsi) * DT; drY += speed * Math.cos(drPsi) * DT; drPsi += yaw * DT; dist += speed * DT;
        double hn = p.hRw * Math.sqrt(DT);
        for (int i = 0; i < N; i++) {
            double v = Math.max(speed * s[i], 0); x[i] += v * Math.sin(psi[i]) * DT; y[i] += v * Math.cos(psi[i]) * DT;
            psi[i] += (yaw + b[i]) * DT + gauss() * hn;
        }
        steps++;
        if (steps % 10 == 0 && roads != null) {
            for (int i = 0; i < N; i++) { s[i] += gauss() * p.sRw; b[i] += gauss() * p.bRw; }
            if (speed > 0.5) mapUpdate();
        }
        double sx = 0, sy = 0; for (int i = 0; i < N; i++) { sx += w[i] * x[i]; sy += w[i] * y[i]; }
        pfX = sx; pfY = sy;
        double z = Math.hypot(pfX - drX, pfY - drY) / (p.fuseA * dist + p.fuseB); lam = 1 / (1 + Math.exp(-(z - p.fuseK) * 3));
        outX = pfX + lam * (drX - pfX); outY = pfY + lam * (drY - pfY);
        double hs = 0, hc = 0; for (int i = 0; i < N; i++) { hs += w[i] * Math.sin(psi[i]); hc += w[i] * Math.cos(psi[i]); }
        outPsi = Math.atan2((1 - lam) * hs + lam * Math.sin(drPsi), (1 - lam) * hc + lam * Math.cos(drPsi));
    }

    private void mapUpdate() {
        int N = p.N; double sum = 0, reach = p.reach, cell = roads.cell, is2 = 1 / (p.sigD * p.sigD), ih2 = 1 / (p.sigH * p.sigH);
        for (int i = 0; i < N; i++) {
            double qx = x[i], qy = y[i], best = Double.POSITIVE_INFINITY;
            int cx0 = (int) Math.floor((qx - reach) / cell), cx1 = (int) Math.floor((qx + reach) / cell);
            int cy0 = (int) Math.floor((qy - reach) / cell), cy1 = (int) Math.floor((qy + reach) / cell);
            for (int cx = cx0; cx <= cx1; cx++) for (int cy = cy0; cy <= cy1; cy++) {
                int[] segs = roads.cellSegments(cx, cy); if (segs == null) continue;
                for (int seg : segs) {                       // a segment seen twice cannot change the minimum
                    double d = roads.dist(seg, qx, qy); if (d > reach) continue;
                    double dh = Math.abs(Math.IEEEremainder(psi[i] - roads.dir[seg], 2 * Math.PI));
                    if (!roads.oneway[seg]) dh = Math.min(dh, Math.PI - dh);
                    double c = d * d * is2 + dh * dh * ih2; if (c < best) best = c;
                }
            }
            w[i] *= Math.exp(-0.5 * best) + p.floor; sum += w[i];
        }
        double ess = 0; for (int i = 0; i < N; i++) { w[i] /= sum; ess += w[i] * w[i]; }
        if (1 / ess < N / 2.0) {                       // systematic resampling
            double u0 = rng.nextDouble(), c = w[0]; int j = 0;
            for (int i = 0; i < N; i++) {
                double u = (u0 + i) / N; while (u > c && j < N - 1) c += w[++j];
                tx[i] = x[j]; ty[i] = y[j]; tpsi[i] = psi[j]; ts[i] = s[j]; tb[i] = b[j];
            }
            double[] t;
            t = x; x = tx; tx = t; t = y; y = ty; ty = t; t = psi; psi = tpsi; tpsi = t; t = s; s = ts; ts = t; t = b; b = tb; tb = t;
            java.util.Arrays.fill(w, 1.0 / N);
        }
    }

    public double x() { return outX; }
    public double y() { return outY; }
    public double pfX() { return pfX; }
    public double pfY() { return pfY; }
    public double drX() { return drX; }
    public double drY() { return drY; }
    /** Fused heading: map-corrected particle heading blended toward plain DR with the same weight as the position. */
    public double heading() { return outPsi; }
}
