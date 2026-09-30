package com.maverickgrid.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Vector icons drawn in code (no image resources). */
final class Icons extends View {
    static final int MENU = 1, SEARCH = 2, CLOSE = 3, LOCATE = 4, DIRECTIONS = 5, TURN = 6, BACK = 7;
    private int kind; private int color; private int turn; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path();

    Icons(Context c, int kind, int color) { super(c); this.kind = kind; this.color = color; p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
    void setTurn(int t) { if (t != turn) { turn = t; invalidate(); } }
    void setColor(int c) { if (c != color) { color = c; invalidate(); } }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h), cx = w / 2, cy = h / 2, u = s / 24f;
        p.setColor(color); p.setStrokeWidth(2.2f * u); p.setStyle(Paint.Style.STROKE); path.rewind();
        switch (kind) {
            case MENU: for (int i = -1; i <= 1; i++) c.drawLine(cx - 8 * u, cy + i * 6 * u, cx + 8 * u, cy + i * 6 * u, p); break;
            case SEARCH: c.drawCircle(cx - 2 * u, cy - 2 * u, 6.5f * u, p); c.drawLine(cx + 2.8f * u, cy + 2.8f * u, cx + 8 * u, cy + 8 * u, p); break;
            case CLOSE: c.drawLine(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u, p); c.drawLine(cx + 6 * u, cy - 6 * u, cx - 6 * u, cy + 6 * u, p); break;
            case BACK: c.drawLine(cx + 6 * u, cy, cx - 7 * u, cy, p); c.drawLine(cx - 7 * u, cy, cx - 1 * u, cy - 6 * u, p); c.drawLine(cx - 7 * u, cy, cx - 1 * u, cy + 6 * u, p); break;
            case LOCATE:
                c.drawCircle(cx, cy, 6.5f * u, p); p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, 3 * u, p); p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx, cy - 10 * u, cx, cy - 6.5f * u, p); c.drawLine(cx, cy + 6.5f * u, cx, cy + 10 * u, p); c.drawLine(cx - 10 * u, cy, cx - 6.5f * u, cy, p); c.drawLine(cx + 6.5f * u, cy, cx + 10 * u, cy, p); break;
            case DIRECTIONS:
                p.setStyle(Paint.Style.FILL); path.moveTo(cx, cy - 10 * u); path.lineTo(cx + 10 * u, cy); path.lineTo(cx, cy + 10 * u); path.lineTo(cx - 10 * u, cy); path.close(); c.drawPath(path, p);
                p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f * u); path.rewind();
                path.moveTo(cx - 3.5f * u, cy + 3.5f * u); path.lineTo(cx - 3.5f * u, cy - 1 * u); path.lineTo(cx + 3 * u, cy - 1 * u); c.drawPath(path, p);
                c.drawLine(cx + 3 * u, cy - 1 * u, cx + 0.5f * u, cy - 3.5f * u, p); c.drawLine(cx + 3 * u, cy - 1 * u, cx + 0.5f * u, cy + 1.5f * u, p); break;
            case TURN: drawTurn(c, turn, cx, cy, u); break;
        }
    }

    /** Turn arrow: -3..3 (sharp left .. sharp right), 0 straight, 9 arrive (flag). */
    private void drawTurn(Canvas c, int t, float cx, float cy, float u) {
        p.setStrokeWidth(2.6f * u);
        if (t == 9) { c.drawLine(cx - 4 * u, cy + 9 * u, cx - 4 * u, cy - 9 * u, p); p.setStyle(Paint.Style.FILL); path.moveTo(cx - 4 * u, cy - 9 * u); path.lineTo(cx + 7 * u, cy - 5.5f * u); path.lineTo(cx - 4 * u, cy - 2 * u); path.close(); c.drawPath(path, p); return; }
        double ang = t == 0 ? 0 : Math.signum(t) * (Math.abs(t) == 1 ? 40 : Math.abs(t) == 2 ? 90 : 140);
        float bx = cx, by = cy + 2 * u; path.moveTo(cx, cy + 10 * u); path.lineTo(bx, by);
        double a = Math.toRadians(ang); float ex = (float) (bx + Math.sin(a) * 9 * u), ey = (float) (by - Math.cos(a) * 9 * u);
        path.lineTo(ex, ey); c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL); path.rewind();
        float hx = (float) Math.sin(a), hy = (float) -Math.cos(a), nx = -hy, ny = hx;
        path.moveTo(ex + hx * 4 * u, ey + hy * 4 * u); path.lineTo(ex + nx * 4.5f * u, ey + ny * 4.5f * u); path.lineTo(ex - nx * 4.5f * u, ey - ny * 4.5f * u); path.close(); c.drawPath(path, p);
    }
}
