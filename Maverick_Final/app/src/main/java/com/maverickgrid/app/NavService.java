package com.maverickgrid.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;

import com.maverickgrid.engine.ArrayStore;
import com.maverickgrid.engine.Engine;
import com.maverickgrid.engine.Geo;
import com.maverickgrid.engine.Model;
import com.maverickgrid.engine.Navigator;
import com.maverickgrid.engine.PlaceSearch;
import com.maverickgrid.engine.Roads;
import com.maverickgrid.engine.Router;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the MaverickGRID engine at 10 Hz, plus offline maps, place search and turn-by-turn routing.
 * Threads: the engine thread (sensors, GNSS, engine, navigation), one background thread (map loading, routing),
 * and the UI thread (reads the snapshot and the immutable map/route objects).
 * LIVE: phone sensors + GNSS (foreground service with location type, only after permission is granted).
 * REPLAY: a held-out IO-VNBD drive from the assets with automatic simulated GNSS outages (no location needed).
 */
public class NavService extends Service implements SensorEventListener, LocationListener {
    public enum Source { NONE, LIVE, REPLAY }

    public interface Listener { void onUpdate(); }

    public final class LocalBinder extends Binder { NavService service() { return NavService.this; } }

    /** State for the UI (written on the engine thread, read on the UI thread; plain values only). */
    public static final class Snapshot {
        public volatile Source source = Source.NONE; public volatile Engine.Mode mode;
        public volatile double x, y, heading, speed, outageS, lat, lon, drDistM;
        public volatile Geo frame;                 // local frame of x/y (null before the first fix)
        public volatile boolean haveGhost; public volatile double ghostX, ghostY; public volatile double liveErrM = Double.NaN, liveDistM = 0;
        public volatile String reportStatus = "", reportUri = "", reportWhere = "";
        public volatile String vehicleName = "", lastOutage = "", mapInfo = "map: none loaded", modelInfo = "", warning = "", recordDir = "";
        public volatile boolean simulateLoss, recording; public volatile int outages; public volatile double sumDriftPct;
        // navigation
        public volatile boolean navActive; public volatile String navText = "", navDestination = "", navStatus = ""; public volatile int navTurn;
        public volatile double navNextM, navRemainM, navRemainS;
        // learning and speed diagnostics
        public volatile boolean headingUnsure, routeLocked; public volatile String routeEvent = ""; public volatile double stopP, stopThreshold; public volatile boolean stopped, routePrior; public volatile int learnedSegs;
        public volatile double tickMsAvg, tickMsMax, tickLagMs;
    }

    /** A map selection (search result, tapped place or dropped pin) in the current map frame. */
    public static final class Target { public final String name, kind; public final double x, y; public final Geo geo; public Target(String n, String k, double x, double y, Geo g) { name = n; kind = k; this.x = x; this.y = y; geo = g; } }

    /** Everything that belongs to one loaded map, published atomically. */
    public static final class MapBundle { public final Roads roads; public final Router router; public final PlaceSearch search; public final Geo geo; MapBundle(Roads r, Router rt, PlaceSearch ps, Geo g) { roads = r; router = rt; search = ps; geo = g; } }

    private static final String CHANNEL = "maverick_nav";
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread; private Handler h; private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile Listener listener;
    private SensorManager sm; private LocationManager lm; private PowerManager.WakeLock wake; private TextToSpeech tts; private volatile boolean ttsReady;
    // sensor accumulation between ticks (averaged -> anti-aliasing; same as the Python ride loader)
    private final Object lock = new Object();
    private final double[] accSum = new double[3], gyrSum = new double[3]; private int accN, gyrN;
    private final double[] accLast = new double[3], gyrLast = new double[3], grav = new double[3]; private boolean haveAcc, haveGyr, haveGrav, gravFromSensor;

