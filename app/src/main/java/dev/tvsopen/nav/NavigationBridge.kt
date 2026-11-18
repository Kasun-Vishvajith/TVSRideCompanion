// The bridge: one nav instruction in, cluster frames out.
//
// This is the piece that makes the app provider-agnostic and account-free.
// Nothing here talks to TVS or to a maps SDK.

package dev.tvsopen.nav

import dev.tvsopen.log.Log
import android.os.SystemClock
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.NavStep
import dev.tvsopen.protocol.OutboundId
import dev.tvsopen.protocol.Pictograms
import dev.tvsopen.protocol.ProtocolDriver
import dev.tvsopen.transport.ScooterBleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Phone-side transmission state. No known packet acknowledges physical rendering. */
data class HudState(
    val active: Boolean = false,
    val instruction: NavInstruction? = null,
    val framesSent: Int = 0,
    val lastError: String? = null,
    /** Which navigation data id is in use — 0x4E or 0x49, or null if disabled. */
    val navigationDataId: Int? = null,
)

/**
 * Forwards navigation to the cluster.
 *
 * ## Cadence
 *
 * The cluster treats the navigation control frame as a *liveness* signal, not
 * a one-shot: the official app re-sends it roughly every 1.5 s while a route is
 * active. Stopping mid-route makes the HUD close. [controlIntervalMs] is
 * therefore not a tuning knob but part of the protocol.
 *
 * Row count and rendering are model-dependent. Raider primary-only mode is
 * an experiment; physical display liveness needs time-synchronised observation.
 */
