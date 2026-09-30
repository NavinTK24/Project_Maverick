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
    }

    public static final class State {
        public Mode mode; public double x, y, lat, lon, headingRad, speed, outageS, displayX, displayY, displayLat, displayLon;
        public boolean mapActive; public double gyroBias;
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
    private MapFilter mf; private double drX, drY, drPsi; private long seed = 1;
    private double offX = 0, offY = 0; private final State out = new State();

    public Engine(Model model, Config cfg) { this.model = model; this.cfg = cfg; }

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
        if (k % Core.STRIDE == 0) { lastRow = fx.features(); lastStop = model.stop.predict(d(lastRow)) >= model.stopThreshold; stopHist[(int) ((k / Core.STRIDE) % ROW_N)] = lastStop; }
        // GNSS speed history, held at 10 Hz (exactly what the models were trained on)
        if (haveFix) { double prev = gpsHist[(int) ((k - 1 + GPS_N) % GPS_N)]; gpsHist[(int) (k % GPS_N)] = heldSpeed; if (k > 0 && heldSpeed != prev) lastChange = k; }

        if (mode == Mode.GNSS && (k - lastFixTick) > Math.round(cfg.outageAfterS / DT)) enterDeadReckoning();
        if (mode == Mode.GNSS) ekfPredict(yaw + 0.0);
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
            mode = Mode.GNSS; return;
        }
        if (mode == Mode.DEAD_RECKONING) {
            if (++goodFixes < cfg.fixesToRecover) return;
            // seamless return: EKF starts from the dead-reckoned state; the displayed icon glides to the fix
            double px = out.displayX, py = out.displayY;
            s[0] = mf != null ? mf.x() : drX; s[1] = mf != null ? mf.y() : drY; s[2] = mf != null ? mf.heading() : drPsi; s[3] = drSpeed;
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
        mf = new MapFilter(roads, cfg.map); mf.start(drX, drY, drPsi, seed++);
    }

    private void drStep(double yaw, double ax) {
        long i = k - k0;
        if (i % Core.STRIDE == 0) {                              // new speed estimate once per second
            int j = (int) (i / Core.STRIDE);
            Float phys = model.physicsInput ? (float) (axSum * DT) : null;
            double r = model.speedMean.predict(Core.residualRow(lastRow, rowK0, model.keyEnergy, ctx, (float) j, phys));
            drSpeed = lastStop ? 0.0 : Math.max(ctx[0] + r, 0);
        }
        axSum += ax;
        double w = yaw + bias;
        mf.step(drSpeed, w);
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
        out.mode = mode; out.gyroBias = bias;
        if (mode == Mode.DEAD_RECKONING) { out.x = mf.x(); out.y = mf.y(); out.headingRad = mf.heading(); out.speed = drSpeed; out.outageS = (k - k0) * DT; out.mapActive = roads != null; }
        else { out.x = s[0]; out.y = s[1]; out.headingRad = s[2]; out.speed = s[3]; out.outageS = 0; out.mapActive = false; }
        double decay = Math.exp(-DT / cfg.displayBlendS); offX *= decay; offY *= decay;
        out.displayX = out.x + offX; out.displayY = out.y + offY;
        if (geo != null) { out.lat = geo.lat(out.y); out.lon = geo.lon(out.x); out.displayLat = geo.lat(out.displayY); out.displayLon = geo.lon(out.displayX); }
    }

    public State state() { return out; }
    public boolean hasFix() { return haveFix; }
    public Mode mode() { return mode; }

    private static double[] d(float[] f) { double[] a = new double[f.length]; for (int i = 0; i < f.length; i++) a[i] = f[i]; return a; }
    private final double[][] F = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}}, T1 = new double[4][4]; private final double[] K = new double[4];
    private static final double Q_PSI = Math.pow(Math.toRadians(1.0), 2) * DT;
    private static void mulInto(double[][] a, double[][] b, double[][] c) { for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { double t = 0; for (int q = 0; q < 4; q++) t += a[i][q] * b[q][j]; c[i][j] = t; } }
}
