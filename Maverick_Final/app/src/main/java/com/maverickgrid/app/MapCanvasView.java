package com.maverickgrid.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.maverickgrid.engine.Engine;
import com.maverickgrid.engine.PlaceSearch;
import com.maverickgrid.engine.Roads;
import com.maverickgrid.engine.Router;

/** Offline vector map in a Google-Maps-like style: land, parks, water, buildings, roads by class, places, route, location dot with heading beam. */
public class MapCanvasView extends View {
    public interface PickListener { void onTap(double x, double y, double tolM); void onLongPress(double x, double y); void onUserMoved(); default void onOrientationChanged() { } }
    /** NORTH: north up. HEADING: the map turns so the way you face / drive is up (navigation, compass mode). FREE: turned by the user with two fingers. */
    public enum Orient { NORTH, HEADING, FREE }

    private NavService svc; private PickListener pick;
    private double cx = 0, cy = 0, scale = 1.2;                   // world centre (m), pixels per metre
    private boolean follow = true; private double beamDeg = Double.NaN; private int bottomInset = 0, topInset = 0; private boolean navMode;
    private final float dp;
    private final Paint land = new Paint(), green = new Paint(Paint.ANTI_ALIAS_FLAG), water = new Paint(Paint.ANTI_ALIAS_FLAG), bld = new Paint(Paint.ANTI_ALIAS_FLAG), bldEdge = new Paint(Paint.ANTI_ALIAS_FLAG),
            caseMinor = new Paint(Paint.ANTI_ALIAS_FLAG), fillMinor = new Paint(Paint.ANTI_ALIAS_FLAG), caseMajor = new Paint(Paint.ANTI_ALIAS_FLAG), fillMajor = new Paint(Paint.ANTI_ALIAS_FLAG),
            caseHwy = new Paint(Paint.ANTI_ALIAS_FLAG), fillHwy = new Paint(Paint.ANTI_ALIAS_FLAG), routeCase = new Paint(Paint.ANTI_ALIAS_FLAG), routeFill = new Paint(Paint.ANTI_ALIAS_FLAG),
            trail = new Paint(Paint.ANTI_ALIAS_FLAG), gnss = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), beam = new Paint(Paint.ANTI_ALIAS_FLAG),
            halo = new Paint(Paint.ANTI_ALIAS_FLAG), haloEdge = new Paint(Paint.ANTI_ALIAS_FLAG), place = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), labelHalo = new Paint(Paint.ANTI_ALIAS_FLAG),
            glyph = new Paint(Paint.ANTI_ALIAS_FLAG), pin = new Paint(Paint.ANTI_ALIAS_FLAG), ghost = new Paint(Paint.ANTI_ALIAS_FLAG), scaleP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] bMinor = new float[4 * 4096], bMajor = new float[4 * 2048], bHwy = new float[4 * 1024], trailBuf = new float[2 * 12000], lineBuf = new float[4 * 12000];
    private int nMinor, nMajor, nHwy;
    private final Path path = new Path(); private final RectF oval = new RectF();
    private final ScaleGestureDetector scaler; private final GestureDetector gestures;
    // spatial index for drawing (400 m tiles)
    private Roads indexed; private int[][] tileAll, tileMajor, tileArea; private int[] bigAreas = new int[0]; private int tx0, ty0, tnx, tny; private boolean hasMajor; private static final double TILE = 400;
    private int[] segStamp = new int[0]; private int stampId = 0;
    private double pinX = Double.NaN, pinY = Double.NaN;
    // map rotation: bearing = compass direction shown at the top of the screen (degrees clockwise from north)
    private Orient orient = Orient.NORTH; private double bearing = 0, cosB = 1, sinB = 0, vcx, vcy;
    private boolean rotating; private double rotStartAngle, rotStartBearing;
    // phone position when no drive is running (last known / live location fix), drawn as a dot with the compass beam
    private double meLat = Double.NaN, meLon = Double.NaN, meAcc = 30; private com.maverickgrid.engine.Geo drawnGeo;

    public MapCanvasView(Context c) {
        super(c); dp = Ui.dp(c, 1);
        land.setColor(Ui.M_LAND); green.setColor(Ui.M_GREEN); water.setColor(Ui.M_WATER); bld.setColor(Ui.M_BLD); bldEdge.setColor(Ui.M_BLD_EDGE); bldEdge.setStyle(Paint.Style.STROKE); bldEdge.setStrokeWidth(0.8f * dp);
        for (Paint p : new Paint[]{caseMinor, fillMinor, caseMajor, fillMajor, caseHwy, fillHwy, routeCase, routeFill, trail}) { p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
        caseMinor.setColor(Ui.M_MINOR_CASE); fillMinor.setColor(Ui.M_MINOR); caseMajor.setColor(Ui.M_MAJOR_CASE); fillMajor.setColor(Ui.M_MAJOR); caseHwy.setColor(Ui.M_HWY_CASE); fillHwy.setColor(Ui.M_HWY);
        routeCase.setColor(Ui.BLUE_DARK); routeFill.setColor(Ui.BLUE); trail.setColor(Ui.withAlpha(Ui.BLUE, 110)); trail.setStrokeWidth(2.5f * dp);
        gnss.setColor(Ui.M_GNSS); gnss.setStrokeWidth(1.8f * dp); gnss.setPathEffect(new DashPathEffect(new float[]{5 * dp, 5 * dp}, 0));
        ring.setColor(0xFFFFFFFF); ring.setShadowLayer(3 * dp, 0, 1 * dp, 0x55000000);
        haloEdge.setStyle(Paint.Style.STROKE); haloEdge.setStrokeWidth(1.2f * dp);
        label.setColor(Ui.M_LABEL); label.setTextSize(11.5f * dp); labelHalo.set(label); labelHalo.setColor(Ui.M_HALO); labelHalo.setStyle(Paint.Style.STROKE); labelHalo.setStrokeWidth(3 * dp);
        glyph.setTextAlign(Paint.Align.CENTER); glyph.setTextSize(9 * dp); glyph.setColor(0xFFFFFFFF);
        pin.setColor(Ui.RED); ghost.setStyle(Paint.Style.STROKE); ghost.setStrokeWidth(2.5f * dp); ghost.setColor(Ui.M_GHOST);
        scaleP.setColor(Ui.TEXT2); scaleP.setStrokeWidth(1.5f * dp); scaleP.setTextSize(11 * dp);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { scale = Math.max(0.01, Math.min(25, scale * d.getScaleFactor())); invalidate(); return true; }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e2.getPointerCount() > 1 || scaler.isInProgress() || rotating) return true;   // pinch / twist: keep following the car
                if (follow && pick != null) pick.onUserMoved(); follow = false;
                double u = dx, v = -dy; cx += (u * cosB + v * sinB) / scale; cy += (-u * sinB + v * cosB) / scale; invalidate(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { scale = Math.min(25, scale * 2); invalidate(); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (pick != null) pick.onTap(wx(e.getX(), e.getY()), wy(e.getX(), e.getY()), scale > 1.2 ? 24 * dp / scale : 0); return true; }
            @Override public void onLongPress(MotionEvent e) { if (pick != null) pick.onLongPress(wx(e.getX(), e.getY()), wy(e.getX(), e.getY())); }
        });
    }

    public void attach(NavService s) { svc = s; invalidate(); }
    public void setPickListener(PickListener p) { pick = p; }
    public void setFollow(boolean f) { follow = f; invalidate(); }
    public boolean following() { return follow; }
    public void setPin(double x, double y) { pinX = x; pinY = y; invalidate(); }
    public void setMe(double lat, double lon, double accM) { meLat = lat; meLon = lon; meAcc = Double.isNaN(accM) ? 30 : accM; invalidate(); }
    public boolean hasMe() { return !Double.isNaN(meLat); }
    public double[] me() { return new double[]{meLat, meLon}; }
    public void clearPin() { pinX = Double.NaN; invalidate(); }
    public double[] centre() { return new double[]{cx, cy}; }
    public void centreOn(double x, double y) { follow = false; cx = x; cy = y; if (scale < 1.0) scale = 1.6; invalidate(); }
    public void setInsets(int top, int bottom) { topInset = top; bottomInset = bottom; invalidate(); }
    public void setNavMode(boolean on) { if (on != navMode) { navMode = on; if (on && scale < 1.5) scale = 2.2; invalidate(); } }
    /** Direction the beam points (degrees clockwise from north), NaN = hide beam. Returns true if it changed visibly. */
    public boolean setBeam(double deg) {
        boolean changed = Double.isNaN(deg) != Double.isNaN(beamDeg) || (!Double.isNaN(deg) && Math.abs(((deg - beamDeg + 540) % 360) - 180) > 1.5);
        if (changed) beamDeg = deg; return changed;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e); gestures.onTouchEvent(e); rotateGesture(e); return true;
    }

    /** Two-finger twist turns the map (after 12° so that pinch-zoom does not rotate by accident). */
    private void rotateGesture(MotionEvent e) {
        int a = e.getActionMasked();
        if (e.getPointerCount() < 2 || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { rotating = false; return; }
        double ang = Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
        if (a == MotionEvent.ACTION_POINTER_DOWN) { rotating = false; rotStartAngle = ang; rotStartBearing = bearing; return; }
        if (a != MotionEvent.ACTION_MOVE) return;
        double d = norm180(ang - rotStartAngle);
        if (!rotating) { if (Math.abs(d) < 12) return; rotating = true; rotStartAngle = ang; rotStartBearing = bearing; d = 0; if (orient != Orient.FREE) { orient = Orient.FREE; if (pick != null) pick.onOrientationChanged(); } }
        bearing = norm360(rotStartBearing - d); invalidate();
    }
    private static double norm180(double d) { return ((d % 360) + 540) % 360 - 180; }
    private static double norm360(double d) { return ((d % 360) + 360) % 360; }

    public void setOrientation(Orient o) { if (o != orient) { orient = o; if (pick != null) pick.onOrientationChanged(); invalidate(); } }
    public Orient orientation() { return orient; }
    /** Compass direction currently at the top of the screen. */
    public double bearing() { return bearing; }

    private float midY() { return (float) (topInset + (getHeight() - topInset - bottomInset) / 2.0); }
    private float sx(double x) { return (float) (getWidth() / 2.0 + (x - cx) * scale); }
    private float sy(double y) { return (float) (midY() - (y - cy) * scale); }
    // world <-> screen with the map rotation (sx/sy above are the un-rotated form, used inside the rotated canvas)
    private float rx(double x, double y) { double dx = (x - cx) * scale, dy = (y - cy) * scale; return (float) (getWidth() / 2.0 + dx * cosB - dy * sinB); }
    private float ry(double x, double y) { double dx = (x - cx) * scale, dy = (y - cy) * scale; return (float) (midY() - (dx * sinB + dy * cosB)); }
    private double wx(float px, float py) { double u = px - getWidth() / 2.0, v = midY() - py; return cx + (u * cosB + v * sinB) / scale; }
    private double wy(float px, float py) { double u = px - getWidth() / 2.0, v = midY() - py; return cy + (-u * sinB + v * cosB) / scale; }

    /** The tile index of a large map takes ~1 s: build it on a background thread and swap it in on the UI thread. */
    private Roads indexing;
    private void requestIndex(Roads r) {
        if (indexing == r) return; indexing = r;
        new Thread(() -> {
            final Object[] res;
            try { res = computeIndex(r); } catch (Throwable t) { post(() -> { if (indexing == r) indexing = null; }); return; }
            post(() -> { if (indexing != r) return; applyIndex(r, res); indexing = null; invalidate(); });
        }, "map-index").start();
    }

    private void applyIndex(Roads r, Object[] o) {
        tileAll = (int[][]) o[0]; tileMajor = (int[][]) o[1]; tileArea = (int[][]) o[2]; bigAreas = (int[]) o[3];
        int[] g = (int[]) o[4]; tx0 = g[0]; ty0 = g[1]; tnx = g[2]; tny = g[3]; hasMajor = (Boolean) o[5];
        segStamp = new int[r.n]; stampId = 0; indexed = r; areaCache.clear();
    }

    private static Object[] computeIndex(Roads r) {
        int tx0, ty0, tnx, tny; int[][] tileAll = null, tileMajor = null, tileArea = null;
        double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE, mny = Double.MAX_VALUE, mxy = -Double.MAX_VALUE;
        for (int i = 0; i < r.n; i++) { mnx = Math.min(mnx, Math.min(r.ax[i], r.bx[i])); mxx = Math.max(mxx, Math.max(r.ax[i], r.bx[i])); mny = Math.min(mny, Math.min(r.ay[i], r.by[i])); mxy = Math.max(mxy, Math.max(r.ay[i], r.by[i])); }
        for (int i = 0; i < r.na; i++) { mnx = Math.min(mnx, r.aMinX[i]); mxx = Math.max(mxx, r.aMaxX[i]); mny = Math.min(mny, r.aMinY[i]); mxy = Math.max(mxy, r.aMaxY[i]); }
        if (mnx > mxx) { mnx = mny = 0; mxx = mxy = 1; }
        tx0 = (int) Math.floor(mnx / TILE); ty0 = (int) Math.floor(mny / TILE); tnx = (int) Math.floor(mxx / TILE) - tx0 + 1; tny = (int) Math.floor(mxy / TILE) - ty0 + 1;
        int T = tnx * tny; int[] ca = new int[T], cm = new int[T], cr = new int[T]; java.util.ArrayList<Integer> big = new java.util.ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) { tileAll = new int[T][]; tileMajor = new int[T][]; tileArea = new int[T][]; for (int t = 0; t < T; t++) { tileAll[t] = new int[ca[t]]; tileMajor[t] = new int[cm[t]]; tileArea[t] = new int[cr[t]]; } java.util.Arrays.fill(ca, 0); java.util.Arrays.fill(cm, 0); java.util.Arrays.fill(cr, 0); }
            for (int i = 0; i < r.n; i++) {
                int x0 = tileOf(Math.min(r.ax[i], r.bx[i]), tx0), x1 = tileOf(Math.max(r.ax[i], r.bx[i]), tx0), y0 = tileOf(Math.min(r.ay[i], r.by[i]), ty0), y1 = tileOf(Math.max(r.ay[i], r.by[i]), ty0);
                boolean mj = r.roadClass[i] <= 3;
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) { int t = y * tnx + x; if (pass == 1) { tileAll[t][ca[t]] = i; if (mj) tileMajor[t][cm[t]] = i; } ca[t]++; if (mj) cm[t]++; }
            }
            for (int i = 0; i < r.na; i++) {
                if (r.aMaxX[i] - r.aMinX[i] > TILE || r.aMaxY[i] - r.aMinY[i] > TILE) { if (pass == 0) big.add(i); continue; }   // large: drawn individually
                int t = tileOf((r.aMinX[i] + r.aMaxX[i]) / 2, tx0) + tileOf((r.aMinY[i] + r.aMaxY[i]) / 2, ty0) * tnx;
                if (t >= 0 && t < T) { if (pass == 1) tileArea[t][cr[t]] = i; cr[t]++; }
            }
        }
        int[] bigAreas = new int[big.size()]; for (int i = 0; i < bigAreas.length; i++) bigAreas[i] = big.get(i);
        boolean hasMajor = false; for (int i = 0; i < r.n && !hasMajor; i++) hasMajor = r.roadClass[i] <= 3;
        return new Object[]{tileAll, tileMajor, tileArea, bigAreas, new int[]{tx0, ty0, tnx, tny}, hasMajor};
    }
    private static int tileOf(double v, int origin) { return Math.max(0, (int) Math.floor(v / TILE) - origin); }

    /** Per-tile area outlines (parks, water, buildings) as three Paths in metres relative to the tile corner; built once, LRU-cached. */
    private final java.util.LinkedHashMap<Integer, Path[]> areaCache = new java.util.LinkedHashMap<Integer, Path[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Path[]> e) { return size() > 400; }
    };
    /** Layer path of one tile (0 parks, 1 water, 2 buildings), built on first use. */
    private Path tilePath(Roads r, int t, int layer) {
        Path[] ps = areaCache.get(t); if (ps == null) { ps = new Path[3]; areaCache.put(t, ps); }
        if (ps[layer] != null) return ps[layer];
        Path p = new Path(); double ox = (tx0 + t % tnx) * TILE, oy = (ty0 + t / tnx) * TILE; int want = layer == 0 ? 3 : layer == 1 ? 2 : 1;
        for (int i : tileArea[t]) {
            if (r.areaKind[i] != want) continue; int s0 = r.areaStart[i], s1 = r.areaStart[i + 1];
            p.moveTo((float) (r.vx[s0] - ox), (float) (r.vy[s0] - oy)); for (int j = s0 + 1; j < s1; j++) p.lineTo((float) (r.vx[j] - ox), (float) (r.vy[j] - oy)); p.close();
        }
        ps[layer] = p; return p;
    }

    // drawing cost (for the diagnostics dialog)
    private double drawMs = 0; private int frames = 0; private long fpsT0 = 0; private double fps = 0;
    public double drawMsAvg() { return drawMs; }
    public double fps() { return fps; }

    @Override protected void onDraw(Canvas c) {
        long t0 = System.nanoTime();
        drawMap(c);
        long t1 = System.nanoTime(); drawMs = 0.9 * drawMs + 0.1 * (t1 - t0) / 1e6; frames++;
        if (t1 - fpsT0 > 1_000_000_000L) { fps = frames * 1e9 / (t1 - fpsT0); frames = 0; fpsT0 = t1; }
    }

    // labels: laid out (collision tests, sorting) at most every 400 ms or when the view zooms / turns; in between the same
    // labels are only re-projected, which keeps following the vehicle smooth on slower phones
    private int[] labSeg = new int[64], labPlace = new int[128]; private boolean[] labPlaceName = new boolean[128]; private String[] labSegName = new String[64];
    private int nLabSeg, nLabPlace; private long labT = 0; private double labScale = -1, labBearing, labCx, labCy; private Roads labRoads; private boolean recording;
    private boolean labelsStale(Roads r) {
        double movedPx = Math.hypot(cx - labCx, cy - labCy) * scale;
        return r != labRoads || SystemClockNow() - labT > 400 || Math.abs(scale / labScale - 1) > 0.04 || Math.abs(norm180(bearing - labBearing)) > 3 || movedPx > getWidth() * 0.15;
    }
    private static long SystemClockNow() { return android.os.SystemClock.uptimeMillis(); }

    private void drawMap(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), land);
        if (svc == null) return;
        NavService.Snapshot s = svc.snapshot(); Engine.Mode mode = s.mode; boolean haveVehicle = mode != null && mode != Engine.Mode.WAITING_FOR_GNSS;
        com.maverickgrid.engine.Geo mg = svc.mapGeo();
        if (mg != null && (drawnGeo == null || !drawnGeo.same(mg))) { drawnGeo = mg; follow = true; }   // a map for a new area: re-centre
        double meX = Double.NaN, meY = Double.NaN;
        if (!haveVehicle && mg != null && !Double.isNaN(meLat)) { meX = mg.x(meLon); meY = mg.y(meLat); if (Math.abs(meX) > 30000 || Math.abs(meY) > 30000) meX = meY = Double.NaN; }
        // rotation: ease towards north / the heading
        double target = orient == Orient.NORTH ? 0 : orient == Orient.HEADING && !Double.isNaN(beamDeg) ? beamDeg : bearing;
        double dB = norm180(target - bearing);
        if (Math.abs(dB) > 0.3) { bearing = norm360(bearing + dB * 0.25); postInvalidateOnAnimation(); } else bearing = norm360(target);
        cosB = Math.cos(Math.toRadians(bearing)); sinB = Math.sin(Math.toRadians(bearing));
        if (follow && haveVehicle) { cx = s.x; cy = s.y; if (navMode) { double off = (getHeight() - topInset - bottomInset) * 0.22 / scale; cx += off * sinB; cy += off * cosB; } }
        else if (follow && !Double.isNaN(meX)) { cx = meX; cy = meY; }
        // visible world area: a circle around the screen centre that covers the rotated screen
        double hw = Math.hypot(getWidth(), getHeight()) / 2.0 / scale + 60, hh = hw;
        vcx = wx(getWidth() / 2f, getHeight() / 2f); vcy = wy(getWidth() / 2f, getHeight() / 2f); double wcy = vcy;
        c.save(); c.rotate((float) -bearing, getWidth() / 2f, midY());
        Roads r = svc.roads();
        if (r != null && haveVehicle && s.frame != null && !r.geo.same(s.frame)) r = null;        // a map for another area is still loaded
        if (r != null) {
            if (r != indexed) { requestIndex(r); if (indexed == null || !indexed.geo.same(r.geo)) r = null; else r = indexed; }   // keep drawing the old index meanwhile
        }
        if (r != null) {
            if (++stampId == Integer.MAX_VALUE) { java.util.Arrays.fill(segStamp, 0); stampId = 1; }
            int x0 = Math.max(0, (int) Math.floor((vcx - hw) / TILE) - tx0), x1 = Math.min(tnx - 1, (int) Math.floor((vcx + hw) / TILE) - tx0);
            int y0 = Math.max(0, (int) Math.floor((wcy - hh) / TILE) - ty0), y1 = Math.min(tny - 1, (int) Math.floor((wcy + hh) / TILE) - ty0);
            boolean drawBuildings = scale >= 1.0;
            // areas: parks, then water, then buildings (buildings only when zoomed in); one path per tile and layer
            for (int layer = 0; layer < 3; layer++) {
                if (layer == 2 && !drawBuildings) break;
                Paint paint = layer == 0 ? green : layer == 1 ? water : bld;
                for (int i : bigAreas) if (r.areaKind[i] == (layer == 0 ? 3 : layer == 1 ? 2 : 1)) drawArea(c, r, i, paint, hw, hh, wcy);
                if (scale < 0.35) continue;                                      // far zoom: only large parks / lakes
                for (int ty = Math.max(0, y0 - 1); ty <= Math.min(tny - 1, y1 + 1); ty++) for (int tx = Math.max(0, x0 - 1); tx <= Math.min(tnx - 1, x1 + 1); tx++) {
                    int t = ty * tnx + tx; if (tileArea[t].length == 0) continue;
                    Path tp = tilePath(r, t, layer); if (tp.isEmpty()) continue;
                    c.save(); c.translate(sx((tx0 + tx) * TILE), sy((ty0 + ty) * TILE)); c.scale((float) scale, (float) -scale); c.drawPath(tp, paint); c.restore();
                }
            }
            // roads: casing pass then fill pass, by class
            boolean majorOnly = scale < 0.25 && hasMajor; nMinor = nMajor = nHwy = 0; nVis = 0;
            for (int ty = y0; ty <= y1; ty++) for (int tx = x0; tx <= x1; tx++) for (int i : (majorOnly ? tileMajor : tileAll)[ty * tnx + tx]) {
                if (segStamp[i] == stampId) continue; segStamp[i] = stampId; addSeg(r, i);
                if (r.nameIdx[i] >= 0) { if (nVis == visSeg.length) visSeg = java.util.Arrays.copyOf(visSeg, nVis * 2); visSeg[nVis++] = i; }
            }
            float wMinor = (float) Math.max(1.4 * dp, Math.min(12 * dp, 7 * scale)), wMajor = wMinor * 1.35f + dp, wHwy = wMinor * 1.6f + 1.5f * dp, cas = Math.max(1.2f * dp, wMinor * 0.25f);
            caseMinor.setStrokeWidth(wMinor + cas); fillMinor.setStrokeWidth(wMinor); caseMajor.setStrokeWidth(wMajor + cas); fillMajor.setStrokeWidth(wMajor); caseHwy.setStrokeWidth(wHwy + cas); fillHwy.setStrokeWidth(wHwy);
            if (nMinor > 0) c.drawLines(bMinor, 0, nMinor, caseMinor); if (nMajor > 0) c.drawLines(bMajor, 0, nMajor, caseMajor); if (nHwy > 0) c.drawLines(bHwy, 0, nHwy, caseHwy);
            if (nMinor > 0) c.drawLines(bMinor, 0, nMinor, fillMinor); if (nMajor > 0) c.drawLines(bMajor, 0, nMajor, fillMajor); if (nHwy > 0) c.drawLines(bHwy, 0, nHwy, fillHwy);
        }
        Router.Route rt = svc.route();
        if (rt != null) { int n = rt.xs.length; float[] lb = lines(rt.xs, rt.ys, n); routeCase.setStrokeWidth(9 * dp); routeFill.setStrokeWidth(6 * dp); c.drawLines(lb, 0, 4 * (n - 1), routeCase); c.drawLines(lb, 0, 4 * (n - 1), routeFill); }
        if (s.source == NavService.Source.REPLAY || s.simulateLoss) drawTrail(c, svc.gnssTrail, gnss);
        drawTrail(c, svc.engineTrail, trail, rt == null);   // the GPS part only without a route; the dead-reckoned part (amber) always
        if (s.haveGhost && mode == Engine.Mode.DEAD_RECKONING) c.drawCircle(sx(s.ghostX), sy(s.ghostY), 7 * dp, ghost);
        if (haveVehicle) drawVehicle(c, s);
        else if (!Double.isNaN(meX)) drawDot(c, sx(meX), sy(meY), Ui.BLUE, (float) Math.max(meAcc * scale, 12 * dp), false);
        c.restore();
        // labels and markers stay upright: drawn after the rotation with rotated coordinates
        if (r != null) {
            if (labelsStale(r)) {
                occReset(); nLabSeg = 0; nLabPlace = 0; recording = true;
                // main road names first (they orient you), then places, then small streets in the space left
                if (scale > 0.35) drawStreetNames(c, r, true);
                if (scale > 0.45 && r.np > 0) drawPlaces(c, r, hw, hh, wcy);
                if (scale > 1.0) drawStreetNames(c, r, false);
                recording = false; labRoads = r; labT = SystemClockNow(); labScale = scale; labBearing = bearing; labCx = cx; labCy = cy;
            } else drawCachedLabels(c, r);
        }
        if (s.haveGhost && mode == Engine.Mode.DEAD_RECKONING) { float gx = rx(s.ghostX, s.ghostY), gy = ry(s.ghostX, s.ghostY); labelHalo.setTextSize(10 * dp); label.setTextSize(10 * dp); c.drawText("true position", gx + 10 * dp, gy + 4 * dp, labelHalo); c.drawText("true position", gx + 10 * dp, gy + 4 * dp, label); label.setTextSize(11.5f * dp); labelHalo.setTextSize(11.5f * dp); }
        if (!Double.isNaN(pinX)) drawPin(c, rx(pinX, pinY), ry(pinX, pinY));
        drawScale(c);
    }

    private void addSeg(Roads r, int i) {
        int cls = r.roadClass[i]; float ax = sx(r.ax[i]), ay = sy(r.ay[i]), bx = sx(r.bx[i]), by = sy(r.by[i]);
        if (cls <= 1) { if (nHwy + 4 > bHwy.length) bHwy = java.util.Arrays.copyOf(bHwy, bHwy.length * 2); bHwy[nHwy++] = ax; bHwy[nHwy++] = ay; bHwy[nHwy++] = bx; bHwy[nHwy++] = by; }
        else if (cls <= 3) { if (nMajor + 4 > bMajor.length) bMajor = java.util.Arrays.copyOf(bMajor, bMajor.length * 2); bMajor[nMajor++] = ax; bMajor[nMajor++] = ay; bMajor[nMajor++] = bx; bMajor[nMajor++] = by; }
        else { if (nMinor + 4 > bMinor.length) bMinor = java.util.Arrays.copyOf(bMinor, bMinor.length * 2); bMinor[nMinor++] = ax; bMinor[nMinor++] = ay; bMinor[nMinor++] = bx; bMinor[nMinor++] = by; }
    }

    private void drawArea(Canvas c, Roads r, int i, Paint paint, double hw, double hh, double wcy) {
        if (r.aMaxX[i] < vcx - hw || r.aMinX[i] > vcx + hw || r.aMaxY[i] < wcy - hh || r.aMinY[i] > wcy + hh) return;
        path.rewind(); int s0 = r.areaStart[i], s1 = r.areaStart[i + 1];
        path.moveTo(sx(r.vx[s0]), sy(r.vy[s0])); for (int j = s0 + 1; j < s1; j++) path.lineTo(sx(r.vx[j]), sy(r.vy[j])); path.close();
        c.drawPath(path, paint);
    }

    private int[] placeOrder = new int[0]; private Roads placeOrderFor; private boolean[] occ = new boolean[0];
    private static final int[] KIND_PRIORITY = {9, 3, 2, 1, 6, 4, 0, 5, 7};   // index = kind (0 street..8 other): lower draws first

    /** Places, decluttered: at most one per 56 dp screen cell, important kinds first (transport, health, fuel, food...). */
    private void drawPlaces(Canvas c, Roads r, double hw, double hh, double wcy) {
        if (placeOrderFor != r) {
            Integer[] o = new Integer[r.np]; for (int i = 0; i < r.np; i++) o[i] = i;
            java.util.Arrays.sort(o, (a, b) -> Integer.compare(KIND_PRIORITY[Math.max(0, Math.min(8, r.pkind[a]))], KIND_PRIORITY[Math.max(0, Math.min(8, r.pkind[b]))]));
            placeOrder = new int[r.np]; for (int i = 0; i < r.np; i++) placeOrder[i] = o[i]; placeOrderFor = r;
        }
        float rad = 6.5f * dp; boolean names = scale > 0.9; int drawn = 0; int maxPlaces = scale > 1.5 ? 90 : scale > 0.9 ? 60 : 30;
        for (int p : placeOrder) {
            if (Math.abs(r.px[p] - vcx) > hw || Math.abs(r.py[p] - wcy) > hh) continue;
            float px = rx(r.px[p], r.py[p]), py = ry(r.px[p], r.py[p]); if (px < 0 || py < 0 || px >= getWidth() || py >= getHeight()) continue;
            if (!occFree(px - rad, py - rad, px + rad, py + rad)) continue;
            String kind = PlaceSearch.KIND[Math.max(0, Math.min(8, r.pkind[p]))];
            String nm = names ? r.placeName(p) : null; float lx = px + rad + 4 * dp, lw = 0;
            if (nm != null) { if (nm.length() > 24) nm = nm.substring(0, 23) + "…"; lw = label.measureText(nm); if (!occFree(lx, py - 8 * dp, lx + lw, py + 8 * dp)) nm = null; }
            occMark(px - rad, py - rad, px + rad, py + rad); if (nm != null) occMark(lx, py - 8 * dp, lx + lw, py + 8 * dp);
            drawPlace(c, r, p, px, py, nm);
            if (recording) { if (nLabPlace == labPlace.length) { labPlace = java.util.Arrays.copyOf(labPlace, nLabPlace * 2); labPlaceName = java.util.Arrays.copyOf(labPlaceName, nLabPlace * 2); } labPlace[nLabPlace] = p; labPlaceName[nLabPlace++] = nm != null; }
            if (++drawn > maxPlaces) break;
        }
    }

    private void drawPlace(Canvas c, Roads r, int p, float px, float py, String nm) {
        float rad = 6.5f * dp; String kind = PlaceSearch.KIND[Math.max(0, Math.min(8, r.pkind[p]))];
        place.setColor(0xFFFFFFFF); c.drawCircle(px, py, rad + 1.5f * dp, place); place.setColor(Ui.kindColor(kind)); c.drawCircle(px, py, rad, place);
        c.drawText(Ui.kindGlyph(kind), px, py + 3.2f * dp, glyph);
        if (nm != null) { float lx = px + rad + 4 * dp; c.drawText(nm, lx, py + 4 * dp, labelHalo); c.drawText(nm, lx, py + 4 * dp, label); }
    }

    /** The last laid-out labels, re-projected to the current view (no layout work). */
    private void drawCachedLabels(Canvas c, Roads r) {
        for (int q = 0; q < nLabSeg; q++) {
            int i = labSeg[q]; if (i < 0 || i >= r.n) continue; String nm = labSegName[q];
            float ax = rx(r.ax[i], r.ay[i]), ay = ry(r.ax[i], r.ay[i]), bx = rx(r.bx[i], r.by[i]), by = ry(r.bx[i], r.by[i]);
            float ang = (float) Math.toDegrees(Math.atan2(by - ay, bx - ax)); if (ang > 90) ang -= 180; if (ang < -90) ang += 180;
            float mx = (ax + bx) / 2, my = (ay + by) / 2;
            c.save(); c.rotate(ang, mx, my); c.drawText(nm, mx, my + 3.5f * dp, streetHalo); c.drawText(nm, mx, my + 3.5f * dp, street); c.restore();
        }
        for (int q = 0; q < nLabPlace; q++) {
            int p = labPlace[q]; if (p < 0 || p >= r.np) continue;
            String nm = null; if (labPlaceName[q]) { nm = r.placeName(p); if (nm != null && nm.length() > 24) nm = nm.substring(0, 23) + "…"; }
            drawPlace(c, r, p, rx(r.px[p], r.py[p]), ry(r.px[p], r.py[p]), nm);
        }
    }

    // ------------------------------------------------------------------ labels: occupancy grid (16 dp cells) so labels never overlap
    private boolean[] occ2 = new boolean[0]; private int ogw, ogh; private float ocell;
    private void occReset() { ocell = 16 * dp; ogw = (int) (getWidth() / ocell) + 1; ogh = (int) (getHeight() / ocell) + 1; if (occ2.length < ogw * ogh) occ2 = new boolean[ogw * ogh]; else java.util.Arrays.fill(occ2, 0, ogw * ogh, false); }
    private boolean occFree(float x0, float y0, float x1, float y1) {
        int a = Math.max(0, (int) (x0 / ocell)), b = Math.min(ogw - 1, (int) (x1 / ocell)), c0 = Math.max(0, (int) (y0 / ocell)), d = Math.min(ogh - 1, (int) (y1 / ocell));
        for (int y = c0; y <= d; y++) for (int x = a; x <= b; x++) if (occ2[y * ogw + x]) return false; return true;
    }
    private void occMark(float x0, float y0, float x1, float y1) {
        int a = Math.max(0, (int) (x0 / ocell)), b = Math.min(ogw - 1, (int) (x1 / ocell)), c0 = Math.max(0, (int) (y0 / ocell)), d = Math.min(ogh - 1, (int) (y1 / ocell));
        for (int y = c0; y <= d; y++) for (int x = a; x <= b; x++) occ2[y * ogw + x] = true;
    }

    /** Street names along the road (once per street on screen, on its longest visible piece; big roads first). */
    private int[] visSeg = new int[1024]; private int nVis; private final java.util.HashMap<Integer, float[]> bestSeg = new java.util.HashMap<>();
    private final Paint street = new Paint(Paint.ANTI_ALIAS_FLAG), streetHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private void drawStreetNames(Canvas c, Roads r, boolean major) {
        street.setTextSize(11.5f * dp); street.setColor(Ui.dark ? 0xFFD0D4DA : 0xFF3C4043); street.setTextAlign(Paint.Align.CENTER);
        streetHalo.set(street); streetHalo.setColor(Ui.M_HALO); streetHalo.setStyle(Paint.Style.STROKE); streetHalo.setStrokeWidth(3.5f * dp);
        bestSeg.clear();
        for (int q = 0; q < nVis; q++) {
            int i = visSeg[q]; if (major != (r.roadClass[i] <= 3)) continue;
            float ax = rx(r.ax[i], r.ay[i]), ay = ry(r.ax[i], r.ay[i]), bx = rx(r.bx[i], r.by[i]), by = ry(r.bx[i], r.by[i]);
            float mx = (ax + bx) / 2, my = (ay + by) / 2; if (mx < 0 || my < 0 || mx > getWidth() || my > getHeight()) continue;
            // OSM pieces are short (a few tens of metres): keep the longest piece for the label's position and angle, and the
            // visible extent of the whole street (bounding box of its pieces) to decide whether the name fits along it
            float len = (float) Math.hypot(bx - ax, by - ay); float[] b = bestSeg.get(r.nameIdx[i]);
            if (b == null) { b = new float[]{len, ax, ay, bx, by, r.roadClass[i], mx, my, mx, my, i}; bestSeg.put(r.nameIdx[i], b); }
            else {
                if (len > b[0]) { b[0] = len; b[1] = ax; b[2] = ay; b[3] = bx; b[4] = by; b[10] = i; }
                b[5] = Math.min(b[5], r.roadClass[i]); b[6] = Math.min(b[6], mx); b[7] = Math.min(b[7], my); b[8] = Math.max(b[8], mx); b[9] = Math.max(b[9], my);
            }
        }
        java.util.List<java.util.Map.Entry<Integer, float[]>> es = new java.util.ArrayList<>(bestSeg.entrySet());
        es.sort((u, v) -> u.getValue()[5] != v.getValue()[5] ? Float.compare(u.getValue()[5], v.getValue()[5]) : Float.compare(v.getValue()[0], u.getValue()[0]));
        int drawn = 0;
        for (java.util.Map.Entry<Integer, float[]> e : es) {
            String nm = r.names[e.getKey()]; if (nm == null || nm.isEmpty()) continue; if (nm.length() > 28) nm = nm.substring(0, 27) + "…";
            float[] b = e.getValue(); float w = street.measureText(nm); if (Math.hypot(b[8] - b[6], b[9] - b[7]) < w + 12 * dp || b[0] < 8 * dp) continue;
            float ang = (float) Math.toDegrees(Math.atan2(b[4] - b[2], b[3] - b[1])); if (ang > 90) ang -= 180; if (ang < -90) ang += 180;
            float mx = (b[1] + b[3]) / 2, my = (b[2] + b[4]) / 2, hwid = w / 2 + 2 * dp;
            float ex = (float) (Math.abs(Math.cos(Math.toRadians(ang))) * hwid + 7 * dp), ey = (float) (Math.abs(Math.sin(Math.toRadians(ang))) * hwid + 7 * dp);
            if (!occFree(mx - ex, my - ey, mx + ex, my + ey)) continue; occMark(mx - ex, my - ey, mx + ex, my + ey);
            c.save(); c.rotate(ang, mx, my); c.drawText(nm, mx, my + 3.5f * dp, streetHalo); c.drawText(nm, mx, my + 3.5f * dp, street); c.restore();
            if (recording) { if (nLabSeg == labSeg.length) { labSeg = java.util.Arrays.copyOf(labSeg, nLabSeg * 2); labSegName = java.util.Arrays.copyOf(labSegName, nLabSeg * 2); } labSeg[nLabSeg] = (int) b[10]; labSegName[nLabSeg++] = nm; }
            if (++drawn >= (major ? 20 : 25)) break;
        }
    }

    private void drawVehicle(Canvas c, NavService.Snapshot s) {
        boolean dr = s.mode == Engine.Mode.DEAD_RECKONING;
        // uncertainty: GNSS ~ a few metres; dead reckoning grows with distance since GNSS was lost (about 8 % measured median)
        float ur = (float) ((dr ? 5 + 0.08 * s.drDistM : 6) * scale); ur = Math.max(ur, 12 * dp);
        drawDot(c, sx(s.x), sy(s.y), dr ? Ui.AMBER : Ui.BLUE, ur, dr);
    }

    private void drawDot(Canvas c, float px, float py, int col, float ur, boolean dr) {
        halo.setColor(Ui.withAlpha(col, 38)); haloEdge.setColor(Ui.withAlpha(col, 110)); c.drawCircle(px, py, ur, halo); if (dr) c.drawCircle(px, py, ur, haloEdge);
        if (!Double.isNaN(beamDeg)) {
            float br = 70 * dp; beam.setShader(new RadialGradient(px, py, br, new int[]{Ui.withAlpha(col, 150), Ui.withAlpha(col, 60), Ui.withAlpha(col, 0)}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            oval.set(px - br, py - br, px + br, py + br); c.drawArc(oval, (float) (beamDeg - 90 - 32), 64, true, beam);
        }
        c.drawCircle(px, py, 10.5f * dp, ring); dot.setColor(col); c.drawCircle(px, py, 7.5f * dp, dot);
    }

    private void drawPin(Canvas c, float x, float y) {
        path.rewind(); float r = 9 * dp; path.addCircle(x, y - 22 * dp, r, Path.Direction.CW);
        path.moveTo(x - r * 0.75f, y - 17 * dp); path.lineTo(x, y); path.lineTo(x + r * 0.75f, y - 17 * dp); path.close();
        c.drawPath(path, pin); place.setColor(0xFFFFFFFF); c.drawCircle(x, y - 22 * dp, 3.5f * dp, place);
    }

    private void drawScale(Canvas c) {
        double m = niceLength(90 * dp / scale); float len = (float) (m * scale), y = getHeight() - bottomInset - 14 * dp, x = 14 * dp;
        c.drawLine(x, y, x + len, y, scaleP); c.drawLine(x, y - 4 * dp, x, y, scaleP); c.drawLine(x + len, y - 4 * dp, x + len, y, scaleP);
        c.drawText(m >= 1000 ? Math.round(m / 1000) + " km" : Math.round(m) + " m", x, y - 6 * dp, scaleP);
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

    private boolean[] trailFlags = new boolean[0]; private float[] drBuf = new float[0]; private final Paint drTrail = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The vehicle's track: GPS part in blue (optional), dead-reckoned part in amber on top. */
    private void drawTrail(Canvas c, Trail t, Paint p, boolean withGps) {
        if (trailBuf.length < 2 * t.capacity()) { trailBuf = new float[2 * t.capacity()]; }
        if (trailFlags.length < t.capacity()) trailFlags = new boolean[t.capacity()];
        int n = t.copy(trailBuf); t.copyFlags(trailFlags); if (n < 2) return;
        if (lineBuf.length < 4 * n) lineBuf = new float[4 * n]; if (drBuf.length < 4 * n) drBuf = new float[4 * n];
        int ng = 0, nd = 0;
        for (int i = 0; i < n - 1; i++) {
            float ax = sx(trailBuf[2 * i]), ay = sy(trailBuf[2 * i + 1]), bx = sx(trailBuf[2 * i + 2]), by = sy(trailBuf[2 * i + 3]);
            if (trailFlags[i + 1]) { drBuf[nd++] = ax; drBuf[nd++] = ay; drBuf[nd++] = bx; drBuf[nd++] = by; }
            else if (withGps) { lineBuf[ng++] = ax; lineBuf[ng++] = ay; lineBuf[ng++] = bx; lineBuf[ng++] = by; }
        }
        if (ng > 0) c.drawLines(lineBuf, 0, ng, p);
        if (nd > 0) { drTrail.setColor(0xFFFFB300); drTrail.setStrokeWidth(4f * dp); drTrail.setStrokeCap(Paint.Cap.ROUND); c.drawLines(drBuf, 0, nd, drTrail); }
    }

    private static double niceLength(double m) { double p = Math.pow(10, Math.floor(Math.log10(m))); double f = m / p; return (f < 2 ? 1 : f < 5 ? 2 : 5) * p; }
}
