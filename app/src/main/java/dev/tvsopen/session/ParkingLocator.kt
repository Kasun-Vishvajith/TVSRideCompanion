package dev.tvsopen.session

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.tvsopen.prefs.RidePrefs
import java.util.Locale

/** One foreground, user-requested fix; caller owns runtime permission and cancellation. */
class ParkingLocator(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null

    fun cancel() {
        listener?.let { runCatching { manager?.removeUpdates(it) } }
        listener = null
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    fun request(result: (String) -> Unit) {
        cancel()
        val lm = manager ?: run { result("Location unavailable"); return }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { result("Enable Location in Android settings"); return }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                if (age !in 0L..60_000L || !location.hasAccuracy() || location.accuracy > 100) return
                cancel()
                val label = String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
                val info = ParkedInfo(System.currentTimeMillis(), label)
                RidePrefs.load(context).saveParked(info.atMillis, info.label)
                Session.onParked(info)
                result("Parking saved on this phone")
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        var requested = false
        providers.forEach { provider ->
            runCatching { lm.requestLocationUpdates(provider, 1000L, 0f, callback, Looper.getMainLooper()) }
                .onSuccess { requested = true }
        }
        if (!requested) { cancel(); result("Allow foreground location permission"); return }
        handler.postDelayed({ cancel(); result("No recent accurate fix; try outdoors") }, 15_000)
    }
}
