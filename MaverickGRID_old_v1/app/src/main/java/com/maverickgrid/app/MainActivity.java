package com.maverickgrid.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.maverickgrid.engine.Engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main screen: status panel, offline map, and controls (live navigation, simulated GNSS loss, ride recording, dataset replay). */
public class MainActivity extends Activity implements NavService.Listener {
    private NavService svc; private MapCanvasView map;
    private TextView mode, line1, line2, line3;
    private Button bLive, bLoss, bRecord, bReplay, bVehicle;
    private final List<String> vehicles = new ArrayList<>(); private int vehicleIdx = 0;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { svc = ((NavService.LocalBinder) b).service(); svc.setListener(MainActivity.this); map.attach(svc); onUpdate(); }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setBackgroundColor(Color.rgb(31, 56, 100));
        int pad = (int) (12 * dp); panel.setPadding(pad, pad, pad, pad);
        mode = tv(20, true); line1 = tv(15, false); line2 = tv(13, false); line3 = tv(12, false);
        panel.addView(mode); panel.addView(line1); panel.addView(line2); panel.addView(line3);
        root.addView(panel);
        map = new MapCanvasView(this); root.addView(map, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout row1 = new LinearLayout(this), row2 = new LinearLayout(this);
        bLive = btn("Start live"); bVehicle = btn("Vehicle"); bLoss = btn("Simulate GNSS loss");
        bRecord = btn("Record ride"); bReplay = btn("Replay IO-VNBD");
        row1.addView(bLive, w1()); row1.addView(bVehicle, w1()); row1.addView(bLoss, w1());
        row2.addView(bRecord, w1()); row2.addView(bReplay, w1());
        root.addView(row1); root.addView(row2);
        setContentView(root);

        try { String[] m = getAssets().list("models"); if (m != null) for (String s : m) if (!s.contains("heldout")) vehicles.add(s); } catch (IOException ignored) { }
        if (vehicles.isEmpty()) vehicles.add("car");
        bVehicle.setText("Vehicle: " + vehicles.get(0));

        bLive.setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.source() == NavService.Source.LIVE) { svc.stopAll(); } else if (hasLocation()) { svc.startLive(vehicles.get(vehicleIdx)); map.setFollow(true); } else askPermissions();
            v.postDelayed(this::onUpdate, 300);
        });
        bVehicle.setOnClickListener(v -> { vehicleIdx = (vehicleIdx + 1) % vehicles.size(); bVehicle.setText("Vehicle: " + vehicles.get(vehicleIdx)); });
        bLoss.setOnClickListener(v -> { if (svc != null) svc.setSimulateLoss(!svc.snapshot().simulateLoss); });
        bRecord.setOnClickListener(v -> { if (svc != null) svc.setRecording(!svc.snapshot().recording, "fixed"); });
        bReplay.setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.source() == NavService.Source.REPLAY) svc.stopAll(); else { svc.startReplay(10.0); map.setFollow(true); }
            v.postDelayed(this::onUpdate, 300);
        });

        askPermissions();
        Intent i = new Intent(this, NavService.class);
        startForegroundService(i); bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onDestroy() { if (svc != null) svc.setListener(null); unbindService(conn); super.onDestroy(); }

    private boolean hasLocation() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        if (!hasLocation()) { p.add(Manifest.permission.ACCESS_FINE_LOCATION); p.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 1);
    }

    @Override public void onUpdate() {
        if (svc == null) return;
        NavService.Snapshot s = svc.snapshot();
        bLive.setText(s.source == NavService.Source.LIVE ? "Stop live" : "Start live");
        bReplay.setText(s.source == NavService.Source.REPLAY ? "Stop replay" : "Replay IO-VNBD");
        bLoss.setText(s.simulateLoss ? "Restore GNSS" : "Simulate GNSS loss");
        bRecord.setText(s.recording ? "Stop recording" : "Record ride");
        bLoss.setEnabled(s.source == NavService.Source.LIVE);
        if (s.source == NavService.Source.NONE || s.mode == null) { mode.setText("MaverickGRID"); line1.setText("Start live navigation, or replay a held-out IO-VNBD drive."); line2.setText(s.modelInfo); line3.setText(s.recording ? "recording: " + s.recordDir : ""); map.invalidate(); return; }
        String m = s.mode == Engine.Mode.GNSS ? "GNSS + INS" : s.mode == Engine.Mode.DEAD_RECKONING ? String.format(Locale.US, "DEAD RECKONING  %.1f s", s.outageS) : "Waiting for GNSS fix";
        mode.setText(m); mode.setTextColor(s.mode == Engine.Mode.DEAD_RECKONING ? Color.rgb(255, 170, 120) : Color.WHITE);
        line1.setText(String.format(Locale.US, "%.1f km/h   heading %.0f°   %.6f, %.6f", s.speed * 3.6, (Math.toDegrees(s.heading) % 360 + 360) % 360, s.lat, s.lon));
        String err = s.mode == Engine.Mode.DEAD_RECKONING && !Double.isNaN(s.liveErrM) && s.liveDistM > 1
                ? String.format(Locale.US, "error vs true position %.0f m over %.0f m (%.1f %%)", s.liveErrM, s.liveDistM, 100 * s.liveErrM / s.liveDistM) : s.lastOutage;
        line2.setText(err.isEmpty() ? s.mapInfo : err);
        String avg = s.outages > 0 ? String.format(Locale.US, "%d outages, mean drift %.1f %%   ", s.outages, s.sumDriftPct / s.outages) : "";
        line3.setText(avg + s.mapInfo.replace("map: ", "") + (s.recording ? "   ● REC" : ""));
        map.invalidate();
    }

    private TextView tv(int sp, boolean bold) { TextView t = new TextView(this); t.setTextColor(Color.WHITE); t.setTextSize(sp); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button btn(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setGravity(Gravity.CENTER); return b; }
    private LinearLayout.LayoutParams w1() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }
}