class NavigationBridge(
    private val scope: CoroutineScope,
    private val driver: ProtocolDriver,
    private val write: suspend (ByteArray) -> Boolean,
    private val ready: () -> Boolean,
    private val compatibility: () -> Boolean = { false },
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val trace: (String) -> Unit = { Log.i(TAG, it) },
    private val stableText: () -> Boolean = { false },
) {
    constructor(ble: ScooterBleService, scope: CoroutineScope, driver: ProtocolDriver,
        compatibility: () -> Boolean = { false }, stableText: () -> Boolean = { false }) : this(
        scope, driver, { ble.write(it) }, { ble.isReady }, compatibility, stableText = stableText,
    )

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private var controlJob: Job? = null
    private var exitJob: Job? = null
    private var pingJob: Job? = null

    /** 0x4E or 0x49; whichever the cluster accepts. Default 0x4E. */
    var navigationDataId: Int = OutboundId.NAVIGATION_CONTROL

    /**
     * Line 1 width on this cluster. The Ntorq has 10 chars on line 1 and
     * 12 on line 2; this property is retained for the legacy one-line path.
     */
    var textRowChars: Int = RowChars.NTORQ

    /** Ntorq second-line width, which is where the street/distance row goes. */
    var secondTextRowChars: Int = RowChars.NTORQ_LINE_2

    /** Ntorq first-line width, which is where the maneuver goes. */
    var firstTextRowChars: Int = RowChars.NTORQ_LINE_1

    var controlIntervalMs: Long = 1500
    var textSwapIntervalMs: Long = 3000

    var pingIntervalMs: Long = 2000

    private var findMy = false

    // Rolling offset for overflowing text. Advances once per send so a line
    // wider than the row scrolls instead of clipping its tail. Reset whenever
    // the instruction changes so a new turn starts at its head, not mid-scroll.
    private var scrollTick = 0
    private var lastInstructionKey: String? = null

    /**
     * Start the keepalive. The cluster drops the link if the presence packet
     * stops, so this runs for the whole session, ride or not.
     */
    fun startKeepalive(dash: () -> DashState) {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            while (isActive) {
                val started = now()
                val state = dash().copy(findMyScooter = findMy)
                if (ready()) driver.buildPing(state)?.let { write(it) }
                delay((pingIntervalMs - (now() - started)).coerceAtLeast(1))
            }
        }
    }

    fun stopKeepalive() {
        pingJob?.cancel()
        pingJob = null
    }

    /** Flash the indicators and beep. Toggled into the keepalive packet. */
    fun setFindMyScooter(enabled: Boolean) {
        findMy = enabled
        Log.i(TAG, "find-my-scooter -> $enabled")
    }

    /** Begin mirroring navigation. No-op when the driver has no nav support. */
    fun startNavigation() {
        if (!driver.capabilities.navigationHud) {
            Log.w(TAG, "${driver.model.displayName} nav not enabled for this driver")
            return
        }
        if (controlJob?.isActive == true) return
        exitJob?.cancel()
        exitJob = null
        val generation = ++runGeneration
        controlJob = scope.launch {
            _hud.value = _hud.value.copy(active = true, navigationDataId = navigationDataId)
            var lastTick: Long? = null
            var showingMetrics = false
            var lastTextSwap = now()
            while (isActive) {
                val cycleStarted = now()
                val step = _hud.value.instruction
                val safe = driver.model == VehicleModel.RAIDER_125 && compatibility()
                val static = driver.model == VehicleModel.RAIDER_125 && stableText()
                if (ready() && step != null) {
                    val key = "${step.title}|${step.street}"
                    if (key != lastInstructionKey) {
                        lastInstructionKey = key
                        scrollTick = 0
                    }
                    val tick = if (static) 0 else scrollTick++
                    val nav = step.toNavStep()
                    val street = step.displayLine(secondTextRowChars, tick)
                    val metrics = if (driver.model == VehicleModel.RAIDER_125)
                        step.tripLine(firstTextRowChars, tick) else step.metricsLine(firstTextRowChars, tick)
                    val secondary = if (safe) null else driver.buildNavTextSecondary(nav.copy(streetName = metrics))
                    val control = nav.copy(rows = if (secondary != null && metrics.isNotBlank()) 2 else 1)
                    val time = now()
                    trace("nav tick t=$time generation=$generation gapMs=${lastTick?.let { time - it }} " +
                        "rows=${control.rows} stable=$static active=true " +
                        "maneuver=${step.direction.kind} source=${step.direction.source} " +
                        "pictogram=${control.pictogram} arrowPlaceholder=${step.pictogram == null} " +
                        "totalKnown=${step.remainingTripMeters != null} totalHeld=${step.totalHeld}")
                    lastTick = time
                    driver.buildNavControl(control)?.let { frame ->
                        val accepted = write(frame)
                        _hud.value = _hud.value.copy(
                            framesSent = _hud.value.framesSent + if (accepted) 1 else 0,
                            lastError = if (!accepted) "nav control rejected" else when {
                                driver.model == VehicleModel.RAIDER_125 && step.pictogram == null -> "Direction unknown: cluster straight arrow is a placeholder"
                                driver.model == VehicleModel.RAIDER_125 && step.remainingTripMeters == null -> "Trip total unavailable; cluster total field is a zero placeholder"
                                else -> null
                            },
                        )
                        if (accepted) lastControl = control
                    }
                    if (cycleStarted - lastTextSwap >= textSwapIntervalMs) {
                        showingMetrics = !showingMetrics
                        lastTextSwap = cycleStarted
                    }
                    val primary = if (!safe && secondary == null && showingMetrics) metrics else street
                    driver.buildNavText(nav.copy(streetName = primary))?.let { write(it) }
                    secondary?.let { write(it) }
                } else if (step == null) {
                    sendExit("route-ended")
                }
                // Text shape and scrolling must never slow the control heartbeat.
                delay((controlIntervalMs - (now() - cycleStarted)).coerceAtLeast(1))
            }
        }
    }

    private var lastControl: NavStep? = null
    private var runGeneration = 0L

    private suspend fun sendExit(reason: String) {
        val old = lastControl ?: return
        if (!ready()) { lastControl = null; return }
        trace("nav exit t=${now()} reason=$reason active=false")
        driver.buildNavControl(old.copy(active = false))?.let {
            if (write(it)) lastControl = null
        }
    }

    fun stopNavigation(clearDisplay: Boolean = true) {
        controlJob?.cancel()
        controlJob = null
        val generation = ++runGeneration
        exitJob?.cancel()
        exitJob = if (clearDisplay) scope.launch {
            if (generation == runGeneration) sendExit("mirroring-stopped")
        } else null
        if (!clearDisplay) lastControl = null
        scrollTick = 0
        lastInstructionKey = null
        _hud.value = HudState(active = false)
    }

    fun onInstruction(step: NavInstruction?) {
        if (_hud.value.instruction != step) {
            trace("nav instruction t=${now()} present=${step != null}")
        }
        _hud.value = _hud.value.copy(instruction = step)
    }

    private fun NavInstruction.toNavStep() = NavStep(
        distanceToTurnMeters = distanceMeters.coerceIn(0, 0xFFFF),
        etaMinutes = etaMinutes,
        remainingDistanceMeters = remainingTripMeters ?: 0,
        // Tier 2 of the pictogram resolver: when the provider gives no usable
        // icon and the words do not resolve, send straight-ahead. Named, not a
        // bare 7, so the value stays checkable against the table above.
        pictogram = pictogram ?: Pictograms.STRAIGHT,
        streetName = street,
    )

    companion object {
        private const val TAG = "NavBridge"
    }
}
