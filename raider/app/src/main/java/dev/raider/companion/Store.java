package dev.raider.companion;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.*;

/** One serial worker owns BLE, settings and disk writes. The UI receives small immutable snapshots. */
final class Store {
    static final HandlerThread THREAD = new HandlerThread("Raider IO", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    static { THREAD.start(); }
    static final Handler IO = new Handler(THREAD.getLooper());
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final Discovery discovery = new Discovery();
    static final Map<Integer, byte[]> frames = new HashMap<>();
    static final Map<Integer, Long> times = new HashMap<>();
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    static volatile String state = "Disconnected", preview = "—", test = "Ready for a baseline. Connect the Raider first.";
    static volatile String speed = "—", rpm = "—", gear = "—", odo = "—";
    static volatile Runnable listener;
    static File directory;
    static long lastPublish;
    static void init(Context c) {
        if (directory == null) directory = new File(c.getFilesDir(), "logs");
    }
    static void event(String message) {
        String line = System.currentTimeMillis() + " mono=" + SystemClock.elapsedRealtime() + " " + message;
        synchronized (lines) { if (lines.size() >= 4000) lines.removeFirst(); lines.addLast(line); }
        if (directory == null) return;
        try {
            if (!directory.exists() && !directory.mkdirs()) return;
            File f = new File(directory, "raider.log");
            if (f.length() >= 2_000_000) {
                File oldest = new File(directory, "raider.3.log");
                if (oldest.exists() && !oldest.delete()) throw new IOException("Rotation delete");
                File second = new File(directory, "raider.2.log");
                if (second.exists() && !second.renameTo(oldest)) throw new IOException("Rotation second");
                if (!f.renameTo(second)) throw new IOException("Rotation current");
            }
            try (FileWriter w = new FileWriter(f, true)) { w.write(line + "\n"); }
        } catch (IOException e) { android.util.Log.e("Raider", "Log write failed", e); }
    }
    static String logs() { synchronized (lines) { return String.join("\n", lines); } }
    static void clearTelemetry() {
        frames.clear(); times.clear(); speed = rpm = gear = odo = "—"; discovery.reset();
        test = discovery.report(SystemClock.elapsedRealtime());
    }
    static void frame(byte[] b) {
        long now = SystemClock.elapsedRealtime();
        discovery.frame(b, now);
        if (Protocol.telemetry(b)) { int id = Protocol.u(b, 1); frames.put(id, b.clone()); times.put(id, now); }
        publish(false);
    }
    static void publish(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPublish < 200) return;
        lastPublish = now;
        byte[] s = fresh(0x10, now), g = fresh(0x11, now);
        speed = s == null ? "—" : Integer.toString(Protocol.u(s, 2));
        rpm = s == null || Protocol.be(s, 16, 2) > 16000 ? "—" : Integer.toString(Protocol.be(s, 16, 2));
        odo = s == null ? "—" : String.format(Locale.ROOT, "%.1f", Protocol.be(s, 3, 3) / 10.0);
        gear = g == null || Protocol.u(g, 5) > 5 || (((Protocol.u(g, 13) & 1) != 0) != (Protocol.u(g, 5) == 0)) ? "—" : (Protocol.u(g, 5) == 0 ? "N" : Integer.toString(Protocol.u(g, 5)));
        test = discovery.report(now);
        MAIN.post(() -> { Runnable l = listener; if (l != null) l.run(); });
    }
    private static byte[] fresh(int id, long now) { return now - times.getOrDefault(id, -100_000L) <= 10_000 ? frames.get(id) : null; }
    private Store() {}
}
