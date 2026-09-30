package com.maverickgrid.engine;

/**
 * MaverickGRID navigation engine: GNSS+INS fusion at 10 Hz while GNSS is healthy, AI dead reckoning + map matching during
 * GNSS loss, and a seamless switch both ways. Call imu() exactly every 100 ms and gnss() whenever a fix arrives.
 * Everything is causal and uses only data up to the current instant.
 */
public final class Engine {
    public enum Mode { WAITING_FOR_GNSS, GNSS, DEAD_RECKONING }

    public static final class Config {
        /** Yaw source: axis index 0..2 of the gyro times yawSign, or -1 = rotation about gravity (phone in any orientation). */
        public int yawAxis = -1; public double yawSign = -1.0;
        public boolean stopLearnedGyroBias = true;     // phone: yes; external vehicle IMU: no (measured slightly worse)
        public double outageAfterS = 1.5;              // fix age that declares GNSS lost (1 Hz fixes: tolerates one late fix)
        public double maxFixAccuracyM = 25.0;          // fixes worse than this are ignored
        public int fixesToRecover = 2;                 // consecutive good fixes to leave dead reckoning
        public double displayBlendS = 2.0;             // display offset decay after recovery (smooth icon)
        public MapFilter.Params map = new MapFilter.Params();
        /** Stop-detector threshold; NaN = the model's own. */
        public double stopThreshold = Double.NaN;
        /** Learn this vehicle's stop threshold while GNSS is good: raise it until at most adaptMaxFalse of moving seconds look "stopped". */
        public boolean adaptStop = true; public double adaptMaxFalse = 0.03, adaptMaxThreshold = 0.95; public int adaptMinMovingS = 60;
        /** Prior stop-probability histograms (seconds per 0.01 bin, moving > 3 m/s and still < 0.3 m/s) from this vehicle's earlier drives. */
        public int[] movingHistPrior, stillHistPrior;
        /** A vehicle cannot be stopped while it turns: mean |yaw rate| over the last second above this (rad/s) vetoes a stop. 0 = off. */
        public double turnVetoRadS = 0.05;
        /** This vehicle's learned speed pattern (GPS speed ÷ AI speed), multiplies the dead-reckoned speed. */
        public double speedScale = 1.0;
        /** Phone compass → vehicle heading: used only while GNSS gives no bearing (standing start, very slow). Uncertainty before / after the phone mount's offset is learned from GPS bearings. */
        public double compassSdRad = Math.toRadians(60), compassLearnedSdRad = Math.toRadians(25);
        /** Test hooks (replay only): heading error added, and the heading uncertainty assumed, when GNSS is lost. */
        public double testHeadingErrRad = 0, testHeadingSdRad = Double.NaN;
        /** Keep dead reckoning on the planned route (turns matched to the route's junctions) while the vehicle follows it. */
        public boolean routeLock = false;   // experimental: on IO-VNBD it was worse than the route-guided particle filter (see report), so off
        public RouteTracker.Params track = new RouteTracker.Params(); public int routeFollowS = 10;
        /** Show the dead-reckoned position on the nearest road that fits the direction of travel (within snapM); the report keeps the raw estimate. */
        public double snapM = 25;
    }
    public static final int HIST_BINS = 100;

    public static final class State {
        public Mode mode; public double x, y, lat, lon, headingRad, speed, outageS, displayX, displayY, displayLat, displayLon;
        public boolean mapActive; public double gyroBias;
        /** Distance dead-reckoned since GNSS was lost (m); 0 while GNSS is healthy. */
        public double drDistM;
        /** Stop-detector probability, the threshold in use (learned), and whether the vehicle is considered stopped. */
        public double stopP, stopThreshold; public boolean stopped;
        /** Dead reckoning is being guided by the planned route (false if there is none or the driver left it). */
        public boolean routeActive;
        /** Dead reckoning started without a reliable heading; the map is still deciding the direction of travel. */
        public boolean headingUnsure;
        /** Dead reckoning is locked to the planned route; routeEvent says what the route tracker last did (for diagnostics). */
        public boolean routeLocked; public String routeEvent = "";
    }

