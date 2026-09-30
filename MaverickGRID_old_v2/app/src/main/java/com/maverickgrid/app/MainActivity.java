package com.maverickgrid.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.maverickgrid.engine.Engine;
import com.maverickgrid.engine.PlaceSearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main screen: status / navigation banner, search, offline map with tap-to-select, and controls. */
public class MainActivity extends Activity implements NavService.Listener {
    private NavService svc; private MapCanvasView map; private float dp;
    private TextView mode, line1, line2, line3, navLine, navSub;
    private Button bLive, bLoss, bRecord, bReplay, bVehicle, bNavigate, bEndRoute;
    private EditText searchBox; private ScrollView resultsScroll; private LinearLayout results, card; private TextView cardTitle, cardSub;
    private final List<String> vehicles = new ArrayList<>(); private int vehicleIdx = 0; private boolean pendingLive;
    private NavService.Target selected;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((NavService.LocalBinder) b).service(); svc.setListener(MainActivity.this); map.attach(svc); preloadMap(); onUpdate();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(31, 56, 100));
        // status + navigation banner
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setBackgroundColor(Color.rgb(31, 56, 100));
        int pad = (int) (10 * dp); panel.setPadding(pad, pad / 2, pad, pad / 2);
        mode = tv(19, true); line1 = tv(14, false); line2 = tv(12, false); line3 = tv(11, false);
        navLine = tv(20, true); navLine.setTextColor(Color.rgb(170, 240, 190)); navSub = tv(13, false);
        panel.addView(navLine); panel.addView(navSub); panel.addView(mode); panel.addView(line1); panel.addView(line2); panel.addView(line3);
        root.addView(panel);
        // search row
        LinearLayout sr = new LinearLayout(this); sr.setBackgroundColor(Color.WHITE); sr.setPadding(pad / 2, 0, pad / 2, 0); sr.setGravity(Gravity.CENTER_VERTICAL);
        searchBox = new EditText(this); searchBox.setHint("Search places or streets"); searchBox.setSingleLine(true); searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setOnEditorActionListener((v, id, ev) -> { if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { runSearch(); return true; } return false; });
        Button bSearch = btn("Search"); bSearch.setOnClickListener(v -> runSearch());
        sr.addView(searchBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); sr.addView(bSearch);
        root.addView(sr);
        // map area with overlays
        FrameLayout area = new FrameLayout(this);
        map = new MapCanvasView(this); area.addView(map, new FrameLayout.LayoutParams(-1, -1));
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); results.setBackgroundColor(Color.WHITE);
        resultsScroll = new ScrollView(this); resultsScroll.addView(results); resultsScroll.setVisibility(View.GONE); resultsScroll.setElevation(6 * dp);
        area.addView(resultsScroll, new FrameLayout.LayoutParams(-1, (int) (280 * dp), Gravity.TOP));
        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(pad, pad, pad, pad); card.setElevation(8 * dp);
        GradientDrawable cbg = new GradientDrawable(); cbg.setColor(Color.WHITE); cbg.setCornerRadius(12 * dp); card.setBackground(cbg);
        cardTitle = new TextView(this); cardTitle.setTextSize(17); cardTitle.setTypeface(Typeface.DEFAULT_BOLD); cardTitle.setTextColor(Color.rgb(30, 30, 30));
        cardSub = new TextView(this); cardSub.setTextSize(13); cardSub.setTextColor(Color.rgb(90, 90, 90));
        LinearLayout cr = new LinearLayout(this); bNavigate = btn("Navigate"); Button bClose = btn("Close");
        cr.addView(bNavigate, w1()); cr.addView(bClose, w1()); card.addView(cardTitle); card.addView(cardSub); card.addView(cr); card.setVisibility(View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); clp.setMargins(pad, pad, pad, (int) (36 * dp)); area.addView(card, clp);
        bEndRoute = btn("End route"); bEndRoute.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); elp.setMargins(pad, pad, pad, (int) (36 * dp)); area.addView(bEndRoute, elp);
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1f));
        // controls
        LinearLayout row1 = new LinearLayout(this), row2 = new LinearLayout(this); row1.setBackgroundColor(Color.rgb(244, 245, 247)); row2.setBackgroundColor(Color.rgb(244, 245, 247));
        bLive = btn("Start live"); bVehicle = btn("Vehicle"); bLoss = btn("Simulate GNSS loss"); bRecord = btn("Record ride"); bReplay = btn("Replay IO-VNBD");
        row1.addView(bLive, w1()); row1.addView(bVehicle, w1()); row1.addView(bLoss, w1()); row2.addView(bRecord, w1()); row2.addView(bReplay, w1());
        root.addView(row1); root.addView(row2);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) { Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()); v.setPadding(i.left, i.top, i.right, i.bottom); return WindowInsets.CONSUMED; }
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()); return in.consumeSystemWindowInsets();
        });

        try { String[] m = getAssets().list("models"); if (m != null) for (String s : m) if (!s.contains("heldout")) vehicles.add(s); } catch (IOException ignored) { }
        if (vehicles.isEmpty()) vehicles.add("car");
        bVehicle.setText("Vehicle: " + vehicles.get(0));

        bLive.setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.source() == NavService.Source.LIVE) svc.stopAll();
            else if (hasFine()) startLiveService();
            else { pendingLive = true; askPermissions(); }
        });
        bVehicle.setOnClickListener(v -> { if (svc != null && svc.source() == NavService.Source.LIVE) { toast("Stop live navigation to change the vehicle"); return; } vehicleIdx = (vehicleIdx + 1) % vehicles.size(); bVehicle.setText("Vehicle: " + vehicles.get(vehicleIdx)); });
        bLoss.setOnClickListener(v -> { if (svc != null) svc.setSimulateLoss(!svc.snapshot().simulateLoss); });
        bRecord.setOnClickListener(v -> { if (svc != null) svc.setRecording(!svc.snapshot().recording, "fixed"); });
        bReplay.setOnClickListener(v -> { if (svc == null) return; if (svc.source() == NavService.Source.REPLAY) svc.stopAll(); else { svc.startReplay(10.0); map.setFollow(true); } });
        bNavigate.setOnClickListener(v -> { if (svc == null || selected == null) return; svc.navigateTo(selected, map.centre()); card.setVisibility(View.GONE); map.clearPin(); if (svc.currentPosition() != null) map.setFollow(true); });
        bClose.setOnClickListener(v -> { card.setVisibility(View.GONE); map.clearPin(); selected = null; });
        bEndRoute.setOnClickListener(v -> { if (svc != null) svc.clearRoute(); });
        map.setPickListener(new MapCanvasView.PickListener() {
            @Override public void onTap(double x, double y, double tol) {
                if (svc == null) return; resultsScroll.setVisibility(View.GONE);
                PlaceSearch ps = svc.placeSearch(); if (ps == null) return;
                PlaceSearch.Result p = ps.placeNear(x, y, tol);
                if (p != null) select(new NavService.Target(p.name, p.kind, p.x, p.y, ps.roads.geo), false);
            }
            @Override public void onLongPress(double x, double y) {
                if (svc == null) return; NavService.MapBundle mb = svc.mapBundle();
                if (mb == null) { toast("No map loaded yet"); return; }
                select(new NavService.Target("Dropped pin", "Point on the map", x, y, mb.geo), false);
            }
        });

        bindService(new Intent(this, NavService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onDestroy() { if (svc != null) svc.setListener(null); unbindService(conn); super.onDestroy(); }

    // ------------------------------------------------------------------ permissions / live start
    private boolean hasFine() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        if (!hasFine()) { p.add(Manifest.permission.ACCESS_FINE_LOCATION); p.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasFine()) { preloadMap(); if (pendingLive) startLiveService(); }
        else if (pendingLive) toast("Live navigation needs PRECISE location (\"Approximate\" is not enough for GNSS). Replay works without it.");
        pendingLive = false;
    }

    private void startLiveService() {
        Intent i = new Intent(this, NavService.class).setAction(NavService.ACTION_LIVE).putExtra(NavService.EXTRA_VEHICLE, vehicles.get(vehicleIdx));
        try { startForegroundService(i); map.setFollow(true); } catch (RuntimeException e) { toast("Could not start navigation: " + e.getMessage()); }
    }

    /** Load the map around the last known position so search and routing work before driving. */
    private void preloadMap() {
        if (svc == null || !hasFine()) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE); Location best = null;
            for (String p : lm.getProviders(true)) { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; }
            if (best != null) svc.browseAround(best.getLatitude(), best.getLongitude());
        } catch (SecurityException ignored) { }
    }

    // ------------------------------------------------------------------ search / selection
    private void runSearch() {
        String q = searchBox.getText().toString();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        if (svc == null) return; PlaceSearch ps = svc.placeSearch();
        if (ps == null) { toast("No map loaded yet: start Live (first GNSS fix) or Replay, or copy a map file to the phone."); return; }
        double[] from = svc.currentPosition(); if (from == null) from = map.centre();
        List<PlaceSearch.Result> res = ps.search(q, from[0], from[1], 25);
        results.removeAllViews();
        if (res.isEmpty()) { TextView t = new TextView(this); t.setText("No match for \"" + q + "\""); t.setPadding((int) (14 * dp), (int) (12 * dp), 0, (int) (12 * dp)); results.addView(t); }
        for (PlaceSearch.Result r : res) {
            TextView t = new TextView(this); t.setText(r.name + "\n" + r.kind + " · " + dist(r.distM)); t.setTextColor(Color.rgb(30, 30, 30)); t.setTextSize(15);
            t.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp)); t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.END);
            t.setOnClickListener(v -> { resultsScroll.setVisibility(View.GONE); select(new NavService.Target(r.name, r.kind, r.x, r.y, ps.roads.geo), true); });
            results.addView(t); View div = new View(this); div.setBackgroundColor(Color.rgb(230, 230, 230)); results.addView(div, new LinearLayout.LayoutParams(-1, 1));
        }
        resultsScroll.setVisibility(View.VISIBLE); card.setVisibility(View.GONE);
    }

    private void select(NavService.Target t, boolean centre) {
        selected = t; map.setPin(t.x, t.y); if (centre) map.centreOn(t.x, t.y);
        double[] from = svc.currentPosition(); cardTitle.setText(t.name);
        cardSub.setText(t.kind + (from != null ? " · " + dist(Math.hypot(t.x - from[0], t.y - from[1])) + " away (straight line)" : ""));
        card.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ UI refresh
    @Override public void onUpdate() {
        if (svc == null) return;
        NavService.Snapshot s = svc.snapshot();
        NavService.MapBundle mb = svc.mapBundle();
        if (selected != null && (mb == null || !selected.geo.same(mb.geo))) { selected = null; card.setVisibility(View.GONE); map.clearPin(); resultsScroll.setVisibility(View.GONE); }
        bLive.setText(s.source == NavService.Source.LIVE ? "Stop live" : "Start live");
        bReplay.setText(s.source == NavService.Source.REPLAY ? "Stop replay" : "Replay IO-VNBD");
        bLoss.setText(s.simulateLoss ? "Restore GNSS" : "Simulate GNSS loss"); bLoss.setEnabled(s.source == NavService.Source.LIVE);
        bRecord.setText(s.recording ? "Stop recording" : "Record ride");
        if (s.navActive) {
            navLine.setVisibility(View.VISIBLE); navSub.setVisibility(View.VISIBLE); bEndRoute.setVisibility(View.VISIBLE);
            navLine.setText(arrow(s.navTurn) + "  " + (s.navText.isEmpty() ? "Follow the route" : s.navText) + (s.navNextM > 0 ? "  ·  " + dist(s.navNextM) : ""));
            navSub.setText(String.format(Locale.US, "to %s: %s, %s", s.navDestination, dist(s.navRemainM), mins(s.navRemainS)) + (s.navStatus.isEmpty() ? "" : "  (" + s.navStatus + ")"));
        } else {
            bEndRoute.setVisibility(View.GONE); navSub.setVisibility(s.navStatus.isEmpty() ? View.GONE : View.VISIBLE); navSub.setText(s.navStatus); navLine.setVisibility(View.GONE);
        }
        if (s.source == NavService.Source.NONE || s.mode == null) {
            mode.setText(s.source == NavService.Source.NONE ? "MaverickGRID" : "Starting..."); mode.setTextColor(Color.WHITE);
            line1.setText(s.source == NavService.Source.NONE ? "Start live navigation, replay a held-out IO-VNBD drive, or search a place." : "waiting for sensors / first GNSS fix");
            line2.setText(!s.warning.isEmpty() ? "⚠ " + s.warning : s.lastOutage.isEmpty() ? s.modelInfo : s.lastOutage);
            line3.setText(s.mapInfo.replace("map: ", "map: ") + (s.recording ? "   ● REC " + s.recordDir : ""));
            map.invalidate(); return;
        }
        String m = s.mode == Engine.Mode.GNSS ? "GNSS + INS" : s.mode == Engine.Mode.DEAD_RECKONING ? String.format(Locale.US, "DEAD RECKONING  %.1f s", s.outageS) : "Waiting for GNSS fix";
        mode.setText(m); mode.setTextColor(s.mode == Engine.Mode.DEAD_RECKONING ? Color.rgb(255, 170, 120) : Color.WHITE);
        line1.setText(String.format(Locale.US, "%.1f km/h   heading %.0f°   %.6f, %.6f", s.speed * 3.6, (Math.toDegrees(s.heading) % 360 + 360) % 360, s.lat, s.lon));
        String err = s.mode == Engine.Mode.DEAD_RECKONING && !Double.isNaN(s.liveErrM) && s.liveDistM > 1
                ? String.format(Locale.US, "error vs true position %.0f m over %.0f m (%.1f %%)", s.liveErrM, s.liveDistM, 100 * s.liveErrM / s.liveDistM) : s.lastOutage;
        line2.setText(!s.warning.isEmpty() ? "⚠ " + s.warning : err.isEmpty() ? s.modelInfo : err);
        String avg = s.outages > 0 ? String.format(Locale.US, "%d outages, mean drift %.1f %%   ", s.outages, s.sumDriftPct / s.outages) : "";
        line3.setText(avg + s.mapInfo.replace("map: ", "") + (s.recording ? "   ● REC" : ""));
        map.invalidate();
    }

    private static String arrow(int t) { switch (t) { case -3: return "⮌"; case -2: return "↰"; case -1: return "↖"; case 1: return "↗"; case 2: return "↱"; case 3: return "⮎"; case 9: return "⚑"; default: return "↑"; } }
    private static String dist(double m) { return m >= 1000 ? String.format(Locale.US, "%.1f km", m / 1000) : String.format(Locale.US, "%.0f m", Math.max(0, Math.round(m / 10.0) * 10)); }
    private static String mins(double s) { return s >= 3600 ? String.format(Locale.US, "%d h %d min", (int) (s / 3600), (int) (s % 3600) / 60) : String.format(Locale.US, "%d min", Math.max(1, Math.round(s / 60))); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private TextView tv(int sp, boolean bold) { TextView t = new TextView(this); t.setTextColor(Color.WHITE); t.setTextSize(sp); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button btn(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setGravity(Gravity.CENTER); return b; }
    private LinearLayout.LayoutParams w1() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }
}