    private Engine engine; private Model model; private Source source = Source.NONE; private int session = 0;
    private volatile MapBundle map; private int mapGen = 0; private Geo mapRequested; private final Roads.Stamp stamp = new Roads.Stamp(); private volatile int fgGen = 0;
    private volatile Router.Route route; private Navigator nav; private Target navTarget; private long lastReroute = 0; private int spokenFor = -1, spokenStage = 0;
    private final Snapshot snap = new Snapshot();
    final Trail engineTrail = new Trail(12000), gnssTrail = new Trail(12000);
    private final RideLogger logger = new RideLogger();
    // learning: roads driven with GPS, the planned route as a prior, this vehicle's speed pattern
    private final LearnedRoads learnedRec = new LearnedRoads(); private com.maverickgrid.engine.Lines learnedLines; private Geo learnedFor; private boolean learnedLoading; private Roads learnedRoadsFor;
    private Router.Route appliedRoute; private Engine appliedEngine, appliedShadow; private double scaleInUse = 1.0, pendingMount = Double.NaN;
    // speed pattern learned during this drive: GPS speed vs the check engine's (unscaled) AI speed in its GPS-loss tests
    private double spT, spA; private int spN; private double priorT, priorA; private int priorN; private Engine.Config cfgMain, cfgShadow;
    private final java.util.concurrent.atomic.AtomicBoolean uiPending = new java.util.concurrent.atomic.AtomicBoolean();
    // drive report: a second ("check") engine is denied GNSS in test windows so every drive measures dead-reckoning accuracy
    private Engine shadow; private DriveLog dlog; private DriveStore dstore; private long driveT0Ns, driveStartEpoch; private String driveStarted = "";
    private int shadowWin = -1; private long fixTick0 = -1;
    private static final int CHK_WARMUP = 1200, CHK_EVERY = 1800, CHK_OUT = 600;       // 10 Hz ticks: 2 min warm-up, then 60 s without GNSS every 3 min
    private volatile boolean simulateLoss = false;
    private long ticks = 0, tickPeriodMs = 100, t0;
    private String vehicle = "car"; private String mapName = ""; private long vehicleId = -1; private String vehicleName = "";
    // this drive's totals for the vehicle's statistics (live drives only)
    private double dKm, dDrKm, dTestPct, lastPX = Double.NaN, lastPY; private int dOutages, dTests; private Engine.Mode prevMode; private long driveStartMs;
    private double lossDist; private double lastGhostX = Double.NaN, lastGhostY = Double.NaN; private boolean inLoss;
    private double[] rAcc, rGrav, rGyr, rX, rY, rH, rS; private int rI, rN; private Geo rGeo; private Random rnd = new Random(7);
    private static final int REPLAY_WARMUP = 1800, REPLAY_EVERY = 1800, REPLAY_OUTAGE = 600;   // 10 Hz samples: 60 s outage every 3 min

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("maverick-engine", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); thread.start(); h = new Handler(thread.getLooper());
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE); lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaverickGRID:engine"); wake.setReferenceCounted(false);
        snap.reportUri = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("last_report", "");   // "Open last drive report" survives restarts
        tts = new TextToSpeech(this, st -> { ttsReady = st == TextToSpeech.SUCCESS; if (ttsReady) tts.setLanguage(Locale.UK); });
    }

    public static final String ACTION_LIVE = "com.maverickgrid.app.LIVE", EXTRA_VEHICLE = "vehicle", EXTRA_VEHICLE_ID = "vehicle_id", EXTRA_VEHICLE_NAME = "vehicle_name";

    /** Live mode is started with startForegroundService(ACTION_LIVE) after location permission is granted: go foreground first, then start. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_LIVE.equals(intent.getAction())) return START_NOT_STICKY;
        fgGen++;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL).setContentTitle("MaverickGRID").setContentText("Navigation running")
                    .setSmallIcon(R.drawable.ic_stat_nav).setContentIntent(pi).setOngoing(true).build();
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (RuntimeException e) {                           // e.g. SecurityException if permission was revoked meanwhile
            snap.warning = "could not start navigation: " + e.getMessage(); notifyUi(); stopSelf(startId); return START_NOT_STICKY;
        }
        String v = intent.getStringExtra(EXTRA_VEHICLE); vehicleId = intent.getLongExtra(EXTRA_VEHICLE_ID, -1); String vn = intent.getStringExtra(EXTRA_VEHICLE_NAME); vehicleName = vn != null ? vn : "";
        startLive(v != null ? v : "car");
        return START_NOT_STICKY;
    }

    public void setListener(Listener l) { listener = l; }
    public Snapshot snapshot() { return snap; }
    public Roads roads() { MapBundle m = map; return m != null ? m.roads : null; }
    public MapBundle mapBundle() { return map; }
    public Router.Route route() { return route; }
    public Source source() { return source; }
    public PlaceSearch placeSearch() { MapBundle m = map; return m != null ? m.search : null; }

    // ------------------------------------------------------------------ sessions (called from the UI thread)
    private void startLive(String type) {
        ++session; vehicle = type;
        h.post(() -> {
            stopInternal(false);
            try {
                model = new Model(asset("models/" + vehicle + "/"));
            } catch (RuntimeException | IOException e) { snap.modelInfo = "model load failed: " + e.getMessage(); dropForeground(); notifyUi(); return; }
            Engine.Config c = liveConfig(); cfgMain = c;   // rotation about gravity + what this vehicle has learned
            engine = new Engine(model, c); snap.modelInfo = "model: " + vehicle;   // frame is chosen at the first fix (see onLocationChanged)
            Engine.Config c2 = liveConfig(); cfgShadow = c2; spT = spA = 0; spN = 0;
            learnedRec.clearDrive(); learnedFor = null; learnedRoadsFor = null; learnedLines = null; appliedRoute = null; appliedEngine = appliedShadow = null;
            if (!Double.isNaN(pendingMount)) { engine.setMountOffset(pendingMount, 20); }
            shadow = new Engine(model, c2); if (!Double.isNaN(pendingMount)) shadow.setMountOffset(pendingMount, 20); shadow.setSeed(1001); shadowWin = -1; fixTick0 = -1;
            snap.warning = ""; if (snap.navStatus.startsWith("press Start")) snap.navStatus = "";
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), gy = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), gr = sm.getDefaultSensor(Sensor.TYPE_GRAVITY), mg = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (a == null || gy == null) { snap.warning = "this phone has no " + (a == null ? "accelerometer" : "gyroscope") + ": dead reckoning is not possible"; dropForeground(); notifyUi(); return; }
            gravFromSensor = gr != null;
            sm.registerListener(this, a, 10000, h); sm.registerListener(this, gy, 10000, h);
            if (gr != null) sm.registerListener(this, gr, 10000, h);
            Sensor rvs = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR); if (rvs != null) sm.registerListener(this, rvs, 20000, h);   // compass: heading at a standing start (and sensors.csv)
            compassRad = Double.NaN; declRad = Double.NaN;
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.getLooper()); }
            catch (SecurityException e) { snap.warning = "precise location permission is needed"; }
            catch (IllegalArgumentException e) { snap.warning = "this phone has no GPS receiver"; }
            source = Source.LIVE; snap.source = source; resetStats(); wake.acquire(6 * 3600 * 1000L); stopWhenSaved = false;
            snap.vehicleName = vehicleName; dKm = dDrKm = dTestPct = 0; dOutages = dTests = 0; lastPX = Double.NaN; prevMode = null; driveStartMs = SystemClock.elapsedRealtime();
            snap.mapInfo = "map: chosen at the first GPS fix";
            openDrive(false, null);
            t0 = SystemClock.uptimeMillis(); ticks = 0; tickPeriodMs = 100; h.removeCallbacks(tick); h.postAtTime(tick, t0 + 100);
            notifyUi();
        });
    }

    /** Engine configuration for a live drive: phone in any orientation, plus this vehicle's learned stop detector and speed pattern. */
    private Engine.Config liveConfig() {
        Engine.Config c = new Engine.Config(); c.yawAxis = -1; c.yawSign = -1.0; c.stopLearnedGyroBias = true;
        if (vehicleId >= 0) {
            c.movingHistPrior = Garage.stopHist(this, vehicleId, true); c.stillHistPrior = Garage.stopHist(this, vehicleId, false);
            Garage.Stats st = Garage.stats(this, vehicleId); c.speedScale = st.speedScale; pendingMount = Garage.mountOffset(this, vehicleId); priorT = st.speedTruth; priorA = st.speedAi; priorN = st.speedN;
        }
        else { priorT = priorA = 0; priorN = 0; }
        scaleInUse = c.speedScale; return c;
    }

    /** A GPS fix inside a check window: compare the GPS speed with the check engine's dead-reckoned speed (moving only). */
    private void learnSpeed(double gpsSpeed, double acc) {
        Engine.State c = shadow.state(); if (c.mode != Engine.Mode.DEAD_RECKONING || cfgShadow == null) return;
        if (!(gpsSpeed > 3) || !(acc <= 15) || c.stopped || !(c.speed > 0.5)) return;
        spT += gpsSpeed; spA += c.speed / cfgShadow.speedScale; spN++;
        double sc = Garage.speedScale(priorT + spT, priorA + spA, priorN + spN);
        if (spN % 10 == 0 && cfgMain != null) { cfgMain.speedScale = sc; cfgShadow.speedScale = sc; scaleInUse = sc; }   // used from the next second on
    }

    /** Engine thread, every tick: hands the planned route and the learned roads to both engines when they change. */
    private void syncPriors() {
        Engine e = engine; if (e == null) return; Geo f = e.frame();
        if (f == null) return;
        MapBundle mb = map;
        if (route != appliedRoute || e != appliedEngine || shadow != appliedShadow) {
            appliedRoute = route; appliedEngine = e; appliedShadow = shadow;
            com.maverickgrid.engine.Lines rl = null; Router.Route r = route;
            if (r != null && mb != null && mb.geo.same(f)) rl = new com.maverickgrid.engine.Lines(25.0).addPolyline(r.xs, r.ys, r.xs.length, true, 0.0).build();
            e.setRoute(rl); if (shadow != null) shadow.setRoute(rl);
            double[] px = rl != null ? r.xs : null, py = rl != null ? r.ys : null; e.setRoutePath(px, py); if (shadow != null) shadow.setRoutePath(px, py);
            e.setLearned(learnedLines); if (shadow != null) shadow.setLearned(learnedLines);
        }
        boolean mapReady = mb != null && mb.geo.same(f), noMap = snap.mapInfo.startsWith("map: none");
        Roads wantRoads = mapReady ? mb.roads : null;
        if (source == Source.LIVE && (learnedFor != f || learnedRoadsFor != wantRoads) && !learnedLoading && (mapReady || noMap)) {
            learnedLoading = true; final Geo g = f; final int ses = session; final Roads rr = wantRoads; learnedRoadsFor = rr;
            bg.execute(() -> {
                com.maverickgrid.engine.Lines l; try { l = LearnedRoads.linesFor(this, g, rr); } catch (RuntimeException | OutOfMemoryError ex) { l = null; }
                final com.maverickgrid.engine.Lines fl = l;
                h.post(() -> {
                    learnedLoading = false; if (ses != session) return;
                    learnedFor = g; learnedLines = fl; snap.learnedSegs = fl == null ? 0 : fl.size();
                    if (engine != null) engine.setLearned(fl); if (shadow != null) shadow.setLearned(fl);
                });
            });
        }
    }

    public void startReplay(double speedFactor) {
        ++session; final int g0 = fgGen;
        h.post(() -> {
            stopInternal(false); if (g0 == fgGen) stopForeground(STOP_FOREGROUND_REMOVE);   // the activity has started this service for the demo
            try {
                model = new Model(asset("models/car_heldout_S1/"));
                ArrayStore rep = new ArrayStore(asset("replay/S1/"));
                rAcc = rep.get("acc"); rGrav = rep.get("grav"); rGyr = rep.get("gyr"); rX = rep.get("tr_x"); rY = rep.get("tr_y"); rH = rep.get("tr_heading"); rS = rep.get("tr_speed");
                rN = rep.rows("acc"); rI = 0; rGeo = ArrayStore.frameOf(rep.get("tr_lat"), rep.get("tr_lon"), rX, rY);
            } catch (RuntimeException | IOException e) { snap.modelInfo = "replay load failed: " + e.getMessage(); notifyUi(); return; }
            Engine.Config c = new Engine.Config(); c.yawAxis = model.yawAxis; c.yawSign = model.headingSign; c.stopLearnedGyroBias = true;
            engine = new Engine(model, c); engine.setFrame(rGeo); rnd = new Random(7);
            snap.modelInfo = "model: car (trained without drive S1)"; snap.warning = "";
            MapBundle mb = map; if (mb != null && mb.geo.same(rGeo)) engine.setRoads(mb.roads); else loadMap(rGeo, engine);
            source = Source.REPLAY; snap.source = source; resetStats(); snap.vehicleName = "demo car"; wake.acquire(6 * 3600 * 1000L); stopWhenSaved = false;
            shadow = null; openDrive(true, rGeo);
            t0 = SystemClock.uptimeMillis(); ticks = 0; tickPeriodMs = Math.max(5, Math.round(100 / speedFactor)); h.removeCallbacks(tick); h.postAtTime(tick, t0 + tickPeriodMs);
            notifyUi();
        });
    }

    public void stopAll() { ++session; final int g = fgGen; h.post(() -> { stopInternal(g == fgGen); notifyUi(); }); }

    private void dropForeground() { stopForeground(STOP_FOREGROUND_REMOVE); requestStop(); }

    // the service stays started until every drive report has been written (engine thread)
    private int savesInFlight; private boolean stopWhenSaved;
    private void requestStop() { if (savesInFlight > 0) stopWhenSaved = true; else stopSelf(); }

    /** Engine thread only. dropForeground=false when a live session (foreground) has been requested meanwhile or follows immediately. */
    private void stopInternal(boolean dropFg) {
        h.removeCallbacks(tick);
        if (source != Source.NONE) finishDrive();
        sm.unregisterListener(this);
        try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
        if (dropFg) dropForeground();
        source = Source.NONE; snap.source = source; snap.mode = null; snap.frame = null;
        synchronized (lock) { haveAcc = haveGyr = haveGrav = false; accN = gyrN = 0; }
        if (wake.isHeld()) wake.release();
    }

    public void setSimulateLoss(boolean on) { h.post(() -> { simulateLoss = on; snap.simulateLoss = on; if (!on) inLoss = false; notifyUi(); }); }

    /** Engine thread: opens the drive's folder (Documents/Maverick/<vehicle>/drive_<date>/) and starts the raw logs. */
    private void openDrive(boolean demo, Geo frame) {
        java.util.Date now = new java.util.Date();
        driveStarted = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now);
        String name = (demo ? "demo_" : "drive_") + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now);
        String folder = demo ? "Demo" : vehicleId >= 0 ? new Garage.Vehicle(vehicleId, vehicleName, vehicle).slug() : vehicle;
        dstore = new DriveStore(this, folder, name); driveT0Ns = SystemClock.elapsedRealtimeNanos(); driveStartEpoch = System.currentTimeMillis();
        dlog = frame != null ? newLog(frame, demo) : null;
        snap.reportStatus = ""; snap.recording = false;
        if (!demo) {
            boolean raw = getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("raw_imu", true);
            try {
                logger.start(dstore, raw, logHz(), driveT0Ns, vehicle, vehicleName, vehicleId, "fixed"); snap.recording = logger.sensorsActive();
                if (raw) {                                      // extra sensors only needed for sensors.csv
                    Garage.addRecording(this, vehicleId);
                    Sensor mf = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD); if (mf != null) sm.registerListener(this, mf, 20000, h);
                }
            }
            catch (IOException | RuntimeException e) { snap.reportStatus = "Could not save drive data: " + e.getMessage(); }
        }
    }

    private DriveLog newLog(Geo frame, boolean demo) {
        String who = demo ? "Demo car (IO-VNBD drive S1)" : vehicleName.isEmpty() ? vehicle : vehicleName;
        return new DriveLog(frame, who + " · " + driveStarted, demo ? "Demo car" : who, demo ? "car" : vehicle, driveStarted, demo);
    }

    /** Engine thread: closes the drive; metrics, CSV / GeoJSON / GPX / JSON and the PNG report are written in the background. */
    private void finishDrive() {
        final DriveLog d = dlog; final DriveStore st = dstore; final boolean live = source == Source.LIVE; final long vid = vehicleId, epoch = driveStartEpoch;
        final double fT = spT, fA = spA; final int fN = spN; spT = spA = 0; spN = 0; final int[][] drivenPts = live ? learnedRec.takeDrive() : null;
        if (live && engine != null && vid >= 0 && !Double.isNaN(engine.mountOffset())) Garage.saveMountOffset(this, vid, engine.mountOffset());
        if (live && engine != null && vid >= 0) Garage.saveStopLearning(this, vid, engine.movingHist(), engine.stillHist(), engine.stopThreshold());
        final double km = dKm, drKm = dDrKm, testPct = dTestPct, hours = (SystemClock.elapsedRealtime() - driveStartMs) / 3.6e6; final int outs = dOutages, tests = dTests;
        dlog = null; dstore = null; shadow = null; dKm = 0;
        logger.stop(); snap.recording = false;
        if (st == null) return;
        snap.reportStatus = "Saving the drive report…"; notifyUi();
        savesInFlight++;
        bg.execute(() -> {
            String status; String uri = "";
            try {
                DriveLog.Metrics M = null;
                if (d != null && d.n > 10) {
                    M = d.compute();
                    try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(st.open("track.csv", "text/csv"), java.nio.charset.StandardCharsets.UTF_8))) { d.writeTrackCsv(w); }
                    try (java.io.Writer w = new java.io.OutputStreamWriter(st.open("track.geojson", "application/geo+json"), java.nio.charset.StandardCharsets.UTF_8)) { d.writeGeoJson(w, M); }
                    try (java.io.Writer w = new java.io.OutputStreamWriter(st.open("track.gpx", "application/gpx+xml"), java.nio.charset.StandardCharsets.UTF_8)) { d.writeGpx(w, epoch); }
                    try (java.io.Writer w = new java.io.OutputStreamWriter(st.open("summary.json", "application/json"), java.nio.charset.StandardCharsets.UTF_8)) { d.writeSummary(w, M); }
                    android.graphics.Bitmap bmp = ReportImage.render(d, M);
                    try (java.io.OutputStream o = st.open("report.png", "image/png")) { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
                    bmp.recycle();
                    android.net.Uri u = st.uri("report.png"); uri = u != null ? u.toString() : "";
                }
                if (live && vid >= 0 && km > 0.02) {
                    double sumDrift = testPct; int nt = tests;
                    if (M != null) for (DriveLog.Window w : M.list) if (w.scored) { sumDrift += 100 * w.finalErrM / w.distM; nt++; }
                    Garage.addDrive(this, vid, km, drKm, outs, nt, sumDrift, hours);
                    if (M != null) Garage.addSpeedSample(this, vid, M.sumTruthSpeed, M.sumDrSpeed);
                    Garage.addSpeedMoving(this, vid, fT, fA, fN);
                }
                if (drivenPts != null && drivenPts[0].length > 1) LearnedRoads.merge(this, drivenPts);
                if (M == null) status = d == null ? "Drive data saved (no GPS fix, so no report)" : "Drive data saved (too short for a report)";
                else status = String.format(Locale.US, "Report saved: %.2f km, %s", M.distanceKm, Double.isNaN(M.driftMedianPct) ? "no GPS-loss test scored" : String.format(Locale.US, "median drift %.1f %% over %d test%s", M.driftMedianPct, M.scoredWindows, M.scoredWindows == 1 ? "" : "s"));
            } catch (IOException | RuntimeException | OutOfMemoryError e) { status = "Could not save the report: " + e; }
            finally { st.publish(); }                             // raw data becomes visible even if the report failed
            final String fs = status, fu = uri, fw = st.where();
            h.post(() -> {
                snap.reportStatus = fs; snap.reportWhere = fw; notifyUi();
                if (!fu.isEmpty()) { snap.reportUri = fu; getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("last_report", fu).apply(); }   // else keep the previous report link
                if (--savesInFlight == 0 && stopWhenSaved && source == Source.NONE) { stopWhenSaved = false; stopSelf(); }
            });
        });
    }

    public void setRawSaving(boolean on) { getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("raw_imu", on).apply(); }
    /** Rows per second of sensors.csv (10, 25, 50 or 100; default 50). */
    public int logHz() { return getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("log_hz", 50); }
    public void setLogHz(int hz) { getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("log_hz", hz).apply(); }
    public boolean rawSaving() { return getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("raw_imu", true); }

    @Override public void onDestroy() {
        h.post(() -> {                                     // tear down on the engine thread, after any running tick
            h.removeCallbacksAndMessages(null); sm.unregisterListener(this); try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
            logger.stop(); if (wake.isHeld()) wake.release(); bg.shutdown(); thread.quitSafely();   // shutdown(): a queued report is still written
        });
        if (tts != null) tts.shutdown(); super.onDestroy();
    }

    // ------------------------------------------------------------------ maps, search, routing
    /** Load a map for browsing and search around a location (e.g. last known position) when no session is running. */
    public void browseAround(double lat, double lon) {
        h.post(() -> {
            if (source != Source.NONE) return;
            if (map != null && covers(map.geo, lat, lon)) return;                   // the loaded map already covers this spot
            if (mapRequested != null && covers(mapRequested, lat, lon) && (map == null || map.geo != mapRequested)) return;   // already loading one for it
            loadMap(new Geo(lat, lon), null);
        });
    }
    private static boolean covers(Geo g, double lat, double lon) { return Math.abs(g.x(lon)) < 20000 && Math.abs(g.y(lat)) < 20000; }
    /** Map frame currently loaded (null if none). */
    public Geo mapGeo() { MapBundle mb = map; return mb != null ? mb.geo : null;
    }

    /** Loads the first map file with roads within 30 km of the frame origin, on the background thread; installs it on the engine thread. */
    private void loadMap(Geo g, Engine forEngine) {
        final int gen = ++mapGen; mapRequested = g; snap.mapInfo = "map: loading...";
        bg.execute(() -> { try { loadMapInBackground(g, forEngine, gen); } catch (Throwable e) { h.post(() -> { if (gen == mapGen) { snap.mapInfo = "map: could not load (" + e.getMessage() + ")"; notifyUi(); } }); } });
    }

    private void loadMapInBackground(Geo g, Engine forEngine, int gen) {
        {
            List<Object> candidates = new ArrayList<>();
            File dir = getExternalFilesDir("maps");
            if (dir != null) { File[] fs = dir.listFiles((d, n) -> n.endsWith(".mgr")); if (fs != null) for (File f : fs) candidates.add(f); }
            try { String[] as = getAssets().list("maps"); if (as != null) for (String n : as) if (n.endsWith(".mgr")) candidates.add("maps/" + n); } catch (IOException ignored) { }
            double[] bbox = {-30000, 30000, -30000, 30000};
            for (Object c : candidates) {
                try (InputStream in = c instanceof File ? new FileInputStream((File) c) : getAssets().open((String) c)) {
                    Roads r = new Roads(in, g, bbox, false, 25.0);
                    if (r.n > 100) {
                        String name = c instanceof File ? ((File) c).getName() : (String) c;
                        Router rt = new Router(r); PlaceSearch ps = new PlaceSearch(r);
                        h.post(() -> {
                            if (gen != mapGen) return;                   // a newer load was requested meanwhile
                            MapBundle old = map; map = new MapBundle(r, rt, ps, g); mapName = name.replace("maps/", "");
                            if (forEngine != null && forEngine == engine && engine.frame() != null && engine.frame().same(g)) engine.setRoads(r);
                            if (shadow != null && shadow.frame() != null && shadow.frame().same(g)) shadow.setRoads(r);
                            if (old == null || !old.geo.same(g)) { clearRouteInternal(); if (source == Source.NONE) { engineTrail.clear(); gnssTrail.clear(); } }     // routes and selections belong to a frame
                            snap.mapInfo = "map: " + name.replace("maps/", "") + " (" + r.n + " roads, " + r.np + " places)"; notifyUi();
                        });
                        return;
                    }
                } catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
            }
            h.post(() -> { if (gen == mapGen) { snap.mapInfo = "map: none covers this area (navigation without map)"; notifyUi(); } });
        }
    }

    /** Current position for routing: the vehicle if a session is running, otherwise null. */
    public double[] currentPosition() { return snap.mode != null && snap.mode != Engine.Mode.WAITING_FOR_GNSS ? new double[]{snap.x, snap.y} : null; }

    /** Compute a route (background) from the vehicle (or `fromXY` if no vehicle) to the target and start guidance. */
    public void navigateTo(Target t, double[] fromXY) {
        MapBundle mb = map; if (mb == null) { snap.navStatus = "no map loaded yet"; notifyUi(); return; }
        if (t.geo == null || !t.geo.same(mb.geo)) { snap.navStatus = "that selection belongs to a previous map; search again"; notifyUi(); return; }
        Router rt = mb.router; double[] from = currentPosition(); if (from != null && (snap.frame == null || !snap.frame.same(mb.geo))) from = null;
        boolean fromCentre = from == null; if (from == null) from = fromXY; final double[] f = from;
        snap.navStatus = fromCentre ? "route from the map centre (no position yet)..." : "computing route..."; notifyUi();
        bg.execute(() -> {
            Router.Route r;
            try { r = rt.route(f[0], f[1], t.x, t.y); }
            catch (Throwable e) { h.post(() -> { snap.navStatus = "routing failed: " + e; notifyUi(); }); return; }
            h.post(() -> {
                if (map == null || rt != map.router) return;
                if (r == null) { snap.navStatus = "no route: " + rt.lastError; notifyUi(); return; }
                route = r; nav = new Navigator(r); navTarget = t; spokenFor = -1; spokenStage = 0; lastReroute = SystemClock.uptimeMillis();
                snap.navActive = true; snap.navDestination = t.name; snap.navStatus = fromCentre ? "press Start for live turn-by-turn guidance" : "";
                snap.navRemainM = r.lengthM; snap.navRemainS = r.timeS; Router.Maneuver m0 = r.maneuvers.size() > 1 ? r.maneuvers.get(1) : r.maneuvers.get(0);
                snap.navNextM = m0.atM; snap.navText = m0.text; snap.navTurn = m0.turn; updateNav(); say("Route to " + t.name + ", " + km(r.lengthM)); notifyUi();
            });
        });
    }

    public void clearRoute() { h.post(() -> { clearRouteInternal(); notifyUi(); }); }

    private void clearRouteInternal() { route = null; nav = null; navTarget = null; snap.navActive = false; snap.navText = ""; snap.navStatus = ""; snap.navDestination = ""; }

    /** Engine thread: progress along the route, spoken instructions, automatic re-route. */
    private void updateNav() {
        Navigator n = nav; if (n == null || snap.mode == null || snap.mode == Engine.Mode.WAITING_FOR_GNSS) return;
        n.update(snap.x, snap.y);
        snap.navRemainM = n.remainingM; snap.navRemainS = n.remainingS(); snap.navNextM = n.nextDistM;
        Router.Maneuver m = n.next; snap.navText = m == null ? "" : m.text; snap.navTurn = m == null ? 0 : m.turn;
        if (m != null) {
            int idx = n.route.maneuvers.indexOf(m);
            if (idx != spokenFor) { spokenFor = idx; spokenStage = 0; }
            if (spokenStage == 0 && n.nextDistM < 250 && n.nextDistM > 60) { say("In " + Math.round(n.nextDistM / 10) * 10 + " metres, " + m.text); spokenStage = 1; }
            else if (spokenStage < 2 && n.nextDistM <= 60) { say(m.text); spokenStage = 2; }
        }
        if (n.arrived) { say("You have arrived at " + snap.navDestination); clearRouteInternal(); snap.navStatus = "arrived";
            h.postDelayed(() -> { if (!snap.navActive && "arrived".equals(snap.navStatus)) { snap.navStatus = ""; notifyUi(); } }, 20000); return; }
        MapBundle mb = map;
        if (n.offRoute && navTarget != null && mb != null && snap.mode != Engine.Mode.DEAD_RECKONING && SystemClock.uptimeMillis() - lastReroute > 10000) {   // no re-route from an uncertain dead-reckoned position: the planned route keeps guiding it
            lastReroute = SystemClock.uptimeMillis();
            if (mb.roads.nearest(snap.x, snap.y, 40, stamp) < 0) { snap.navStatus = "off the road network"; return; }
            snap.navStatus = "re-routing..."; Target t = navTarget; Router rt = mb.router; double sx = snap.x, sy = snap.y;
            bg.execute(() -> { Router.Route r; try { r = rt.route(sx, sy, t.x, t.y); } catch (Throwable e) { r = null; } final Router.Route rr = r; h.post(() -> {
                if (navTarget != t) return;
                if (rr != null) { route = rr; nav = new Navigator(rr); spokenFor = -1; snap.navStatus = ""; } else snap.navStatus = "could not re-route: " + rt.lastError; }); });
        }
    }

    private void say(String s) { if (ttsReady) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "nav"); }
    static String km(double m) { return m >= 1000 ? String.format(Locale.US, "%.1f kilometres", m / 1000) : Math.round(m / 10) * 10 + " metres"; }

    // ------------------------------------------------------------------ sensors / GNSS
    @Override public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        synchronized (lock) {
            if (t == Sensor.TYPE_ACCELEROMETER) {
                for (int i = 0; i < 3; i++) { accSum[i] += e.values[i]; accLast[i] = e.values[i]; } accN++; haveAcc = true;
                if (!gravFromSensor) { for (int i = 0; i < 3; i++) grav[i] = haveGrav ? 0.995 * grav[i] + 0.005 * e.values[i] : e.values[i]; haveGrav = true; }
            } else if (t == Sensor.TYPE_GYROSCOPE) { for (int i = 0; i < 3; i++) { gyrSum[i] += e.values[i]; gyrLast[i] = e.values[i]; } gyrN++; haveGyr = true; }
            else if (t == Sensor.TYPE_GRAVITY) { for (int i = 0; i < 3; i++) grav[i] = e.values[i]; haveGrav = true; }
        }
        if (t == Sensor.TYPE_ROTATION_VECTOR) compassFrom(e.values);
        logger.sensor(t, e.timestamp, e.values);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // vehicle-forward compass heading from the rotation vector: phone upright in a holder -> the way the back of the phone
    // faces; phone lying flat -> the way its top points (the engine learns the real mount offset from GPS bearings)
    private final float[] cRot = new float[9], cRot2 = new float[9], cOri = new float[3]; private volatile double compassRad = Double.NaN; private double declRad = Double.NaN;
    private void compassFrom(float[] v) {
        try { SensorManager.getRotationMatrixFromVector(cRot, v); } catch (IllegalArgumentException ex) { return; }
        if (Math.abs(cRot[8]) < 0.5f) SensorManager.remapCoordinateSystem(cRot, SensorManager.AXIS_X, SensorManager.AXIS_Z, cRot2); else System.arraycopy(cRot, 0, cRot2, 0, 9);
        SensorManager.getOrientation(cRot2, cOri);
        compassRad = cOri[0] + (Double.isNaN(declRad) ? 0 : declRad);
    }

    @Override public void onLocationChanged(Location l) {
        if (source != Source.LIVE || engine == null) return;
        logger.gnss(l.getElapsedRealtimeNanos(), l.getLatitude(), l.getLongitude(), l.getAltitude(), l.hasSpeed() ? l.getSpeed() : Float.NaN,
                l.hasBearing() ? l.getBearing() : Float.NaN, l.hasAccuracy() ? l.getAccuracy() : Float.NaN,
                l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : Float.NaN, l.hasBearingAccuracy() ? l.getBearingAccuracyDegrees() : Float.NaN, l.getProvider());
        if (Double.isNaN(declRad)) declRad = Math.toRadians(new android.hardware.GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(), (float) l.getAltitude(), System.currentTimeMillis()).getDeclination());
        learnedRec.fix(l.getElapsedRealtimeNanos(), l.getLatitude(), l.getLongitude(), l.hasSpeed() ? l.getSpeed() : Double.NaN, l.hasAccuracy() ? l.getAccuracy() : Double.NaN);
        Geo g = engine.frame();
        if (g != null) {
            double gx = g.x(l.getLongitude()), gy = g.y(l.getLatitude()); gnssTrail.add(gx, gy);
            if (inLoss && !Double.isNaN(lastGhostX)) lossDist += Math.hypot(gx - lastGhostX, gy - lastGhostY);
            lastGhostX = gx; lastGhostY = gy; snap.haveGhost = simulateLoss; snap.ghostX = gx; snap.ghostY = gy;
        }
        double spd = l.hasSpeed() ? l.getSpeed() : Double.NaN, brg = l.hasBearing() ? l.getBearing() : Double.NaN, acc = l.hasAccuracy() ? l.getAccuracy() : 10.0;
        if (!(simulateLoss && engine.mode() != Engine.Mode.WAITING_FOR_GNSS)) {         // "Test GPS loss" hides fixes from navigation
            if (engine.frame() == null) {
                MapBundle mb = map;
                if (mb != null && Math.abs(mb.geo.x(l.getLongitude())) < 20000 && Math.abs(mb.geo.y(l.getLatitude())) < 20000) { engine.setFrame(mb.geo); engine.setRoads(mb.roads); }
            }
            engine.gnss(l.getLatitude(), l.getLongitude(), spd, brg, acc);
            MapBundle cur = map;
            if (engine.frame() != null && (cur == null || !cur.geo.same(engine.frame())) && mapRequested != engine.frame()) loadMap(engine.frame(), engine);
        }
        // the check engine gets every fix except inside its test windows; the report keeps every good fix as truth
        Geo fg = engine.frame();
        if (fg != null) {
            if (shadow != null) {
                if (shadow.frame() == null) { shadow.setFrame(fg); MapBundle mb = map; if (mb != null && mb.geo.same(fg)) shadow.setRoads(mb.roads); }
                if (shadowWin < 0) shadow.gnss(l.getLatitude(), l.getLongitude(), spd, brg, acc);
                else learnSpeed(spd, acc);
            }
            if (dlog == null && dstore != null) dlog = newLog(fg, false);
            if (dlog != null && acc <= 25) dlog.truth((l.getElapsedRealtimeNanos() - driveT0Ns) / 1e9, fg.x(l.getLongitude()), fg.y(l.getLatitude()), spd, brg, acc);
        }
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { if (LocationManager.GPS_PROVIDER.equals(p)) { snap.warning = ""; notifyUi(); } }
    /** Location switched off: dead reckoning starts at once (no waiting for the missing fixes to time out). */
    @Override public void onProviderDisabled(String p) {
        if (!LocationManager.GPS_PROVIDER.equals(p)) return;
        snap.warning = "location is switched off"; if (engine != null && source == Source.LIVE) { engine.forceOutage(); publish(engine.state()); } notifyUi();
    }

    // ------------------------------------------------------------------ 10 Hz tick
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticks++; long due = t0 + ticks * tickPeriodMs, st = SystemClock.uptimeMillis(); h.postAtTime(this, t0 + (ticks + 1) * tickPeriodMs);
            if (source == Source.LIVE) liveTick(); else if (source == Source.REPLAY) replayTick();
            double ms = SystemClock.uptimeMillis() - st;             // engine load: time per tick and how late the tick ran
            snap.tickMsAvg = 0.98 * snap.tickMsAvg + 0.02 * ms; snap.tickMsMax = Math.max(snap.tickMsMax * 0.999, ms); snap.tickLagMs = 0.9 * snap.tickLagMs + 0.1 * Math.max(0, st - due);
        }
    };

    private void liveTick() {
        double[] a = new double[3], g = new double[3], w = new double[3];
        synchronized (lock) {
            if (!(haveAcc && haveGyr && haveGrav)) { if (ticks == 50) { snap.warning = "waiting for motion sensors"; notifyUi(); } return; }
            for (int i = 0; i < 3; i++) {
                a[i] = accN > 0 ? accSum[i] / accN : accLast[i]; w[i] = gyrN > 0 ? gyrSum[i] / gyrN : gyrLast[i]; g[i] = grav[i];
                accSum[i] = 0; gyrSum[i] = 0;
            }
            accN = 0; gyrN = 0;
        }
        syncPriors();
        double cmp = compassRad; engine.compass(cmp); if (shadow != null) shadow.compass(cmp);
        engine.imu(a, g, w);
        Engine.State s = engine.state();
        if (shadow != null) {
            if (fixTick0 < 0 && engine.mode() != Engine.Mode.WAITING_FOR_GNSS) fixTick0 = ticks;
            long q = fixTick0 < 0 ? -1 : ticks - fixTick0 - CHK_WARMUP;
            shadowWin = q >= 0 && (q % CHK_EVERY) < CHK_OUT ? (int) (q / CHK_EVERY) : -1;
            shadow.imu(a, g, w);
            if (dlog != null) {
                Engine.State c = shadow.state(); int wid = shadowWin >= 0 && c.mode == Engine.Mode.DEAD_RECKONING ? shadowWin : -1;
                dlog.tick((SystemClock.elapsedRealtimeNanos() - driveT0Ns) / 1e9, modeCode(s.mode), s.displayX, s.displayY, s.speed, s.headingRad, wid, c.x, c.y, c.speed, c.headingRad);
            }
        }
        if (simulateLoss && engine.mode() == Engine.Mode.DEAD_RECKONING && !inLoss) { inLoss = true; lossDist = 0; }
        if (inLoss && snap.haveGhost) { snap.liveErrM = Math.hypot(s.displayX - snap.ghostX, s.displayY - snap.ghostY); snap.liveDistM = lossDist; }
        if (!simulateLoss && inLoss) {                   // a "Test GPS loss" just ended: keep its result
            inLoss = false;
            if (snap.liveDistM > 50 && !Double.isNaN(snap.liveErrM)) {
                double pct = 100 * snap.liveErrM / snap.liveDistM; snap.outages++; snap.sumDriftPct += pct; dTests++; dTestPct += pct;
                snap.lastOutage = String.format(Locale.US, "GPS-loss test: %.0f m travelled, error %.0f m = %.1f %%", snap.liveDistM, snap.liveErrM, pct);
            }
            snap.liveErrM = Double.NaN; snap.haveGhost = false;
        }
        publish(s);
    }

    private void replayTick() {
        if (rI >= rN) { stopInternal(true); snap.lastOutage = "Demo drive finished. " + snap.lastOutage; notifyUi(); return; }   // stops the started service once the report is saved
        int i = rI++; syncPriors();
        engine.imu(new double[]{rAcc[3 * i], rAcc[3 * i + 1], rAcc[3 * i + 2]}, new double[]{rGrav[3 * i], rGrav[3 * i + 1], rGrav[3 * i + 2]}, new double[]{rGyr[3 * i], rGyr[3 * i + 1], rGyr[3 * i + 2]});
        int phase = i >= REPLAY_WARMUP ? (i - REPLAY_WARMUP) % REPLAY_EVERY : -1; boolean outage = phase >= 0 && phase < REPLAY_OUTAGE;
        if (phase == 0) { lossDist = 0; snap.liveErrM = Double.NaN; }
        boolean valid = !Double.isNaN(rX[i]);
        if (valid) {
            if (i % 10 == 0) gnssTrail.add(rX[i], rY[i]);
            snap.haveGhost = outage; snap.ghostX = rX[i]; snap.ghostY = rY[i];
            if (outage && i > 0 && !Double.isNaN(rX[i - 1])) lossDist += Math.hypot(rX[i] - rX[i - 1], rY[i] - rY[i - 1]);
            if (i % 10 == 0 && !outage) {
                double sp = Math.max(0, rS[i] + rnd.nextGaussian() * 0.2), br = sp > 2 ? Math.toDegrees(rH[i]) + rnd.nextGaussian() * 2 : Double.NaN;
                engine.gnss(rGeo.lat(rY[i] + rnd.nextGaussian() * 3), rGeo.lon(rX[i] + rnd.nextGaussian() * 3), sp, br, 5.0);
            }
        }
        Engine.State s = engine.state();
        if (dlog != null) {
            int wid = outage && s.mode == Engine.Mode.DEAD_RECKONING ? (i - REPLAY_WARMUP) / REPLAY_EVERY : -1;
            dlog.tick(i / 10.0, modeCode(s.mode), s.displayX, s.displayY, s.speed, s.headingRad, wid, s.x, s.y, s.speed, s.headingRad);
            if (valid) dlog.truth(i / 10.0, rX[i], rY[i], rS[i], Math.toDegrees(rH[i]), 0);
        }
        if (outage && valid) { snap.liveErrM = Math.hypot(s.x - rX[i], s.y - rY[i]); snap.liveDistM = lossDist; }
        if (phase == REPLAY_OUTAGE - 1 && valid && lossDist > 50 && !Double.isNaN(snap.liveErrM)) {
            double pct = 100 * snap.liveErrM / lossDist; snap.outages++; snap.sumDriftPct += pct;
            snap.lastOutage = String.format(Locale.US, "last 60 s outage: %.0f m travelled, error %.0f m = %.1f %%", lossDist, snap.liveErrM, pct);
        }
        publish(s);
    }

    private static int modeCode(Engine.Mode m) { return m == Engine.Mode.GNSS ? 1 : m == Engine.Mode.DEAD_RECKONING ? 2 : 0; }

    private void publish(Engine.State s) {
        snap.stopP = s.stopP; snap.stopThreshold = s.stopThreshold; snap.stopped = s.stopped; snap.routePrior = s.routeActive; snap.headingUnsure = s.headingUnsure; snap.routeLocked = s.routeLocked; snap.routeEvent = s.routeEvent;
        snap.mode = s.mode; snap.x = s.displayX; snap.y = s.displayY; snap.heading = s.headingRad; snap.speed = s.speed; snap.outageS = s.outageS; snap.lat = s.displayLat; snap.lon = s.displayLon; snap.drDistM = s.drDistM; snap.frame = engine.frame();
        if (s.mode != Engine.Mode.WAITING_FOR_GNSS) engineTrail.add(s.displayX, s.displayY, s.mode == Engine.Mode.DEAD_RECKONING);
        if (source == Source.LIVE && s.mode != Engine.Mode.WAITING_FOR_GNSS) {
            if (!Double.isNaN(lastPX)) { double d = Math.hypot(s.displayX - lastPX, s.displayY - lastPY); if (d < 60) { dKm += d / 1000; if (s.mode == Engine.Mode.DEAD_RECKONING && !simulateLoss) dDrKm += d / 1000; } }
            lastPX = s.displayX; lastPY = s.displayY;
            if (prevMode == Engine.Mode.GNSS && s.mode == Engine.Mode.DEAD_RECKONING && !simulateLoss) dOutages++;
            prevMode = s.mode;
        }
        if (nav != null) updateNav();
        if (ticks % Math.max(1, 100 / tickPeriodMs) == 0) notifyUi();
    }

    /** At most one UI update waits in the main queue: a slow frame never builds up a backlog (the UI always shows the latest state). */
    private void notifyUi() {
        Listener l = listener; if (l == null || !uiPending.compareAndSet(false, true)) return;
        main.post(() -> { uiPending.set(false); Listener m = listener; if (m != null) m.onUpdate(); });
    }

    private void resetStats() {
        simulateLoss = false; snap.simulateLoss = false;
        engineTrail.clear(); gnssTrail.clear(); snap.outages = 0; snap.sumDriftPct = 0; snap.lastOutage = ""; snap.liveErrM = Double.NaN; snap.liveDistM = 0;
        snap.haveGhost = false; inLoss = false; lastGhostX = Double.NaN; snap.mode = null;
    }

    private java.util.function.Function<String, InputStream> asset(String prefix) {
        AssetManager am = getAssets();
        return name -> { try { return am.open(prefix + name); } catch (IOException e) { throw new UncheckedIOException(e); } };
    }
}
