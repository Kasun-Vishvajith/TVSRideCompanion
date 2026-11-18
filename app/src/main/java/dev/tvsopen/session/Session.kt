// Single source of truth for the UI.
//
// The Compose screens must not reach into the BLE service directly: a GATT
// callback has no business knowing a ViewModel exists. Everything the UI shows
// is published here as StateFlow, and the service pushes into it.

package dev.tvsopen.session

import android.content.Context
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.DecodedFrame
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.Field
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.transport.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live telemetry, derived from decoded inbound frames.
 *
 * `avgEconomyKmpl` and `distanceToEmptyKm` are decoded but deliberately not
 * surfaced on the dashboard: TVS's Ntorq 125 manual lists every display
 * element the cluster has, and neither range nor fuel economy is one of them.
 * They are phone-app or cloud figures, and this app has no cloud.
 */
data class Telemetry(
    val raiderProfile: Boolean = false,
    val speedKmh: Int? = null,            // SM1 idx 2
    val odometerKm: Double? = null,       // SM1 idx 3..5, /10
    val fuelBars: Int? = null,            // SM1 idx 6, low nibble
    val averageSpeedKmh: Int? = null,     // SM1 idx 7
    val tripStatus: Int? = null,          // SM1 idx 8
    val topSpeedKmh: Int? = null,         // SM1 idx 9
    val throttlePercent: Int? = null,     // SM1 idx 10, /2
    val timeTo60Sec: Double? = null,      // SM1 idx 13, /10
    val tripFMeters: Int? = null,         // SM1 idx 14..16, /10
    val engineRpm: Int? = null,           // SM1 idx 17..18
    val serviceDue: Boolean? = null,      // SM2 idx 4
    /** MIL / check-engine lamp, from 0x11 diagnostics byte bit 2. */
    val milOn: Boolean? = null,
    /** Blink-code count from 0x11 byte 8 when the MIL is reporting a fault. */
    val milBlinkCode: Int? = null,
    val softwareVersion: String? = null,  // SM2 idx 7
    val avgEconomyKmpl: Int? = null,      // SM3 idx 8
    val tripAKm: Double? = null,
    val tripBKm: Double? = null,
    val gear: Int? = null,
    val averageEconomyCandidate: Int? = null,
    val averageEconomyRaw: Int? = null,
    val instantEconomyRaw: Int? = null,
    val instantEconomyKmpl: Int? = null,  // SM3 idx 9
    val distanceToEmptyKm: Int? = null,   // SM3 idx 11..12
    val engineTempC: Int? = null,         // SM4 idx 8
    val batteryVoltage: Int? = null,      // SM4 idx 11
    val lastFrameAt: Long = 0L,
    /** Per-frame timestamps; a fast speed packet must not freshen slow diagnostics. */
    val updatedAtById: Map<Int, Long> = emptyMap(),
    /**
     * Odometer reading when this link was established.
     *
     * The official app shows "Connected Distance" on its Vehicle Overview, but
     * that is computed server-side from ride records. We have no cloud, so this
     * is the same number done locally: odometer now minus odometer at connect.
     */
    val odometerAtConnectKm: Double? = null,
) {
    /**
     * Distance travelled on this connection, or null when it cannot be known —
     * either no odometer yet, or the bike appears to have gone backwards
     * (a reset or a cluster swap), which must not report as a negative ride.
     */
    fun connectedDistanceKm(): Double? {
        val start = odometerAtConnectKm ?: return null
        val now = odometerKm ?: return null
        val d = now - start
        return if (d >= 0) d else null
    }

    fun evidence(dataId: Int, verified: Boolean, now: Long = TelemetryClock.nowMillis()): String {
        val at = updatedAtById[dataId] ?: return "Unknown"
        if ((now - at).coerceAtLeast(0) >= STALE_AFTER_MS) return "Stale"
        return if (verified) "Verified mapping" else "Candidate / unverified"
    }

    val fuelBarMax: Int get() = if (raiderProfile) 5 else FUEL_BAR_MAX

    /** Clear only expired groups; live speed must not refresh battery or status. */
    fun forDisplay(now: Long): Telemetry {
        fun fresh(id: Int) = updatedAtById[id]?.let { (now - it).coerceAtLeast(0) < STALE_AFTER_MS } == true
        var shown = this
        if (!fresh(0x10)) shown = shown.copy(speedKmh = null, odometerKm = null, fuelBars = null,
            averageSpeedKmh = null, topSpeedKmh = null, throttlePercent = null, timeTo60Sec = null,
            tripFMeters = null, engineRpm = null)
        if (!fresh(0x11)) shown = shown.copy(serviceDue = null, milOn = null, milBlinkCode = null,
            softwareVersion = null, gear = null)
        if (!fresh(0x18)) shown = shown.copy(engineTempC = null, batteryVoltage = null)
        if (!fresh(0x19)) shown = shown.copy(avgEconomyKmpl = null, instantEconomyKmpl = null,
            distanceToEmptyKm = null)
        // Trip counters are historical readings; keep them visible with a freshness label.
        return shown
    }

    /** No frames for this long means the link is stale, not merely idle. */
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        lastFrameAt > 0 && now - lastFrameAt < STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 10_000L

        /** Bars in the Ntorq 125 cluster's fuel gauge. TVS manual, section 1c. */
        const val FUEL_BAR_MAX = 6
    }
}

