package com.maverickgrid.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.maverickgrid.engine.Engine;
import com.maverickgrid.engine.Fmt;
import com.maverickgrid.engine.PlaceSearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Maverick main screen: full-screen offline map with floating search, compass, status, and a context bottom panel. */
public class MainActivity extends Activity implements NavService.Listener, SensorEventListener {
    private NavService svc; private MapCanvasView map; private CompassView compass;
    private LinearLayout top, searchCard, navBanner, resultsBox, bottom, idlePanel, drivePanel, placePanel, navPanel; private ScrollView resultsScroll;
    private EditText search; private Icons clearIcon, turnIcon; private TextView status, navDist, navText, navThen;
    private TextView speedBig, modeLine, detailLine, bStop, chipLoss, chipRecord, placeGlyph, placeName, placeSub, placeNote, etaBig, etaSub, idleNote, chipVehicle, chipReport;
    private View fab; private TextView navStart; private double declLat = Double.NaN, declLon, decl;
    private final List<String> models = new ArrayList<>(); private Garage.Vehicle vehicle; private boolean pendingLive, darkNow;
    private NavService.Target selected; private int insetTop, insetBottom, imeBottom;
    private SensorManager sm; private double azimuth = Double.NaN; private boolean navHeadingSet, wasRunning;

    // The service is bound from the application context and kept bound while the activity is only being recreated (theme change),
    // so the loaded map, a running demo and the last report survive; it is unbound when the user really leaves the app.
    private static NavService sSvc; private static boolean sBound; private static MainActivity sCurrent;
    private static final ServiceConnection SCONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { sSvc = ((NavService.LocalBinder) b).service(); if (sCurrent != null) sCurrent.onService(sSvc); }
        @Override public void onServiceDisconnected(ComponentName n) { sSvc = null; if (sCurrent != null) sCurrent.svc = null; }
    };
    private void onService(NavService s) { svc = s; svc.setListener(this); map.attach(svc); preloadMap(); onUpdate(); }

    @Override protected void onCreate(Bundle b) {
        darkNow = Ui.wantDark(this); Ui.setDark(darkNow); setTheme(darkNow ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(b);
        CrashLog.install(getApplicationContext());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        try { String[] m = getAssets().list("models"); if (m != null) for (String s : m) if (!s.contains("heldout")) models.add(s); } catch (IOException ignored) { }
        if (models.isEmpty()) models.add("car");
        String crash = CrashLog.takeLast(this);
        buildUi();
        sCurrent = this;
        if (!sBound) sBound = getApplicationContext().bindService(new Intent(this, NavService.class), SCONN, Context.BIND_AUTO_CREATE);
        else if (sSvc != null) onService(sSvc);
        if (crash != null) new AlertDialog.Builder(this).setTitle("Maverick closed unexpectedly last time").setMessage("Please send this report so it can be fixed:\n\n" + crash)
                .setPositiveButton("Copy report", (d, w) -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Maverick crash", crash)); toast("Report copied"); })
                .setNegativeButton("Close", null).show();
    }

    // ------------------------------------------------------------------ layout
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        map = new MapCanvasView(this); root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        int p8 = Ui.px(this, 8), p12 = Ui.px(this, 12), p16 = Ui.px(this, 16);

        // ---- top overlay: search card / navigation banner, status chip + compass, results
        top = new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL); top.setPadding(p12, p8, p12, 0);
        searchCard = new LinearLayout(this); searchCard.setGravity(Gravity.CENTER_VERTICAL); Ui.card(this, searchCard, 28, 6); searchCard.setPadding(Ui.px(this, 4), 0, Ui.px(this, 4), 0);
        Icons menu = new Icons(this, Icons.MENU, Ui.TEXT2); menu.setOnClickListener(this::showMenu); menu.setBackground(Ui.ripple(Ui.SURFACE, Ui.dp(this, 22)));
        search = new EditText(this); search.setHint("Search places or streets"); search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.HINT); search.setTextSize(16);
        search.setBackground(null); search.setSingleLine(true); search.setImeOptions(EditorInfo.IME_ACTION_SEARCH); search.setPadding(Ui.px(this, 4), 0, 0, 0);
        clearIcon = new Icons(this, Icons.CLOSE, Ui.TEXT2); clearIcon.setVisibility(View.GONE); clearIcon.setBackground(Ui.ripple(Ui.SURFACE, Ui.dp(this, 22)));
        clearIcon.setOnClickListener(v -> { search.setText(""); hideResults(); hideKeyboard(); });
        searchCard.addView(menu, new LinearLayout.LayoutParams(Ui.px(this, 44), Ui.px(this, 44)));
        searchCard.addView(search, new LinearLayout.LayoutParams(0, Ui.px(this, 52), 1f));
        searchCard.addView(clearIcon, new LinearLayout.LayoutParams(Ui.px(this, 44), Ui.px(this, 44)));
        top.addView(searchCard, new LinearLayout.LayoutParams(-1, -2));

        navBanner = new LinearLayout(this); navBanner.setGravity(Gravity.CENTER_VERTICAL); navBanner.setBackground(Ui.round(Ui.GREEN, Ui.dp(this, 18))); navBanner.setElevation(Ui.dp(this, 6));
        navBanner.setPadding(p12, p12, p16, p12); navBanner.setVisibility(View.GONE);
        turnIcon = new Icons(this, Icons.TURN, Color.WHITE); navBanner.addView(turnIcon, new LinearLayout.LayoutParams(Ui.px(this, 56), Ui.px(this, 56)));
        LinearLayout nb = new LinearLayout(this); nb.setOrientation(LinearLayout.VERTICAL); nb.setPadding(p12, 0, 0, 0);
        navDist = Ui.text(this, "", 28, Color.WHITE, true); navText = Ui.text(this, "", 17, Color.WHITE, false); navText.setMaxLines(2); navText.setEllipsize(TextUtils.TruncateAt.END);
        navThen = Ui.text(this, "", 13, 0xCCFFFFFF, false);
        nb.addView(navDist); nb.addView(navText); nb.addView(navThen); navBanner.addView(nb, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(navBanner, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.TOP); row.setPadding(0, p8, 0, 0);
        status = Ui.text(this, "", 13, Ui.TEXT, true); status.setPadding(p12, Ui.px(this, 7), p12, Ui.px(this, 7)); Ui.card(this, status, 16, 3); status.setVisibility(View.GONE); status.setOnLongClickListener(v -> { showDiagnostics(); return true; });
        row.addView(status, new LinearLayout.LayoutParams(-2, -2));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        compass = new CompassView(this); row.addView(compass, new LinearLayout.LayoutParams(Ui.px(this, 64), Ui.px(this, 72)));
        compass.setClickable(true); compass.setOnClickListener(v -> { map.setOrientation(MapCanvasView.Orient.NORTH); toast("North up"); });
        top.addView(row, new LinearLayout.LayoutParams(-1, -2));

        resultsBox = new LinearLayout(this); resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsScroll = new ScrollView(this); resultsScroll.addView(resultsBox); Ui.card(this, resultsScroll, 18, 6); resultsScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2); rlp.topMargin = -Ui.px(this, 72) + p8; top.addView(resultsScroll, rlp);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---- my-location button
        Icons loc = new Icons(this, Icons.LOCATE, Ui.ACCENT); fab = loc; loc.setBackground(Ui.ripple(Ui.SURFACE, Ui.dp(this, 28))); loc.setElevation(Ui.dp(this, 6)); loc.setPadding(p12, p12, p12, p12);
        loc.setOnClickListener(v -> {
            boolean have = (svc != null && svc.currentPosition() != null) || meXY() != null;
            if (have && map.following()) {                   // second tap: compass mode (map turns with you), third: north up again
                boolean toHeading = map.orientation() != MapCanvasView.Orient.HEADING;
                map.setOrientation(toHeading ? MapCanvasView.Orient.HEADING : MapCanvasView.Orient.NORTH);
                toast(toHeading ? "Map turns with the direction you face" : "North up");
            }
            else if (have) map.setFollow(true);
            else if (map.hasMe() && svc != null && svc.mapGeo() == null) toast("No offline map covers your location yet");
            else if (map.hasMe()) toast("Your location is outside the offline map area");
            else toast(hasFine() ? "Finding your location… (Location must be switched on)" : "Allow location access to see where you are");
        });
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(Ui.px(this, 54), Ui.px(this, 54), Gravity.BOTTOM | Gravity.END); flp.setMargins(0, 0, p16, 0); root.addView(loc, flp);

        // ---- bottom panel (one of four views visible)
        bottom = new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setElevation(Ui.dp(this, 14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(Ui.SURFACE); float r = Ui.dp(this, 24); bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bottom.setBackground(bg); bottom.setPadding(Ui.px(this, 20), Ui.px(this, 10), Ui.px(this, 20), Ui.px(this, 16));
        View handle = new View(this); handle.setBackground(Ui.round(Ui.HANDLE, Ui.dp(this, 2))); LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.px(this, 36), Ui.px(this, 4)); hl.gravity = Gravity.CENTER_HORIZONTAL; hl.bottomMargin = Ui.px(this, 10); bottom.addView(handle, hl);

        // idle
        idlePanel = vbox();
        TextView title = Ui.text(this, "Maverick", 24, Ui.TEXT, true);
        TextView sub = Ui.text(this, "Navigation that keeps going when GPS drops out: tunnels, flyovers, dense streets. Works fully offline.", 14, Ui.TEXT2, false);
        LinearLayout vrow = new LinearLayout(this); vrow.setPadding(0, p12, 0, p12);
        vrow.setGravity(Gravity.CENTER_VERTICAL);
        TextView vlab = Ui.text(this, "Vehicle", 13, Ui.TEXT2, false); vrow.addView(vlab);
        chipVehicle = Ui.chip(this, ""); chipVehicle.setOnClickListener(v -> chooseVehicle());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.leftMargin = p8; vrow.addView(chipVehicle, bl);
        LinearLayout brow = new LinearLayout(this);
        TextView bStart = Ui.pill(this, "Start", Ui.BLUE, Color.WHITE); bStart.setOnClickListener(v -> startLive());
        TextView bDemo = Ui.ghost(this, "Demo drive", Ui.ACCENT); bDemo.setOnClickListener(v -> startDemo());
        brow.addView(bStart, new LinearLayout.LayoutParams(0, -2, 1.3f)); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f); dl.leftMargin = p12; brow.addView(bDemo, dl);
        idleNote = Ui.text(this, "", 12, Ui.TEXT2, false); idleNote.setPadding(0, p8, 0, 0);
        chipReport = Ui.chip(this, "📊  Open last drive report"); chipReport.setVisibility(View.GONE); chipReport.setOnClickListener(v -> showLastReport());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2); rl.topMargin = p8;
        idlePanel.addView(title); idlePanel.addView(sub); idlePanel.addView(vrow); idlePanel.addView(brow); idlePanel.addView(idleNote); idlePanel.addView(chipReport, rl);
        bottom.addView(idlePanel);

        // driving
        drivePanel = vbox();
        LinearLayout d1 = new LinearLayout(this); d1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sp = vbox(); sp.setGravity(Gravity.CENTER_HORIZONTAL);
        speedBig = Ui.text(this, "0", 34, Ui.TEXT, true); TextView kmh = Ui.text(this, "km/h", 12, Ui.TEXT2, false); sp.addView(speedBig); sp.addView(kmh);
        LinearLayout mid = vbox(); mid.setPadding(p16, 0, p8, 0);
        modeLine = Ui.text(this, "", 16, Ui.TEXT, true); detailLine = Ui.text(this, "", 13, Ui.TEXT2, false); detailLine.setMaxLines(3);
        mid.addView(modeLine); mid.addView(detailLine);
        bStop = Ui.ghost(this, "Stop", Ui.RED_TEXT); bStop.setOnClickListener(v -> { if (svc != null) svc.stopAll(); });
        d1.addView(sp, new LinearLayout.LayoutParams(Ui.px(this, 72), -2)); d1.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f)); d1.addView(bStop);
        LinearLayout d2 = new LinearLayout(this); d2.setPadding(0, p12, 0, 0);
        chipLoss = Ui.chip(this, "Test GPS loss"); chipLoss.setOnClickListener(v -> { if (svc != null) svc.setSimulateLoss(!svc.snapshot().simulateLoss); });
        chipRecord = Ui.chip(this, ""); chipRecord.setOnClickListener(v -> toast(svc != null && svc.snapshot().recording
                ? "Saving GPS + all sensor data for training, and a drive report (map, errors, PNG) when you press Stop."
                : "Saving GPS data and a drive report when you press Stop. Sensor data for training is off (menu)."));
        d2.addView(chipLoss); LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.leftMargin = p8; d2.addView(chipRecord, cl);
        drivePanel.addView(d1); drivePanel.addView(d2); drivePanel.setVisibility(View.GONE);
        bottom.addView(drivePanel);

        // selected place
        placePanel = vbox();
        LinearLayout p1 = new LinearLayout(this); p1.setGravity(Gravity.CENTER_VERTICAL);
        placeGlyph = Ui.text(this, "", 18, Color.WHITE, false); placeGlyph.setGravity(Gravity.CENTER);
        LinearLayout pn = vbox(); pn.setPadding(p12, 0, 0, 0);
        placeName = Ui.text(this, "", 20, Ui.TEXT, true); placeName.setMaxLines(2); placeName.setEllipsize(TextUtils.TruncateAt.END); placeSub = Ui.text(this, "", 14, Ui.TEXT2, false);
        pn.addView(placeName); pn.addView(placeSub);
        p1.addView(placeGlyph, new LinearLayout.LayoutParams(Ui.px(this, 44), Ui.px(this, 44))); p1.addView(pn, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout p2 = new LinearLayout(this); p2.setPadding(0, Ui.px(this, 14), 0, 0);
        LinearLayout bDir = new LinearLayout(this); bDir.setGravity(Gravity.CENTER); bDir.setBackground(Ui.ripple(Ui.BLUE, Ui.dp(this, 24))); bDir.setPadding(p16, Ui.px(this, 8), Ui.px(this, 20), Ui.px(this, 8)); bDir.setMinimumHeight(Ui.px(this, 44));
        Icons di = new Icons(this, Icons.DIRECTIONS, Color.WHITE); bDir.addView(di, new LinearLayout.LayoutParams(Ui.px(this, 24), Ui.px(this, 24)));
        TextView dt = Ui.text(this, "  Directions", 15, Color.WHITE, true); bDir.addView(dt);
        bDir.setOnClickListener(v -> navigate());
        TextView bClose = Ui.ghost(this, "Close", Ui.TEXT2); bClose.setOnClickListener(v -> clearSelection());
        p2.addView(bDir, new LinearLayout.LayoutParams(0, -2, 1.4f)); LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(0, -2, 1f); cll.leftMargin = p12; p2.addView(bClose, cll);
        placeNote = Ui.text(this, "", 12, Ui.TEXT2, false); placeNote.setPadding(0, p8, 0, 0);
        placePanel.addView(p1); placePanel.addView(p2); placePanel.addView(placeNote); placePanel.setVisibility(View.GONE);
        bottom.addView(placePanel);

        // navigating
        navPanel = vbox();
        LinearLayout n1 = new LinearLayout(this); n1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ne = vbox(); etaBig = Ui.text(this, "", 26, Ui.GREEN_TEXT, true); etaSub = Ui.text(this, "", 14, Ui.TEXT2, false); ne.addView(etaBig); ne.addView(etaSub);
        TextView bEnd = Ui.pill(this, "End", Ui.RED, Color.WHITE); bEnd.setOnClickListener(v -> { if (svc != null) svc.clearRoute(); map.setNavMode(false); map.setOrientation(MapCanvasView.Orient.NORTH); });
        navStart = Ui.pill(this, "Start", Ui.BLUE, Color.WHITE);
        navStart.setOnClickListener(v -> { if (svc != null && svc.source() != NavService.Source.NONE) svc.stopAll(); else startLive(); });
        n1.addView(ne, new LinearLayout.LayoutParams(0, -2, 1f)); n1.addView(navStart, new LinearLayout.LayoutParams(Ui.px(this, 96), -2));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(Ui.px(this, 88), -2); el.leftMargin = Ui.px(this, 8); n1.addView(bEnd, el);
        navPanel.addView(n1); navPanel.setVisibility(View.GONE);
        bottom.addView(navPanel);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        // keep the map's visible centre between the overlays, and the FAB above the panel
        bottom.addOnLayoutChangeListener((v, l, t, rr, bb, ol, ot, orr, ob) -> { FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fab.getLayoutParams(); int m = v.getHeight() + Ui.px(this, 12); if (lp.bottomMargin != m) { lp.bottomMargin = m; fab.setLayoutParams(lp); } updateMapInsets(); });
        top.addOnLayoutChangeListener((v, l, t, rr, bb, ol, ot, orr, ob) -> updateMapInsets());
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int t, bo;
            if (Build.VERSION.SDK_INT >= 30) { Insets i = in.getInsets(WindowInsets.Type.systemBars()); t = i.top; bo = i.bottom; }
            else { t = in.getSystemWindowInsetTop(); bo = in.getSystemWindowInsetBottom(); }
            insetTop = t; insetBottom = bo;
            if (Build.VERSION.SDK_INT >= 30) imeBottom = in.getInsets(WindowInsets.Type.ime()).bottom;
            top.setPadding(p12, t + p8, p12, 0); bottom.setPadding(Ui.px(this, 20), Ui.px(this, 10), Ui.px(this, 20), Ui.px(this, 16) + bo);
            return in;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                clearIcon.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE); search.removeCallbacks(searchLater);
                if (s.toString().trim().length() >= 2) search.postDelayed(searchLater, 180); else hideResults();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnFocusChangeListener((v, f) -> updateOverlays());
        search.setOnEditorActionListener((v, id, ev) -> { if (id == EditorInfo.IME_ACTION_SEARCH) { runSearch(true); hideKeyboard(); return true; } return false; });
        map.setPickListener(new MapCanvasView.PickListener() {
            @Override public void onTap(double x, double y, double tol) {
                hideResults(); hideKeyboard(); if (svc == null) return;
                PlaceSearch ps = svc.placeSearch(); if (ps == null) return;
                PlaceSearch.Result p = ps.placeNear(x, y, tol);
                if (p != null) select(new NavService.Target(p.name, p.kind, p.x, p.y, ps.roads.geo), false);
            }
            @Override public void onLongPress(double x, double y) {
                if (svc == null) return; NavService.MapBundle mb = svc.mapBundle();
                if (mb == null) { toast("No map loaded yet"); return; }
                select(new NavService.Target("Dropped pin", "Point on the map", x, y, mb.geo), false);
            }
            @Override public void onUserMoved() { hideKeyboard(); }
        });
        setVehicle(Garage.current(this));
    }

    @Override protected void onDestroy() {
        if (svc != null) svc.setListener(null);
        if (sCurrent == this) sCurrent = null;
        if (isFinishing() && sBound) { try { getApplicationContext().unbindService(SCONN); } catch (IllegalArgumentException ignored) { } sBound = false; sSvc = null; }
        super.onDestroy();
    }

    private LinearLayout vbox() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    // ------------------------------------------------------------------ actions
    // ------------------------------------------------------------------ vehicles ("garage")
    private void setVehicle(Garage.Vehicle v) {
        vehicle = v; Garage.setCurrent(this, v.id);
        chipVehicle.setText(v.icon() + "  " + v.name + ("bike".equals(v.type) ? "  · beta" : "") + "  ▾"); Ui.chipState(this, chipVehicle, true);
    }

    /** Pick which vehicle you are using now, or add / edit / delete one. */
    private void chooseVehicle() {
        List<Garage.Vehicle> vs = Garage.list(this); String[] items = new String[vs.size() + 2]; int checked = -1;
        for (int i = 0; i < vs.size(); i++) { Garage.Vehicle v = vs.get(i); items[i] = v.icon() + "  " + v.name + "   (" + v.typeLabel() + ")"; if (vehicle != null && v.id == vehicle.id) checked = i; }
        items[vs.size()] = "＋  Add a vehicle"; items[vs.size() + 1] = "✎  Edit, delete or see statistics";
        new AlertDialog.Builder(this).setTitle("Which vehicle are you using?").setSingleChoiceItems(items, checked, (d, w) -> {
            d.dismiss();
            if (w < vs.size()) { setVehicle(vs.get(w)); toast(vs.get(w).name + " selected: drives and recordings are saved under this vehicle"); }
            else if (w == vs.size()) editVehicle(null);
            else pickVehicleToEdit();
        }).setNegativeButton("Cancel", null).show();
    }

    private void pickVehicleToEdit() {
        List<Garage.Vehicle> vs = Garage.list(this); String[] items = new String[vs.size()];
        for (int i = 0; i < vs.size(); i++) items[i] = vs.get(i).icon() + "  " + vs.get(i).name;
        new AlertDialog.Builder(this).setTitle("Edit which vehicle?").setItems(items, (d, w) -> editVehicle(vs.get(w))).setNegativeButton("Cancel", null).show();
    }

    /** Add (v == null) or edit a vehicle: name, type and its statistics. */
    private void editVehicle(Garage.Vehicle v) {
        LinearLayout box = vbox(); int p = Ui.px(this, 22); box.setPadding(p, Ui.px(this, 8), p, 0);
        EditText name = new EditText(this); name.setHint("e.g. Pulsar 150"); name.setSingleLine(true); name.setTextColor(Ui.TEXT); name.setHintTextColor(Ui.HINT);
        if (v != null) { name.setText(v.name); name.setSelection(v.name.length()); }
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView tl = Ui.text(this, "Type (chooses the AI model)", 13, Ui.TEXT2, false); tl.setPadding(0, Ui.px(this, 12), 0, Ui.px(this, 6)); box.addView(tl);
        LinearLayout types = new LinearLayout(this);
        TextView tCar = Ui.chip(this, "🚗  Car"), tBike = Ui.chip(this, "🏍  Bike / scooter (beta)");
        final String[] type = {v != null ? v.type : "car"};
        Runnable paint = () -> { Ui.chipState(this, tCar, type[0].equals("car")); Ui.chipState(this, tBike, type[0].equals("bike")); }; paint.run();
        tCar.setOnClickListener(x -> { type[0] = "car"; paint.run(); }); tBike.setOnClickListener(x -> { type[0] = "bike"; paint.run(); });
        if (!models.contains("bike")) tBike.setVisibility(View.GONE);
        types.addView(tCar); LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.leftMargin = Ui.px(this, 8); types.addView(tBike, ml);
        box.addView(types);
        if (v != null) {
            TextView st = Ui.text(this, "Statistics\n" + Garage.stats(this, v.id).summary() + "\n\nDrive folders: Documents/Maverick/" + v.slug() + "/", 13, Ui.TEXT2, false);
            st.setPadding(0, Ui.px(this, 14), 0, 0); box.addView(st);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(v == null ? "Add a vehicle" : "Edit vehicle").setView(box)
                .setPositiveButton(v == null ? "Add" : "Save", (d, w) -> {
                    String n = name.getText().toString();
                    if (v == null) { long id = Garage.add(this, n, type[0]); for (Garage.Vehicle x : Garage.list(this)) if (x.id == id) setVehicle(x); }
                    else { Garage.update(this, v.id, n, type[0]); if (vehicle != null && vehicle.id == v.id) setVehicle(Garage.current(this)); }
                })
                .setNegativeButton("Cancel", null);
        if (v != null) b.setNeutralButton("Delete", (d, w) -> new AlertDialog.Builder(this).setTitle("Delete " + v.name + "?")
                .setMessage("Its statistics are removed. Recorded ride files stay on the phone.")
                .setPositiveButton("Delete", (d2, w2) -> { Garage.remove(this, v.id); setVehicle(Garage.current(this)); }).setNegativeButton("Cancel", null).show());
        b.show();
    }

    // ------------------------------------------------------------------ drive report
    private void showLastReport() {
        if (svc == null || svc.snapshot().reportUri.isEmpty()) { toast("No drive report yet"); return; }
        android.net.Uri u = android.net.Uri.parse(svc.snapshot().reportUri); android.graphics.Bitmap bmp;
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inSampleSize = 2; bmp = android.graphics.BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | RuntimeException e) { toast("Cannot open the report: " + e.getMessage()); return; }
        if (bmp == null) { toast("Cannot open the report"); return; }
        android.widget.ImageView iv = new android.widget.ImageView(this); iv.setImageBitmap(bmp); iv.setAdjustViewBounds(true); iv.setBackgroundColor(Color.WHITE);
        ScrollView sv = new ScrollView(this); sv.addView(iv);
        new AlertDialog.Builder(this).setTitle("Drive report").setView(sv)
                .setPositiveButton("Share", (d, w) -> {
                    if ("file".equals(u.getScheme())) { toast("Sharing is not available for this folder; the files are in " + svc.snapshot().reportWhere); return; }
                    Intent sh = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(sh, "Share drive report")); } catch (RuntimeException e) { toast("No app to share with"); }
                })
                .setNeutralButton("Where are the files?", (d, w) -> new AlertDialog.Builder(this).setTitle("Drive files").setMessage(
                        "Folder: " + svc.snapshot().reportWhere + "\n\n"
                        + "report.png – this picture\nsummary.json – all metrics\ntrack.csv – 10 Hz: Maverick position, dead-reckoning test, GPS, error\n"
                        + "track.geojson / track.gpx – tracks for QGIS, Google Earth, geojson.io\ngnss.csv – every GPS fix\nsensors.csv – all sensors, one column each, " + svc.logHz() + " rows per second (if saving is on)\ninfo.txt – vehicle and phone\n\n"
                        + "Copy the folder to the laptop over USB (Internal storage › Documents › Maverick).").setPositiveButton("OK", null).show())
                .setNegativeButton("Close", null).show();
    }

    private void chooseLogRate() {
        if (svc == null) return; int[] rates = {10, 25, 50, 100}; String[] items = {"10 Hz (small files)", "25 Hz", "50 Hz (recommended)", "100 Hz (large files)"}; int cur = 2;
        for (int i = 0; i < rates.length; i++) if (rates[i] == svc.logHz()) cur = i;
        new AlertDialog.Builder(this).setTitle("Sensor log rate (rows per second in sensors.csv)").setSingleChoiceItems(items, cur, (d, w) -> {
            d.dismiss(); svc.setLogHz(rates[w]); toast("From the next drive: " + rates[w] + " rows per second. Navigation always runs at 10 Hz.");
        }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ appearance
    private void chooseTheme() {
        String[] items = {"Same as phone", "Light", "Dark"};
        new AlertDialog.Builder(this).setTitle("Appearance").setSingleChoiceItems(items, Ui.themeSetting(this), (d, w) -> {
            d.dismiss(); Ui.setThemeSetting(this, w); if (Ui.wantDark(this) != darkNow) recreate();
        }).setNegativeButton("Cancel", null).show();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (Ui.wantDark(this) != darkNow) recreate();          // phone switched between light and dark
    }

    private long startTapMs;
    private void startLive() {
        if (svc == null) return;
        long now = android.os.SystemClock.elapsedRealtime(); if (now - startTapMs < 3000) return; startTapMs = now;   // ignore a double tap
        if (!hasFine()) { pendingLive = true; askPermissions(); return; }
        Garage.Vehicle v = vehicle != null ? vehicle : Garage.current(this); String type = models.contains(v.type) ? v.type : "car";
        Intent i = new Intent(this, NavService.class).setAction(NavService.ACTION_LIVE).putExtra(NavService.EXTRA_VEHICLE, type)
                .putExtra(NavService.EXTRA_VEHICLE_ID, v.id).putExtra(NavService.EXTRA_VEHICLE_NAME, v.name);
        try { startForegroundService(i); map.setFollow(true); } catch (RuntimeException e) { toast("Could not start: " + e.getMessage()); }
    }

    private void startDemo() {
        if (svc == null) return;
        if (svc.source() == NavService.Source.LIVE) {
            new AlertDialog.Builder(this).setTitle("End your drive?").setMessage("The demo replaces the drive that is running. Your drive is saved first.")
                    .setPositiveButton("End drive and start demo", (d, w) -> runDemo()).setNegativeButton("Cancel", null).show();
            return;
        }
        runDemo();
    }
    private void runDemo() {
        try { startService(new Intent(this, NavService.class)); } catch (RuntimeException ignored) { }   // keeps the demo alive if the screen is recreated
        svc.startReplay(5.0); map.setFollow(true); clearSelection();
    }

    /** What the phone has learned from GPS drives, with a way to forget it (learned roads are stored only on this phone). */
    private void showLearning() {
        Garage.Vehicle v = vehicle != null ? vehicle : Garage.current(this);
        new Thread(() -> {
            int pts = LearnedRoads.count(this); Garage.Stats st = Garage.stats(this, v.id);
            String m = String.format(Locale.US, "Learned roads on this phone: %d points (about %.1f km of road driven with GPS).\n\n", pts, pts * 0.010)
                    + v.name + ":\n" + (st.movingS > 0 ? String.format(Locale.US, "• Stop detector tuned on %d min of GPS driving (threshold %.2f)\n", st.movingS / 60, st.stopThreshold) : "• Stop detector: not tuned yet (needs 1 min of GPS driving)\n")
                    + (st.speedN >= Garage.SPEED_MIN_N ? String.format(Locale.US, "• Speed pattern: ×%.2f applied (%d s of tests)\n", st.speedScale, st.speedN) : String.format(Locale.US, "• Speed pattern: learning (%d of %d s of tests)\n", st.speedN, Garage.SPEED_MIN_N))
                    + "\nEvery drive with GPS adds to this. Nothing leaves the phone.";
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("What Maverick has learned").setMessage(m).setPositiveButton("OK", null)
                    .setNeutralButton("Forget…", (d, w) -> new AlertDialog.Builder(this).setTitle("Forget what was learned?")
                            .setItems(new String[]{"Learned roads (all vehicles)", "Stop detector and speed pattern of " + v.name}, (d2, w2) -> {
                                if (w2 == 0) new Thread(() -> LearnedRoads.clear(this)).start(); else Garage.resetLearning(this, v.id); toast("Forgotten");
                            }).setNegativeButton("Cancel", null).show()).show());
        }).start();
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        boolean running = svc != null && svc.source() != NavService.Source.NONE;
        m.getMenu().add(0, 1, 0, "Demo drive (ISRO dataset)"); if (running) m.getMenu().add(0, 2, 1, "Stop");
        m.getMenu().add(0, 3, 2, (svc == null || svc.rawSaving() ? "✓ " : "") + "Save sensor data for training");
        m.getMenu().add(0, 8, 2, "Sensor log rate: " + (svc != null ? svc.logHz() : 50) + " Hz");
        if (svc != null && !svc.snapshot().reportUri.isEmpty()) m.getMenu().add(0, 7, 2, "Open last drive report");
        m.getMenu().add(0, 5, 3, "Vehicles & statistics");
        m.getMenu().add(0, 9, 3, "What Maverick has learned");
        m.getMenu().add(0, 6, 4, "Appearance (light / dark)");
        m.getMenu().add(0, 4, 5, "What is Maverick?");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: startDemo(); return true;
                case 5: chooseVehicle(); return true;
                case 6: chooseTheme(); return true;
                case 2: svc.stopAll(); return true;
                case 3: if (svc != null) { boolean on = !svc.rawSaving(); svc.setRawSaving(on); toast(on ? "Sensor data (sensors.csv, " + svc.logHz() + " rows per second) will be saved with every drive, for training." : "Only GPS data and the drive report will be saved."); } return true;
                case 7: showLastReport(); return true;
                case 8: chooseLogRate(); return true;
                case 9: showLearning(); return true;
                case 4: new AlertDialog.Builder(this).setTitle("Maverick").setMessage(
                        "Maverick keeps your position moving when GPS is lost (tunnels, flyovers, tall buildings), using the phone's motion sensors, "
                        + "AI speed models and the offline road map.\n\n"
                        + "Blue dot: GPS + sensors.  Amber dot: dead reckoning; the circle shows the uncertainty.\n\n"
                        + "Demo drive: replays a real drive from the ISRO IO-VNBD dataset (Coventry, UK) that the model never saw, cutting GPS for 60 s every 3 minutes, "
                        + "and shows the true position and the error.\n\n"
                        + "Test GPS loss: while driving, hides GPS from Maverick so you can see how far off it gets.\n\n"
                        + "Every drive is saved in Documents/Maverick/<vehicle>/: GPS and sensor data (CSV), the track (GeoJSON, GPX), a summary (JSON) and a report picture (PNG). "
                        + "To measure dead reckoning honestly, a second engine runs alongside and is denied GPS for 60 s every 3 minutes; its error against the real GPS gives the drift %, MAE, RMSE and R² in the report. Navigation itself keeps GPS.\n\n"
                        + "Vehicles: choose the car or bike you are using (tap the vehicle chip). Each vehicle keeps its own drive statistics and ride recordings, "
                        + "so its behaviour can be analysed and a model trained for it.\n\n"
                        + "Learning: every drive with GPS teaches Maverick three things: (1) the roads you drive (repeated routes are followed without GPS even where the map has no road), "
                        + "(2) how your vehicle and phone mount vibrate when moving versus stopped, (3) your vehicle's speed pattern. With a destination set, the planned route (its turns) guides dead reckoning.\n\n"
                        + "Everything works offline: maps, search, routing and the AI models are on the phone.").setPositiveButton("OK", null).show(); return true;
            }
            return false;
        });
        m.show();
    }

    private void navigate() {
        if (svc == null || selected == null) return;
        double[] me = meXY(); svc.navigateTo(selected, me != null ? me : map.centre()); map.clearPin(); selected = null;
        if (svc.currentPosition() != null) { map.setFollow(true); map.setNavMode(true); map.setOrientation(MapCanvasView.Orient.HEADING); }
        onUpdate();
    }

    private void clearSelection() { selected = null; map.clearPin(); onUpdate(); }

    // ------------------------------------------------------------------ search
    private void runSearch(boolean explicit) {
        if (svc == null) return; PlaceSearch ps = svc.placeSearch(); String q = search.getText().toString().trim(); if (q.length() < 2) { hideResults(); return; }
        if (ps == null) { if (explicit) toast("No map loaded yet. Press Start (first GPS fix) or run the demo drive."); return; }
        double[] from = svc.currentPosition(); if (from == null) from = map.centre();
        List<PlaceSearch.Result> res;
        try { res = ps.search(q, from[0], from[1], 20); } catch (RuntimeException e) { toast("Search failed: " + e.getMessage()); return; }
        resultsBox.removeAllViews();
        if (res.isEmpty()) { TextView t = Ui.text(this, "No results for “" + q + "”", 15, Ui.TEXT2, false); t.setPadding(Ui.px(this, 16), Ui.px(this, 16), Ui.px(this, 16), Ui.px(this, 16)); resultsBox.addView(t); }
        for (PlaceSearch.Result r : res) {
            LinearLayout rowV = new LinearLayout(this); rowV.setGravity(Gravity.CENTER_VERTICAL); rowV.setPadding(Ui.px(this, 14), Ui.px(this, 10), Ui.px(this, 14), Ui.px(this, 10));
            rowV.setBackground(Ui.ripple(Ui.SURFACE, 0));
            TextView g = Ui.text(this, Ui.kindGlyph(r.kind), 15, Color.WHITE, false); g.setGravity(Gravity.CENTER); g.setBackground(Ui.round(Ui.kindColor(r.kind), Ui.dp(this, 18)));
            LinearLayout tx = vbox(); tx.setPadding(Ui.px(this, 12), 0, 0, 0);
            TextView n = Ui.text(this, r.name, 16, Ui.TEXT, false); n.setMaxLines(1); n.setEllipsize(TextUtils.TruncateAt.END);
            TextView s = Ui.text(this, r.kind + " · " + Fmt.dist(r.distM), 13, Ui.TEXT2, false);
            tx.addView(n); tx.addView(s);
            rowV.addView(g, new LinearLayout.LayoutParams(Ui.px(this, 36), Ui.px(this, 36))); rowV.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            rowV.setOnClickListener(v -> { hideResults(); hideKeyboard(); select(new NavService.Target(r.name, r.kind, r.x, r.y, ps.roads.geo), true); });
            resultsBox.addView(rowV);
        }
        resultsScroll.setVisibility(View.VISIBLE); updateOverlays();
        ViewGroup.LayoutParams lp = resultsScroll.getLayoutParams(); int max = Ui.px(this, 360);
        resultsScroll.measure(View.MeasureSpec.makeMeasureSpec(top.getWidth() > 0 ? top.getWidth() : 1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
        View rootV = resultsScroll.getRootView(); int room = rootV.getHeight() - imeBottom - resultsScroll.getTop() - top.getTop() - Ui.px(this, 8);
        lp.height = Math.min(Math.min(max, resultsScroll.getMeasuredHeight()), room > Ui.px(this, 120) ? room : max); resultsScroll.setLayoutParams(lp);
    }

    private final Runnable searchLater = () -> runSearch(false);
    private void hideResults() { resultsScroll.setVisibility(View.GONE); updateOverlays(); }

    /** While searching, the bottom panel and location button step aside so the results and keyboard have room. */
    private void updateOverlays() {
        boolean searching = search.hasFocus() || resultsScroll.getVisibility() == View.VISIBLE;
        bottom.setVisibility(searching ? View.GONE : View.VISIBLE); fab.setVisibility(searching ? View.GONE : View.VISIBLE); updateMapInsets();
    }

    private void updateMapInsets() {
        View row = (View) compass.getParent(); int topH = row != null ? row.getBottom() : top.getHeight();
        map.setInsets(topH, bottom.getVisibility() == View.VISIBLE ? bottom.getHeight() : insetBottom);
    }
    private void hideKeyboard() { ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0); search.clearFocus(); }

    private void select(NavService.Target t, boolean centre) {
        selected = t; map.setPin(t.x, t.y); if (centre) map.centreOn(t.x, t.y);
        placeName.setText(t.name); placeGlyph.setText(Ui.kindGlyph(t.kind)); placeGlyph.setBackground(Ui.round(Ui.kindColor(t.kind), Ui.dp(this, 22)));
        double[] from = fromPos();
        placeSub.setText(t.kind + (from != null ? " · " + Fmt.dist(Math.hypot(t.x - from[0], t.y - from[1])) + " away" : ""));
        boolean live = svc != null && svc.currentPosition() != null;
        placeNote.setText(from == null ? "Your location is not known yet: the route will start from the map centre." : !live ? "Press Start after Directions for turn-by-turn guidance." : "");
        placeNote.setVisibility(live ? View.GONE : View.VISIBLE);
        onUpdate();
    }

    @Override public void onBackPressed() {
        if (resultsScroll.getVisibility() == View.VISIBLE) { hideResults(); return; }
        if (selected != null) { clearSelection(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ permissions
    private boolean hasFine() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        if (!hasFine()) { p.add(Manifest.permission.ACCESS_FINE_LOCATION); p.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasFine()) { startIdleLocation(); if (pendingLive) startLive(); }
        else if (pendingLive) toast("Maverick needs PRECISE location for live navigation. The demo drive works without it.");
        pendingLive = false;
    }

    private void preloadMap() {
        if (svc == null || !hasFine()) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE); Location best = null;
            for (String p : lm.getProviders(true)) { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; }
            if (best != null) onIdleFix(best);
        } catch (SecurityException ignored) { }
    }

    // ------------------------------------------------------------------ your location while no drive is running (blue dot + facing beam)
    private boolean idleLocOn;
    private final LocationListener idleLoc = new LocationListener() {
        @Override public void onLocationChanged(Location l) { onIdleFix(l); }
        @Override public void onStatusChanged(String p, int st, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };
    private void onIdleFix(Location l) {
        map.setMe(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : Double.NaN);
        if (svc != null) svc.browseAround(l.getLatitude(), l.getLongitude());
        if (selected != null) select(selected, false);
    }
    private void startIdleLocation() {
        if (idleLocOn || !hasFine()) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER})
                if (lm.getAllProviders().contains(p)) lm.requestLocationUpdates(p, 2000L, 0f, idleLoc, getMainLooper());
            idleLocOn = true; preloadMap();
        } catch (SecurityException | IllegalArgumentException ignored) { }
    }
    private void stopIdleLocation() {
        if (!idleLocOn) return; idleLocOn = false;
        try { ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(idleLoc); } catch (SecurityException ignored) { }
    }
    /** Your position in the loaded map's frame from the phone's location (no drive running), or null. */
    private double[] meXY() {
        if (svc == null || !map.hasMe()) return null; com.maverickgrid.engine.Geo g = svc.mapGeo(); if (g == null) return null;
        double[] ll = map.me(); double x = g.x(ll[1]), y = g.y(ll[0]); return Math.abs(x) < 30000 && Math.abs(y) < 30000 ? new double[]{x, y} : null;
    }
    /** Where routes start: the running drive's position, else your phone location. */
    private double[] fromPos() { double[] p = svc != null ? svc.currentPosition() : null; return p != null ? p : meXY(); }

    // ------------------------------------------------------------------ compass (phone orientation)
    @Override protected void onResume() {
        super.onResume();
        Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR); if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
        startIdleLocation();
    }
    @Override protected void onPause() { super.onPause(); sm.unregisterListener(this); stopIdleLocation(); }

    private final float[] rot = new float[9], rot2 = new float[9], ori = new float[3];
    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        try { SensorManager.getRotationMatrixFromVector(rot, e.values); } catch (IllegalArgumentException ex) { return; }
        // phone standing upright (dashboard mount): use the direction the back of the phone faces
        boolean upright = Math.abs(rot[8]) < 0.5f;
        if (upright) SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, rot2); else System.arraycopy(rot, 0, rot2, 0, 9);
        SensorManager.getOrientation(rot2, ori);
        double az = Math.toDegrees(ori[0]);
        NavService.Snapshot sn = svc != null ? svc.snapshot() : null;
        double la = Double.NaN, lo = Double.NaN;
        if (sn != null && (sn.mode == Engine.Mode.GNSS || sn.mode == Engine.Mode.DEAD_RECKONING) && !Double.isNaN(sn.lat)) { la = sn.lat; lo = sn.lon; }
        else if (map.hasMe()) { double[] m = map.me(); la = m[0]; lo = m[1]; }
        if (!Double.isNaN(la)) {      // magnetic -> true north
            if (Double.isNaN(declLat) || Math.abs(la - declLat) > 0.1 || Math.abs(lo - declLon) > 0.1) { declLat = la; declLon = lo; decl = new GeomagneticField((float) la, (float) lo, 0f, System.currentTimeMillis()).getDeclination(); }
            az += decl;
        }
        az = ((az % 360) + 360) % 360;
        if (Double.isNaN(azimuth)) azimuth = az; else { double d = ((az - azimuth + 540) % 360) - 180; azimuth = ((azimuth + 0.2 * d) % 360 + 360) % 360; }
        compass.setHeading(azimuth); compass.setMapBearing(map.bearing()); if (updateBeam()) map.invalidate();
    }
    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private boolean updateBeam() {
        if (svc == null) return false; NavService.Snapshot s = svc.snapshot();
        if (s.mode == null || s.mode == Engine.Mode.WAITING_FOR_GNSS) return map.setBeam(map.hasMe() ? azimuth : Double.NaN);   // no drive: the way the phone faces
        if (s.source == NavService.Source.REPLAY) return map.setBeam(Fmt.degrees(s.heading));   // demo: the replayed car's heading, not the phone
        return map.setBeam(s.speed > 1.5 || Double.isNaN(azimuth) ? Fmt.degrees(s.heading) : azimuth);
    }

    // ------------------------------------------------------------------ refresh
    /** Speed and learning diagnostics (long-press the status chip): for field tests. */
    private void showDiagnostics() {
        if (svc == null) return; NavService.Snapshot s = svc.snapshot();
        String m = String.format(Locale.US, "Engine: %.1f ms per 0.1 s tick (max %.0f ms), running %.0f ms late\nMap drawing: %.1f ms per frame (%.0f frames/s)\n"
                + "Stop detector: p=%.2f, threshold %.2f (learned) → %s\nRoute prior: %s · route lock: %s (%s) · learned roads: %d segments",
                s.tickMsAvg, s.tickMsMax, s.tickLagMs, map.drawMsAvg(), map.fps(), s.stopP, s.stopThreshold, s.stopped ? "stopped" : "moving",
                s.routePrior ? "on" : "off", s.routeLocked ? "on" : "off", s.routeEvent, s.learnedSegs);
        new android.app.AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(m).setPositiveButton("OK", null).show();
    }

    @Override public void onUpdate() {
        if (svc == null) return;
        NavService.Snapshot s = svc.snapshot(); NavService.MapBundle mb = svc.mapBundle();
        if (selected != null && (mb == null || !selected.geo.same(mb.geo))) { selected = null; map.clearPin(); hideResults(); }
        boolean running = s.source != NavService.Source.NONE;
        if (wasRunning && !running && map.hasMe()) { double[] m = map.me(); svc.browseAround(m[0], m[1]); }   // back to your own area right away
        wasRunning = running;
        // status chip
        if (running && s.mode != null) {
            status.setVisibility(View.VISIBLE);
            if (s.mode == Engine.Mode.DEAD_RECKONING) { Ui.txt(status, "●  No GPS · dead reckoning " + Fmt.clock(s.outageS)); Ui.color(status, Ui.WARN_TEXT); }
            else if (s.mode == Engine.Mode.GNSS) { Ui.txt(status, "●  GPS + sensors" + (s.source == NavService.Source.REPLAY ? " · demo" : s.vehicleName.isEmpty() ? "" : " · " + s.vehicleName)); Ui.color(status, Ui.GREEN_TEXT); }
            else { Ui.txt(status, "●  Waiting for GPS…"); Ui.color(status, Ui.TEXT2); }
        } else status.setVisibility(View.GONE);
        // navigation banner
        boolean nav = s.navActive;
        navBanner.setVisibility(nav ? View.VISIBLE : View.GONE); searchCard.setVisibility(nav ? View.GONE : View.VISIBLE); map.setNavMode(nav);
        if (nav && running && !navHeadingSet) { map.setOrientation(MapCanvasView.Orient.HEADING); navHeadingSet = true; }   // navigating: direction of travel up
        if (!nav && navHeadingSet) { map.setOrientation(MapCanvasView.Orient.NORTH); navHeadingSet = false; }
        compass.setMapBearing(map.bearing());
        if (nav) {
            turnIcon.setTurn(s.navTurn); Ui.txt(navDist, s.navNextM > 0 ? Fmt.dist(s.navNextM) : "");
            Ui.txt(navText, s.navText.isEmpty() ? "Follow the route" : s.navText);
            Ui.txt(navThen, s.mode == Engine.Mode.DEAD_RECKONING ? "No GPS · dead reckoning" + (s.routeLocked ? " on the route" : "") + " · to " + s.navDestination : s.navStatus.isEmpty() ? "to " + s.navDestination : s.navStatus);
            Ui.txt(etaBig, Fmt.duration(s.navRemainS));
            Calendar c = Calendar.getInstance(); c.add(Calendar.SECOND, (int) s.navRemainS);
            Ui.txt(etaSub, Fmt.dist(s.navRemainM) + " · arrive " + String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
        }
        // bottom panel state
        idlePanel.setVisibility(!nav && selected == null && !running ? View.VISIBLE : View.GONE);
        placePanel.setVisibility(!nav && selected != null ? View.VISIBLE : View.GONE);
        drivePanel.setVisibility(!nav && selected == null && running ? View.VISIBLE : View.GONE);
        navPanel.setVisibility(nav ? View.VISIBLE : View.GONE); navStart.setVisibility(nav ? View.VISIBLE : View.GONE); Ui.txt(navStart, running ? "Stop" : "Start");
        if (!running) {
            String note = !s.warning.isEmpty() ? "⚠ " + s.warning : !s.reportStatus.isEmpty() ? s.reportStatus + (s.reportWhere.isEmpty() ? "" : "\nFiles: " + s.reportWhere) : !s.navStatus.isEmpty() ? s.navStatus : !s.lastOutage.isEmpty() ? s.lastOutage : s.mapInfo.startsWith("map: none") || s.mapInfo.startsWith("map: loading") ? s.mapInfo.replace("map: ", "Map: ") : "";
            Ui.txt(idleNote, note); idleNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
            chipReport.setVisibility(s.reportUri.isEmpty() ? View.GONE : View.VISIBLE);
        } else {
            Ui.txt(speedBig, s.mode == null ? "--" : Fmt.speedKmh(s.speed));
            if (s.mode == Engine.Mode.DEAD_RECKONING) { Ui.txt(modeLine, "Dead reckoning" + (s.routeLocked ? " · on route" : s.headingUnsure ? " · finding direction" : s.routePrior ? " · following route" : "") + (s.stopped ? " · stopped" : "")); Ui.color(modeLine, Ui.WARN_TEXT); }
            else if (s.mode == Engine.Mode.GNSS) { Ui.txt(modeLine, s.source == NavService.Source.REPLAY ? "Demo drive · GPS" : "GPS + sensors"); Ui.color(modeLine, Ui.TEXT); }
            else { Ui.txt(modeLine, "Waiting for GPS…"); Ui.color(modeLine, Ui.TEXT2); }
            String detail;
            if (!s.warning.isEmpty()) detail = "⚠ " + s.warning;
            else if (s.mode == Engine.Mode.DEAD_RECKONING && !Double.isNaN(s.liveErrM) && s.liveDistM > 1) detail = String.format(Locale.US, "Error vs true position: %s after %s (%.1f %%)", Fmt.dist(s.liveErrM), Fmt.dist(s.liveDistM), 100 * s.liveErrM / s.liveDistM);
            else if (!s.navStatus.isEmpty()) detail = s.navStatus;
            else if (!s.lastOutage.isEmpty()) detail = s.lastOutage + (s.outages > 1 ? String.format(Locale.US, " · average %.1f %% over %d outages", s.sumDriftPct / s.outages, s.outages) : "");
            else detail = (s.source == NavService.Source.REPLAY ? "GPS is cut for 60 s every 3 min. " : "") + s.mapInfo.replace("map: ", "Map: ");
            Ui.txt(detailLine, detail);
            chipLoss.setVisibility(s.source == NavService.Source.LIVE ? View.VISIBLE : View.GONE); Ui.chipState(this, chipLoss, s.simulateLoss); Ui.txt(chipLoss, s.simulateLoss ? "GPS hidden – tap to restore" : "Test GPS loss");
            chipRecord.setVisibility(s.source == NavService.Source.LIVE ? View.VISIBLE : View.GONE); Ui.chipState(this, chipRecord, true); Ui.txt(chipRecord, s.recording ? "●  Saving drive + sensor data" : "●  Saving drive report");
        }
        updateBeam();
        map.invalidate();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