    private static final double DT = Core.DT;
    private final Model model; private final Config cfg; private Geo geo; private Roads roads;
    private final ImuFeatures fx = new ImuFeatures(); private long k = -1; private float[] lastRow; private boolean lastStop;
    // histories (ring buffers indexed by absolute sample / row number)
    private static final int RATE_N = 9200, ROW_N = 920, GPS_N = 600;
    private final double[] rateHist = new double[RATE_N]; private final boolean[] stopHist = new boolean[ROW_N]; private final double[] gpsHist = new double[GPS_N];
    private double heldSpeed = 0; private long lastChange = -1; private boolean haveFix = false;
    // GNSS/INS EKF: state [x, y, psi, v]
    private final double[] s = new double[4]; private final double[][] P = new double[4][4];
    private long lastFixTick = Long.MIN_VALUE / 2; private int goodFixes = 0;
    // dead reckoning
    private Mode mode = Mode.WAITING_FOR_GNSS; private long k0; private float[] rowK0; private float[] ctx; private double drSpeed, bias, axSum;
    private double lastPsiSd; private MapFilter mf, mfRoute; private double[] routeXs, routeYs; private RouteTracker tracker; private final Roads.Stamp snapStamp = new Roads.Stamp(); private boolean routeOff; private int routeLow; private double drX, drY, drPsi; private long seed = 1;
    private double offX = 0, offY = 0; private final State out = new State();
    private Lines route, learned;
    // phone compass (vehicle-forward azimuth) and the mount offset learned from GPS bearings
    private double compassRad = Double.NaN, mountSin = 0, mountCos = 0; private long compassTick = Long.MIN_VALUE / 2, bearingTick = Long.MIN_VALUE / 2; private int mountN;
    // stop-threshold learning (GNSS truth vs the stop detector, once per second)
    private final int[] movingHist = new int[HIST_BINS], stillHist = new int[HIST_BINS]; private double lastStopP, stopThr; private int nMoving, rowsSinceAdapt;

    public Engine(Model model, Config cfg) {
        this.model = model; this.cfg = cfg; stopThr = Double.isNaN(cfg.stopThreshold) ? model.stopThreshold : cfg.stopThreshold;
        if (cfg.movingHistPrior != null && cfg.movingHistPrior.length == HIST_BINS) System.arraycopy(cfg.movingHistPrior, 0, movingHist, 0, HIST_BINS);
        if (cfg.stillHistPrior != null && cfg.stillHistPrior.length == HIST_BINS) System.arraycopy(cfg.stillHistPrior, 0, stillHist, 0, HIST_BINS);
        for (int v : movingHist) nMoving += v;
        if (cfg.adaptStop) adaptThreshold();
    }

    /** The planned route while navigating (null = none). Takes effect immediately, also during an outage. */
    public void setRoute(Lines r) { route = r; mfRoute = null; }
    /** The planned route as a polyline (same frame): lets dead reckoning follow it (see {@link RouteTracker}). */
    public void setRoutePath(double[] xs, double[] ys) {
        routeXs = xs; routeYs = ys; tracker = null; onRouteS = 0;
        routeRef = xs != null && xs.length >= 2 ? new RouteTracker(xs, ys, cfg.track) : null;
    }
    private RouteTracker routeRef; private int onRouteS;
    /** Seconds the vehicle has been following the route with GNSS (the lock is only trusted after routeFollowS of it, or at the route's start). */
    public int onRouteSeconds() { return onRouteS; }
    /** GNSS is known to be gone (e.g. the user switched Location off): start dead reckoning now instead of waiting for fixes to time out. */
    public void forceOutage() { if (mode == Mode.GNSS) enterDeadReckoning(); }   // a new route guides from the next outage (a change mid-outage drops the guidance)
    /** Roads this phone has driven with GPS before (null = none). */
    public void setLearned(Lines l) { learned = l; if (mf != null) mf.setLearned(l); if (mfRoute != null) mfRoute.setLearned(l); }
    /** The filter whose estimate is shown: the route-guided one while the driver follows the route, else the free one. */
    private MapFilter act() { return mfRoute != null && !routeOff ? mfRoute : mf; }
    /** Stop-probability histograms learned so far (prior + this drive): save them for the vehicle's next drive. */
    public int[] movingHist() { return movingHist.clone(); }
    public int[] stillHist() { return stillHist.clone(); }
    public double stopThreshold() { return stopThr; }