/** Phone guidance state; physical cluster rendering requires observer confirmation. */
data class HudState(
    val active: Boolean = false,
    val instructionTitle: String? = null,
    val street: String? = null,
    val distanceMeters: Int = -1,
    val etaMinutes: Int = 0,
    val pictogramId: Int? = null,
    val remainingTripMeters: Int? = null,
    val totalHeld: Boolean = false,
    val direction: dev.tvsopen.nav.ManeuverReading = dev.tvsopen.nav.ManeuverReading(
        dev.tvsopen.nav.ManeuverKind.UNKNOWN, dev.tvsopen.nav.ManeuverSource.NONE),
    val provider: String? = null,
    val framesSent: Int = 0,
    val lastError: String? = null,
)

/**
 * A call or message to mirror on the cluster.
 *
 * [subtitle] carries the number or a short source label (e.g. "WhatsApp").
 * The message text itself is never included: the cluster gets a name and a
 * count, not the content.
 */
data class ClusterAlert(
    val kind: Kind,
    val title: String,
    val subtitle: String = "",
    /** Source notification key, so removal clears only the alert it posted. */
    val key: String = "",
) {
    enum class Kind { CALL, MESSAGE }
}

data class ConnectionState1(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val model: VehicleModel = VehicleModel.UNKNOWN,
    val address: String? = null,
    val writeReady: Boolean = false,
)

/**
 * Where the scooter was last seen.
 *
 * There is no cloud in this project, so this cannot be a server-side record.
 * It is written locally when the phone has a fix while the cluster is connected,
 * and it never leaves the device — there is no permission that would let it.
 */
data class ParkedInfo(
    val atMillis: Long,
    val label: String? = null,
    val distanceMeters: Int? = null,
)

/**
 * Session store.
 *
 * Mutable rather than a ViewModel: the BLE service and the notification
 * listener are framework components with their own lifecycles, and neither can
 * easily hold a ViewModel. A process-scoped singleton they can both write to is
 * simpler and has no lifecycle trap.
 */
