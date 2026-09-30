package com.maverickgrid.engine;

/** Local east/north metric frame around (lat0, lon0) - same projection as idr.data / idr.roads. */
public final class Geo {
    public static final double R_EARTH = 6378137.0;
    public final double lat0, lon0, k;
    public Geo(double lat0, double lon0) { this.lat0 = lat0; this.lon0 = lon0; this.k = Math.cos(Math.toRadians(lat0)) * R_EARTH; }
    /** Same frame (by value). */
    public boolean same(Geo o) { return o != null && o.lat0 == lat0 && o.lon0 == lon0; }
    public double x(double lon) { return Math.toRadians(lon - lon0) * k; }
    public double y(double lat) { return Math.toRadians(lat - lat0) * R_EARTH; }
    public double lat(double y) { return lat0 + Math.toDegrees(y / R_EARTH); }
    public double lon(double x) { return lon0 + Math.toDegrees(x / k); }
}
