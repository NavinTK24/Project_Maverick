package com.maverickgrid.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.maverickgrid.engine.Engine;
import com.maverickgrid.engine.Roads;

/** Offline vector map: OSM roads, GNSS track, engine track, true position during outages, and the vehicle icon. */
public class MapCanvasView extends View {
    private NavService svc;
    private double cx = 0, cy = 0, scale = 1.5;              // world centre (m) and pixels per metre
    private boolean follow = true;
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG), gnss = new Paint(Paint.ANTI_ALIAS_FLAG), eng = new Paint(Paint.ANTI_ALIAS_FLAG),
            car = new Paint(Paint.ANTI_ALIAS_FLAG), ghost = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] buf = new float[4 * 4096], trailBuf = new float[2 * 12000];
    private final float dp;
    private final ScaleGestureDetector scaler; private final GestureDetector panner;

    public MapCanvasView(Context c) {
        super(c); dp = c.getResources().getDisplayMetrics().density;
        road.setColor(Color.rgb(212, 212, 212)); road.setStrokeWidth(3 * dp); road.setStrokeCap(Paint.Cap.ROUND);
        gnss.setColor(Color.rgb(120, 120, 120)); gnss.setStrokeWidth(2 * dp); gnss.setStyle(Paint.Style.STROKE); gnss.setPathEffect(new DashPathEffect(new float[]{6 * dp, 6 * dp}, 0));
        eng.setColor(Color.rgb(42, 120, 214)); eng.setStrokeWidth(3 * dp); eng.setStyle(Paint.Style.STROKE); eng.setStrokeJoin(Paint.Join.ROUND);
        ghost.setColor(Color.rgb(26, 26, 26)); ghost.setStrokeWidth(2.5f * dp); ghost.setStyle(Paint.Style.STROKE);
        text.setColor(Color.rgb(60, 60, 60)); text.setTextSize(12 * dp);
        halo.setColor(Color.argb(60, 42, 120, 214));
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { scale = Math.max(0.02, Math.min(20, scale * d.getScaleFactor())); invalidate(); return true; }
        });
        panner = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) { follow = false; cx += dx / scale; cy -= dy / scale; invalidate(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { follow = true; invalidate(); return true; }
        });
    }

    public void attach(NavService s) { svc = s; invalidate(); }
    public void setFollow(boolean f) { follow = f; invalidate(); }

    @Override public boolean onTouchEvent(MotionEvent e) { scaler.onTouchEvent(e); panner.onTouchEvent(e); return true; }

    private float sx(double x) { return (float) (getWidth() / 2.0 + (x - cx) * scale); }
    private float sy(double y) { return (float) (getHeight() / 2.0 - (y - cy) * scale); }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.rgb(248, 248, 246));
        if (svc == null) return;
        NavService.Snapshot s = svc.snapshot();
        if (follow && s.mode != null && s.mode != Engine.Mode.WAITING_FOR_GNSS) { cx = s.x; cy = s.y; }
        // roads
        Roads r = svc.roads();
        if (r != null) {
            double hw = getWidth() / 2.0 / scale + 50, hh = getHeight() / 2.0 / scale + 50; int k = 0;
            road.setStrokeWidth((float) Math.max(1 * dp, Math.min(8 * dp, 7 * scale)));
            for (int i = 0; i < r.n; i++) {
                if (Math.max(r.ax[i], r.bx[i]) < cx - hw || Math.min(r.ax[i], r.bx[i]) > cx + hw || Math.max(r.ay[i], r.by[i]) < cy - hh || Math.min(r.ay[i], r.by[i]) > cy + hh) continue;
                buf[k++] = sx(r.ax[i]); buf[k++] = sy(r.ay[i]); buf[k++] = sx(r.bx[i]); buf[k++] = sy(r.by[i]);
                if (k == buf.length) { c.drawLines(buf, 0, k, road); k = 0; }
            }
            if (k > 0) c.drawLines(buf, 0, k, road);
        }
        drawTrail(c, svc.gnssTrail, gnss); drawTrail(c, svc.engineTrail, eng);
        // true position (hidden from the engine) during an outage
        if (s.haveGhost && s.mode == Engine.Mode.DEAD_RECKONING) { c.drawCircle(sx(s.ghostX), sy(s.ghostY), 7 * dp, ghost); }
        // vehicle icon
        if (s.mode != null && s.mode != Engine.Mode.WAITING_FOR_GNSS) {
            float px = sx(s.x), py = sy(s.y);
            boolean dr = s.mode == Engine.Mode.DEAD_RECKONING;
            car.setColor(dr ? Color.rgb(235, 104, 52) : Color.rgb(42, 120, 214)); halo.setColor(dr ? Color.argb(60, 235, 104, 52) : Color.argb(60, 42, 120, 214));
            c.drawCircle(px, py, 18 * dp, halo);
            c.save(); c.rotate((float) Math.toDegrees(s.heading), px, py);
            Path p = new Path(); p.moveTo(px, py - 14 * dp); p.lineTo(px + 9 * dp, py + 10 * dp); p.lineTo(px, py + 5 * dp); p.lineTo(px - 9 * dp, py + 10 * dp); p.close();
            c.drawPath(p, car); c.restore();
        }
        // scale bar
        double m = niceLength(100 * dp / scale); float len = (float) (m * scale), y0 = getHeight() - 16 * dp, x0 = 16 * dp;
        c.drawLine(x0, y0, x0 + len, y0, text); c.drawLine(x0, y0 - 5 * dp, x0, y0, text); c.drawLine(x0 + len, y0 - 5 * dp, x0 + len, y0, text);
        c.drawText(m >= 1000 ? String.format(java.util.Locale.US, "%.0f km", m / 1000) : String.format(java.util.Locale.US, "%.0f m", m), x0, y0 - 8 * dp, text);
        if (!follow) c.drawText("double-tap to follow", getWidth() - 150 * dp, y0, text);
    }

    private void drawTrail(Canvas c, Trail t, Paint p) {
        if (trailBuf.length < 2 * t.capacity()) trailBuf = new float[2 * t.capacity()];
        int n = t.copy(trailBuf); if (n < 2) return;
        Path path = new Path(); path.moveTo(sx(trailBuf[0]), sy(trailBuf[1]));
        for (int i = 1; i < n; i++) path.lineTo(sx(trailBuf[2 * i]), sy(trailBuf[2 * i + 1]));
        c.drawPath(path, p);
    }

    private static double niceLength(double m) { double p = Math.pow(10, Math.floor(Math.log10(m))); double f = m / p; return (f < 2 ? 1 : f < 5 ? 2 : 5) * p; }
}
