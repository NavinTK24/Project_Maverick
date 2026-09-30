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
import android.os.SystemClock;

import com.maverickgrid.engine.ArrayStore;
import com.maverickgrid.engine.Engine;
import com.maverickgrid.engine.Geo;
import com.maverickgrid.engine.Model;
import com.maverickgrid.engine.Roads;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Runs the MaverickGRID engine at 10 Hz in a foreground service.
 * LIVE: phone accelerometer / gravity / gyroscope + GNSS.  REPLAY: a held-out IO-VNBD drive from the app assets,
 * with automatic simulated GNSS outages, so the on-phone engine can be demonstrated on the dataset itself.
 */
public class NavService extends Service implements SensorEventListener, LocationListener {
    public enum Source { NONE, LIVE, REPLAY }

    public interface Listener { void onUpdate(); }

    public final class LocalBinder extends Binder { NavService service() { return NavService.this; } }

    /** Snapshot for the UI (written on the engine thread, read on the UI thread). */
    public static final class Snapshot {
        public Source source = Source.NONE; public Engine.Mode mode; public double x, y, heading, speed, outageS, lat, lon;
        public boolean haveGhost; public double ghostX, ghostY; public double liveErrM = Double.NaN, liveDistM = 0;
        public String lastOutage = ""; public String mapInfo = "no map"; public String modelInfo = ""; public boolean simulateLoss, recording;
        public int outages; public double sumDriftPct; public String recordDir = "";
    }

    private static final String CHANNEL = "maverick_nav";
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread; private Handler h;
    private volatile Listener listener;
    private SensorManager sm; private LocationManager lm;
    private final float[] acc = new float[3], grav = new float[3], gyr = new float[3]; private boolean haveAcc, haveGyr, haveGrav, gravFromSensor;
    private final Object lock = new Object();