object Session {

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val _alert = MutableStateFlow<ClusterAlert?>(null)
    val alert: StateFlow<ClusterAlert?> = _alert.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState1())
    val connection: StateFlow<ConnectionState1> = _connection.asStateFlow()

    private val _parked = MutableStateFlow<ParkedInfo?>(null)
    val parked: StateFlow<ParkedInfo?> = _parked.asStateFlow()

    private val _rideMode = MutableStateFlow(false)
    val rideMode: StateFlow<Boolean> = _rideMode.asStateFlow()

    /**
     * Local ride log, created once the Activity supplies a Context.
     *
     * Nullable rather than injected so `Session` stays usable from a
     * BroadcastReceiver that has no Activity to construct it with.
     */
    @Volatile
    private var rideLog: RideLog? = null

    private val _rides = MutableStateFlow<List<Ride>>(emptyList())
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    private val _totalRides = MutableStateFlow(0)
    val totalRides: StateFlow<Int> = _totalRides.asStateFlow()

    private val _totalRideDistanceKm = MutableStateFlow(0.0)
    val totalRideDistanceKm: StateFlow<Double> = _totalRideDistanceKm.asStateFlow()

    fun attachRideLog(context: android.content.Context) {
        if (rideLog != null) return
        synchronized(this) {
            if (rideLog != null) return
            val log = RideLog(context)
            rideLog = log
            _rides.value = log.rides.value
            _totalRides.value = log.totalRides
            _totalRideDistanceKm.value = log.totalDistanceKm
        }
    }

    /** Keep the open ride's distance in step with the odometer. */
    private fun trackRideDistance() {
        val log = rideLog ?: return
        // connectedDistanceKm() is a Telemetry member, not a Session one, so the
        // receiver has to be spelled out.
        log.update(_telemetry.value.connectedDistanceKm() ?: 0.0)
        _rides.value = log.rides.value
    }

    fun setRideMode(on: Boolean) { _rideMode.value = on }

    fun clearParked() { _parked.value = null }

    fun onParked(info: ParkedInfo) { _parked.value = info }

    fun loadParked(context: Context) {
        val p = RidePrefs.load(context)
        val at = p.parkedAtMillis
        if (at > 0L) {
            _parked.value = ParkedInfo(atMillis = at, label = p.parkedLabel, distanceMeters = null)
        }
    }

    fun reset() {
        _telemetry.value = Telemetry()
        _hud.value = HudState()
    }

    fun onWriteReady(ready: Boolean) {
        _connection.value = _connection.value.copy(writeReady = ready)
    }

    fun onConnection(state: ConnectionState, model: VehicleModel, address: String?) {
        val previous = _connection.value
        val wasConnected = previous.state == ConnectionState.CONNECTED
        val isConnected = state == ConnectionState.CONNECTED

        // Stamp the baseline on the first frame after a connect, so
        // "Connected Distance" is measured from the start of this link.
        // Cleared here rather than stamped: at CONNECTED time the odometer is
        // either stale (previous link) or null (fresh install, forever — the
        // old code stamped null once and "This connection" never recovered).
        // onFrame fills it from the first 0x10 that carries an odometer.
        if (isConnected && !wasConnected) {
            _telemetry.value = Telemetry(raiderProfile = model == VehicleModel.RAIDER_125)
            rideLog?.start()
            rideLog?.let { _rides.value = it.rides.value }
        }
        if (!isConnected && wasConnected) {
            rideLog?.finish()
            rideLog?.let { _rides.value = it.rides.value }
            _totalRides.value = rideLog?.totalRides ?: _totalRides.value
            _totalRideDistanceKm.value = rideLog?.totalDistanceKm ?: _totalRideDistanceKm.value
        }

        _connection.value = ConnectionState1(state, model, address)
    }

    /**
     * Fold one decoded frame into the telemetry snapshot.
     *
     * Only fields the frame actually carries are overwritten, so a slow frame
     * (say `0x19`, once a second) does not blank values that arrived with the
     * fast `0x10` stream.
     */
    fun onFrame(frame: DecodedFrame) {
        val now = System.currentTimeMillis()
        val receivedAt = TelemetryClock.nowMillis()

        // Raw integer divided once, here, into a Double — never an Int, so a
        // 7.3 s 0-60 run does not become 7, and a 3098.0 km odometer does not
        // become 3098 and then 309. (It did both of those before the driver
        // stopped pre-scaling in decode.)
        fun scaled(name: Field, div: Double): Double? =
            frame.field(name)?.let { it / div }

        val raider = _connection.value.model == VehicleModel.RAIDER_125
        val cur = _telemetry.value
        // The fuel byte's meaning is model-gated: the 125 family extracts the
        // low nibble, the 110 family passes the raw byte through (dex-verified
        // split behind JUPITER_U566). Read it from the current driver rather
        // than hardcoding the Ntorq rule for every model.
        val fuelLowNibble = DriverRegistry.forModel(_connection.value.model)
            .capabilities.fuelLowNibble
        _telemetry.value = when (frame.dataId) {
            0x10 -> cur.copy(
                speedKmh = frame.field(Field.SPEED)?.toInt() ?: cur.speedKmh,
                odometerKm = scaled(Field.ODOMETER, 10.0) ?: cur.odometerKm,
                // Baseline for "this connection", from the first frame that
                // carries an odometer — see onConnection, which clears it.
                odometerAtConnectKm = cur.odometerAtConnectKm
                    ?: scaled(Field.ODOMETER, 10.0),
                // Fuel is the low nibble of index 6 — that is exactly what the
                // official app does (NtorqSpeedOMeter1.getFuelLevel takes the last
                // hex character), and this app matches it. The decode is right;
                // the bar interpretation is not.
                //
                // The cluster's own gauge has SIX bars per TVS's manual, and the
                // raw value transmitted while the dash was photographed reading
                // FUEL EMPTY was 7. A six-bar gauge cannot represent 7, so 7 is
                // not a bar count. Clamping it to 6 — what this used to do —
                // displayed a FULL tank on an empty one.
                //
                // So an out-of-range value is dropped rather than clamped, and
                // the gauge says so. A genuine 0..6 still renders normally.
                // Note the absence of `?: cur.fuelBars`: a rejected reading has
                // to clear the previous one, not leave a stale level on screen.
                fuelBars = frame.field(Field.FUEL_LEVEL)?.toInt()
                    ?.let { if (fuelLowNibble) it and 0x0F else it }
                    ?.takeIf { it in 0..(if (raider) 5 else Telemetry.FUEL_BAR_MAX) },
                averageSpeedKmh = frame.field(Field.AVERAGE_SPEED)?.toInt()
                    ?.takeUnless { raider && it == 0 } ?: if (raider) null else cur.averageSpeedKmh,
                averageEconomyCandidate = frame.field(Field.AVERAGE_ECONOMY_CANDIDATE)?.toInt()
                    ?.takeIf { it in 1..254 },
                tripStatus = frame.field(Field.TRIP_STATUS)?.toInt() ?: cur.tripStatus,
                topSpeedKmh = frame.field(Field.TOP_SPEED)?.toInt() ?: cur.topSpeedKmh,
                throttlePercent = scaled(Field.THROTTLE_POSITION, 2.0)?.toInt()?.takeIf { it in 0..100 },
                timeTo60Sec = if (raider) null else scaled(Field.TIME_60KMPH, 10.0) ?: cur.timeTo60Sec,
                tripFMeters = if (raider) null else scaled(Field.TRIP_F_METER, 10.0)?.toInt() ?: cur.tripFMeters,
                engineRpm = frame.field(Field.ENGINE_RPM)?.toInt()?.takeIf { it in 0..16000 },
                lastFrameAt = now,
                updatedAtById = cur.updatedAtById + (frame.dataId to receivedAt),
            ).also { trackRideDistance() }

            0x11 -> cur.copy(
                gear = if (raider) frame.field(Field.GEAR_POSITION)?.toInt()?.takeIf { value ->
                    value in 0..5 && frame.field(Field.NEUTRAL_STATUS)?.let {
                        ((it and 1L) != 0L) == (value == 0)
                    } == true
                } else cur.gear,
                serviceDue = (frame.field(Field.SERVICE_REMINDER)?.toInt()?.let { it != 0 })
                    ?: cur.serviceDue,
                milOn = (frame.field(Field.VEHICLE_DIAGNOSTICS)?.toInt()
                    ?.let { (it and 0x04) != 0 }) ?: cur.milOn,
                milBlinkCode = frame.field(Field.MIL_BLINK_CODE)?.toInt()
                    ?: cur.milBlinkCode,
                softwareVersion = frame.field(Field.SOFTWARE_VERSION)?.toInt()
                    ?.takeIf { it != 0xFF }?.let { "0x%X".format(it) },
                lastFrameAt = now,
                updatedAtById = cur.updatedAtById + (frame.dataId to receivedAt),
            )

            0x19 -> cur.copy(
                tripAKm = scaled(Field.TRIP_A_DISTANCE, 10.0) ?: cur.tripAKm,
                tripBKm = scaled(Field.TRIP_B_DISTANCE, 10.0) ?: cur.tripBKm,
                averageEconomyRaw = if (raider) null else frame.field(Field.AVERAGE_FUEL_ECONOMY)?.toInt() ?: cur.averageEconomyRaw,
                instantEconomyRaw = if (raider) null else frame.field(Field.INSTANTANEOUS_FUEL_ECONOMY)?.toInt() ?: cur.instantEconomyRaw,
                avgEconomyKmpl = if (raider) null else frame.field(Field.AVERAGE_FUEL_ECONOMY)?.toInt()
                    ?: cur.avgEconomyKmpl,
                instantEconomyKmpl = if (raider) null else frame.field(Field.INSTANTANEOUS_FUEL_ECONOMY)?.toInt()
                    ?: cur.instantEconomyKmpl,
                distanceToEmptyKm = if (raider) null else frame.field(Field.DISTANCE_TO_EMPTY)?.toInt()
                    ?: cur.distanceToEmptyKm,
                lastFrameAt = now,
                updatedAtById = cur.updatedAtById + (frame.dataId to receivedAt),
            )

            0x18 -> cur.copy(
                engineTempC = if (raider) null else frame.field(Field.ENGINE_TEMPRATURE)?.toInt() ?: cur.engineTempC,
                batteryVoltage = frame.field(Field.BATTERY_VOLTAGE)?.toInt() ?: cur.batteryVoltage,
                lastFrameAt = now,
                updatedAtById = cur.updatedAtById + (frame.dataId to receivedAt),
            )

            else -> cur.copy(lastFrameAt = now)
        }
    }

    fun onHud(update: (HudState) -> HudState) {
        _hud.value = update(_hud.value)
    }

    /** Post or clear the active cluster alert (call/message). */
    fun onAlert(alert: ClusterAlert?) {
        _alert.value = alert
    }

    /** Clear the alert only if it was posted by [key]. */
    fun clearAlert(key: String) {
        if (_alert.value?.key == key) _alert.value = null
    }
}