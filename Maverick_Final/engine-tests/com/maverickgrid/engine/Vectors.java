package com.maverickgrid.engine;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Loads arrays exported by idr.export_java (big-endian float64 + manifest.json). */
final class Vectors {
    final Map<String, double[]> data = new HashMap<>(); final Map<String, int[]> shape = new HashMap<>();
    Vectors(Path dir) throws IOException {
        String man = new String(Files.readAllBytes(dir.resolve("manifest.json")));
        Matcher m = Pattern.compile("\"(\\w+)\":\\s*\\[([^\\]]*)\\]").matcher(man);
        while (m.find()) {
            String[] p = m.group(2).trim().isEmpty() ? new String[0] : m.group(2).replaceAll("\\s", "").split(",");
            int[] sh = new int[p.length]; for (int i = 0; i < p.length; i++) sh[i] = Integer.parseInt(p[i]);
            byte[] b = Files.readAllBytes(dir.resolve(m.group(1) + ".bin")); DoubleBuffer db = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).asDoubleBuffer();
            double[] a = new double[db.remaining()]; db.get(a); data.put(m.group(1), a); shape.put(m.group(1), sh);
        }
    }
    double[] get(String k) { return data.get(k); }
    double[] row(String k, int i) { int c = shape.get(k)[1]; return Arrays.copyOfRange(data.get(k), i * c, (i + 1) * c); }
    int rows(String k) { return shape.get(k)[0]; }
}