    /**
     * The phone's compass heading of the vehicle's forward direction (radians clockwise from true north), e.g. from the
     * rotation vector. Call a few times per second. It only matters while GNSS gives no bearing (standing, slow).
     */
    public void compass(double headingRad) { if (!Double.isNaN(headingRad)) { compassRad = headingRad; compassTick = k; } }
    /** Mount offset (GPS bearing − compass) learned so far, radians; NaN until 5 moving fixes. */
    public double mountOffset() { return mountN >= 5 ? Math.atan2(mountSin, mountCos) : Double.NaN; }
    public void setMountOffset(double rad, int weight) { if (!Double.isNaN(rad) && weight > 0) { mountSin = Math.sin(rad) * weight; mountCos = Math.cos(rad) * weight; mountN = weight; } }
    /** Heading uncertainty (1 sd, radians) of the fused estimate. */
    public double headingSd() { return mode == Mode.DEAD_RECKONING ? Double.NaN : Math.sqrt(Math.max(P[2][2], 0)); }

    private void compassUpdate() {
        if (k - compassTick > 10 || k - bearingTick < 50 || Double.isNaN(compassRad)) return;   // fresh compass, no GPS bearing for 5 s
        double off = mountOffset(), sd = Double.isNaN(off) ? cfg.compassSdRad : cfg.compassLearnedSdRad;
        double z = compassRad + (Double.isNaN(off) ? 0 : off);
        if (P[2][2] < sd * sd) return;                     // the EKF already knows the heading better (gyro since the last bearing)
        scalarUpdate(2, Math.IEEEremainder(z - s[2], 2 * Math.PI), sd * sd);
    }

    private void adaptThreshold() {
        double base = Double.isNaN(cfg.stopThreshold) ? model.stopThreshold : cfg.stopThreshold;
        if (nMoving < cfg.adaptMinMovingS) { stopThr = base; return; }
        int b0 = Math.min(HIST_BINS - 1, (int) Math.ceil(base * HIST_BINS - 1e-9)); double t = base;
        int above = 0; for (int b = b0; b < HIST_BINS; b++) above += movingHist[b];
        int b = b0;
        while (above > cfg.adaptMaxFalse * nMoving && b < HIST_BINS - 1) { above -= movingHist[b]; b++; t = (double) b / HIST_BINS; }
        stopThr = Math.min(Math.max(base, t), Math.max(base, cfg.adaptMaxThreshold));
    }

    /** Local frame (normally set automatically at the first fix) and optional road network in that frame. */
    public void setFrame(Geo g) { geo = g; }
    public Geo frame() { return geo; }
    public void setRoads(Roads r) { roads = r; }
    public void setSeed(long sd) { seed = sd; }

