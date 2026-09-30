package com.maverickgrid.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Saves the stack trace of any crash to a file so it can be shown (and sent) on the next start. */
final class CrashLog {
    private CrashLog() {}
    private static boolean installed;

    static synchronized void install(Context ctx) {
        if (installed) return; installed = true;
        final File f = new File(ctx.getFilesDir(), "last_crash.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileWriter w = new FileWriter(f)) {
                StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
                w.write("Maverick crashed on thread '" + t.getName() + "' (" + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE + ")\n\n" + sw);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** The saved crash report (and deletes it), or null. */
    static String takeLast(Context ctx) {
        File f = new File(ctx.getFilesDir(), "last_crash.txt"); if (!f.exists()) return null;
        try { String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); f.delete(); return s; } catch (Exception e) { f.delete(); return null; }
    }
}
