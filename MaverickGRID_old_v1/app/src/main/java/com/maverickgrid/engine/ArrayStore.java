package com.maverickgrid.engine;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** Named float64 arrays written by idr.export_java (manifest.json + big-endian .bin files). Used for replays and tests. */
public final class ArrayStore {
    private final Map<String, double[]> data = new HashMap<>(); private final Map<String, int[]> shape = new HashMap<>();

    public ArrayStore(Function<String, InputStream> opener) throws IOException {
        String man = Model.read(opener.apply("manifest.json"));
        Matcher m = Pattern.compile("\"(\\w+)\":\\s*\\[([^\\]]*)\\]").matcher(man);
        while (m.find()) {
            String body = m.group(2).replaceAll("\\s", ""); String[] p = body.isEmpty() ? new String[0] : body.split(",");
            int[] sh = new int[p.length]; for (int i = 0; i < p.length; i++) sh[i] = Integer.parseInt(p[i]);
            byte[] b = readAll(opener.apply(m.group(1) + ".bin")); DoubleBuffer db = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).asDoubleBuffer();
            double[] a = new double[db.remaining()]; db.get(a); data.put(m.group(1), a); shape.put(m.group(1), sh);
        }
    }

    public static ArrayStore fromFolder(File dir) throws IOException {
        return new ArrayStore(name -> { try { return new FileInputStream(new File(dir, name)); } catch (IOException e) { throw new UncheckedIOException(e); } });
    }

    public boolean has(String k) { return data.containsKey(k); }
    public double[] get(String k) { return data.get(k); }
    public int rows(String k) { return shape.get(k).length == 0 ? 1 : shape.get(k)[0]; }

    static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); return b.toByteArray(); }

    /** Local frame of a truth track (inverse of the projection used when it was exported). */
    public static Geo frameOf(double[] lat, double[] lon, double[] x, double[] y) {
        int n = lat.length; double[] a = new double[n]; int m = 0;
        for (int i = 0; i < n; i++) if (!Double.isNaN(lat[i]) && !Double.isNaN(y[i])) a[m++] = lat[i] - Math.toDegrees(y[i] / Geo.R_EARTH);
        double lat0 = median(Arrays.copyOf(a, m)); double k = Math.cos(Math.toRadians(lat0)) * Geo.R_EARTH; m = 0;
        for (int i = 0; i < n; i++) if (!Double.isNaN(lon[i]) && !Double.isNaN(x[i])) a[m++] = lon[i] - Math.toDegrees(x[i] / k);
        return new Geo(lat0, median(Arrays.copyOf(a, m)));
    }

    static double median(double[] a) { double[] s = a.clone(); Arrays.sort(s); int n = s.length; return n == 0 ? 0 : n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]); }
}
