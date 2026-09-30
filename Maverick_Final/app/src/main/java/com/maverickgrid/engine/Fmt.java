package com.maverickgrid.engine;

import java.util.Locale;

/** Display formatting (pure Java so it is unit-tested on the desktop). */
public final class Fmt {
    private Fmt() {}
    private static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** "850 m", "1.2 km", "12 km". */
    public static String dist(double m) {
        if (!(m >= 0)) m = 0;
        if (m < 995) return String.format(Locale.US, "%d m", Math.round(m / 10.0) * 10);
        if (m < 9950) return String.format(Locale.US, "%.1f km", m / 1000.0);
        return String.format(Locale.US, "%d km", Math.round(m / 1000.0));
    }

    /** "1 min", "25 min", "1 h 05 min". */
    public static String duration(double s) {
        if (!(s >= 0)) s = 0;
        long min = Math.max(1, Math.round(s / 60.0));
        return min < 60 ? String.format(Locale.US, "%d min", min) : String.format(Locale.US, "%d h %02d min", min / 60, min % 60);
    }

    /** Heading in degrees 0..359 from radians clockwise from north. */
    public static int degrees(double rad) { long d = Math.round(Math.toDegrees(rad)) % 360; return (int) (d < 0 ? d + 360 : d); }

    /** 8-point compass name for a heading in degrees. */
    public static String cardinal(double deg) { double d = ((deg % 360) + 360) % 360; return DIRS[(int) Math.round(d / 45.0) % 8]; }

    public static String speedKmh(double mps) { return String.format(Locale.US, "%d", Math.round(Math.max(0, mps) * 3.6)); }

    public static String clock(double seconds) { long s = Math.round(Math.max(0, seconds)); return s < 60 ? s + " s" : String.format(Locale.US, "%d:%02d", s / 60, s % 60); }
}
