package com.maverickgrid.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.regex.*;

/** A trained engine model: speed model (LightGBM residual), stop detector, and metadata (from idr.export_java). */
public final class Model {
    public final Gbdt speedMean, stop; public final double stopThreshold, headingSign; public final int[] keyEnergy;
    public final boolean physicsInput; public final int yawAxis; public final String engine;

    /** opener maps a file name (meta.json, speed_mean.txt, stop.txt) to a stream: a folder, Android assets, a zip... */
    public Model(Function<String, InputStream> opener) throws IOException {
        String meta = read(opener.apply("meta.json"));
        speedMean = new Gbdt(new InputStreamReader(opener.apply("speed_mean.txt"), StandardCharsets.UTF_8));
        stop = new Gbdt(new InputStreamReader(opener.apply("stop.txt"), StandardCharsets.UTF_8));
        stopThreshold = num(meta, "stop_threshold"); headingSign = num(meta, "heading_sign"); keyEnergy = ints(meta, "key_energy");
        physicsInput = meta.matches("(?s).*\"physics_input\":\\s*true.*"); yawAxis = (int) num(meta, "yaw_axis");
        Matcher m = Pattern.compile("\"engine\":\\s*\"(\\w+)\"").matcher(meta); engine = m.find() ? m.group(1) : "phone";
    }

    public static Model fromFolder(File dir) throws IOException {
        return new Model(name -> { try { return new FileInputStream(new File(dir, name)); } catch (IOException e) { throw new UncheckedIOException(e); } });
    }

    static String read(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); return b.toString("UTF-8"); }
    static double num(String json, String k) { Matcher m = Pattern.compile("\"" + k + "\":\\s*([-0-9.eE+]+)").matcher(json); if (!m.find()) throw new IllegalArgumentException(k); return Double.parseDouble(m.group(1)); }
    static int[] ints(String json, String k) { Matcher m = Pattern.compile("\"" + k + "\":\\s*\\[([^\\]]*)\\]").matcher(json); if (!m.find()) throw new IllegalArgumentException(k); String[] p = m.group(1).replaceAll("\\s", "").split(","); int[] a = new int[p.length]; for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i]); return a; }
}
