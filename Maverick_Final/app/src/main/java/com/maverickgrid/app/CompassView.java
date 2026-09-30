package com.maverickgrid.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import com.maverickgrid.engine.Fmt;

/** Round compass: the rose's N points to north on the map (it turns when the map turns); the label shows the phone's heading in degrees and 8 directions. Tap = north up. */
public class CompassView extends View {
    private double headingDeg = Double.NaN, mapBearing = 0; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path();

    public CompassView(Context c) {
        super(c); setElevation(Ui.dp(c, 4)); setBackground(Ui.round(Ui.SURFACE, Ui.dp(c, 32)));
        t.setTextAlign(Paint.Align.CENTER); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    /** Degrees clockwise from true north, or NaN if unknown. */
    /** Compass direction at the top of the map (0 = north up). */
    public void setMapBearing(double deg) { if (Math.abs(((deg - mapBearing + 540) % 360) - 180) > 0.5) { mapBearing = deg; invalidate(); } }

    public void setHeading(double deg) { if (Double.isNaN(deg) != Double.isNaN(headingDeg) || Math.abs(deg - headingDeg) > 0.5) { headingDeg = deg; invalidate(); } }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h * 0.42f, r = Math.min(w, h) * 0.30f, dp = Ui.dp(getContext(), 1);
        boolean known = !Double.isNaN(headingDeg);
        c.save(); c.rotate((float) -mapBearing, cx, cy);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f * dp); p.setColor(Ui.DIVIDER); c.drawCircle(cx, cy, r, p);
        for (int i = 0; i < 8; i++) { double a = Math.toRadians(i * 45); float in = i % 2 == 0 ? r * 0.78f : r * 0.86f;
            p.setColor(Ui.HINT); c.drawLine(cx + (float) Math.sin(a) * in, cy - (float) Math.cos(a) * in, cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, p); }
        p.setStyle(Paint.Style.FILL); path.rewind(); p.setColor(Ui.RED);                       // north half of the needle
        path.moveTo(cx, cy - r * 0.72f); path.lineTo(cx + r * 0.2f, cy); path.lineTo(cx - r * 0.2f, cy); path.close(); c.drawPath(path, p);
        path.rewind(); p.setColor(0xFFBDC1C6); path.moveTo(cx, cy + r * 0.72f); path.lineTo(cx + r * 0.2f, cy); path.lineTo(cx - r * 0.2f, cy); path.close(); c.drawPath(path, p);
        t.setTextSize(8.5f * dp); t.setColor(Ui.RED); c.drawText("N", cx, cy - r - 2 * dp, t);
        c.restore();
        t.setTextSize(10.5f * dp); t.setColor(Ui.TEXT);
        c.drawText(known ? Fmt.cardinal(headingDeg) + " " + (Math.round(((headingDeg % 360) + 360) % 360) % 360) + "°" : "--", cx, h - 7 * dp, t);
    }
}
