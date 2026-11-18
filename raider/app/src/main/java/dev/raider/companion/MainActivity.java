package dev.raider.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Fixed native views, no UI framework runtime or work while the activity is stopped. */
@SuppressLint("MissingPermission")
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status, speed, rpm, gear, odometer, nav, access, result;
    private EditText rider, note;
    private String page = "Overview", savedAddress, profile = "Rider";
    private boolean mirror = true, connectAfterPermission, scanAfterPermission, scanning;
    private BluetoothLeScanner scanner;
    private AlertDialog scanDialog;
    private LinearLayout devices;
    private final Set<String> found = new HashSet<>();
    private File pendingExport;
    private static final int INK = Color.rgb(233, 240, 230), MUTED = Color.rgb(154, 170, 151), ACCENT = Color.rgb(185, 243, 107);
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Store.init(this);
        if (state != null) page = state.getString("page", "Overview");
        draw();
        Store.IO.post(() -> {
            android.content.SharedPreferences p = getSharedPreferences("raider", 0);
            String address = p.getString("address", null), name = p.getString("name", "Rider");
            boolean enabled = p.getBoolean("navigation", true);
            Store.event("Raider app opened");
            main.post(() -> {
                savedAddress = address; profile = name; mirror = enabled;
                if (isFinishing() || isDestroyed()) return;
                draw();
                connectAfterPermission = savedAddress != null;
                if (requestGrants()) { if (connectAfterPermission) startRide(savedAddress); }
            });
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("page", page); super.onSaveInstanceState(state); }
    @Override protected void onResume() { super.onResume(); Store.listener = this::refresh; refresh(); }
    @Override protected void onPause() { Store.listener = null; stopScan(); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, dp(6), 0, dp(6)); return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void button(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(INK);
        b.setOnClickListener(v -> action.run()); parent.addView(b, new LinearLayout.LayoutParams(-1, dp(52)));
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(27, 35, 27)); bg.setCornerRadius(dp(18)); c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(14); parent.addView(c, lp); return c;
    }
    private void draw() {
        status = speed = rpm = gear = odometer = nav = access = result = null;
        LinearLayout root = column(); root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(16, 20, 16));
        TextView brand = text("RAIDER  /  125", 14, ACCENT); brand.setLetterSpacing(.16f); root.addView(brand);
        root.addView(text(page, 32, INK));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content = column(); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page.equals("Settings")) settings(); else if (page.equals("Testing")) testing(); else overview();
        LinearLayout tabs = new LinearLayout(this);
        for (String tab : new String[]{"Overview", "Settings"}) {
            Button b = new Button(this); b.setAllCaps(false); b.setText(tab); b.setTextColor(page.equals(tab) ? ACCENT : MUTED);
            b.setOnClickListener(v -> { page = tab; draw(); }); tabs.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        root.addView(tabs); setContentView(root); refresh();
    }
    private void overview() {
        LinearLayout header = card(content); status = text(Store.state, 17, ACCENT); header.addView(status);
        button(header, "Connect", () -> { connectAfterPermission = true; if (requestGrants()) connect(); });
        button(header, "Stop ride", () -> { stopScan(); stopService(new Intent(this, RideService.class)); });
        LinearLayout live = card(content);
        speed = text("—", 64, INK); live.addView(speed); live.addView(text("km/h", 14, MUTED));
        gear = text("Gear  —", 22, INK); live.addView(gear);
        rpm = text("RPM  —", 18, MUTED); live.addView(rpm);
        odometer = text("Odometer  —", 18, MUTED); live.addView(odometer);
        live.addView(text("Raider readings · calibration incomplete", 12, MUTED));
        LinearLayout guidance = card(content); guidance.addView(text("Navigation", 15, MUTED));
        nav = text(Store.preview, 20, INK); guidance.addView(nav);
    }
    private void settings() {
        LinearLayout p = card(content); p.addView(text("Profile", 16, ACCENT));
        rider = new EditText(this); rider.setText(profile); rider.setHint("Rider name"); rider.setSingleLine(true); rider.setTextColor(INK);
        rider.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(17)}); p.addView(rider);
        button(p, "Save name", () -> {
            profile = rider.getText().toString().trim();
            Store.IO.post(() -> getSharedPreferences("raider", 0).edit().putString("name", profile).apply());
            // Update only a running ride; saving a profile must never start a service.
            RideService.updateName(profile); toast("Name saved");
        });
        LinearLayout n = card(content); n.addView(text("Notifications", 16, ACCENT));
        access = text("", 15, INK); n.addView(access);
        button(n, "Notification access", () -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });
        Switch toggle = new Switch(this); toggle.setText("Mirror navigation"); toggle.setTextColor(INK); toggle.setChecked(mirror);
        toggle.setOnCheckedChangeListener((b, checked) -> { mirror = checked; Store.IO.post(() -> getSharedPreferences("raider", 0).edit().putBoolean("navigation", checked).apply()); RideService.refreshPolicy(); }); n.addView(toggle);
        LinearLayout logs = card(content); logs.addView(text("Logs", 16, ACCENT));
        button(logs, "View logs", () -> Store.IO.post(() -> {
            String value = readLogs(); main.post(() -> showText("Logs", value));
        }));
        button(logs, "Export logs", () -> Store.IO.post(() -> {
            try { File f = new File(getCacheDir(), "raider-logs.txt"); try (FileWriter w = new FileWriter(f)) { w.write(readLogs()); } main.post(() -> export(f)); }
            catch (IOException e) { main.post(() -> toast("Could not prepare logs")); }
        }));
        button(content, "Testing", () -> { page = "Testing"; draw(); });
        button(content, "Choose another Raider", () -> { connectAfterPermission = false; scanAfterPermission = true; if (requestGrants()) scan(); });
        content.addView(text("Raider 0.1.0 · offline", 12, MUTED));
    }
    private void testing() {
        content.addView(text("Capture 15 seconds around a bike-button press. Known RPM, gear and counters are hidden.", 15, MUTED));
        button(content, "Start button capture", () -> Store.IO.post(() -> {
            boolean ok = Store.discovery.arm(SystemClock.elapsedRealtime()); Store.publish(true);
            if (!ok) main.post(() -> toast("Wait for live Raider frames first"));
        }));
        result = text(Store.test, 15, INK); result.setTypeface(Typeface.MONOSPACE); result.setTextIsSelectable(true); content.addView(result);
        note = new EditText(this); note.setHint("What did you press or change?"); note.setTextColor(INK); content.addView(note);
        button(content, "Save observation", () -> {
            String report = Store.test, observation = note.getText().toString().trim();
            if (!Store.discovery.started()) { toast("Run a capture first"); return; }
            if (Store.discovery.active(SystemClock.elapsedRealtime())) { toast("Wait for capture to finish"); return; }
            if (observation.isEmpty()) { toast("Add a short observation"); return; }
            new AlertDialog.Builder(this).setTitle("Did you change something?")
                .setItems(new String[]{"Yes", "No", "Unsure"}, (d, index) -> saveObservation(observation, new String[]{"yes", "no", "unsure"}[index], report)).show();
        });
        button(content, "Saved observations", this::savedObservations);
    }
    private void saveObservation(String noteText, String answer, String report) {
        Store.IO.post(() -> {
            try {
                File dir = new File(getFilesDir(), "observations");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Directory");
                File[] old = dir.listFiles(); if (old != null && old.length >= 100) { main.post(() -> toast("100 observations saved; delete one first")); return; }
                File file = new File(dir, "observation-" + System.currentTimeMillis() + ".txt");
                try (FileWriter w = new FileWriter(file)) { w.write("Raider 125\nTime: " + new Date() + "\nChanged: " + answer + "\nObservation: " + noteText + "\n\n" + report); }
                Store.event("Observation saved (private local file)"); main.post(() -> toast("Observation saved"));
            } catch (IOException e) { main.post(() -> toast("Save failed")); }
        });
    }
    private void savedObservations() {
        Store.IO.post(() -> {
            File[] files = new File(getFilesDir(), "observations").listFiles();
            if (files == null || files.length == 0) { main.post(() -> toast("No saved observations")); return; }
            Arrays.sort(files, Comparator.comparing(File::getName).reversed());
            String[] names = Arrays.stream(files).map(File::getName).toArray(String[]::new);
            main.post(() -> new AlertDialog.Builder(this).setTitle("Saved observations").setItems(names, (d, index) -> {
                File file = files[index];
                Store.IO.post(() -> {
                    String value; try { value = read(file); } catch (IOException e) { value = "Cannot read observation"; }
                    String body = value;
                    main.post(() -> new AlertDialog.Builder(this).setTitle(file.getName()).setMessage(body)
                        .setPositiveButton("Export", (a, b) -> export(file)).setNeutralButton("Delete", (a, b) -> Store.IO.post(() -> { boolean deleted = file.delete(); main.post(() -> toast(deleted ? "Deleted" : "Delete failed")); }))
                        .setNegativeButton("Close", null).show());
                });
            }).setNegativeButton("Close", null).show());
        });
    }
    private boolean requestGrants() {
        ArrayList<String> missing = new ArrayList<>();
        String[] ble = Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : ble) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        // Request notification posting once; denial never blocks Bluetooth.
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(0).getBoolean("askedPost", false)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS); getPreferences(0).edit().putBoolean("askedPost", true).apply();
        }
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), 1); return false; } return true;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 1) return;
        boolean granted = true;
        for (int i = 0; i < permissions.length; i++) if (!permissions[i].equals(Manifest.permission.POST_NOTIFICATIONS) && results[i] != PackageManager.PERMISSION_GRANTED) granted = false;
        if (granted && scanAfterPermission) { scanAfterPermission = false; scan(); }
        else if (granted && connectAfterPermission) connect();
    }
    private void connect() { if (savedAddress == null) scan(); else startRide(savedAddress); }
    private void startRide(String address) { startForegroundService(new Intent(this, RideService.class).putExtra("address", address)); }
    private void scan() {
        stopScan();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { toast("Turn on Bluetooth"); return; }
        scanner = adapter.getBluetoothLeScanner(); if (scanner == null) { toast("Scanner unavailable"); return; }
        found.clear(); devices = column(); devices.setPadding(dp(18), 0, dp(18), 0);
        devices.addView(text("Select your Raider · scanning for 20s", 14, MUTED));
        ScrollView scroll = new ScrollView(this); scroll.addView(devices);
        scanDialog = new AlertDialog.Builder(this).setTitle("Raider 125").setView(scroll).setNegativeButton("Close", null).create();
        scanDialog.setOnDismissListener(d -> stopScan()); scanDialog.show();
        scanning = true; scanner.startScan(scanCallback); main.postDelayed(scanTimeout, 20_000);
    }
    private final Runnable scanTimeout = this::stopScan;
    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) { try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) {} }
        if (scanning && devices != null) devices.addView(text("Scan finished", 14, MUTED)); scanning = false;
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> {
                if (!scanning) return;
                BluetoothDevice d = result.getDevice(); String name;
                try { name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName(); if (name == null) name = d.getName(); }
                catch (SecurityException e) { stopScan(); return; }
                if (name == null || !name.toUpperCase(Locale.ROOT).startsWith("TVSBT") || !found.add(d.getAddress())) return;
                button(devices, name, () -> {
                    savedAddress = d.getAddress();
                    Store.IO.post(() -> getSharedPreferences("raider", 0).edit().putString("address", savedAddress).apply());
                    stopScan(); scanDialog.dismiss(); startRide(savedAddress);
                });
            });
        }
        @Override public void onScanFailed(int error) { main.post(() -> { stopScan(); toast("Scan failed: " + error); }); }
    };
    private void refresh() {
        if (status != null) status.setText(Store.state);
        if (speed != null) speed.setText(Store.speed);
        if (rpm != null) rpm.setText("RPM  " + Store.rpm);
        if (gear != null) gear.setText("Gear  " + Store.gear);
        if (odometer != null) odometer.setText("Odometer  " + Store.odo + " km");
        if (nav != null) nav.setText(Store.preview);
        if (result != null) result.setText(Store.test);
        if (access != null) {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            String own = new ComponentName(this, NotificationBridge.class).flattenToString();
            access.setText(enabled != null && Arrays.asList(enabled.split(":")).contains(own) ? "Access enabled" : "Access off");
        }
    }
    private String readLogs() {
        StringBuilder out = new StringBuilder();
        for (String n : new String[]{"raider.3.log", "raider.2.log", "raider.log"}) {
            File f = new File(Store.directory, n); if (!f.exists()) continue;
            try { out.append(read(f)); } catch (IOException e) { out.append("Log read failed\n"); }
        }
        return out.length() == 0 ? "No logs yet" : out.toString();
    }
    private static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }
    private void showText(String title, String body) {
        ScrollView scroll = new ScrollView(this); TextView t = text(body.length() > 100_000 ? body.substring(body.length() - 100_000) : body, 12, INK);
        t.setTextIsSelectable(true); t.setPadding(dp(18), 0, dp(18), 0); scroll.addView(t);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }
    private void export(File f) {
        pendingExport = f;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, f.getName()), 2);
    }
    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request != 2 || resultCode != RESULT_OK || data == null || data.getData() == null || pendingExport == null) return;
        File source = pendingExport; android.net.Uri destination = data.getData(); pendingExport = null;
        Store.IO.post(() -> {
            try (FileInputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("No destination");
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
                main.post(() -> toast("Exported"));
            } catch (IOException e) { main.post(() -> toast("Export failed")); }
        });
    }
    private void toast(String s) { if (!isDestroyed()) Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