    private Engine engine; private Model model; private volatile Roads roads; private Source source = Source.NONE;
    private final Snapshot snap = new Snapshot();
    final Trail engineTrail = new Trail(12000), gnssTrail = new Trail(12000);
    private final RideLogger logger = new RideLogger();
    private volatile boolean simulateLoss = false;
    private long ticks = 0, tickPeriodMs = 100, t0;
    private String vehicle = "car";
    // live outage bookkeeping (while "simulate GNSS loss" is on)
    private double lossStartX, lossStartY, lossDist, lastGhostX = Double.NaN, lastGhostY = Double.NaN; private boolean inLoss;
    // replay
    private ArrayStore rep; private double[] rAcc, rGrav, rGyr, rX, rY, rH, rS; private int rI, rN; private Geo rGeo; private final Random rnd = new Random(7);
    private static final int REPLAY_WARMUP = 1800, REPLAY_EVERY = 1800, REPLAY_OUTAGE = 600;   // samples (10 Hz): outage of 60 s every 3 min

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("maverick-engine", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); thread.start(); h = new Handler(thread.getLooper());
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE); lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { goForeground(); return START_NOT_STICKY; }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL).setContentTitle("MaverickGRID").setContentText("Navigation engine running")
                .setSmallIcon(R.drawable.ic_launcher).setContentIntent(pi).setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }

    public void setListener(Listener l) { listener = l; }
    public Snapshot snapshot() { return snap; }
    public Roads roads() { return roads; }
    public Source source() { return source; }
    public String vehicle() { return vehicle; }

    // ------------------------------------------------------------------ control (called from the UI thread)
    public void startLive(String vehicleName) {
        stopAll(); vehicle = vehicleName;
        h.post(() -> {
            try {
                model = new Model(asset("models/" + vehicle + "/"));
                Engine.Config c = new Engine.Config(); c.yawAxis = -1; c.yawSign = -1.0; c.stopLearnedGyroBias = true;   // rotation about gravity: any mount orientation
                engine = new Engine(model, c); roads = null; snap.mapInfo = "map: waiting for first GNSS fix"; snap.modelInfo = "model: " + vehicle;
            } catch (Exception e) { snap.modelInfo = "model load failed: " + e.getMessage(); notifyUi(); return; }
            source = Source.LIVE; snap.source = source; resetStats();
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), gr = sm.getDefaultSensor(Sensor.TYPE_GRAVITY), mg = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            gravFromSensor = gr != null;
            sm.registerListener(this, a, 10000, h); sm.registerListener(this, g, 10000, h);
            if (gr != null) sm.registerListener(this, gr, 10000, h);
            if (mg != null) sm.registerListener(this, mg, 20000, h);
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.getLooper()); }
            catch (SecurityException e) { snap.mapInfo = "location permission missing"; }
            t0 = SystemClock.uptimeMillis(); ticks = 0; tickPeriodMs = 100; h.postAtTime(tick, t0 + 100);
        });
    }

    public void startReplay(double speedFactor) {
        stopAll();
        h.post(() -> {
            try {
                model = new Model(asset("models/car_heldout_S1/"));
                rep = new ArrayStore(asset("replay/S1/"));
                rAcc = rep.get("acc"); rGrav = rep.get("grav"); rGyr = rep.get("gyr"); rX = rep.get("tr_x"); rY = rep.get("tr_y"); rH = rep.get("tr_heading"); rS = rep.get("tr_speed");
                rN = rep.rows("acc"); rI = 0; rGeo = ArrayStore.frameOf(rep.get("tr_lat"), rep.get("tr_lon"), rX, rY);
                Engine.Config c = new Engine.Config(); c.yawAxis = model.yawAxis; c.yawSign = model.headingSign; c.stopLearnedGyroBias = true;
                engine = new Engine(model, c); engine.setFrame(rGeo);
                snap.modelInfo = "model: car (trained without drive S1)"; loadRoads(rGeo);
            } catch (Exception e) { snap.modelInfo = "replay load failed: " + e.getMessage(); notifyUi(); return; }
            source = Source.REPLAY; snap.source = source; resetStats();
            t0 = SystemClock.uptimeMillis(); ticks = 0; tickPeriodMs = Math.max(5, Math.round(100 / speedFactor)); h.postAtTime(tick, t0 + tickPeriodMs);
        });
    }

    public void stopAll() {
        h.removeCallbacks(tick);
        h.post(() -> {
            sm.unregisterListener(this);
            try { lm.removeUpdates(this); } catch (SecurityException ignored) { }
            source = Source.NONE; snap.source = source; synchronized (lock) { haveAcc = haveGyr = haveGrav = false; }
        });
    }

    public void setSimulateLoss(boolean on) { h.post(() -> { simulateLoss = on; snap.simulateLoss = on; if (!on) inLoss = false; notifyUi(); }); }

    public void setRecording(boolean on, String mount) {
        h.post(() -> {
            try {
                if (on) { File d = logger.start(getExternalFilesDir("rides"), vehicle, mount); snap.recordDir = d.getAbsolutePath(); }
                else { logger.stop(); }
                snap.recording = logger.active();
            } catch (IOException e) { snap.recordDir = "recording failed: " + e.getMessage(); snap.recording = false; }
            notifyUi();
        });
    }

    @Override public void onDestroy() { h.removeCallbacksAndMessages(null); sm.unregisterListener(this); try { lm.removeUpdates(this); } catch (SecurityException ignored) { } logger.stop(); thread.quitSafely(); super.onDestroy(); }

    // ------------------------------------------------------------------ sensors / GNSS
    @Override public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        synchronized (lock) {
            if (t == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(e.values, 0, acc, 0, 3); haveAcc = true;
                if (!gravFromSensor) { for (int i = 0; i < 3; i++) grav[i] = haveGrav ? 0.98f * grav[i] + 0.02f * e.values[i] : e.values[i]; haveGrav = true; }
            } else if (t == Sensor.TYPE_GYROSCOPE) { System.arraycopy(e.values, 0, gyr, 0, 3); haveGyr = true; }
            else if (t == Sensor.TYPE_GRAVITY) { System.arraycopy(e.values, 0, grav, 0, 3); haveGrav = true; }
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
        if (simulateLoss && engine.mode() != Engine.Mode.WAITING_FOR_GNSS) return;     // engine does not see the fix
        engine.gnss(l.getLatitude(), l.getLongitude(), l.hasSpeed() ? l.getSpeed() : Double.NaN, l.hasBearing() ? l.getBearing() : Double.NaN,
                l.hasAccuracy() ? l.getAccuracy() : 10.0);
        if (roads == null && engine.frame() != null && snap.mapInfo.startsWith("map: waiting")) { snap.mapInfo = "map: loading..."; final Geo fg = engine.frame(); new Thread(() -> loadRoads(fg)).start(); }
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    // ------------------------------------------------------------------ 10 Hz engine tick
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ticks++; h.postAtTime(this, t0 + (ticks + 1) * tickPeriodMs);
            if (source == Source.LIVE) liveTick(); else if (source == Source.REPLAY) replayTick();
        }
    };

    private void liveTick() {
        double[] a = new double[3], g = new double[3], w = new double[3];
        synchronized (lock) {
            if (!(haveAcc && haveGyr && haveGrav)) return;
            for (int i = 0; i < 3; i++) { a[i] = acc[i]; g[i] = grav[i]; w[i] = gyr[i]; }
        }
        engine.imu(a, g, w);
        Engine.State s = engine.state();
        if (simulateLoss && engine.mode() == Engine.Mode.DEAD_RECKONING && !inLoss) { inLoss = true; lossDist = 0; lossStartX = s.x; lossStartY = s.y; }
        if (inLoss && snap.haveGhost) {
            snap.liveErrM = Math.hypot(s.x - snap.ghostX, s.y - snap.ghostY); snap.liveDistM = lossDist;
        }
        if (!simulateLoss && inLoss) inLoss = false;
        publish(s);
    }

    private void replayTick() {
        if (rI >= rN) { source = Source.NONE; snap.source = source; h.removeCallbacks(tick); notifyUi(); return; }
        int i = rI++;
        engine.imu(new double[]{rAcc[3 * i], rAcc[3 * i + 1], rAcc[3 * i + 2]}, new double[]{rGrav[3 * i], rGrav[3 * i + 1], rGrav[3 * i + 2]}, new double[]{rGyr[3 * i], rGyr[3 * i + 1], rGyr[3 * i + 2]});
        boolean outage = i >= REPLAY_WARMUP && ((i - REPLAY_WARMUP) % REPLAY_EVERY) < REPLAY_OUTAGE;
        int phase = i >= REPLAY_WARMUP ? (i - REPLAY_WARMUP) % REPLAY_EVERY : -1;
        if (!Double.isNaN(rX[i])) {
            if (i % 10 == 0) gnssTrail.add(rX[i], rY[i]);
            snap.haveGhost = outage; snap.ghostX = rX[i]; snap.ghostY = rY[i];
            if (phase == 0) { lossDist = 0; }
            if (outage && i > 0 && !Double.isNaN(rX[i - 1])) lossDist += Math.hypot(rX[i] - rX[i - 1], rY[i] - rY[i - 1]);
            if (i % 10 == 0 && !outage) {
                double sp = Math.max(0, rS[i] + rnd.nextGaussian() * 0.2), br = sp > 2 ? Math.toDegrees(rH[i]) + rnd.nextGaussian() * 2 : Double.NaN;
                double fx = rX[i] + rnd.nextGaussian() * 3, fy = rY[i] + rnd.nextGaussian() * 3;
                engine.gnss(rGeo.lat(fy), rGeo.lon(fx), sp, br, 5.0);
            }
        }
        Engine.State s = engine.state();
        if (outage && !Double.isNaN(rX[i])) { snap.liveErrM = Math.hypot(s.x - rX[i], s.y - rY[i]); snap.liveDistM = lossDist; }
        if (phase == REPLAY_OUTAGE - 1 && lossDist > 50) {
            double pct = 100 * snap.liveErrM / lossDist; snap.outages++; snap.sumDriftPct += pct;
            snap.lastOutage = String.format(java.util.Locale.US, "last 60 s outage: %.0f m travelled, error %.0f m = %.1f %%", lossDist, snap.liveErrM, pct);
        }
        publish(s);
    }

    private void publish(Engine.State s) {
        snap.mode = s.mode; snap.x = s.displayX; snap.y = s.displayY; snap.heading = s.headingRad; snap.speed = s.speed; snap.outageS = s.outageS; snap.lat = s.displayLat; snap.lon = s.displayLon;
        if (s.mode != Engine.Mode.WAITING_FOR_GNSS) engineTrail.add(s.displayX, s.displayY);
        if (ticks % Math.max(1, 100 / tickPeriodMs) == 0 || source == Source.LIVE) notifyUi();
    }

    private void notifyUi() { Listener l = listener; if (l != null) main.post(l::onUpdate); }

    private void resetStats() { engineTrail.clear(); gnssTrail.clear(); snap.outages = 0; snap.sumDriftPct = 0; snap.lastOutage = ""; snap.liveErrM = Double.NaN; snap.liveDistM = 0; inLoss = false; lastGhostX = Double.NaN; }

    // ------------------------------------------------------------------ assets / maps
    private java.util.function.Function<String, InputStream> asset(String prefix) {
        AssetManager am = getAssets();
        return name -> { try { return am.open(prefix + name); } catch (IOException e) { throw new UncheckedIOException(e); } };
    }

    /** Loads the first road file (app files/maps/*.mgr, then assets/maps/*.mgr) that has roads within 20 km of the origin. */
    private void loadRoads(Geo g) {
        List<Object> candidates = new ArrayList<>();
        File dir = getExternalFilesDir("maps");
        if (dir != null) { File[] fs = dir.listFiles((d, n) -> n.endsWith(".mgr")); if (fs != null) for (File f : fs) candidates.add(f); }
        try { String[] as = getAssets().list("maps"); if (as != null) for (String n : as) if (n.endsWith(".mgr")) candidates.add("maps/" + n); } catch (IOException ignored) { }
        double[] bbox = {-20000, 20000, -20000, 20000};
        for (Object c : candidates) {
            try (InputStream in = c instanceof File ? new FileInputStream((File) c) : getAssets().open((String) c)) {
                Roads r = new Roads(in, g, bbox, false, 25.0);
                if (r.n > 100) {
                    String name = c instanceof File ? ((File) c).getName() : (String) c;
                    h.post(() -> { roads = r; engine.setRoads(r); snap.mapInfo = "map: " + name + " (" + r.n + " road segments)"; notifyUi(); });
                    return;
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        h.post(() -> { snap.mapInfo = "map: none covers this area (dead reckoning without map)"; notifyUi(); });
    }
}
