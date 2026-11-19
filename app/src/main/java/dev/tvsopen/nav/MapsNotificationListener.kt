package dev.tvsopen.nav

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads turn-by-turn out of *any* maps app.
 *
 * This is the same approach as the official app
 * (`com.tvs.ble.feature.NotificationListenerService`): the cluster has no idea
 * what a maps app is, so we take instructions from whatever app is foreground
 * and forward them over BLE. No map SDK, no API key, no account — which is
 * what makes "use Google Maps without a TVS account" work.
 *
 * ## Recognising a navigation notification
 *
 * There is no standard flag, so we accept a notification when it
 *  - comes from a known maps package, **or**
 *  - looks like navigation (has the navigation extras we use).
 *
 * A user-configurable package allowlist comes first so a new maps app works
 * without a code change.
 */
class MapsNotificationListener : NotificationListenerService() {

    private val _instruction = MutableStateFlow<NavInstruction?>(null)
    val instruction: StateFlow<NavInstruction?> = _instruction.asStateFlow()

    /** True when a maps app is currently navigating. */
    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()

    /** Packages to trust in addition to structural detection. */
    @Volatile
    var allowedPackages: Set<String> = DEFAULT_MAPS_PACKAGES

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName
        val extras = n.notification?.extras ?: return
        if (pkg.isBlank()) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val ongoing = n.isOngoing

        if (pkg in allowedPackages && !_readerStatus.value.mapsNotificationReceived) {
            _readerStatus.value = _readerStatus.value.copy(mapsNotificationReceived = true)
            Log.i(TAG, "maps notification received provider=$pkg (content redacted)")
        }

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        val iconKind = notificationIconManeuver(n)
        val expandedTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty()
        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val ticker = n.notification.tickerText?.toString().orEmpty()
        val parsed = NavParsing.parse(title, text, bigText, subText,
            textLines = lines + listOf(expandedTitle, summary, ticker), iconManeuver = iconKind)
        if (dev.tvsopen.log.RaiderDiagnostics.enabled && pkg in allowedPackages) {
            Log.i(TAG, "nav shape title=${title.isNotBlank()} text=${text.isNotBlank()} " +
                "expanded=${bigText.isNotBlank()} summary=${subText.isNotBlank()} lines=${lines.size} " +
                "largeIcon=${n.notification.getLargeIcon() != null} " +
                "iconDirection=$iconKind semantic=${parsed.direction.kind} source=${parsed.direction.source}")
        }
        val distance = parsed.distanceMeters

        val looksLikeNav = pkg in allowedPackages ||
            (extras.containsKey("android.app.extra.MAPS_MODE") ||
                extras.containsKey("navigation.mode") ||
                (ongoing && title.isNotEmpty() && (text.isNotEmpty() || bigText.isNotEmpty()) &&
                    distance >= 0))

        if (looksLikeNav) {
            postNav(n.key, pkg, parsed)
            return
        }

