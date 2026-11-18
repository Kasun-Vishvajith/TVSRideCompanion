package dev.raider.companion;

import android.app.Notification;
import android.service.notification.*;
import java.util.*;

/** Local navigation text only; no contact permissions, call actions or full notification logs. */
public final class NotificationBridge extends NotificationListenerService {
    private static final Set<String> PACKAGES = new HashSet<>(Arrays.asList("com.google.android.apps.maps", "com.waze"));
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!getSharedPreferences("raider", 0).getBoolean("navigation", true) || !PACKAGES.contains(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (!Notification.CATEGORY_NAVIGATION.equals(n.category)) return;
        String first = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE, ""));
        String second = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT, ""));
        if (first.trim().isEmpty() && second.trim().isEmpty()) return;
        RideService.navigation(sbn.getKey(), first, second);
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { RideService.removed(sbn.getKey()); }
    @Override public void onListenerDisconnected() {
        Store.IO.post(() -> { Store.event("Notification listener disconnected"); });
    }
}
