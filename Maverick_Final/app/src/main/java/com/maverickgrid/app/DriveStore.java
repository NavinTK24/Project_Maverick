package com.maverickgrid.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One drive's folder of files in the phone's public Documents folder: Documents/Maverick/&lt;vehicle&gt;/&lt;drive&gt;/
 * (visible over USB and in any file manager). Files are written through MediaStore; if that fails the folder
 * falls back to the app's own storage (Android/data/com.maverickgrid.app/files/drives/...).
 */
final class DriveStore {
    private final Context ctx; private final ContentResolver cr; final String relDir; private final File fallbackDir;
    private final List<Uri> pending = new ArrayList<>(); private boolean useFallback;

    DriveStore(Context c, String vehicleFolder, String driveName) {
        ctx = c.getApplicationContext(); cr = ctx.getContentResolver();
        relDir = "Documents/Maverick/" + vehicleFolder + "/" + driveName + "/";
        fallbackDir = new File(ctx.getExternalFilesDir("drives"), vehicleFolder + "/" + driveName);
    }

    /** Opens a new file for writing. Returns the stream; the uri (or file) is remembered for publishing. */
    synchronized OutputStream open(String name, String mime) throws IOException {
        if (!useFallback) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, relDir); v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = cr.insert(MediaStore.Files.getContentUri("external"), v);
                if (u == null) throw new IOException("MediaStore refused " + name);
                OutputStream o = cr.openOutputStream(u, "w"); if (o == null) throw new IOException("cannot open " + name);
                pending.add(u); lastUri.put(name, u); return o;
            } catch (IOException | RuntimeException e) { useFallback = true; }
        }
        if (!fallbackDir.isDirectory() && !fallbackDir.mkdirs()) throw new IOException("cannot create " + fallbackDir);
        File f = new File(fallbackDir, name); lastUri.put(name, Uri.fromFile(f)); return new FileOutputStream(f);
    }

    private final java.util.HashMap<String, Uri> lastUri = new java.util.HashMap<>();
    synchronized Uri uri(String name) { return lastUri.get(name); }

    /** Makes the written files visible to other apps (call after all streams are closed). */
    synchronized void publish() {
        ContentValues v = new ContentValues(); v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        for (Uri u : pending) { try { cr.update(u, v, null, null); } catch (RuntimeException ignored) { } }
        pending.clear();
    }

    /** Where the files are, in words for the user. */
    String where() { return useFallback ? "Android/data/" + ctx.getPackageName() + "/files/drives/" + fallbackDir.getParentFile().getName() + "/" + fallbackDir.getName() : relDir; }
}
