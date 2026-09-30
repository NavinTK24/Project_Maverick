package com.maverickgrid.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Evaluator for LightGBM models saved in LightGBM's native text format (Booster.save_model). Numerical splits only. */
public final class Gbdt {
    private static final double K_ZERO = 1e-35;
    private final Tree[] trees;
    private final boolean sigmoid;
    private final double sigmoidScale;
    public final int numFeatures;

    private static final class Tree {
        int[] feat, left, right, dtype; double[] thr, leaf;
        double eval(double[] x) {
            if (feat.length == 0) return leaf[0];
            int node = 0;
            while (node >= 0) {
                double v = x[feat[node]]; int dt = dtype[node]; int missing = (dt >> 2) & 3; boolean defLeft = (dt & 2) != 0;
                boolean goLeft;
                if (missing == 2 && Double.isNaN(v)) goLeft = defLeft;
                else if (missing == 1 && (Double.isNaN(v) || (v >= -K_ZERO && v <= K_ZERO))) goLeft = defLeft;
                else { if (Double.isNaN(v)) v = 0.0; goLeft = v <= thr[node]; }
                node = goLeft ? left[node] : right[node];
            }
            return leaf[~node];
        }
    }

    public Gbdt(Reader src) throws IOException {
        BufferedReader r = new BufferedReader(src); String line; List<Tree> ts = new ArrayList<>(); Tree cur = null;
        boolean sig = false; double scale = 1.0; int nf = 0; boolean inTrees = false;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("max_feature_idx=")) nf = Integer.parseInt(line.substring(16)) + 1;
            else if (line.startsWith("objective=") && !inTrees) {
                String o = line.substring(10);
                if (o.startsWith("binary")) { sig = true; for (String p : o.split(" ")) if (p.startsWith("sigmoid:")) scale = Double.parseDouble(p.substring(8)); }
            } else if (line.startsWith("Tree=")) { inTrees = true; cur = new Tree(); ts.add(cur); cur.feat = new int[0]; }
            else if (line.equals("end of trees")) break;
            else if (cur != null) {
                int eq = line.indexOf('='); if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1);
                switch (k) {
                    case "split_feature": cur.feat = ints(v); break;
                    case "threshold": cur.thr = dbls(v); break;
                    case "decision_type": cur.dtype = ints(v); break;
                    case "left_child": cur.left = ints(v); break;
                    case "right_child": cur.right = ints(v); break;
                    case "leaf_value": cur.leaf = dbls(v); break;
                    case "num_cat": if (Integer.parseInt(v) != 0) throw new IOException("categorical splits not supported"); break;
                    default: break;
                }
            }
        }
        trees = ts.toArray(new Tree[0]); sigmoid = sig; sigmoidScale = scale; numFeatures = nf;
        for (Tree t : trees) if (t.dtype == null) t.dtype = new int[t.feat.length];
    }

    private static int[] ints(String s) { String[] p = s.trim().split(" "); int[] a = new int[p.length]; for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i]); return a; }
    private static double[] dbls(String s) { String[] p = s.trim().split(" "); double[] a = new double[p.length]; for (int i = 0; i < p.length; i++) a[i] = Double.parseDouble(p[i]); return a; }

    /** Raw score (sum over trees). */
    public double raw(double[] x) { double s = 0; for (Tree t : trees) s += t.eval(x); return s; }

    /** Prediction as LightGBM's predict(): probability for binary models, value otherwise. */
    public double predict(double[] x) { double s = raw(x); return sigmoid ? 1.0 / (1.0 + Math.exp(-sigmoidScale * s)) : s; }

    public int numTrees() { return trees.length; }
}
