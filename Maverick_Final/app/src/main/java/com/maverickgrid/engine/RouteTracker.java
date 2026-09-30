package com.maverickgrid.engine;

/**
 * Dead reckoning locked to the planned route. When GNSS is lost while the vehicle is on its route, the position is kept
 * ON the route line: the AI speed moves it forward along the route, and every turn the gyroscope sees is matched to the
 * route's next junction with the same kind of turn, which resets the distance along the route at that junction.
 * The dot waits at a junction the route turns at until the gyro sees the turn (it never runs past a turn it has not made).
 * If the vehicle clearly does something the route does not (a turn where the route goes straight, or straight on where
 * it must turn), the lock is released and the map-matching particle filter takes over.
 */
public final class RouteTracker {
    public static final class Params {
        public double lockMaxOffM = 35, lockMaxHeadDeg = 60;      // lock only if on the route at GNSS loss
        public double turnMinDeg = 35;                             // a gyro heading change this big within turnWinS is a turn
        public double turnWinS = 6, turnSettleDegS = 6;            // ...and the turn is over when the rate drops below this
        public double matchMinM = 60, matchFrac = 0.6, matchAngleDeg = 55;   // window grows with the distance since the last junction (speed error)   // search window for the matching route turn
        public double holdM = 12;                                  // how far past an unmade route turn the dot may go
        public double straightOnM = 70, straightOnFrac = 0.5;       // travelled past a route turn without turning (min, or this share of the distance since the last junction) -> off route
        public double headOffDeg = 60, headOffS = 4;               // gyro heading far from the route's for this long -> off route
    }

    private final double[] xs, ys, cum, dir; private final int n;
    private final double[] turnS, turnA; private final int nt;         // route turns: centre (m along the route) and angle (rad, clockwise +)
    private final Params p;
    private boolean locked; private double s, g; private int nextTurn;  // along-route distance, gyro heading (absolute, rad)
    private double holdExcess, offTime, cooldown, travelled, sinceAnchor;
    // 10 Hz history for turn detection: gyro heading, along-route distance, yaw rate
    private static final int H = 200; private final double[] hg = new double[H], hs = new double[H], hy = new double[H]; private int hk;
    private String lastEvent = "";

    public RouteTracker(double[] xs, double[] ys, Params p) {
        this.p = p; n = xs.length; this.xs = xs.clone(); this.ys = ys.clone(); cum = new double[n]; dir = new double[Math.max(1, n - 1)];
        for (int i = 1; i < n; i++) cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        for (int i = 0; i < n - 1; i++) dir[i] = Math.atan2(xs[i + 1] - xs[i], ys[i + 1] - ys[i]);
        // turns: heading change between the direction 15 m before and 15 m after each vertex; neighbouring vertices merged
        double[] ts = new double[n], ta = new double[n]; int k = 0;
        for (int i = 1; i < n - 1; i++) {
            double a = Math.IEEEremainder(headingAt(cum[i] + 15) - headingAt(cum[i] - 15), 2 * Math.PI);
            if (Math.abs(a) < Math.toRadians(p.turnMinDeg)) continue;
            if (k > 0 && cum[i] - ts[k - 1] < 25 && Math.signum(a) == Math.signum(ta[k - 1])) { if (Math.abs(a) > Math.abs(ta[k - 1])) { ts[k - 1] = cum[i]; ta[k - 1] = a; } continue; }
            ts[k] = cum[i]; ta[k] = a; k++;
        }
        nt = k; turnS = java.util.Arrays.copyOf(ts, k); turnA = java.util.Arrays.copyOf(ta, k);
    }

    public int turns() { return nt; }
    public double length() { return cum[n - 1]; }
    public boolean locked() { return locked; }
    public String lastEvent() { return lastEvent; }

