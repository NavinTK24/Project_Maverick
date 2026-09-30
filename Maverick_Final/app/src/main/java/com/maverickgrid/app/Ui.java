package com.maverickgrid.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Colours, shapes and small widget factories for a clean, Google-Maps-like look (framework only, no libraries). */
final class Ui {
    private Ui() {}
    // Palette: filled once per activity creation by setDark() (light = Google-Maps day style, dark = night style).
    static final int BLUE = 0xFF1A73E8, BLUE_DARK = 0xFF1558B0, GREEN = 0xFF0F9D58, AMBER = 0xFFF29900, RED = 0xFFD93025;
    static boolean dark;
    static int TEXT, TEXT2, HINT, SURFACE, CHIP, DIVIDER, ACCENT, CHIP_ON, WARN_TEXT, HANDLE, RIPPLE, GREEN_TEXT, RED_TEXT;
    // map
    static int M_LAND, M_GREEN, M_WATER, M_BLD, M_BLD_EDGE, M_MINOR_CASE, M_MINOR, M_MAJOR_CASE, M_MAJOR, M_HWY_CASE, M_HWY, M_LABEL, M_HALO, M_GHOST, M_GNSS;
    static { setDark(false); }

    static void setDark(boolean d) {
        dark = d;
        if (!d) {
            TEXT = 0xFF202124; TEXT2 = 0xFF5F6368; HINT = 0xFF80868B; SURFACE = 0xFFFFFFFF; CHIP = 0xFFF1F3F4; DIVIDER = 0xFFE8EAED;
            ACCENT = BLUE; CHIP_ON = 0xFFE8F0FE; WARN_TEXT = 0xFFB06000; HANDLE = 0xFFDADCE0; RIPPLE = 0x22000000; GREEN_TEXT = GREEN; RED_TEXT = RED;
            M_LAND = 0xFFF1EFE9; M_GREEN = 0xFFCDE9C4; M_WATER = 0xFFAAD3F2; M_BLD = 0xFFE3DFD8; M_BLD_EDGE = 0xFFD4CEC4;
            M_MINOR_CASE = 0xFFD9D5CD; M_MINOR = 0xFFFFFFFF; M_MAJOR_CASE = 0xFFE3B95A; M_MAJOR = 0xFFFFE9A6; M_HWY_CASE = 0xFFDB8A36; M_HWY = 0xFFF7B267;
            M_LABEL = 0xFF202124; M_HALO = 0xFFFFFFFF; M_GHOST = 0xFF202124; M_GNSS = 0xFF9AA0A6;
        } else {
            TEXT = 0xFFE8EAED; TEXT2 = 0xFFBDC1C6; HINT = 0xFF9AA0A6; SURFACE = 0xFF202124; CHIP = 0xFF303134; DIVIDER = 0xFF3C4043;
            ACCENT = 0xFF8AB4F8; CHIP_ON = 0xFF1E3A5F; WARN_TEXT = 0xFFFDD663; HANDLE = 0xFF5F6368; RIPPLE = 0x33FFFFFF; GREEN_TEXT = 0xFF81C995; RED_TEXT = 0xFFF28B82;
            M_LAND = 0xFF1D2430; M_GREEN = 0xFF1F3A33; M_WATER = 0xFF0E1E33; M_BLD = 0xFF2B3442; M_BLD_EDGE = 0xFF374152;
            M_MINOR_CASE = 0xFF151B24; M_MINOR = 0xFF3A4455; M_MAJOR_CASE = 0xFF2A2A26; M_MAJOR = 0xFF6F6650; M_HWY_CASE = 0xFF3A2E1E; M_HWY = 0xFF9C7A45;
            M_LABEL = 0xFFD7DCE3; M_HALO = 0xFF1D2430; M_GHOST = 0xFFFFFFFF; M_GNSS = 0xFF80868B;
        }
    }

