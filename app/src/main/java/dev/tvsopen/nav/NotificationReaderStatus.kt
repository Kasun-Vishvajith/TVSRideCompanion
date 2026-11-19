package dev.tvsopen.nav

/** Connection permission and an actual listener binding are separate states. */
data class NotificationReaderStatus(
    val connected: Boolean = false,
    val mapsNotificationReceived: Boolean = false,
    val guidanceReceived: Boolean = false,
)

/** Avoid repeated rebind requests when several activity resumes happen together. */
class NotificationRebindGate {
    private var lastRequestAt: Long? = null

    fun onConnected() { lastRequestAt = null }

    fun shouldRequest(accessGranted: Boolean, connected: Boolean, now: Long): Boolean {
        if (!accessGranted || connected) return false
        val last = lastRequestAt
        if (last != null && now - last < 10_000L) return false
        lastRequestAt = now
        return true
    }
}
