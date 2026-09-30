package com.maverickgrid.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.maverickgrid.engine.Engine;
import com.maverickgrid.engine.Roads;
import com.maverickgrid.engine.Router;

/** Offline vector map: OSM roads, places, route, GNSS track, engine track, true position during outages, vehicle icon. */
public class MapCanvasView extends View {
    public interface PickListener { void onTap(double x, double y, double tolM); void onLongPress(double x, double y); }

    private NavService svc; private PickListener pick;
    private double cx = 0, cy = 0, scale = 1.5;              // world centre (m) and pixels per metre
    private boolean follow = true;
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG), major = new Paint(Paint.ANTI_ALIAS_FLAG), gnss = new Paint(Paint.ANTI_ALIAS_FLAG), eng = new Paint(Paint.ANTI_ALIAS_FLAG),
            car = new Paint(Paint.ANTI_ALIAS_FLAG), ghost = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG),
            routeP = new Paint(Paint.ANTI_ALIAS_FLAG), routeEdge = new Paint(Paint.ANTI_ALIAS_FLAG), place = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), pin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] buf = new float[4 * 4096], trailBuf = new float[2 * 12000], lineBuf = new float[4 * 12000];
    private final Path icon = new Path();
    private final float dp;
    private final ScaleGestureDetector scaler; private final GestureDetector gestures;
    // draw index: segments per 400 m tile, all roads and major roads (class <= 3)
    private Roads indexed; private int[][] tileAll, tileMajor; private int tx0, ty0, tnx, tny; private boolean hasMajor; private static final double TILE = 400;
    private int[] tileStamp = new int[0]; private int stampId = 0;
    private double pinX = Double.NaN, pinY = Double.NaN;
    private static final int[] PLACE_COLORS = {0xFF888888, 0xFFE8883A, 0xFF7A5CC8, 0xFFD64545, 0xFF3A9A6A, 0xFF3A78C8, 0xFF2A9AB0, 0xFF555555, 0xFF8A6A3A};

    public MapCanvasView(Context c) {
        super(c); dp = c.getResources().getDisplayMetrics().density;
        road.setColor(Color.rgb(214, 214, 214)); road.setStrokeCap(Paint.Cap.ROUND);
        major.setColor(Color.rgb(190, 190, 190)); major.setStrokeCap(Paint.Cap.ROUND);
        gnss.setColor(Color.rgb(140, 140, 140)); gnss.setStrokeWidth(2 * dp);
        eng.setColor(Color.rgb(42, 120, 214)); eng.setStrokeWidth(3 * dp); eng.setStrokeCap(Paint.Cap.ROUND);
        ghost.setColor(Color.rgb(26, 26, 26)); ghost.setStrokeWidth(2.5f * dp); ghost.setStyle(Paint.Style.STROKE);
        text.setColor(Color.rgb(60, 60, 60)); text.setTextSize(12 * dp); text.setStrokeWidth(1.5f * dp);
        routeP.setColor(Color.rgb(46, 160, 90)); routeP.setStrokeWidth(6 * dp); routeP.setStrokeCap(Paint.Cap.ROUND);
        routeEdge.setColor(Color.rgb(28, 110, 60)); routeEdge.setStrokeWidth(8 * dp); routeEdge.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(Color.rgb(50, 50, 50)); label.setTextSize(11 * dp);
        pin.setColor(Color.rgb(214, 60, 60));
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { scale = Math.max(0.01, Math.min(20, scale * d.getScaleFactor())); invalidate(); return true; }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) { follow = false; cx += dx / scale; cy -= dy / scale; invalidate(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { follow = true; invalidate(); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (pick != null) pick.onTap(wx(e.getX()), wy(e.getY()), 24 * dp / scale); return true; }
            @Override public void onLongPress(MotionEvent e) { if (pick != null) pick.onLongPress(wx(e.getX()), wy(e.getY())); }
        });
    }

    public void attach(NavService s) { svc = s; invalidate(); }
    public void setPickListener(PickListener p) { pick = p; }
    public void setFollow(boolean f) { follow = f; invalidate(); }
    public void setPin(double x, double y) { pinX = x; pinY = y; invalidate(); }
    public void clearPin() { pinX = Double.NaN; invalidate(); }
    public double[] centre() { return new double[]{cx, cy}; }
    public void centreOn(double x, double y) { follow = false; cx = x; cy = y; if (scale < 0.8) scale = 1.5; invalidate(); }

    @Override public boolean onTouchEvent(MotionEvent e) { scaler.onTouchEvent(e); gestures.onTouchEvent(e); return true; }

    private float sx(double x) { return (float) (getWidth() / 2.0 + (x - cx) * scale); }
    private float sy(double y) { return (float) (getHeight() / 2.0 - (y - cy) * scale); }
    private double wx(float px) { return cx + (px - getWidth() / 2.0) / scale; }
    private double wy(float py) { return cy - (py - getHeight() / 2.0) / scale; }

    private void buildIndex(Roads r) {
        double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE, mny = Double.MAX_VALUE, mxy = -Double.MAX_VALUE;
        for (int i = 0; i < r.n; i++) { mnx = Math.min(mnx, Math.min(r.ax[i], r.bx[i])); mxx = Math.max(mxx, Math.max(r.ax[i], r.bx[i])); mny = Math.min(mny, Math.min(r.ay[i], r.by[i])); mxy = Math.max(mxy, Math.max(r.ay[i], r.by[i])); }
        tx0 = (int) Math.floor(mnx / TILE); ty0 = (int) Math.floor(mny / TILE); tnx = (int) Math.floor(mxx / TILE) - tx0 + 1; tny = (int) Math.floor(mxy / TILE) - ty0 + 1;
        int[] ca = new int[tnx * tny], cm = new int[tnx * tny];
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) { tileAll = new int[tnx * tny][]; tileMajor = new int[tnx * tny][]; for (int t = 0; t < ca.length; t++) { tileAll[t] = new int[ca[t]]; tileMajor[t] = new int[cm[t]]; } java.util.Arrays.fill(ca, 0); java.util.Arrays.fill(cm, 0); }
            for (int i = 0; i < r.n; i++) {
                int x0 = (int) Math.floor(Math.min(r.ax[i], r.bx[i]) / TILE) - tx0, x1 = (int) Math.floor(Math.max(r.ax[i], r.bx[i]) / TILE) - tx0;
                int y0 = (int) Math.floor(Math.min(r.ay[i], r.by[i]) / TILE) - ty0, y1 = (int) Math.floor(Math.max(r.ay[i], r.by[i]) / TILE) - ty0;
                boolean mj = r.roadClass[i] <= 3;
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) {
                    int t = y * tnx + x;
                    if (pass == 1) { tileAll[t][ca[t]] = i; if (mj) tileMajor[t][cm[t]] = i; }
                    ca[t]++; if (mj) cm[t]++;
                }
            }
        }
        hasMajor = false; for (int i = 0; i < r.n && !hasMajor; i++) hasMajor = r.roadClass[i] <= 3;
        tileStamp = new int[r.n]; stampId = 0; indexed = r;
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.rgb(248, 248, 246));
        if (svc == null) return;
        NavService.Snapshot s = svc.snapshot();
        Engine.Mode mode = s.mode;
        if (follow && mode != null && mode != Engine.Mode.WAITING_FOR_GNSS) { cx = s.x; cy = s.y; }
        double hw = getWidth() / 2.0 / scale + 50, hh = getHeight() / 2.0 / scale + 50;
        Roads r = svc.roads();
        if (r != null) {
            if (r != indexed) buildIndex(r);
            boolean majorOnly = scale < 0.25 && hasMajor;                         // wider than ~4 km on screen (old maps have no classes)
            float wAll = (float) Math.max(1 * dp, Math.min(8 * dp, 7 * scale)); road.setStrokeWidth(wAll); major.setStrokeWidth(wAll * 1.4f);
            if (++stampId == Integer.MAX_VALUE) { java.util.Arrays.fill(tileStamp, 0); stampId = 1; }
            int x0 = Math.max(0, (int) Math.floor((cx - hw) / TILE) - tx0), x1 = Math.min(tnx - 1, (int) Math.floor((cx + hw) / TILE) - tx0);
            int y0 = Math.max(0, (int) Math.floor((cy - hh) / TILE) - ty0), y1 = Math.min(tny - 1, (int) Math.floor((cy + hh) / TILE) - ty0);
            int k = 0;
            for (int ty = y0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++) {
                int[] segs = (majorOnly ? tileMajor : tileAll)[ty * tnx + tx];
                for (int i : segs) {
                    if (tileStamp[i] == stampId) continue; tileStamp[i] = stampId;
                    buf[k++] = sx(r.ax[i]); buf[k++] = sy(r.ay[i]); buf[k++] = sx(r.bx[i]); buf[k++] = sy(r.by[i]);
                    if (k == buf.length) { c.drawLines(buf, 0, k, majorOnly ? major : road); k = 0; }
                }
            }
            if (k > 0) c.drawLines(buf, 0, k, majorOnly ? major : road);
            // places: dots when zoomed in, names when closer
            if (scale > 0.6 && r.np > 0) {
                float rad = 4 * dp;
                for (int p = 0; p < r.np; p++) {
                    if (Math.abs(r.px[p] - cx) > hw || Math.abs(r.py[p] - cy) > hh) continue;
                    place.setColor(PLACE_COLORS[Math.max(0, Math.min(8, r.pkind[p]))]); float px = sx(r.px[p]), py = sy(r.py[p]);
                    c.drawCircle(px, py, rad, place);
                    if (scale > 2.0) { String nm = r.placeName(p); if (nm != null) c.drawText(nm, px + 6 * dp, py + 4 * dp, label); }
                }
            }
        }
        // route
        Router.Route rt = svc.route();
        if (rt != null) { int n = rt.xs.length; float[] lb = lines(rt.xs, rt.ys, n); c.drawLines(lb, 0, 4 * (n - 1), routeEdge); c.drawLines(lb, 0, 4 * (n - 1), routeP); }
        drawTrail(c, svc.gnssTrail, gnss); drawTrail(c, svc.engineTrail, eng);
        if (s.haveGhost && mode == Engine.Mode.DEAD_RECKONING) c.drawCircle(sx(s.ghostX), sy(s.ghostY), 7 * dp, ghost);
        if (!Double.isNaN(pinX)) { float px = sx(pinX), py = sy(pinY); c.drawCircle(px, py - 14 * dp, 7 * dp, pin); c.drawLine(px, py - 8 * dp, px, py, text); }
        if (mode != null && mode != Engine.Mode.WAITING_FOR_GNSS) {
            float px = sx(s.x), py = sy(s.y); boolean dr = mode == Engine.Mode.DEAD_RECKONING;
            car.setColor(dr ? Color.rgb(235, 104, 52) : Color.rgb(42, 120, 214)); halo.setColor(dr ? Color.argb(60, 235, 104, 52) : Color.argb(60, 42, 120, 214));
            c.drawCircle(px, py, 18 * dp, halo);
            c.save(); c.rotate((float) Math.toDegrees(s.heading), px, py);
            icon.rewind(); icon.moveTo(px, py - 14 * dp); icon.lineTo(px + 9 * dp, py + 10 * dp); icon.lineTo(px, py + 5 * dp); icon.lineTo(px - 9 * dp, py + 10 * dp); icon.close();
            c.drawPath(icon, car); c.restore();
        }
        double m = niceLength(100 * dp / scale); float len = (float) (m * scale), yb = getHeight() - 16 * dp, xb = 16 * dp;
        c.drawLine(xb, yb, xb + len, yb, text); c.drawLine(xb, yb - 5 * dp, xb, yb, text); c.drawLine(xb + len, yb - 5 * dp, xb + len, yb, text);
        c.drawText(m >= 1000 ? String.format(java.util.Locale.US, "%.0f km", m / 1000) : String.format(java.util.Locale.US, "%.0f m", m), xb, yb - 8 * dp, text);
        if (!follow && mode != null) c.drawText("double-tap to follow", getWidth() - 150 * dp, yb, text);
    }

    private float[] lines(double[] xs, double[] ys, int n) {
        if (lineBuf.length < 4 * n) lineBuf = new float[4 * n];
        for (int i = 0; i < n - 1; i++) { lineBuf[4 * i] = sx(xs[i]); lineBuf[4 * i + 1] = sy(ys[i]); lineBuf[4 * i + 2] = sx(xs[i + 1]); lineBuf[4 * i + 3] = sy(ys[i + 1]); }
        return lineBuf;
    }

    private void drawTrail(Canvas c, Trail t, Paint p) {
        if (trailBuf.length < 2 * t.capacity()) trailBuf = new float[2 * t.capacity()];
        int n = t.copy(trailBuf); if (n < 2) return;
        if (lineBuf.length < 4 * n) lineBuf = new float[4 * n];
        for (int i = 0; i < n - 1; i++) { lineBuf[4 * i] = sx(trailBuf[2 * i]); lineBuf[4 * i + 1] = sy(trailBuf[2 * i + 1]); lineBuf[4 * i + 2] = sx(trailBuf[2 * i + 2]); lineBuf[4 * i + 3] = sy(trailBuf[2 * i + 3]); }
        c.drawLines(lineBuf, 0, 4 * (n - 1), p);
    }

    private static double niceLength(double m) { double p = Math.pow(10, Math.floor(Math.log10(m))); double f = m / p; return (f < 2 ? 1 : f < 5 ? 2 : 5) * p; }
}
