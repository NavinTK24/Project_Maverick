package com.maverickgrid.engine;

/** Follows a route: progress along it, next instruction, remaining distance/time, and off-route detection. */
public final class Navigator {
    public final Router.Route route; private int seg = 0; private int offCount = 0;
    public double alongM, offRouteM, remainingM, nextDistM; public Router.Maneuver next; public boolean arrived, offRoute;

    public Navigator(Router.Route r) { route = r; }

    private double lastAlong = 0; private boolean started = false;

    /** Update with the current position (local metres); call at 10 Hz. Progress may not jump more than 60 m ahead per update. */
    public void update(double x, double y) {
        double[] xs = route.xs, ys = route.ys; int n = xs.length;
        double lo = started ? lastAlong - 30 : 0, hi = started ? lastAlong + 60 : 60;
        double best = Double.POSITIVE_INFINITY, bestAlong = lastAlong; double gBest = Double.POSITIVE_INFINITY, gAlong = lastAlong;
        for (int i = 0; i < n - 1; i++) {
            if (route.cum[i] > hi + 1 && best < 40) break;
            double dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i], L2 = dx * dx + dy * dy; double t = L2 > 0 ? Math.max(0, Math.min(1, ((x - xs[i]) * dx + (y - ys[i]) * dy) / L2)) : 0;
            double d = Math.hypot(xs[i] + dx * t - x, ys[i] + dy * t - y), al = route.cum[i] + t * (route.cum[i + 1] - route.cum[i]);
            if (d < gBest) { gBest = d; gAlong = al; }
            if (al >= lo && al <= hi && d < best) { best = d; bestAlong = al; }
        }
        if (best > 40 && gBest < best) { best = gBest; if (gBest < 40) bestAlong = gAlong; }   // re-join the route elsewhere only when clearly on it
        started = true; lastAlong = bestAlong; seg = 0;
        offRouteM = best; alongM = bestAlong; remainingM = Math.max(0, route.lengthM - alongM);
        next = null; for (Router.Maneuver m : route.maneuvers) if (m.atM > alongM + 1) { next = m; break; }
        nextDistM = next != null ? next.atM - alongM : 0;
        if (remainingM < 150) nearEnd = true;
        arrived = nearEnd && remainingM < 20 && best < 40;
        offCount = best > 40 ? offCount + 1 : 0; offRoute = offCount > 30;       // > 40 m away for 3 s
    }
    private boolean nearEnd = false;

    /** Remaining time assuming the route's average speed. */
    public double remainingS() { return route.lengthM > 0 ? route.timeS * remainingM / route.lengthM : 0; }
}