        val prefs = RidePrefs.load(this)
        val category = n.notification?.category
        val isCall = category == Notification.CATEGORY_CALL ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_EXTRAS") ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_ADDRESS")
        if (isCall) {
            // Phone dialer calls follow the phone-calls toggle; VoIP/app calls
            // (WhatsApp, Telegram, …) are mirrored when phone calls are enabled,
            // since the cluster shows "who is calling" regardless of transport.
            if (prefs.mirrorPhoneCalls) {
                postCall(n.key, pkg, extras, title, text)
            }
            return
        }

        val hasMessageStyle = extractMessagingStyle(extras) != null
        val isMessage = hasMessageStyle || category == Notification.CATEGORY_MESSAGE
        if (isMessage && prefs.mirrorMessages) {
            // Only mirror messaging apps; a random app using MessagingStyle for
            // progress would be noise. Known packages plus any app whose
            // category is explicitly MESSAGE.
            if (pkg in MESSAGE_PACKAGES || category == Notification.CATEGORY_MESSAGE || hasMessageStyle) {
                postMessage(n.key, pkg, extras, title)
            }
        }
    }

    private fun postNav(key: String, pkg: String, parsed: ParsedNav) {
        // Package membership alone also matches Maps downloads and summaries.
        if ((parsed.distanceMeters < 0 && parsed.pictogram == null) ||
            (parsed.maneuver.isBlank() && parsed.street.isBlank())) {
            if (pkg in allowedPackages && !unreadableGuidanceLogged) {
                unreadableGuidanceLogged = true
                Log.i(TAG, "maps notification has no readable guidance provider=$pkg " +
                    "distanceKnown=${parsed.distanceMeters >= 0} directionKnown=${parsed.pictogram != null} " +
                    "instructionPresent=${parsed.maneuver.isNotBlank() || parsed.street.isNotBlank()}")
            }
            scheduleClear(key)
            return
        }
        unreadableGuidanceLogged = false
        _readerStatus.value = _readerStatus.value.copy(guidanceReceived = true)
        if (key != instructionKey) continuity.reset()
        instructionKey = key
        lifecycle.posted(key)

        val step = NavInstruction(
            title = parsed.maneuver,
            street = parsed.street,
            distanceMeters = parsed.distanceMeters,
            etaMinutes = parsed.etaMinutes,
            pictogram = parsed.pictogram,
            provider = pkg,
            remainingTripMeters = parsed.remainingTripMeters,
            direction = parsed.direction,
        )
        val stableStep = continuity.accept(step)
        val details = if (dev.tvsopen.log.RaiderDiagnostics.enabled)
            " maneuver=${stableStep.direction.kind} source=${stableStep.direction.source} " +
                "totalKnown=${stableStep.remainingTripMeters != null} totalHeld=${stableStep.totalHeld}" else ""
        Log.i(TAG, "nav parsed t=${android.os.SystemClock.elapsedRealtime()} " +
            "generation=${lifecycle.generation} provider=$pkg distanceKnown=${stableStep.distanceMeters >= 0} " +
            "zeroHeld=${step.distanceMeters != stableStep.distanceMeters} pic=${stableStep.pictogram}$details")
        Session.onHud {
            it.copy(
                active = true,
                instructionTitle = stableStep.title,
                street = stableStep.street,
                distanceMeters = stableStep.distanceMeters,
                etaMinutes = stableStep.etaMinutes,
                pictogramId = stableStep.pictogram,
                remainingTripMeters = stableStep.remainingTripMeters,
                totalHeld = stableStep.totalHeld,
                direction = stableStep.direction,
                provider = stableStep.provider,
            )
        }
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        _instruction.value = stableStep
        _navigating.value = true
    }

    private fun isMessagingCall(pkg: String): Boolean =
        pkg in APP_CALL_PACKAGES

    private fun postCall(key: String, pkg: String, extras: Bundle, title: String, text: String) {
        val number = extras.getString("android.telecom.extra.INCOMING_CALL_ADDRESS")
            ?: extras.getString("android.text")
            ?: text.takeIf { it.isNotBlank() }
            ?: ""
        val name = title.ifBlank { number }.ifBlank { "Incoming call" }
        val label = appLabel(pkg).takeUnless { it == "Phone" } ?: ""
        Log.i(TAG, "call notification from $pkg (content redacted)")
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = name,
                subtitle = if (label.isNotBlank() && number.isNotBlank()) "$label · $number" else number.ifBlank { label },
                key = key,
            ),
        )
    }

    private fun postMessage(key: String, pkg: String, extras: Bundle, title: String) {
        val info = extractMessageInfo(extras, title)
        val app = appLabel(pkg)
        val display = when {
            info.title.isNotBlank() && info.count > 1 -> "${info.title} ($app ${info.count})"
            info.title.isNotBlank() && app.isNotBlank() -> "${info.title} ($app)"
            info.title.isNotBlank() -> info.title
            info.count > 1 -> "$app: ${info.count} msgs"
            else -> app.ifBlank { "Message" }
        }
        Log.i(TAG, "message notification from $pkg (content redacted)")
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.MESSAGE,
                title = display,
                subtitle = app,
                key = key,
            ),
        )
    }

    private data class MessageInfo(val title: String, val count: Int)

    private fun extractMessageInfo(extras: Bundle, fallbackTitle: String): MessageInfo {
        val style = extractMessagingStyle(extras)
        if (style != null) {
            val conv = style.conversationTitle?.toString()?.trim().orEmpty()
            val messages = style.messages
            val count = messages.size
            val sender = conv.ifBlank {
                messages.lastOrNull()?.senderPerson?.name?.toString()?.trim().orEmpty()
            }
            return MessageInfo(sender.ifBlank { fallbackTitle }, count.coerceAtLeast(1))
        }
        return MessageInfo(fallbackTitle, 1)
    }

    @Suppress("DEPRECATION")
    private fun extractMessagingStyle(extras: Bundle): Notification.MessagingStyle? {
        val p = extras.getParcelable<android.os.Parcelable>("android.messagingStyle")
            ?: extras.getParcelable<android.os.Parcelable>(EXTRA_MESSAGING_STYLE_KEY)
        return p as? Notification.MessagingStyle
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
        "com.google.android.apps.messaging" -> "Messages"
        "com.facebook.orca" -> "Messenger"
        "com.instagram.android" -> "Instagram"
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer" -> "Phone"
        else -> pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        val key = removed.key
        // Clear a call/message alert posted from this notification.
        Session.clearAlert(key)

        scheduleClear(key)
    }

    private fun scheduleClear(key: String) {
        val generation = lifecycle.removed(key) ?: return
        pendingClear?.let { handler.removeCallbacks(it) }
        Log.i(TAG, "nav clear pending t=${android.os.SystemClock.elapsedRealtime()} generation=$generation")
        pendingClear = Runnable {
            if (!lifecycle.expire(generation)) return@Runnable
            // A callback can be missed during listener handover. Re-read only
            // ongoing Maps guidance before deciding the route actually ended.
            val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            active.filter { it.packageName in allowedPackages && it.isOngoing }
                .forEach { onNotificationPosted(it) }
            if (!lifecycle.expire(generation)) return@Runnable
            Log.i(TAG, "navigation ended t=${android.os.SystemClock.elapsedRealtime()} generation=$generation")
            continuity.reset()
            instructionKey = null
            Session.onHud { it.copy(active = false, instructionTitle = null, street = null,
                distanceMeters = -1, remainingTripMeters = null, totalHeld = false,
                etaMinutes = 0, pictogramId = null,
                direction = ManeuverReading(ManeuverKind.UNKNOWN, ManeuverSource.NONE)) }
            _navigating.value = false
            _instruction.value = null
            pendingClear = null
        }.also { handler.postDelayed(it, CLEAR_GRACE_MS) }
    }

    private var instructionKey: String? = null

    /** Read only publicly exposed icon resource names from the posting app. Bitmap pixels remain unknown. */
    private fun notificationIconManeuver(n: StatusBarNotification): ManeuverKind {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return ManeuverKind.UNKNOWN
        val icons = listOfNotNull(n.notification.getLargeIcon(), n.notification.smallIcon)
        val known = icons.mapNotNull { icon ->
            if (icon.type != android.graphics.drawable.Icon.TYPE_RESOURCE) return@mapNotNull null
            if (icon.resPackage != n.packageName) return@mapNotNull null
            runCatching {
                val resources = packageManager.getResourcesForApplication(n.packageName)
                ManeuverText.fromIconResource(resources.getResourceEntryName(icon.resId))
            }.getOrNull()?.takeUnless { it == ManeuverKind.UNKNOWN }
        }.distinct()
        return known.singleOrNull() ?: ManeuverKind.UNKNOWN
    }

    private val lifecycle = NavNotificationLifecycle()
    private val continuity = NavContinuity()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClear: Runnable? = null
    private var unreadableGuidanceLogged = false

    override fun onCreate() {
        super.onCreate()
        // Android may start the reader before the activity or ride service.
        Log.enableFileSink(java.io.File(filesDir, "logs"))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        rebindGate.onConnected()
        _readerStatus.value = NotificationReaderStatus(connected = true)
        unreadableGuidanceLogged = false
        Log.i(TAG, "listener connected")
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
            .onFailure { Log.w(TAG, "could not read active notifications: ${it.javaClass.simpleName}") }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _readerStatus.value = _readerStatus.value.copy(connected = false)
        Log.w(TAG, "listener disconnected — navigation mirroring unavailable")
        ensureConnected(this)
    }

    override fun onDestroy() {
        _readerStatus.value = _readerStatus.value.copy(connected = false)
        pendingClear?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MapsNavListener"
        private val _readerStatus = MutableStateFlow(NotificationReaderStatus())
        val readerStatus: StateFlow<NotificationReaderStatus> = _readerStatus.asStateFlow()
        private val rebindGate = NotificationRebindGate()

        fun hasAccess(context: Context): Boolean {
            val component = ComponentName(context, MapsNotificationListener::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                context.getSystemService(NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(component)
            } else {
                android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
            }
        }

        /** Ask Android to bind again without toggling the user's permission. */
        fun ensureConnected(context: Context) {
            if (!rebindGate.shouldRequest(hasAccess(context), _readerStatus.value.connected,
                    android.os.SystemClock.elapsedRealtime())) return
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(context, MapsNotificationListener::class.java))
                Log.i(TAG, "notification reader reconnect requested")
            }.onFailure {
                Log.w(TAG, "notification reader reconnect failed: ${it.javaClass.simpleName}")
            }
        }

        /** Apps commonly posting messages/calls that riders expect to see. */
        private val APP_CALL_PACKAGES = setOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.facebook.orca",
            "com.google.android.apps.tachyon", // Duo/Meet legacy
            "com.instagram.android",
        )

        private val MESSAGE_PACKAGES = setOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.facebook.orca",
            "com.instagram.android",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "org.thoughtcrime.securesms", // Signal
        )

        private const val EXTRA_MESSAGING_STYLE_KEY = "android.messagingStyle"

        /**
         * How long a cancelled navigation notification is given to be replaced.
         *
         * Nine seconds was the gap actually observed, so this sits just above
         * it. Long enough to survive a refresh, short enough that genuinely
         * ending a route clears the cluster promptly.
         */
        private const val CLEAR_GRACE_MS = 12_000L

        val DEFAULT_MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps",       // Google Maps
            "com.mappls.app",                     // MapMyIndia
            "com.mmi.android.mmaps",              // Mapmyne
            "org.organicmaps",                    // Organic Maps
            "com.here.app.maps",                  // HERE WeGo
            "com.waze",                           // Waze
            "com.tomtom.gotcha",                  // TomTom Go
            "com.transit.app.transitmaps",        // Transit
            "com.immobiletrack",
        )
    }
}
/**
 * How many characters each cluster text line actually shows.
 *
 * The protocol's text payload is 17 bytes and the Jupiter app fills all 17, which
 * is where the original assumption came from. TVS's Ntorq 125 manual documents
 * what this cluster renders. The rider confirms the display is asymmetric:
 * **10 characters on line 1, 12 on line 2**. The manual's line-2 examples
 * corroborate the longer limit with two visible cuts:
 *
 * - `DISTANCE REMAINING` -> `DISTANCE REM`
 * - `STAY SAME ROAD` -> `STAY SAME RD`
 *
 * Sending 17 characters means the tail is clipped on both rows. Line 1 must be
 * capped at 10; line 2 at 12. See [RowChars].
 */
object RowChars {
    /** Ntorq physical line 1, confirmed by the rider. */
    const val NTORQ_LINE_1 = 10

    /** Ntorq physical line 2, confirmed by the rider and shown in TVS's manual. */
    const val NTORQ_LINE_2 = 12

    /** Longest Ntorq row, retained for the legacy single-row path. */
    const val NTORQ = NTORQ_LINE_2

    /**
     * The Jupiter's row, kept for the Jupiter driver.
     *
     * The 17-byte payload is the frame shape both clusters accept; only the
     * usable characters differ, so this changes how much text is *sent*, never
     * how the frame is laid out.
     */
    const val JUPITER = 17
}
