package com.maverickgrid.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Locale;

/** Draws a drive report as a PNG-ready bitmap: map of GNSS vs dead-reckoned tracks, error and speed charts, metrics table. */
final class ReportImage {
    private ReportImage() {}

    // light, print-friendly palette; the two data colours differ strongly in lightness (safe for colour-blind readers)
    private static final int BG = 0xFFFFFFFF, PANEL = 0xFFF8F9FA, BORDER = 0xFFDADCE0, GRID = 0xFFE8EAED, INK = 0xFF202124, INK2 = 0xFF5F6368,
            TRUTH = 0xFF3C4043, DR = 0xFFE8710A, REAL = 0xFF9334E6, START = 0xFF188038, END = 0xFFC5221F, WIN_BG = 0x22E8710A, GOOD = 0xFF188038, BAD = 0xFFC5221F;
    private static final int W = 1600, H = 2680, L = 60, R = 1540;

    static Bitmap render(DriveLog d, DriveLog.Metrics M) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(bmp); c.drawColor(BG);
        Paint tx = text(26, INK, false), tb = text(44, INK, true);
        c.drawText("Maverick drive report", L, 90, tb);
        String sub = d.vehicleName + " (" + ("bike".equals(d.vehicleType) ? "bike" : "car") + ") · " + d.startedAt + " · " + dur(M.durationS) + " · " + f1(M.distanceKm) + " km";
        c.drawText(sub, L, 136, text(26, INK2, false));
        if (d.demo) c.drawText("Demo: ISRO IO-VNBD drive, truth = dataset reference trajectory", L, 172, text(22, INK2, false));
        else c.drawText("GPS was withheld from a second engine for 60 s every 3 min; truth = the phone's GPS fixes it did not receive", L, 172, text(22, INK2, false));