    /** Theme setting: 0 = follow the phone, 1 = light, 2 = dark. */
    static int themeSetting(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", 0); }
    static void setThemeSetting(Context c, int v) { c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("theme", v).apply(); }
    static boolean systemDark(Context c) { return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES; }
    static boolean wantDark(Context c) { int t = themeSetting(c); return t == 2 || (t == 0 && systemDark(c)); }

    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
    static int px(Context c, float v) { return Math.round(dp(c, v)); }

    static GradientDrawable round(int color, float radiusPx) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusPx); return g; }
    static GradientDrawable outline(int fill, int stroke, float radiusPx, int strokePx) { GradientDrawable g = round(fill, radiusPx); g.setStroke(strokePx, stroke); return g; }

    static RippleDrawable ripple(int fill, float radiusPx) { return new RippleDrawable(ColorStateList.valueOf(RIPPLE), round(fill, radiusPx), round(0xFFFFFFFF, radiusPx)); }
    static RippleDrawable rippleOutline(int fill, int stroke, float radiusPx, int strokePx) { return new RippleDrawable(ColorStateList.valueOf(RIPPLE), outline(fill, stroke, radiusPx, strokePx), round(0xFFFFFFFF, radiusPx)); }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(true);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t;
    }

    /** Filled pill button (e.g. blue "Start"). */
    static TextView pill(Context c, String s, int fill, int textColor) {
        TextView b = text(c, s, 15, textColor, true); b.setGravity(Gravity.CENTER); b.setBackground(ripple(fill, dp(c, 24)));
        b.setPadding(px(c, 20), px(c, 11), px(c, 20), px(c, 11)); b.setClickable(true); b.setFocusable(true); b.setMinHeight(px(c, 44)); return b;
    }

    /** Outlined pill (secondary action). */
    static TextView ghost(Context c, String s, int color) {
        TextView b = text(c, s, 15, color, true); b.setGravity(Gravity.CENTER); b.setBackground(rippleOutline(SURFACE, DIVIDER, dp(c, 24), px(c, 1)));
        b.setPadding(px(c, 18), px(c, 10), px(c, 18), px(c, 10)); b.setClickable(true); b.setFocusable(true); b.setMinHeight(px(c, 44)); return b;
    }

    /** Small toggle chip; call chipState to switch its look. */
    static TextView chip(Context c, String s) {
        TextView b = text(c, s, 13, TEXT, true); b.setGravity(Gravity.CENTER); b.setPadding(px(c, 14), px(c, 7), px(c, 14), px(c, 7)); b.setClickable(true); chipState(c, b, false); return b;
    }
    /** Sets the text only if it changed (a TextView re-measures and re-lays out on every setText; the UI refreshes 10 times a second). */
    static void txt(TextView t, CharSequence s) { if (!android.text.TextUtils.equals(t.getText(), s)) t.setText(s); }
    static void color(TextView t, int col) { if (t.getCurrentTextColor() != col) t.setTextColor(col); }

    static void chipState(Context c, TextView b, boolean on) {
        String key = "chip:" + on + ":" + dark; if (key.equals(b.getTag())) return; b.setTag(key);   // unchanged: keep the drawable
        b.setBackground(on ? ripple(CHIP_ON, dp(c, 18)) : rippleOutline(SURFACE, DIVIDER, dp(c, 18), px(c, 1)));
        b.setTextColor(on ? ACCENT : TEXT);
    }

    static View card(Context c, View v, float radiusDp, float elevationDp) { v.setBackground(round(SURFACE, dp(c, radiusDp))); v.setElevation(dp(c, elevationDp)); return v; }

    static int withAlpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
    static int kindColor(String kind) {
        switch (kind) { case "Food": return 0xFFE8710A; case "Fuel": return 0xFF7B1FA2; case "Health": return 0xFFD93025; case "Shop": return 0xFF1E8E3E;
            case "Education": return 0xFF1967D2; case "Transport": return 0xFF12B5CB; case "Area": return 0xFF5F6368; case "Street": return 0xFF9AA0A6; default: return 0xFFA142F4; }
    }
    static String kindGlyph(String kind) {
        switch (kind) { case "Food": return "🍴"; case "Fuel": return "⛽"; case "Health": return "✚"; case "Shop": return "🛍"; case "Education": return "🎓";
            case "Transport": return "🚌"; case "Area": return "🏘"; case "Street": return "↕"; case "Point on the map": return "📍"; default: return "★"; }
    }
    static int textOn(int bg) { return Color.WHITE; }
}
