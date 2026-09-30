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
        public volatile double x, y, heading, speed, outageS, lat, lon;
        public volatile boolean haveGhost; public volatile double ghostX, ghostY; public volatile double liveErrM = Double.NaN, liveDistM = 0;
        public volatile String lastOutage = "", mapInfo = "map: none loaded", modelInfo = "", warning = "", recordDir = "";
        public volatile boolean simulateLoss, recording; public volatile int outages; public volatile double sumDriftPct;
        // navigation
        public volatile boolean navActive; public volatile String navText = "", navDestination = "", navStatus = ""; public volatile int navTurn;
        public volatile double navNextM, navRemainM, navRemainS;
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
    private volatile boolean simulateLoss = false;
    private long ticks = 0, tickPeriodMs = 100, t0;
    private String vehicle = "car"; private String mapName = "";
    private double lossDist; private double lastGhostX = Double.NaN, lastGhostY = Double.NaN; private boolean inLoss;
    private double[] rAcc, rGrav, rGyr, rX, rY, rH, rS; private int rI, rN; private Geo rGeo; private Random rnd = new Random(7);
    private static final int REPLAY_WARMUP = 1800, REPLAY_EVERY = 1800, REPLAY_OUTAGE = 600;   // 10 Hz samples: 60 s outage every 3 min

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("maverick-engine", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); thread.start(); h = new Handler(thread.getLooper());
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE); lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaverickGRID:engine"); wake.setReferenceCounted(false);
        tts = new TextToSpeech(this, st -> { ttsReady = st == TextToSpeech.SUCCESS; if (ttsReady) tts.setLanguage(Locale.UK); });
    }

    public static final String ACTION_LIVE = "com.maverickgrid.app.LIVE", EXTRA_VEHICLE = "vehicle";

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
        String v = intent.getStringExtra(EXTRA_VEHICLE); startLive(v != null ? v : "car");
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
    private void startLive(String vehicleName) {
        ++session; vehicle = vehicleName;
        h.post(() -> {
            stopInternal(false);
            try {
                model = new Model(asset("models/" + vehicle + "/"));
            } catch (RuntimeException | IOException e) { snap.modelInfo = "model load failed: " + e.getMessage(); dropForeground(); notifyUi(); return; }
            Engine.Config c = new Engine.Config(); c.yawAxis = -1; c.yawSign = -1.0; c.stopLearnedGyroBias = true;   // rotation about gravity
            engine = new Engine(model, c); snap.modelInfo = "model: " + vehicle;   // frame is chosen at the first fix (see onLocationChanged)
            snap.warning = "";
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), gy = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), gr = sm.getDefaultSensor(Sensor.TYPE_GRAVITY), mg = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (a == null || gy == null) { snap.warning = "this phone has no " + (a == null ? "accelerometer" : "gyroscope") + ": dead reckoning is not possible"; dropForeground(); notifyUi(); return; }
            gravFromSensor = gr != null;
            sm.registerListener(this, a, 10000, h); sm.registerListener(this, gy, 10000, h);
            if (gr != null) sm.registerListener(this, gr, 10000, h);
            if (mg != null) sm.registerListener(this, mg, 20000, h);
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.getLooper()); }
            catch (SecurityException e) { snap.warning = "precise location permission is needed"; }
            catch (IllegalArgumentException e) { snap.warning = "this phone has no GPS receiver"; }
            source = Source.LIVE; snap.source = source; resetStats(); wake.acquire(6 * 3600 * 1000L);
            snap.mapInfo = map != null ? "map: " + mapName + " (used if the first fix is inside it)" : "map: waiting for first GNSS fix";
            t0 = SystemClock.uptimeMillis(); ticks = 0; tickPeriodMs = 100; h.removeCallbacks(tick); h.postAtTime(tick, t0 + 100);
            notifyUi();
        });
    }

    public void startReplay(double speedFactor) {
        ++session; final int g0 = fgGen;
        h.post(() -> {
            stopInternal(g0 == fgGen);
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
            source = Source.REPLAY; snap.source = source; resetStats(); wake.acquire(6 * 3600 * 1000L);
            t0 = SystemClock.uptimeMillis(); ticks = 0; tickPeriodMs = Math.max(5, Math.round(100 / speedFactor)); h.removeCallbacks(tick); h.postAtTime(tick, t0 + tickPeriodMs);
            notifyUi();
        });
    }

    public void stopAll() { ++session; final int g = fgGen; h.post(() -> { stopInternal(g == fgGen); notifyUi(); }); }

    private void dropForeground() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }

    /** Engine thread only. dropForeground=false when a live session (foreground) has been requested meanwhile or follows immediately. */
    private void stopInternal(boolean dropFg) {
        h.removeCallbacks(tick);
        sm.unregisterListener(this);
        try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
        if (dropFg) dropForeground();
        source = Source.NONE; snap.source = source; snap.mode = null;
        synchronized (lock) { haveAcc = haveGyr = haveGrav = false; accN = gyrN = 0; }
        if (wake.isHeld()) wake.release();
    }

    public void setSimulateLoss(boolean on) { h.post(() -> { simulateLoss = on; snap.simulateLoss = on; if (!on) inLoss = false; notifyUi(); }); }

    public void setRecording(boolean on, String mount) {
        h.post(() -> {
            try {
                if (on) { File d = logger.start(getExternalFilesDir("rides"), vehicle, mount); snap.recordDir = d.getAbsolutePath(); } else logger.stop();
                snap.recording = logger.active();
            } catch (IOException e) { snap.recordDir = "recording failed: " + e.getMessage(); snap.recording = false; }
            notifyUi();
        });
    }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null); sm.unregisterListener(this); try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
        logger.stop(); if (wake.isHeld()) wake.release(); if (tts != null) tts.shutdown(); bg.shutdownNow(); thread.quitSafely(); super.onDestroy();
    }

    // ------------------------------------------------------------------ maps, search, routing
    /** Load a map for browsing and search around a location (e.g. last known position) when no session is running. */
    public void browseAround(double lat, double lon) {
        h.post(() -> { if (source == Source.NONE && map == null && mapRequested == null) loadMap(new Geo(lat, lon), null); });
    }

    /** Loads the first map file with roads within 30 km of the frame origin, on the background thread; installs it on the engine thread. */
    private void loadMap(Geo g, Engine forEngine) {
        final int gen = ++mapGen; mapRequested = g; snap.mapInfo = "map: loading...";
        bg.execute(() -> {
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
                            if (old == null || !old.geo.same(g)) clearRouteInternal();     // routes and selections belong to a frame
                            snap.mapInfo = "map: " + name.replace("maps/", "") + " (" + r.n + " roads, " + r.np + " places)"; notifyUi();
                        });
                        return;
                    }
                } catch (IOException | RuntimeException ignored) { }
            }
            h.post(() -> { if (gen == mapGen) { snap.mapInfo = "map: none covers this area (navigation without map)"; notifyUi(); } });
        });
    }

    /** Current position for routing: the vehicle if a session is running, otherwise null. */
    public double[] currentPosition() { return snap.mode != null && snap.mode != Engine.Mode.WAITING_FOR_GNSS ? new double[]{snap.x, snap.y} : null; }

    /** Compute a route (background) from the vehicle (or `fromXY` if no vehicle) to the target and start guidance. */
    public void navigateTo(Target t, double[] fromXY) {
        MapBundle mb = map; if (mb == null) { snap.navStatus = "no map loaded yet"; notifyUi(); return; }
        if (t.geo == null || !t.geo.same(mb.geo)) { snap.navStatus = "that selection belongs to a previous map; search again"; notifyUi(); return; }
        Router rt = mb.router; double[] from = currentPosition(); boolean fromCentre = from == null; if (from == null) from = fromXY; final double[] f = from;
        snap.navStatus = fromCentre ? "route from the map centre (no position yet)..." : "computing route..."; notifyUi();
        bg.execute(() -> {
            Router.Route r = rt.route(f[0], f[1], t.x, t.y);
            h.post(() -> {
                if (map == null || rt != map.router) return;
                if (r == null) { snap.navStatus = "no route: " + rt.lastError; notifyUi(); return; }
                route = r; nav = new Navigator(r); navTarget = t; spokenFor = -1; spokenStage = 0; lastReroute = SystemClock.uptimeMillis();
                snap.navActive = true; snap.navDestination = t.name; snap.navStatus = fromCentre ? "route starts at the map centre" : ""; updateNav(); say("Route to " + t.name + ", " + km(r.lengthM)); notifyUi();
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
        if (n.arrived) { say("You have arrived at " + snap.navDestination); clearRouteInternal(); snap.navStatus = "arrived"; return; }
        MapBundle mb = map;
        if (n.offRoute && navTarget != null && mb != null && SystemClock.uptimeMillis() - lastReroute > 10000) {
            lastReroute = SystemClock.uptimeMillis();
            if (mb.roads.nearest(snap.x, snap.y, 40, stamp) < 0) { snap.navStatus = "off the road network"; return; }
            snap.navStatus = "re-routing..."; Target t = navTarget; Router rt = mb.router; double sx = snap.x, sy = snap.y;
            bg.execute(() -> { Router.Route r = rt.route(sx, sy, t.x, t.y); h.post(() -> {
                if (navTarget != t) return;
                if (r != null) { route = r; nav = new Navigator(r); spokenFor = -1; snap.navStatus = ""; } else snap.navStatus = "could not re-route: " + rt.lastError; }); });
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
        if (logger.active()) {
            String name = t == Sensor.TYPE_ACCELEROMETER ? "acc" : t == Sensor.TYPE_GYROSCOPE ? "gyr" : t == Sensor.TYPE_GRAVITY ? "grav" : t == Sensor.TYPE_MAGNETIC_FIELD ? "mag" : null;
            if (name != null) logger.imu(e.timestamp, name, e.values);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onLocationChanged(Location l) {
        if (source != Source.LIVE || engine == null) return;
        logger.gnss(l.getElapsedRealtimeNanos(), l.getLatitude(), l.getLongitude(), l.getAltitude(), l.hasSpeed() ? l.getSpeed() : Float.NaN,
                l.hasBearing() ? l.getBearing() : Float.NaN, l.hasAccuracy() ? l.getAccuracy() : Float.NaN,
                l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : Float.NaN, l.hasBearingAccuracy() ? l.getBearingAccuracyDegrees() : Float.NaN, l.getProvider());
        Geo g = engine.frame();
        if (g != null) {
            double gx = g.x(l.getLongitude()), gy = g.y(l.getLatitude()); gnssTrail.add(gx, gy);
            if (inLoss && !Double.isNaN(lastGhostX)) lossDist += Math.hypot(gx - lastGhostX, gy - lastGhostY);
            lastGhostX = gx; lastGhostY = gy; snap.haveGhost = simulateLoss; snap.ghostX = gx; snap.ghostY = gy;
        }
        if (simulateLoss && engine.mode() != Engine.Mode.WAITING_FOR_GNSS) return;     // hidden from the engine
        if (engine.frame() == null) {
            MapBundle mb = map;
            if (mb != null && Math.abs(mb.geo.x(l.getLongitude())) < 20000 && Math.abs(mb.geo.y(l.getLatitude())) < 20000) { engine.setFrame(mb.geo); engine.setRoads(mb.roads); }
        }
        engine.gnss(l.getLatitude(), l.getLongitude(), l.hasSpeed() ? l.getSpeed() : Double.NaN, l.hasBearing() ? l.getBearing() : Double.NaN,
                l.hasAccuracy() ? l.getAccuracy() : 10.0);
        MapBundle cur = map;
        if (engine.frame() != null && (cur == null || !cur.geo.same(engine.frame())) && mapRequested != engine.frame()) loadMap(engine.frame(), engine);
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { if (LocationManager.GPS_PROVIDER.equals(p)) { snap.warning = ""; notifyUi(); } }
    @Override public void onProviderDisabled(String p) { if (LocationManager.GPS_PROVIDER.equals(p)) { snap.warning = "location is switched off"; notifyUi(); } }

    // ------------------------------------------------------------------ 10 Hz tick
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticks++; h.postAtTime(this, t0 + (ticks + 1) * tickPeriodMs);
            if (source == Source.LIVE) liveTick(); else if (source == Source.REPLAY) replayTick();
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
        engine.imu(a, g, w);
        Engine.State s = engine.state();
        if (simulateLoss && engine.mode() == Engine.Mode.DEAD_RECKONING && !inLoss) { inLoss = true; lossDist = 0; }
        if (inLoss && snap.haveGhost) { snap.liveErrM = Math.hypot(s.displayX - snap.ghostX, s.displayY - snap.ghostY); snap.liveDistM = lossDist; }
        if (!simulateLoss && inLoss) inLoss = false;
        publish(s);
    }

    private void replayTick() {
        if (rI >= rN) { stopInternal(false); snap.lastOutage = "replay finished. " + snap.lastOutage; notifyUi(); return; }
        int i = rI++;
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
        if (outage && valid) { snap.liveErrM = Math.hypot(s.x - rX[i], s.y - rY[i]); snap.liveDistM = lossDist; }
        if (phase == REPLAY_OUTAGE - 1 && valid && lossDist > 50 && !Double.isNaN(snap.liveErrM)) {
            double pct = 100 * snap.liveErrM / lossDist; snap.outages++; snap.sumDriftPct += pct;
            snap.lastOutage = String.format(Locale.US, "last 60 s outage: %.0f m travelled, error %.0f m = %.1f %%", lossDist, snap.liveErrM, pct);
        }
        publish(s);
    }

    private void publish(Engine.State s) {
        snap.mode = s.mode; snap.x = s.displayX; snap.y = s.displayY; snap.heading = s.headingRad; snap.speed = s.speed; snap.outageS = s.outageS; snap.lat = s.displayLat; snap.lon = s.displayLon;
        if (s.mode != Engine.Mode.WAITING_FOR_GNSS) engineTrail.add(s.displayX, s.displayY);
        if (nav != null) updateNav();
        if (ticks % Math.max(1, 100 / tickPeriodMs) == 0) notifyUi();
    }

    private void notifyUi() { Listener l = listener; if (l != null) main.post(() -> { Listener m = listener; if (m != null) m.onUpdate(); }); }

    private void resetStats() {
        engineTrail.clear(); gnssTrail.clear(); snap.outages = 0; snap.sumDriftPct = 0; snap.lastOutage = ""; snap.liveErrM = Double.NaN; snap.liveDistM = 0;
        snap.haveGhost = false; inLoss = false; lastGhostX = Double.NaN; snap.mode = null;
    }

    private java.util.function.Function<String, InputStream> asset(String prefix) {
        AssetManager am = getAssets();
        return name -> { try { return am.open(prefix + name); } catch (IOException e) { throw new UncheckedIOException(e); } };
    }
}
