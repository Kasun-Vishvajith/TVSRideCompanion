package dev.raider.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;

/** Explicit ride lifetime; all transport state is confined to Store.IO. */
@SuppressLint("MissingPermission")
public final class RideService extends Service {
    static final String STOP = "dev.raider.STOP", NAME = "dev.raider.NAME";
    private static final UUID SERVICE = UUID.fromString("5456534d-5647-5341-5342-454e544f5251");
    private static final UUID RX = UUID.fromString("00005354-0000-1000-8000-00805f9b34fb");
    private static final UUID TX = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static RideService active;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private byte[] lastPrimary, lastSecondary;
    private boolean running, ready, writing, noResponse;
    private int attempts;
    private String address;
    private final Object callbacks = new Object();
    private void later(Runnable r, long delay) { Store.IO.postAtTime(r, callbacks, SystemClock.uptimeMillis() + delay); }
    private void state(String s) { Store.state = s; Store.event(s); Store.publish(true); }

    @Override public void onCreate() { super.onCreate(); Store.init(this); }
    @Override public android.os.IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i == null || STOP.equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("ride", "Active ride", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RideService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            startForeground(1, new Notification.Builder(this, "ride").setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Raider 125").setContentText("Ride active").setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop ride", stop).build()).setOngoing(true).build());
        } catch (SecurityException e) { stopSelf(); return START_NOT_STICKY; }
        String requested = i.getStringExtra("address");
        Store.IO.post(() -> {
            active = this;
            if (NAME.equals(i.getAction())) {
                if (ready) send(Protocol.text(0x52, getSharedPreferences("raider", 0).getString("name", "Rider")));
                return;
            }
            if (running && Objects.equals(address, requested)) return;
            Store.IO.removeCallbacksAndMessages(callbacks); closeLink(); Store.clearTelemetry();
            address = requested; running = true; attempts = 0; connect();
        });
        return START_NOT_STICKY;
    }
    private void connect() {
        if (!running || address == null) { stopSelf(); return; }
        state("Connecting…");
        try {
            BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
            if (adapter == null || !adapter.isEnabled()) { fail("Bluetooth is off"); return; }
            gatt = adapter.getRemoteDevice(address).connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            BluetoothGatt pending = gatt;
            later(() -> { if (pending == gatt && !ready) fail("Connection timed out"); }, 25_000);
        } catch (SecurityException | IllegalArgumentException e) { state("Bluetooth permission/device unavailable"); stopSelf(); }
    }
    private void closeLink() {
        Store.IO.removeCallbacks(writeTimeout);
        ready = writing = false; tx = null; queue.clear(); lastPrimary = lastSecondary = null;
        BluetoothGatt old = gatt; gatt = null;
        if (old != null) { try { old.disconnect(); } catch (SecurityException ignored) {} old.close(); }
    }
    private void fail(String reason) {
        if (!running) return;
        Store.IO.removeCallbacksAndMessages(callbacks); closeLink(); Store.clearTelemetry();
        if (attempts >= 10) { state(reason + " · retries stopped"); stopSelf(); return; }
        long delay = Math.min(60_000L, 5_000L << Math.min(attempts++, 4));
        state(reason + " · retry in " + delay / 1000 + "s"); later(this::connect, delay);
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int next) {
            Store.IO.post(() -> {
                if (g != gatt) return;
                if (status != 0 || next == BluetoothProfile.STATE_DISCONNECTED) { fail("Link disconnected (" + status + ")"); return; }
                if (next == BluetoothProfile.STATE_CONNECTED) {
                    state("Checking Raider service…");
                    if (!g.discoverServices()) fail("Discovery rejected");
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            Store.IO.post(() -> {
                if (g != gatt) return;
                BluetoothGattService service = g.getService(SERVICE);
                BluetoothGattCharacteristic rx = service == null ? null : service.getCharacteristic(RX);
                tx = service == null ? null : service.getCharacteristic(TX);
                if (status != 0 || rx == null || tx == null) { state("Required SmartXonnect service missing"); stopSelf(); return; }
                int props = tx.getProperties(); noResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                if (!noResponse && (props & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) { fail("No writable characteristic"); return; }
                BluetoothGattDescriptor descriptor = rx.getDescriptor(CCCD);
                if (descriptor == null || (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 || !g.setCharacteristicNotification(rx, true)) { fail("Notifications unavailable"); return; }
                boolean accepted;
                if (Build.VERSION.SDK_INT >= 33) accepted = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS;
                else { descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); accepted = g.writeDescriptor(descriptor); }
                if (!accepted) fail("Notification setup rejected");
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Store.IO.post(() -> {
                if (g != gatt || ready || !CCCD.equals(d.getUuid())) return;
                if (status != 0) { fail("Notification setup failed"); return; }
                ready = true; attempts = 0; state("Connected · Raider 125");
                send(Protocol.text(0x52, getSharedPreferences("raider", 0).getString("name", "Rider")));
                pulse();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) { receive(g, c, value); }
        @SuppressWarnings("deprecation") @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { if (c.getValue() != null) receive(g, c, c.getValue()); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Store.IO.post(() -> {
                if (g != gatt || noResponse || !writing) return;
                if (status != 0) { fail("Write completion failed"); return; }
                writing = false; Store.IO.removeCallbacks(writeTimeout); pump();
            });
        }
    };
    private final Map<Integer, Long> lastLog = new HashMap<>();
    private final Map<Integer, Integer> suppressed = new HashMap<>();
    private void receive(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] raw) {
        if (!RX.equals(c.getUuid())) return;
        byte[] b = raw.clone();
        Store.IO.post(() -> {
            if (g != gatt || !ready) return;
            Store.frame(b);
            int key = b.length >= 2 ? (Protocol.u(b, 0) << 8) | Protocol.u(b, 1) : -1;
            long now = SystemClock.elapsedRealtime();
            if (now - lastLog.getOrDefault(key, -1000L) >= 1000) {
                lastLog.put(key, now);
                // Metadata only: no address, route, caller, or startup identity bytes.
                Store.event(String.format(Locale.ROOT, "RX %04X length=%d validTelemetry=%s (+%d more)", key, b.length, Protocol.telemetry(b), suppressed.getOrDefault(key, 0)));
                suppressed.put(key, 0);
            } else suppressed.put(key, suppressed.getOrDefault(key, 0) + 1);
        });
    }
    private final Runnable writeTimeout = () -> { if (writing) fail("Write timed out; closing ambiguous link"); };
    private void send(byte[] packet) {
        if (!ready || queue.size() >= 12) return;
        queue.addLast(packet); pump();
    }
    private void pump() {
        if (!ready || writing || queue.isEmpty()) return;
        byte[] b = queue.removeFirst(); writing = true;
        int type = noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) accepted = gatt.writeCharacteristic(tx, b, type) == BluetoothGatt.GATT_SUCCESS;
            else { tx.setWriteType(type); tx.setValue(b); accepted = gatt.writeCharacteristic(tx); }
            if (!accepted) { fail("Write rejected"); return; }
            if (Protocol.u(b, 1) == 0x4f) lastPrimary = b;
            if (Protocol.u(b, 1) == 0x50) lastSecondary = b;
            Store.event(String.format(Locale.ROOT, "TX %02X%02X submitted", b[0], b[1]));
            if (noResponse) later(() -> { writing = false; pump(); }, 120);
            else Store.IO.postDelayed(writeTimeout, 4000);
        } catch (SecurityException e) { state("Bluetooth permission revoked"); stopSelf(); }
    }
    private String navKey, navLine1 = "", navLine2 = "";
    private long navAt, navRevision;
    static void updateName(String name) {
        Store.IO.post(() -> { RideService s = active; if (s != null && s.ready) s.send(Protocol.text(0x52, name)); });
    }
    static void refreshPolicy() {
        Store.IO.post(() -> { RideService s = active; if (s != null && s.navKey != null && !s.getSharedPreferences("raider", 0).getBoolean("navigation", true)) s.clearNavigation(); });
    }
    static void navigation(String key, String first, String second) {
        Store.IO.post(() -> {
            RideService s = active;
            if (s == null || !s.running) return;
            s.navKey = key; s.navRevision++; s.navLine1 = first; s.navLine2 = second; s.navAt = SystemClock.elapsedRealtime();
            Store.preview = first + "\n" + second;
            if (s.ready) s.sendNavigation();
            Store.publish(true);
        });
    }
    static void removed(String key) {
        Store.IO.post(() -> { RideService s = active; if (s != null && Objects.equals(s.navKey, key)) {
            long revision = s.navRevision;
            s.later(() -> { if (Objects.equals(s.navKey, key) && s.navRevision == revision) s.clearNavigation(); }, 2500);
        } });
    }
    private void clearNavigation() {
        navKey = null; Store.preview = "—";
        queue.removeIf(RideService::isNavigation);
        lastPrimary = lastSecondary = null;
        if (ready) send(Protocol.navigation(false)); Store.publish(true);
    }
    private void sendNavigation() {
        if (!getSharedPreferences("raider", 0).getBoolean("navigation", true)) { clearNavigation(); return; }
        queue.removeIf(RideService::isNavigation);
        send(Navigation.control(navLine1));
        byte[] first = Protocol.text(0x4f, navLine1), second = Protocol.text(0x50, navLine2);
        if (!Arrays.equals(first, lastPrimary)) send(first);
        if (!Arrays.equals(second, lastSecondary)) send(second);
    }
    private static boolean isNavigation(byte[] b) { int id = Protocol.u(b, 1); return id == 0x4e || id == 0x4f || id == 0x50; }
    private void pulse() {
        if (!running || !ready) return;
        BatteryManager battery = getSystemService(BatteryManager.class);
        int percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        send(Protocol.presence(Calendar.getInstance(), percent < 0 ? 0 : Math.min(5, (percent + 19) / 20)));
        if (navKey != null) {
            if (SystemClock.elapsedRealtime() - navAt > 60_000 || !getSharedPreferences("raider", 0).getBoolean("navigation", true)) clearNavigation();
            else sendNavigation();
        }
        Store.publish(true); later(this::pulse, 2000);
    }
    @Override public void onDestroy() {
        Store.IO.post(() -> {
            running = false; if (active == this) active = null;
            Store.IO.removeCallbacksAndMessages(callbacks); Store.IO.removeCallbacks(writeTimeout);
            closeLink(); Store.clearTelemetry(); Store.preview = "—"; state("Disconnected");
        });
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