    /** Heading of the route (rad, clockwise from north) at distance d along it. */
    public double headingAt(double d) { int i = seg(d); return dir[i]; }
    private int seg(double d) {
        if (n < 2) return 0; int lo = 0, hi = n - 2;
        while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (cum[m] <= d) lo = m; else hi = m - 1; }
        return lo;
    }
    public double x() { return pointX(s); }
    /** The route point at distance d along it. */
    public double[] pointAt(double d) { return new double[]{pointX(d), pointY(d)}; }
    public double y() { return pointY(s); }
    public double heading() { return headingAt(s); }
    public double along() { return s; }
    private double pointX(double d) { d = Math.max(0, Math.min(cum[n - 1], d)); int i = seg(d); double L = cum[i + 1] - cum[i]; double t = L > 0 ? (d - cum[i]) / L : 0; return xs[i] + (xs[i + 1] - xs[i]) * t; }
    private double pointY(double d) { d = Math.max(0, Math.min(cum[n - 1], d)); int i = seg(d); double L = cum[i + 1] - cum[i]; double t = L > 0 ? (d - cum[i]) / L : 0; return ys[i] + (ys[i + 1] - ys[i]) * t; }

    /** Closest point of the route to (x, y): {along, distance}. */
    public double[] project(double x, double y) {
        double best = Double.POSITIVE_INFINITY, al = 0;
        for (int i = 0; i < n - 1; i++) {
            double dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i], L2 = dx * dx + dy * dy; double t = L2 > 0 ? Math.max(0, Math.min(1, ((x - xs[i]) * dx + (y - ys[i]) * dy) / L2)) : 0;
            double d = Math.hypot(xs[i] + dx * t - x, ys[i] + dy * t - y); if (d < best) { best = d; al = cum[i] + t * (cum[i + 1] - cum[i]); }
        }
        return new double[]{al, best};
    }

    /**
     * At GNSS loss: lock onto the route if the vehicle is on it. headingRad may be NaN (standing start): the route's
     * direction is then assumed. Returns whether the tracker locked.
     */
    public boolean start(double x, double y, double headingRad) {
        locked = false; if (n < 2) return false;
        double[] pr = project(x, y); if (pr[1] > p.lockMaxOffM || pr[0] > cum[n - 1] - 5) return false;
        double rh = headingAt(pr[0]);
        if (!Double.isNaN(headingRad) && Math.abs(Math.IEEEremainder(headingRad - rh, 2 * Math.PI)) > Math.toRadians(p.lockMaxHeadDeg)) return false;
        s = pr[0]; g = rh; locked = true; holdExcess = 0; offTime = 0; cooldown = 0; travelled = 0; sinceAnchor = 0; hk = 0; lastEvent = "locked";
        nextTurn = 0; while (nextTurn < nt && turnS[nextTurn] < s - 10) nextTurn++;
        for (int i = 0; i < H; i++) { hg[i] = g; hs[i] = s; hy[i] = 0; }
        return true;
    }

    /** 0.1 s step with speed (m/s) and bias-corrected yaw rate (rad/s, clockwise +). Returns false once the lock is lost. */
    public boolean step(double speed, double yaw) {
        if (!locked) return false;
        final double DT = Core.DT;
        g += yaw * DT; double ds = Math.max(0, speed) * DT; travelled += ds; sinceAnchor += ds; cooldown = Math.max(0, cooldown - DT);
        // move along the route, but never far past a route turn the gyro has not seen yet
        double sNew = s + ds;
        if (nextTurn < nt && sNew > turnS[nextTurn] + p.holdM) { holdExcess += sNew - Math.max(s, turnS[nextTurn] + p.holdM); sNew = Math.max(s, turnS[nextTurn] + p.holdM); }
        s = Math.min(sNew, cum[n - 1]);
        hk++; int ih = hk % H; hg[ih] = g; hs[ih] = s; hy[ih] = yaw;
        if (holdExcess > Math.max(p.straightOnM, p.straightOnFrac * sinceAnchor)) { locked = false; lastEvent = "went straight where the route turns"; return false; }
        // a finished gyro turn: heading change over the window, rate now low
        int win = (int) Math.round(p.turnWinS / DT), iw = Math.floorMod(hk - win, H);
        double dg = Math.IEEEremainder(g - hg[iw], 2 * Math.PI), rate = 0;
        for (int q = 0; q < 10; q++) rate += Math.abs(hy[Math.floorMod(hk - q, H)]); rate /= 10;
        if (cooldown == 0 && hk > 10 && Math.abs(dg) > Math.toRadians(p.turnMinDeg) && rate < Math.toRadians(p.turnSettleDegS)) {
            // where along the route was the middle of the turn?
            double half = hg[iw] + dg / 2; int im = ih;
            for (int q = 0; q < win; q++) { int j = Math.floorMod(hk - q, H); if (Math.abs(Math.IEEEremainder(hg[j] - hg[iw], 2 * Math.PI)) <= Math.abs(dg / 2)) { im = j; break; } }
            double sMid = hs[im], since = s - sMid;
            int best = -1; double bestD = Math.max(p.matchMinM, p.matchFrac * sinceAnchor) + p.holdM;
            for (int t = Math.max(0, nextTurn - 1); t < Math.min(nt, nextTurn + 3); t++) {     // the next junctions only, never back
                if (Math.signum(turnA[t]) != Math.signum(dg) || Math.abs(turnA[t] - dg) > Math.toRadians(p.matchAngleDeg)) continue;
                double d = Math.abs(turnS[t] - sMid); if (d < bestD) { bestD = d; best = t; }
            }
            if (best >= 0) {
                s = Math.min(turnS[best] + since, cum[n - 1]); nextTurn = best + 1; holdExcess = 0; g = headingAt(s); sinceAnchor = since;
                for (int i = 0; i < H; i++) hg[i] = g;           // restart the turn detector from the corrected heading
                lastEvent = String.format(java.util.Locale.US, "turn %.0f° matched at %.0f m (moved %.0f m)", Math.toDegrees(dg), turnS[best], turnS[best] - sMid);
            } else { locked = false; lastEvent = String.format(java.util.Locale.US, "turn %.0f° not on the route", Math.toDegrees(dg)); return false; }
            cooldown = 3;
        }
        // heading far from the route for a while, and no route turn nearby to explain it
        double off = Math.abs(Math.IEEEremainder(g - headingAt(s), 2 * Math.PI)); boolean nearTurn = false;
        for (int t = Math.max(0, nextTurn - 1); t < Math.min(nt, nextTurn + 1); t++) if (Math.abs(turnS[t] - s) < 40) nearTurn = true;
        offTime = off > Math.toRadians(p.headOffDeg) && !nearTurn && speed > 1 ? offTime + DT : 0;
        if (offTime > p.headOffS) { locked = false; lastEvent = "heading left the route"; return false; }
        return true;
    }

    /** Gyro-integrated heading (rad): the direction to continue in if the lock is lost. */
    public double gyroHeading() { return g; }
}
