package com.maverickgrid.app;

import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Raw data of a drive, for training and analysis:
 *   gnss.csv    – every GPS fix (about 1 per second), always;
 *   sensors.csv – when "Save sensor data" is on: one row per fixed time step (default 50 Hz = 50 rows per second),
 *                 every sensor in its own columns (acc_x, acc_y, acc_z, gyr_x ...), plus the latest GPS fix;
 *   info.txt    – vehicle, phone, log rate.
 * Accelerometer and gyroscope are the MEAN of all events inside each step (anti-aliasing, like the engine's 10 Hz input);
 * n_acc / n_gyr say how many events were averaged, so 10 Hz engine inputs can be rebuilt exactly on the laptop.
 * Gravity, magnetic field and rotation vector are the latest value. Steps with no new event (short sensor gaps, up to
 * 0.5 s) repeat the previous values with n_acc = n_gyr = 0; longer gaps are left out (visible as a jump in t_s).
 */
final class RideLogger {
    static final String HEADER = "t_s,t_ns,"
            + "acc_x_mps2,acc_y_mps2,acc_z_mps2,gyr_x_rads,gyr_y_rads,gyr_z_rads,grav_x_mps2,grav_y_mps2,grav_z_mps2,"
            + "linacc_x_mps2,linacc_y_mps2,linacc_z_mps2,mag_x_uT,mag_y_uT,mag_z_uT,rot_x,rot_y,rot_z,rot_w,yaw_deg,pitch_deg,roll_deg,"
            + "gnss_lat,gnss_lon,gnss_alt_m,gnss_speed_mps,gnss_bearing_deg,gnss_acc_m,gnss_age_s,n_acc,n_gyr\n";

    private BufferedWriter rows, gnss; private long stepNs = 20_000_000L, originNs, driveT0Ns; private boolean haveOrigin;
    // two open steps (slot 0 = `bin`, slot 1 = bin + 1): Android orders timestamps per sensor only, so an accelerometer event
    // may arrive after a gyroscope event of the next step; a step is written once an event two steps later arrives
    private long bin = -1; private final double[][] accSum = new double[2][3], gyrSum = new double[2][3]; private final int[] nAcc = new int[2], nGyr = new int[2];
    private final double[] accMean = new double[3], gyrMean = new double[3]; private final float[] grav = new float[3], mag = new float[3], rot = new float[5]; private boolean hasGrav, hasMag, hasRot, hasAcc, hasGyr;
    private double gLat = Double.NaN, gLon, gAlt, gSpd, gBrg, gAcc; private long gT;
    private final float[] rm = new float[9], ori = new float[3], rv3 = new float[3], rv4 = new float[4]; private final StringBuilder sb = new StringBuilder(512);

    synchronized void start(DriveStore store, boolean withSensors, int hz, long driveT0Ns, String vehicle, String vehicleName, long vehicleId, String mount) throws IOException {
        stop();
        hz = Math.max(5, Math.min(200, hz)); stepNs = 1_000_000_000L / hz; this.driveT0Ns = driveT0Ns; haveOrigin = false; bin = -1; gLat = Double.NaN;
        hasGrav = hasMag = hasRot = hasAcc = hasGyr = false; clearSlot(0); clearSlot(1);
        try (Writer w = new OutputStreamWriter(store.open("info.txt", "text/plain"), StandardCharsets.UTF_8)) {
            w.write("vehicle=" + vehicle + "\nvehicle_name=" + vehicleName + "\nvehicle_id=" + vehicleId + "\nmount=" + mount
                    + "\ndevice=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\nandroid=" + android.os.Build.VERSION.RELEASE
                    + "\nsensors_saved=" + withSensors + "\nsensor_rate_hz=" + hz + "\n");
        }
        gnss = new BufferedWriter(new OutputStreamWriter(store.open("gnss.csv", "text/csv"), StandardCharsets.UTF_8), 1 << 14);
        gnss.write("t_ns,lat,lon,alt_m,speed_mps,bearing_deg,accuracy_m,speed_acc_mps,bearing_acc_deg,provider\n");
        if (withSensors) { rows = new BufferedWriter(new OutputStreamWriter(store.open("sensors.csv", "text/csv"), StandardCharsets.UTF_8), 1 << 16); rows.write(HEADER); }
    }

    synchronized boolean active() { return gnss != null; }
    synchronized boolean sensorsActive() { return rows != null; }

    /** Every sensor event (engine thread). */
    synchronized void sensor(int type, long tNs, float[] v) {
        if (rows == null) return;
        if (!haveOrigin) { long now = android.os.SystemClock.elapsedRealtimeNanos(); originNs = Math.abs(tNs - now) < 5_000_000_000L ? driveT0Ns : tNs; haveOrigin = true; }
        long b = Math.floorDiv(tNs - originNs, stepNs);
        if (bin < 0) bin = b;
        if (b < bin) return;                                  // later than two steps late: ignore
        long maxGap = Math.max(1, 500_000_000L / stepNs);
        if (b - bin > maxGap + 2) {                           // long sensor gap: write what is open, leave the gap out
            writeRow(bin, 0); writeRow(bin + 1, 1); clearSlot(0); clearSlot(1); bin = b;
        }
        while (b >= bin + 2) {                                // step `bin` is complete (short gaps repeat the last values with n = 0)
            writeRow(bin, 0); shiftSlots(); bin++;
        }
        int k = (int) (b - bin);
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: for (int i = 0; i < 3; i++) accSum[k][i] += v[i]; nAcc[k]++; break;
            case Sensor.TYPE_GYROSCOPE: for (int i = 0; i < 3; i++) gyrSum[k][i] += v[i]; nGyr[k]++; break;
            case Sensor.TYPE_GRAVITY: System.arraycopy(v, 0, grav, 0, 3); hasGrav = true; break;
            case Sensor.TYPE_MAGNETIC_FIELD: System.arraycopy(v, 0, mag, 0, 3); hasMag = true; break;
            case Sensor.TYPE_ROTATION_VECTOR: rot[0] = v[0]; rot[1] = v[1]; rot[2] = v[2]; rot[3] = v.length > 3 ? v[3] : Float.NaN; hasRot = true; break;
            default: break;
        }
    }

    private void clearSlot(int k) { for (int i = 0; i < 3; i++) { accSum[k][i] = 0; gyrSum[k][i] = 0; } nAcc[k] = 0; nGyr[k] = 0; }
    private void shiftSlots() {
        for (int i = 0; i < 3; i++) { accSum[0][i] = accSum[1][i]; gyrSum[0][i] = gyrSum[1][i]; } nAcc[0] = nAcc[1]; nGyr[0] = nGyr[1]; clearSlot(1);
    }

    private void writeRow(long b, int k) {
        if (nAcc[k] > 0) { for (int i = 0; i < 3; i++) accMean[i] = accSum[k][i] / nAcc[k]; hasAcc = true; }
        if (nGyr[k] > 0) { for (int i = 0; i < 3; i++) gyrMean[i] = gyrSum[k][i] / nGyr[k]; hasGyr = true; }
        if (!hasAcc || !hasGyr) return;                       // nothing useful before both have arrived
        long tStart = originNs + b * stepNs;
        sb.setLength(0);
        fixed(sb, (tStart - driveT0Ns) / 1e9, 1000); sb.append(',').append(tStart);
        for (int i = 0; i < 3; i++) sb.append(',').append((float) accMean[i]);
        for (int i = 0; i < 3; i++) sb.append(',').append((float) gyrMean[i]);
        for (int i = 0; i < 3; i++) sb.append(',').append(hasGrav ? String.valueOf(grav[i]) : "");
        for (int i = 0; i < 3; i++) sb.append(',').append(hasGrav ? String.valueOf((float) (accMean[i] - grav[i])) : "");
        for (int i = 0; i < 3; i++) sb.append(',').append(hasMag ? String.valueOf(mag[i]) : "");
        if (hasRot) {
            for (int i = 0; i < 3; i++) sb.append(',').append(rot[i]); sb.append(',').append(Float.isNaN(rot[3]) ? "" : String.valueOf(rot[3]));
            try {
                float[] rv = Float.isNaN(rot[3]) ? rv3 : rv4; rv[0] = rot[0]; rv[1] = rot[1]; rv[2] = rot[2]; if (rv == rv4) rv4[3] = rot[3];
                SensorManager.getRotationMatrixFromVector(rm, rv); SensorManager.getOrientation(rm, ori);
                sb.append(',').append((float) Math.toDegrees(ori[0])).append(',').append((float) Math.toDegrees(ori[1])).append(',').append((float) Math.toDegrees(ori[2]));
            } catch (IllegalArgumentException e) { sb.append(",,,"); }
        } else sb.append(",,,,,,,");
        if (!Double.isNaN(gLat) && gT <= tStart + stepNs) {
            sb.append(',').append(gLat).append(',').append(gLon).append(',').append(num(gAlt)).append(',').append(num(gSpd)).append(',').append(num(gBrg)).append(',').append(num(gAcc))
              .append(','); fixed(sb, Math.max(0, tStart + stepNs - gT) / 1e9, 100);
        } else sb.append(",,,,,,,");
        sb.append(',').append(nAcc[k]).append(',').append(nGyr[k]).append('\n');
        try { rows.write(sb.toString()); } catch (IOException ignored) { }
    }
    /** v with a fixed number of decimals (scale 100 = 2, 1000 = 3) without String.format (50 rows per second). */
    static void fixed(StringBuilder sb, double v, int scale) {
        long q = Math.round(v * scale); if (q < 0) { sb.append('-'); q = -q; }
        sb.append(q / scale).append('.'); long f = q % scale;
        for (int d = scale / 10; d >= 1; d /= 10) { sb.append((char) ('0' + (f / d) % 10)); }
    }
    private static String num(double v) { return Double.isNaN(v) ? "" : String.valueOf((float) v); }

    synchronized void gnss(long tNs, double lat, double lon, double alt, float speed, float bearing, float acc, float sAcc, float bAcc, String provider) {
        if (gnss == null) return;
        try { gnss.write(tNs + "," + lat + "," + lon + "," + alt + "," + speed + "," + bearing + "," + acc + "," + sAcc + "," + bAcc + "," + provider + "\n"); } catch (IOException ignored) { }
        gLat = lat; gLon = lon; gAlt = alt; gSpd = speed; gBrg = bearing; gAcc = acc; gT = tNs;
    }

    synchronized void stop() {
        if (rows != null && bin >= 0) { writeRow(bin, 0); if (nAcc[1] > 0 || nGyr[1] > 0) writeRow(bin + 1, 1); }
        try { if (rows != null) rows.close(); } catch (IOException ignored) { }
        try { if (gnss != null) gnss.close(); } catch (IOException ignored) { }
        rows = null; gnss = null;
    }
}