    // ------------------------------------------------------------------ inputs
    /** One 10 Hz IMU sample (sensor frame). For the external-IMU edge engine pass acc=[ax, ay, g], grav=[0,0,g], gyr=[0, yaw, 0]. */
    public void imu(double[] acc, double[] grav, double[] gyr) {
        k++;
        fx.push(acc, grav, gyr);
        double yaw = yawRate(grav, gyr); rateHist[(int) (k % RATE_N)] = yaw;
        if (k % Core.STRIDE == 0) {
            lastRow = fx.features(); lastStopP = model.stop.predict(d(lastRow));
            if (cfg.adaptStop && mode == Mode.GNSS && k - lastFixTick <= 15) {          // a fresh fix says whether we really move
                int bin = Math.min(HIST_BINS - 1, Math.max(0, (int) (lastStopP * HIST_BINS)));
                if (heldSpeed > 3.0) { movingHist[bin]++; nMoving++; } else if (heldSpeed < 0.3) stillHist[bin]++;
                if (++rowsSinceAdapt >= 10) { rowsSinceAdapt = 0; adaptThreshold(); }
            }
            lastStop = lastStopP >= stopThr;
            if (lastStop && cfg.turnVetoRadS > 0) {                                   // turning => moving
                double m = 0; for (int q = 0; q < Core.STRIDE; q++) m += Math.abs(rateHist[(int) (((k - q) % RATE_N + RATE_N) % RATE_N)]);
                if (m / Core.STRIDE > cfg.turnVetoRadS) lastStop = false;
            }
            stopHist[(int) ((k / Core.STRIDE) % ROW_N)] = lastStop;
            if (mode == Mode.GNSS && routeRef != null) {                          // is the vehicle following the planned route?
                double[] pr = routeRef.project(s[0], s[1]);
                boolean on = pr[1] < 20 && (s[3] < 2 || Math.abs(Math.IEEEremainder(s[2] - routeRef.headingAt(pr[0]), 2 * Math.PI)) < Math.toRadians(45));
                onRouteS = on ? onRouteS + 1 : 0;
            }
        }
        // GNSS speed history, held at 10 Hz (exactly what the models were trained on)
        if (haveFix) { double prev = gpsHist[(int) ((k - 1 + GPS_N) % GPS_N)]; gpsHist[(int) (k % GPS_N)] = heldSpeed; if (k > 0 && heldSpeed != prev) lastChange = k; }

        if (mode == Mode.GNSS && (k - lastFixTick) > Math.round(cfg.outageAfterS / DT)) enterDeadReckoning();
        if (mode == Mode.GNSS) { ekfPredict(yaw + 0.0); if (k % 5 == 0) compassUpdate(); }
        else if (mode == Mode.DEAD_RECKONING) drStep(yaw, acc[0]);
        publish();
    }

    /** A GNSS fix: position, speed (m/s), bearing (degrees clockwise from north, NaN if unknown), horizontal accuracy (m). */
    public void gnss(double lat, double lon, double speed, double bearingDeg, double accuracyM) {
        if (!(accuracyM <= cfg.maxFixAccuracyM)) { goodFixes = 0; return; }
        if (geo == null) geo = new Geo(lat, lon);
        double x = geo.x(lon), y = geo.y(lat); if (!Double.isNaN(speed)) heldSpeed = speed; haveFix = true; lastFixTick = k;
        if (mode == Mode.WAITING_FOR_GNSS) {
            s[0] = x; s[1] = y; s[2] = Double.isNaN(bearingDeg) ? 0 : Math.toRadians(bearingDeg); s[3] = Double.isNaN(speed) ? 0 : speed;
            for (double[] r : P) java.util.Arrays.fill(r, 0); P[0][0] = P[1][1] = accuracyM * accuracyM; P[2][2] = Double.isNaN(bearingDeg) ? 10 : 0.05; P[3][3] = 1;
            if (Double.isNaN(bearingDeg)) bearingTick = Long.MIN_VALUE / 2;
            mode = Mode.GNSS; return;
        }
        if (mode == Mode.DEAD_RECKONING) {
            if (++goodFixes < cfg.fixesToRecover) return;
            // seamless return: EKF starts from the dead-reckoned state; the displayed icon glides to the fix
            double px = out.displayX, py = out.displayY;
            s[0] = out.x; s[1] = out.y; s[2] = out.headingRad; s[3] = drSpeed;
            P[0][0] = P[1][1] = 400; P[2][2] = 0.05; P[3][3] = 4; P[0][1] = P[1][0] = 0;
            mode = Mode.GNSS; ekfUpdate(x, y, speed, bearingDeg, accuracyM);
            offX = px - s[0]; offY = py - s[1]; return;
        }
        ekfUpdate(x, y, speed, bearingDeg, accuracyM);
    }

