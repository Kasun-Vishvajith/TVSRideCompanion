package dev.tvsopen.diagnostic

import android.content.Context
import android.os.BatteryManager
import dev.tvsopen.nav.NavInstruction
import dev.tvsopen.nav.NavigationBridge
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.JupiterDriver
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.ProtocolDriver
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.transport.BleFrame
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.ScooterBleService
import dev.tvsopen.transport.ScooterScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The bench supplies inputs to the same scanner, transport and navigation loop as the companion. */
internal class BenchLink(context: Context, private val scope: CoroutineScope, private val journal: Journal) {
    val scanner = ScooterScanner(context)
    private val transport = ScooterBleService(context, journal::frame)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var bridge: NavigationBridge? = null
    var driver: ProtocolDriver? = null
        private set
    var primaryOnly = false
    var stableText = false
    val ready: Boolean get() = transport.isReady && transport.state.value == ConnectionState.CONNECTED
    val disconnected: Boolean get() = transport.state.value == ConnectionState.DISCONNECTED

    suspend fun observe(update: (String) -> Unit) = coroutineScope {
        launch {
            transport.frames.collect { frame ->
                if (frame.direction == BleFrame.Direction.INBOUND) {
                    val current = driver ?: return@collect
                    val reply = current.buildChallengeResponse(frame.bytes) ?: return@collect
                    launch {
                        journal.add("HANDSHAKE", "Cluster challenged; waiting for GATT readiness")
                        kotlinx.coroutines.withTimeoutOrNull(8000) {
                            combine(transport.ready, transport.state) { ready, state ->
                                ready || state == ConnectionState.DISCONNECTED
                            }.first { it }
                        }
                        if (current === driver && ready) send("Challenge response", listOf(reply))
                        else journal.add("WARN", "Challenge response skipped; link unavailable")
                    }
                }
            }
        }
        transport.state.combine(transport.ready) { state, ready -> state to ready }.collect { (state, ready) ->
            val status = "${driver?.model?.displayName ?: "Cluster"} · $state · ${if (ready) "Ready to write" else "Writes unavailable"}"
            journal.add("LINK", status)
            update(status)
            if (state == ConnectionState.DISCONNECTED) stopLoops()
        }
    }

    fun connect(address: String, model: VehicleModel) {
        if (!disconnected) {
            journal.add("WARN", "Disconnect before connecting again")
            return
        }
        stopLoops()
        scanner.stop()
        val selected = if (model.sharesNtorqProtocol()) NtorqDriver(model) else JupiterDriver(model)
        driver = selected
        val nav = NavigationBridge(transport, scope, selected,
            compatibility = { primaryOnly }, stableText = { stableText })
        bridge = nav
        journal.add("ACTION", "Connect using ${model.displayName}")
        val started = runCatching { transport.connect(address) }
            .onFailure { journal.add("WARN", "Connect failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        if (started && selected.capabilities.keepalive) {
            nav.startKeepalive {
                val percentage = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
                DashState(System.currentTimeMillis(), batteryBars = (percentage / 20).coerceIn(0, 5), signalBars = 4)
            }
        } else if (!started) {
            journal.add("WARN", "GATT client did not start")
            stopLoops()
        }
    }

    fun navigation(step: NavInstruction) {
        bridge?.onInstruction(step)
        bridge?.startNavigation()
    }

    fun stopNavigation() {
        bridge?.stopNavigation()
    }

    suspend fun send(label: String, packets: List<ByteArray>) {
        if (packets.isEmpty()) {
            journal.add("WARN", "$label has no packet for this profile")
            return
        }
        journal.add("ACTION", "$label · ${packets.size} packet(s)")
        for ((index, bytes) in packets.withIndex()) {
            val accepted = transport.write(bytes)
            journal.add(if (accepted) "WRITE" else "WARN",
                "$label ${index + 1}/${packets.size} · transport success=$accepted")
            if (!accepted) break
        }
    }

    suspend fun disconnect() {
        scanner.stop()
        stopLoops()
        transport.disconnect()
    }

    private fun stopLoops() {
        bridge?.stopNavigation(clearDisplay = false)
        bridge?.stopKeepalive()
        bridge = null
    }
}
