package com.maverickgrid.app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records raw rides for training: imu.csv (every sensor event at native rate) and gnss.csv (every fix).
 * Files go to Android/data/com.maverickgrid.app/files/rides/ride_<date>/ (copy them to the PC over USB).
 */
final class RideLogger {
    private BufferedWriter imu, gnss; private File dir;

    synchronized File start(File root, String vehicle, String mount) throws IOException {
        stop();
        dir = new File(root, "ride_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        try (FileWriter w = new FileWriter(new File(dir, "info.txt"))) {
            w.write("vehicle=" + vehicle + "\nmount=" + mount + "\ndevice=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\nandroid=" + android.os.Build.VERSION.RELEASE + "\n");
        }
        imu = new BufferedWriter(new FileWriter(new File(dir, "imu.csv")), 1 << 16);
        imu.write("t_ns,sensor,x,y,z\n");
        gnss = new BufferedWriter(new FileWriter(new File(dir, "gnss.csv")), 1 << 14);
        gnss.write("t_ns,lat,lon,alt_m,speed_mps,bearing_deg,accuracy_m,speed_acc_mps,bearing_acc_deg,provider\n");
        return dir;
    }

    synchronized boolean active() { return imu != null; }

    synchronized void imu(long tNs, String sensor, float[] v) {
        if (imu == null) return;
        try { imu.write(tNs + "," + sensor + "," + v[0] + "," + v[1] + "," + v[2] + "\n"); } catch (IOException ignored) { }
    }

    synchronized void gnss(long tNs, double lat, double lon, double alt, float speed, float bearing, float acc, float sAcc, float bAcc, String provider) {
        if (gnss == null) return;
        try { gnss.write(tNs + "," + lat + "," + lon + "," + alt + "," + speed + "," + bearing + "," + acc + "," + sAcc + "," + bAcc + "," + provider + "\n"); } catch (IOException ignored) { }
    }

    synchronized File stop() {
        File d = dir;
        try { if (imu != null) imu.close(); if (gnss != null) gnss.close(); } catch (IOException ignored) { }
        imu = null; gnss = null; dir = null; return d;
    }
}