    // ------------------------------------------------------------------ GNSS + INS fusion (EKF)
    private void ekfPredict(double yaw) {
        double psi = s[2], v = s[3], sn = Math.sin(psi), cs = Math.cos(psi);
        s[0] += v * sn * DT; s[1] += v * cs * DT; s[2] += yaw * DT;
        F[0][2] = v * cs * DT; F[0][3] = sn * DT; F[1][2] = -v * sn * DT; F[1][3] = cs * DT;
        mulInto(F, P, T1); for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double t = 0; for (int q = 0; q < 4; q++) t += T1[i][q] * F[j][q]; P[i][j] = t; }
        P[0][0] += 0.05; P[1][1] += 0.05; P[2][2] += Q_PSI; P[3][3] += 1.0 * DT;
    }

    private void ekfUpdate(double x, double y, double speed, double bearingDeg, double acc) {
        double r = Math.max(acc, 2.0); scalarUpdate(0, x - s[0], r * r); scalarUpdate(1, y - s[1], r * r);
        if (!Double.isNaN(speed)) scalarUpdate(3, speed - s[3], 0.3 * 0.3);
        if (!Double.isNaN(bearingDeg) && !Double.isNaN(speed) && speed > 3.0) {
            bearingTick = k;
            if (k - compassTick <= 10 && !Double.isNaN(compassRad)) {     // learn how the phone sits in the vehicle
                double d = Math.IEEEremainder(Math.toRadians(bearingDeg) - compassRad, 2 * Math.PI);
                double f = mountN >= 200 ? 199.0 / 200 : 1.0; mountSin = mountSin * f + Math.sin(d); mountCos = mountCos * f + Math.cos(d); mountN = Math.min(200, mountN + 1);
            }
        }
        if (!Double.isNaN(bearingDeg) && !Double.isNaN(speed) && speed > 3.0) scalarUpdate(2, Math.IEEEremainder(Math.toRadians(bearingDeg) - s[2], 2 * Math.PI), Math.pow(Math.toRadians(3), 2));
    }

    private void scalarUpdate(int i, double innov, double R) {
        double S = P[i][i] + R; for (int a = 0; a < 4; a++) K[a] = P[a][i] / S;
        for (int a = 0; a < 4; a++) s[a] += K[a] * innov;
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) T1[a][b] = P[a][b] - K[a] * P[i][b];
        for (int a = 0; a < 4; a++) System.arraycopy(T1[a], 0, P[a], 0, 4);
    }

    // ------------------------------------------------------------------ dead reckoning
    private void enterDeadReckoning() {
        mode = Mode.DEAD_RECKONING; goodFixes = 0; k0 = k; rowK0 = lastRow; ctx = context(k0);
        bias = cfg.stopLearnedGyroBias ? gyroBias(k0) : 0.0; axSum = 0;
        drX = s[0]; drY = s[1]; drPsi = s[2]; drSpeed = s[3];
        if (cfg.testHeadingErrRad != 0) drPsi += cfg.testHeadingErrRad;
        double psiSd = Double.isNaN(cfg.testHeadingSdRad) ? Math.sqrt(Math.max(P[2][2], 0)) : cfg.testHeadingSdRad; lastPsiSd = psiSd;
        mf = new MapFilter(roads, cfg.map); mf.setLearned(learned); mf.start(drX, drY, drPsi, psiSd, seed);
        // with a planned route: a second filter that believes the route; the route-free filter above checks the belief every second
        mfRoute = null; routeOff = false; routeLow = 0;
        if (route != null) { mfRoute = new MapFilter(roads, cfg.map); mfRoute.setRoute(route); mfRoute.setLearned(learned); mfRoute.start(drX, drY, drPsi, psiSd, seed + 7777); }
        seed++;
        tracker = null;
        boolean atRouteStart = routeRef != null && routeRef.project(drX, drY)[0] < 40;
        if (cfg.routeLock && routeXs != null && routeXs.length >= 2 && (onRouteS >= cfg.routeFollowS || atRouteStart)) {
            RouteTracker t = new RouteTracker(routeXs, routeYs, cfg.track);
            if (t.start(drX, drY, psiSd < Math.toRadians(20) ? drPsi : Double.NaN)) tracker = t;
        }
    }

    private void drStep(double yaw, double ax) {
        long i = k - k0;
        if (i % Core.STRIDE == 0) {                              // new speed estimate once per second
            int j = (int) (i / Core.STRIDE);
            Float phys = model.physicsInput ? (float) (axSum * DT) : null;
            double r = model.speedMean.predict(Core.residualRow(lastRow, rowK0, model.keyEnergy, ctx, (float) j, phys));
            drSpeed = lastStop ? 0.0 : Math.max(ctx[0] + r, 0) * cfg.speedScale;
        }
        axSum += ax;
        double w = yaw + bias;
        mf.step(drSpeed, w);
        if (tracker != null && tracker.locked() && !tracker.step(drSpeed, w)) {
            // the vehicle left the route: the map filter continues from where the tracker was, in the gyro's direction
            mf = new MapFilter(roads, cfg.map); mf.setLearned(learned); mf.start(tracker.x(), tracker.y(), tracker.gyroHeading(), Math.toRadians(25), seed++);
            mfRoute = null; routeOff = true; drX = tracker.x(); drY = tracker.y(); drPsi = tracker.gyroHeading();
        }
        if (mfRoute != null && !routeOff) {
            mfRoute.step(drSpeed, w);
            if (i % Core.STRIDE == Core.STRIDE - 1 && drSpeed > 0.5) {       // has the driver left the route? (judged by the filter that ignores it)
                routeLow = mf.routeLikelihood(route) < cfg.map.routeMinLik ? routeLow + 1 : 0;
                if (routeLow >= cfg.map.routeOffAfter) routeOff = true;
            }
        }
        drX += drSpeed * Math.sin(drPsi) * DT; drY += drSpeed * Math.cos(drPsi) * DT; drPsi += w * DT;
    }

    private double yawRate(double[] grav, double[] gyr) {
        if (cfg.yawAxis >= 0) return cfg.yawSign * gyr[cfg.yawAxis];
        double gn = Math.max(Math.sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2]), 1e-6);
        return cfg.yawSign * (gyr[0] * grav[0] + gyr[1] * grav[1] + gyr[2] * grav[2]) / gn;
    }

    private float[] context(long k0) {
        int n = (int) Math.min(GPS_N, k0 + 1); double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = gpsHist[(int) ((k0 - n + 1 + i) % GPS_N)];
        float[] c = Core.context(v, n - 1);
        double age = lastChange >= 0 ? (k0 - lastChange) * DT : (k0 + 1) * DT; c[4] = (float) age;
        return c;
    }

    private double gyroBias(long k0) {
        long r0 = Math.max(0, Math.floorDiv(k0 - 9000, Core.STRIDE)), r1 = k0 / Core.STRIDE;
        double[] buf = new double[9100]; int nb = 0; long i = r0;
        while (i < r1) {
            if (stopHist[(int) (i % ROW_N)]) {
                long j = i; while (j < r1 && stopHist[(int) (j % ROW_N)]) j++;
                if (j - i >= 3) { long a = (i + 1) * Core.STRIDE, b = (j - 1) * Core.STRIDE; for (long q = a; q < b && nb < buf.length; q++) buf[nb++] = rateHist[(int) (q % RATE_N)]; }
                i = j;
            } else i++;
        }
        return nb == 0 ? 0.0 : -(ImuFeatures.psum(buf, 0, nb) / nb);
    }

    // ------------------------------------------------------------------ output
    private void publish() {
        out.mode = mode; out.gyroBias = bias; out.stopP = lastStopP; out.stopThreshold = stopThr; out.stopped = lastStop; out.routeActive = mode == Mode.DEAD_RECKONING ? mfRoute != null && !routeOff : route != null; out.headingUnsure = mode == Mode.DEAD_RECKONING && act().headingUnsure();
        boolean lockedNow = mode == Mode.DEAD_RECKONING && tracker != null && tracker.locked(); out.routeLocked = lockedNow; if (lockedNow) out.routeActive = true; out.routeEvent = tracker != null ? tracker.lastEvent() : "";
        if (lockedNow) { out.x = tracker.x(); out.y = tracker.y(); out.headingRad = tracker.heading(); out.speed = drSpeed; out.outageS = (k - k0) * DT; out.mapActive = true; out.drDistM = mf.distance(); }
        else if (mode == Mode.DEAD_RECKONING) { MapFilter a = act(); out.x = a.x(); out.y = a.y(); out.headingRad = a.heading(); out.speed = drSpeed; out.outageS = (k - k0) * DT; out.mapActive = roads != null || learned != null; out.drDistM = mf.distance(); }
        else { out.x = s[0]; out.y = s[1]; out.headingRad = s[2]; out.speed = s[3]; out.outageS = 0; out.mapActive = false; out.drDistM = 0; }
        double decay = Math.exp(-DT / cfg.displayBlendS); offX *= decay; offY *= decay;
        out.displayX = out.x + offX; out.displayY = out.y + offY;
        if (mode == Mode.DEAD_RECKONING && !lockedNow && cfg.snapM > 0) {
            // shown ON the planned route while the driver follows it, otherwise on the nearest road in the direction of travel
            boolean onRoute = false;
            if (routeRef != null && out.routeActive) {
                double[] pr = routeRef.project(out.displayX, out.displayY);
                if (pr[1] < 40) { out.displayX = routeRef.pointAt(pr[0])[0]; out.displayY = routeRef.pointAt(pr[0])[1]; onRoute = true; }
            }
            if (!onRoute && roads != null) snapToRoad();
        }
        if (geo != null) { out.lat = geo.lat(out.y); out.lon = geo.lon(out.x); out.displayLat = geo.lat(out.displayY); out.displayLon = geo.lon(out.displayX); }
    }

    /** Moves the displayed position onto the nearest road segment that runs in the direction of travel (±45°), if one is within snapM. */
    private void snapToRoad() {
        final double qx = out.displayX, qy = out.displayY, h = out.headingRad; final double[] best = {cfg.snapM, Double.NaN, Double.NaN};
        roads.near(qx, qy, cfg.snapM, snapStamp, i -> {
            double d = roads.dist(i, qx, qy); if (d >= best[0]) return;
            double dh = Math.abs(Math.IEEEremainder(h - roads.dir[i], 2 * Math.PI)); if (!roads.oneway[i]) dh = Math.min(dh, Math.PI - dh);
            if (dh > Math.toRadians(45)) return;
            double t = roads.along(i, qx, qy); best[0] = d; best[1] = roads.ax[i] + (roads.bx[i] - roads.ax[i]) * t; best[2] = roads.ay[i] + (roads.by[i] - roads.ay[i]) * t;
        });
        if (!Double.isNaN(best[1])) { out.displayX = best[1]; out.displayY = best[2]; }
    }

    public State state() { return out; }
    public boolean hasFix() { return haveFix; }
    public Mode mode() { return mode; }

    private static double[] d(float[] f) { double[] a = new double[f.length]; for (int i = 0; i < f.length; i++) a[i] = f[i]; return a; }
    private final double[][] F = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}}, T1 = new double[4][4]; private final double[] K = new double[4];
    private static final double Q_PSI = Math.pow(Math.toRadians(1.0), 2) * DT;
    private static void mulInto(double[][] a, double[][] b, double[][] c) { for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double t = 0; for (int q = 0; q < 4; q++) t += a[i][q] * b[q][j]; c[i][j] = t; } }
}