        drawMap(c, d, M, new RectF(L, 200, R, 1260));
        drawErrorChart(c, d, M, new RectF(L + 90, 1340, R, 1650));
        drawSpeedChart(c, d, M, new RectF(L + 90, 1810, R, 2110));
        drawTable(c, M, d.demo, 2210);
        return bmp;
    }

    // ------------------------------------------------------------------ map
    private static void drawMap(Canvas c, DriveLog d, DriveLog.Metrics M, RectF box) {
        Paint bg = fill(PANEL), bd = stroke(BORDER, 2); c.drawRect(box, bg); c.drawRect(box, bd);
        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int k = 0; k < d.m; k++) { x0 = Math.min(x0, d.tx[k]); x1 = Math.max(x1, d.tx[k]); y0 = Math.min(y0, d.ty[k]); y1 = Math.max(y1, d.ty[k]); }
        for (int i = 0; i < d.n; i++) {
            if (d.win[i] >= 0) { x0 = Math.min(x0, d.dx[i]); x1 = Math.max(x1, d.dx[i]); y0 = Math.min(y0, d.dy[i]); y1 = Math.max(y1, d.dy[i]); }
            if (d.fm[i] != 0) { x0 = Math.min(x0, d.fx[i]); x1 = Math.max(x1, d.fx[i]); y0 = Math.min(y0, d.fy[i]); y1 = Math.max(y1, d.fy[i]); }
        }
        if (x0 > x1) { centred(c, "No position data recorded", box, text(30, INK2, false)); return; }
        double span = Math.max(Math.max(x1 - x0, y1 - y0), 50), pad = 0.08;
        double sx = (box.width() * (1 - 2 * pad)) / Math.max(x1 - x0, 1), sy = (box.height() * (1 - 2 * pad)) / Math.max(y1 - y0, 1), s = Math.min(Math.min(sx, sy), box.width() / 50.0);
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        java.util.function.DoubleUnaryOperator X = v -> box.centerX() + (v - cx) * s, Y = v -> box.centerY() - (v - cy) * s;
        c.save(); c.clipRect(box);
        // truth
        Paint pt = stroke(TRUTH, 3); Path path = new Path(); boolean pen = false;
        for (int k = 0; k < d.m; k++) {
            float px = (float) X.applyAsDouble(d.tx[k]), py = (float) Y.applyAsDouble(d.ty[k]);
            boolean jump = k > 0 && (d.tt[k] - d.tt[k - 1] > 10);
            if (!pen || jump) { path.moveTo(px, py); pen = true; } else path.lineTo(px, py);
        }
        c.drawPath(path, pt);
        // real outages of the navigation output
        Paint pr = stroke(REAL, 5); pr.setPathEffect(new DashPathEffect(new float[]{14, 10}, 0)); path.rewind(); boolean anyReal = false;
        for (int i = 1; i < d.n; i++) if (d.fm[i] == 2) {
            if (d.fm[i - 1] != 2) path.moveTo((float) X.applyAsDouble(d.fx[i]), (float) Y.applyAsDouble(d.fy[i])); else path.lineTo((float) X.applyAsDouble(d.fx[i]), (float) Y.applyAsDouble(d.fy[i]));
            anyReal = true;
        }
        if (anyReal && !d.demo) c.drawPath(path, pr);
        // dead-reckoning test windows
        Paint pd = stroke(DR, 5), link = stroke(INK2, 2); link.setPathEffect(new DashPathEffect(new float[]{6, 6}, 0));
        Paint dot = fill(DR), dotT = fill(TRUTH), ring = stroke(BG, 3), lab = text(20, INK, true);
        for (DriveLog.Window w : M.list) {
            path.rewind(); boolean st = false;
            for (int i = 0; i < d.n; i++) if (d.win[i] == w.id) { float px = (float) X.applyAsDouble(d.dx[i]), py = (float) Y.applyAsDouble(d.dy[i]); if (!st) { path.moveTo(px, py); st = true; } else path.lineTo(px, py); }
            c.drawPath(path, pd);
            float ex = (float) X.applyAsDouble(w.endX), ey = (float) Y.applyAsDouble(w.endY);
            if (!Double.isNaN(w.finalErrM)) { float gx = (float) X.applyAsDouble(w.truthEndX), gy = (float) Y.applyAsDouble(w.truthEndY); c.drawLine(ex, ey, gx, gy, link); c.drawCircle(gx, gy, 6, dotT); }
            c.drawCircle(ex, ey, 9, dot); c.drawCircle(ex, ey, 9, ring);
            if (M.list.size() <= 20 && w.scored) { String t = "#" + (w.id + 1) + "  " + f1(100 * w.finalErrM / w.distM) + "%"; halo(c, t, ex + 12, ey - 10, lab); }
        }
        // start / end
        if (d.m > 0) {
            float sx0 = (float) X.applyAsDouble(d.tx[0]), sy0 = (float) Y.applyAsDouble(d.ty[0]), ex1 = (float) X.applyAsDouble(d.tx[d.m - 1]), ey1 = (float) Y.applyAsDouble(d.ty[d.m - 1]);
            c.drawCircle(sx0, sy0, 12, fill(START)); c.drawCircle(sx0, sy0, 12, ring); halo(c, "Start", sx0 + 16, sy0 + 8, text(22, INK, true));
            c.drawRect(ex1 - 10, ey1 - 10, ex1 + 10, ey1 + 10, fill(END)); halo(c, "End", ex1 + 16, ey1 + 8, text(22, INK, true));
        }
        c.restore();
        // scale bar and north
        double m = nice(box.width() * 0.22 / s); float len = (float) (m * s), bx = box.left + 30, by = box.bottom - 34;
        Paint sb = stroke(INK, 3); c.drawLine(bx, by, bx + len, by, sb); c.drawLine(bx, by - 8, bx, by + 8, sb); c.drawLine(bx + len, by - 8, bx + len, by + 8, sb);
        c.drawText(m >= 1000 ? f0(m / 1000) + " km" : f0(m) + " m", bx, by - 14, text(22, INK, false));
        float nx = box.right - 50, ny = box.top + 70; Path na = new Path(); na.moveTo(nx, ny - 34); na.lineTo(nx + 14, ny + 6); na.lineTo(nx, ny - 4); na.lineTo(nx - 14, ny + 6); na.close();
        c.drawPath(na, fill(INK)); Paint nt = text(24, INK, true); nt.setTextAlign(Paint.Align.CENTER); c.drawText("N", nx, ny + 34, nt);
        // legend
        float lx = box.left + 20, ly = box.top + 20; int rows = anyReal && !d.demo ? 4 : 3;
        c.drawRoundRect(new RectF(lx, ly, lx + 560, ly + 24 + rows * 38), 10, 10, fill(0xEEFFFFFF)); c.drawRoundRect(new RectF(lx, ly, lx + 560, ly + 24 + rows * 38), 10, 10, stroke(BORDER, 2));
        Paint lt = text(22, INK, false); float yy = ly + 38;
        c.drawLine(lx + 16, yy - 7, lx + 70, yy - 7, stroke(TRUTH, 4)); c.drawText(d.demo ? "True path (dataset reference)" : "GPS track (truth)", lx + 84, yy, lt); yy += 38;
        c.drawLine(lx + 16, yy - 7, lx + 70, yy - 7, stroke(DR, 6)); c.drawText("Dead reckoning while GPS withheld (#n: drift %)", lx + 84, yy, lt); yy += 38;
        if (rows == 4) { c.drawLine(lx + 16, yy - 7, lx + 70, yy - 7, pr); c.drawText("GPS lost during navigation (real or Test button)", lx + 84, yy, lt); yy += 38; }
        c.drawCircle(lx + 30, yy - 7, 9, fill(START)); c.drawRect(lx + 50, yy - 16, lx + 68, yy + 2, fill(END)); c.drawText("Start / end", lx + 84, yy, lt);
    }

    // ------------------------------------------------------------------ charts
    private static void drawErrorChart(Canvas c, DriveLog d, DriveLog.Metrics M, RectF b) {
        c.drawText("Position error of dead reckoning while GPS was withheld (m)", L, b.top - 24, text(28, INK, true));
        double t0 = d.n > 0 ? d.t[0] : 0, t1 = d.n > 0 ? Math.max(d.t[d.n - 1], t0 + 1) : 1;
        double ymax = 10; for (float e : M.errE) ymax = Math.max(ymax, e); ymax = niceCeil(ymax * 1.1);
        axes(c, b, t0, t1, 0, ymax, "m");
        for (DriveLog.Window w : M.list) { float a = (float) (b.left + (w.t0 - t0) / (t1 - t0) * b.width()), z = (float) (b.left + (w.t1 - t0) / (t1 - t0) * b.width()); c.drawRect(a, b.top, Math.max(z, a + 2), b.bottom, fill(WIN_BG)); }
        if (M.errT.length == 0) { centred(c, d.demo ? "No test windows yet" : "No scored test windows (drive longer than ~3 minutes, moving)", b, text(24, INK2, false)); return; }
        Paint p = stroke(DR, 3); Path path = new Path();
        for (int i = 0; i < M.errT.length; i++) {
            float x = (float) (b.left + (M.errT[i] - t0) / (t1 - t0) * b.width()), y = (float) (b.bottom - M.errE[i] / ymax * b.height());
            if (i == 0 || M.errT[i] - M.errT[i - 1] > 3) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, p);
    }

    private static void drawSpeedChart(Canvas c, DriveLog d, DriveLog.Metrics M, RectF b) {
        c.drawText("Speed (km/h): GPS vs Maverick's AI estimate while GPS was withheld", L, b.top - 24, text(28, INK, true));
        double t0 = d.n > 0 ? d.t[0] : 0, t1 = d.n > 0 ? Math.max(d.t[d.n - 1], t0 + 1) : 1;
        double ymax = 20; for (int k = 0; k < d.m; k++) if (!Float.isNaN(d.tsp[k])) ymax = Math.max(ymax, d.tsp[k] * 3.6);
        ymax = niceCeil(ymax * 1.1);
        axes(c, b, t0, t1, 0, ymax, "km/h");
        for (DriveLog.Window w : M.list) { float a = (float) (b.left + (w.t0 - t0) / (t1 - t0) * b.width()), z = (float) (b.left + (w.t1 - t0) / (t1 - t0) * b.width()); c.drawRect(a, b.top, Math.max(z, a + 2), b.bottom, fill(WIN_BG)); }
        Paint pg = stroke(TRUTH, 2); Path path = new Path(); boolean pen = false;
        for (int k = 0; k < d.m; k++) {
            if (Float.isNaN(d.tsp[k])) { pen = false; continue; }
            float x = (float) (b.left + (d.tt[k] - t0) / (t1 - t0) * b.width()), y = (float) (b.bottom - d.tsp[k] * 3.6 / ymax * b.height());
            if (!pen || (k > 0 && d.tt[k] - d.tt[k - 1] > 5)) { path.moveTo(x, y); pen = true; } else path.lineTo(x, y);
        }
        c.drawPath(path, pg);
        Paint pd = stroke(DR, 3); path.rewind();
        for (int i = 0; i < d.n; i++) {
            if (d.win[i] < 0) continue;
            float x = (float) (b.left + (d.t[i] - t0) / (t1 - t0) * b.width()), y = (float) (b.bottom - Math.min(d.ds[i] * 3.6, ymax) / ymax * b.height());
            if (i == 0 || d.win[i - 1] != d.win[i]) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, pd);
        Paint lt = text(22, INK, false); float lx = b.right - 560, ly = b.top + 30;
        c.drawRoundRect(new RectF(lx - 12, ly - 26, b.right - 8, ly + 50), 8, 8, fill(0xEEFFFFFF));
        c.drawLine(lx, ly - 7, lx + 44, ly - 7, stroke(TRUTH, 3)); c.drawText(d.demo ? "True speed (dataset)" : "GPS speed", lx + 56, ly, lt);
        c.drawLine(lx, ly + 31, lx + 44, ly + 31, stroke(DR, 4)); c.drawText("AI speed estimate (GPS withheld)", lx + 56, ly + 38, lt);
    }

    private static void axes(Canvas c, RectF b, double t0, double t1, double y0, double y1, String unit) {
        Paint g = stroke(GRID, 1.5f), ax = stroke(INK2, 2), lab = text(20, INK2, false), labR = text(20, INK2, false); labR.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) { float y = b.bottom - i / 4f * b.height(); c.drawLine(b.left, y, b.right, y, g); c.drawText(fmtAxis(y0 + (y1 - y0) * i / 4), b.left - 12, y + 7, labR); }
        double span = (t1 - t0) / 60.0, step = nice(span / 6); if (step <= 0) step = 1;
        lab.setTextAlign(Paint.Align.CENTER);
        for (double mnt = Math.ceil(t0 / 60 / step) * step; mnt <= t1 / 60 + 1e-9; mnt += step) { float x = (float) (b.left + (mnt * 60 - t0) / (t1 - t0) * b.width()); c.drawLine(x, b.bottom, x, b.bottom + 8, ax); c.drawText(fmtAxis(mnt), x, b.bottom + 32, lab); }
        c.drawLine(b.left, b.bottom, b.right, b.bottom, ax); c.drawLine(b.left, b.top, b.left, b.bottom, ax);
        Paint un = text(20, INK2, false); un.setTextAlign(Paint.Align.RIGHT); c.drawText("drive time (min)", b.right, b.bottom + 62, un);
    }

    // ------------------------------------------------------------------ metrics table
    private static void drawTable(Canvas c, DriveLog.Metrics M, boolean demo, float top) {
        c.drawText("Results", L, top, text(32, INK, true));
        String[][] left = {
                {"Distance travelled", f2(M.distanceKm) + " km"},
                {"Duration", dur(M.durationS)},
                {"Average / max speed", f0(M.avgSpeedKmh) + " / " + f0(M.maxSpeedKmh) + " km/h"},
                {"GPS-loss tests (scored / all)", M.scoredWindows + " / " + M.windows},
                {"Distance driven in tests", f2(M.windowDistKm) + " km"},
                {"GPS lost during navigation", demo ? "– (demo)" : M.realOutages + (M.realOutages > 0 ? " (" + f0(M.realOutageS) + " s, " + f2(M.realOutageKm) + " km)" : "")},
        };
        String[][] right = {
                {"Drift error, median", pct(M.driftMedianPct)},
                {"Drift error, mean / max", pct(M.driftMeanPct) + " / " + pct(M.driftMaxPct)},
                {"Position MAE / RMSE", m1(M.posMae) + " / " + m1(M.posRmse)},
                {"Position R² (displacement)", f3(M.posR2)},
                {"Speed MAE / RMSE (km/h)", f1(M.spdMae * 3.6) + " / " + f1(M.spdRmse * 3.6)},
                {"Speed R²", f3(M.spdR2)},
                {"Heading MAE", Double.isNaN(M.headMae) ? "–" : f1(M.headMae) + "°"},
        };
        Paint k = text(24, INK2, false), v = text(26, INK, true); float y = top + 52, dy = 44;
        for (int i = 0; i < left.length; i++) { c.drawText(left[i][0], L, y + i * dy, k); c.drawText(left[i][1], L + 380, y + i * dy, v); }
        for (int i = 0; i < right.length; i++) { c.drawText(right[i][0], 800, y + i * dy, k); c.drawText(right[i][1], 800 + 380, y + i * dy, v); }
        float fy = y + 7 * dy + 20;
        if (!Double.isNaN(M.driftMedianPct)) {
            boolean ok = M.driftMedianPct < 10; Paint verdict = text(26, ok ? GOOD : BAD, true);
            c.drawText((ok ? "✓ " : "✗ ") + "Median drift " + pct(M.driftMedianPct) + (ok ? " is below" : " is above") + " the 10 % target (drift = final error ÷ distance travelled without GPS)", L, fy, verdict);
        } else c.drawText("Drift not scored yet: needs at least one test window with 100 m or more of driving.", L, fy, text(24, INK2, false));
        c.drawText("Speed scale learned (GPS ÷ AI speed): " + (Double.isNaN(M.speedScale) ? "–" : f3(M.speedScale)) + "   ·   R² of displacement: 1 = dead reckoning follows the true path exactly", L, fy + 40, text(22, INK2, false));
    }

    // ------------------------------------------------------------------ helpers
    private static Paint text(float size, int color, boolean bold) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setTextSize(size); p.setColor(color); p.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL)); return p; }
    private static Paint fill(int color) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); return p; }
    private static Paint stroke(int color, float w) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); return p; }
    private static void halo(Canvas c, String s, float x, float y, Paint p) { Paint h = new Paint(p); h.setColor(0xFFFFFFFF); h.setStyle(Paint.Style.STROKE); h.setStrokeWidth(6); c.drawText(s, x, y, h); c.drawText(s, x, y, p); }
    private static void centred(Canvas c, String s, RectF b, Paint p) { p.setTextAlign(Paint.Align.CENTER); c.drawText(s, b.centerX(), b.centerY(), p); p.setTextAlign(Paint.Align.LEFT); }
    private static double nice(double v) { if (v <= 0) return 1; double p = Math.pow(10, Math.floor(Math.log10(v))), f = v / p; return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * p; }
    private static double niceCeil(double v) { double p = Math.pow(10, Math.floor(Math.log10(v))); for (double f : new double[]{1, 2, 2.5, 4, 5, 8, 10}) if (f * p >= v) return f * p; return 10 * p; }
    private static String fmtAxis(double v) { return Math.abs(v - Math.round(v)) < 1e-6 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v); }
    private static String f0(double v) { return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.0f", v); }
    private static String f1(double v) { return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.1f", v); }
    private static String f2(double v) { return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.2f", v); }
    private static String f3(double v) { return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.3f", v); }
    private static String pct(double v) { return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.1f %%", v); }
    private static String m1(double v) { return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.1f m", v); }
    private static String kmh(double v) { return Double.isNaN(v) ? "–" : String.format(Locale.US, "%.1f km/h", v * 3.6); }
    static String dur(double s) { long t = Math.round(s); return t >= 3600 ? String.format(Locale.US, "%d h %02d min", t / 3600, (t % 3600) / 60) : String.format(Locale.US, "%d min %02d s", t / 60, t % 60); }
}
