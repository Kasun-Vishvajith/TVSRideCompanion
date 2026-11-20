// BLE transport.
//
// Model-agnostic: it knows about GATT and framing, not about what any
// particular cluster's frames mean. Per-model meaning lives behind
// ProtocolDriver.

package dev.tvsopen.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import dev.tvsopen.log.Log
import dev.tvsopen.protocol.Gatt
import dev.tvsopen.protocol.GattConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Human-readable label. The enum's own `name` renders as "DISCONNECTED", which
 * is shouting in a status pill.
 */
val ConnectionState.label: String
    get() = when (this) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.SCANNING -> "Scanning"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.RECONNECTING -> "Reconnecting"
    }

data class BleFrame(val direction: Direction, val bytes: ByteArray, val atMillis: Long) {
    enum class Direction { INBOUND, OUTBOUND }
    val hex: String get() = bytes.joinToString(" ") { "%02X".format(it) }
    override fun equals(other: Any?) =
        this === other || (other is BleFrame && direction == other.direction &&
            bytes.contentEquals(other.bytes) && atMillis == other.atMillis)
    override fun hashCode() = ((direction.ordinal * 31 + bytes.contentHashCode()) * 31 + atMillis.hashCode())
}

/**
 * One GATT link. The mutex covers completion of response writes, not just API
 * submission. Select the write type from discovered properties: the Raider
 * capture reports 0x08 (WRITE), which does not advertise WRITE_NO_RESPONSE.
 * GATT completion is transport evidence, never a physical-display ACK.
 */
class ScooterBleService(
    private val context: Context,
    private val frameObserver: ((BleFrame) -> Unit)? = null,
) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<BleFrame>(replay = 0, extraBufferCapacity = 256)
    val frames: SharedFlow<BleFrame> = _frames.asSharedFlow()

    private val _lastFrameAt = MutableStateFlow(0L)
    val lastFrameAt: StateFlow<Long> = _lastFrameAt.asStateFlow()

    private val writeLock = Mutex()
    // Written on the binder thread by the GATT callback and read on a coroutine
    // by connect/write, so the visibility guarantee is not optional: without it
    // a reconnect can read a stale non-null and refuse, which is exactly the
    // failure this class had.
    @Volatile
    private var gatt: BluetoothGatt? = null
    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val isReady: Boolean get() = _ready.value
    private val writeSequence = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var connectionGeneration = 0L
    private data class PendingWrite(val client: BluetoothGatt, val characteristic: UUID,
        val sequence: Long, val result: CompletableDeferred<Int>)
    @Volatile private var pendingWrite: PendingWrite? = null
    private var cccdFailed = false
    @Volatile private var setupCompleted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set once per connection, so the MTU callback and the fallback timer
     * cannot both start discovery. */
    @Volatile
    private var discoveryStarted = false

    /**
     * Whether the current link outage has already been reported.
     *
     * Guarded by [writeLock] rather than volatile: every read and write happens
     * inside `write`, so the lock is already held and a separate field would
     * only invite a second synchronisation scheme to drift out of step.
     */
    private var dropWarned = false

    val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.bluetooth_le")

    // ---- lifecycle ----

    @Synchronized
    @SuppressLint("MissingPermission")
    fun connect(address: String, config: GattConfig = GattConfig.DEFAULT): Boolean {
        val a = adapter ?: return false
        val device: BluetoothDevice = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid cluster address")
            return false
        }
        gatt?.let { previous ->
            if (isReady) return false
            // A new user request must be able to replace an unfinished or
            // write-stalled link, even if Android never delivered a callback.
            Log.w(TAG, "replacing unfinished GATT connection=$connectionGeneration")
            closeGatt(previous)
        }
        _state.value = ConnectionState.CONNECTING
        connectionGeneration++

        val client = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, config.autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, config.autoConnect, gattCallback)
        }
        gatt = client
        if (client == null) {
            _state.value = ConnectionState.DISCONNECTED
            return false
        }
        // Bound the entire setup, including service discovery and CCCDs.
        // Some Android failures deliver neither success nor disconnection.
        mainHandler.postDelayed({
            synchronized(this@ScooterBleService) {
                if (gatt === client && !setupCompleted) {
                    Log.w(TAG, "GATT setup timed out connection=$connectionGeneration; releasing client")
                    closeGatt(client)
                    _state.value = ConnectionState.DISCONNECTED
                }
            }
        }, CONNECT_SETUP_TIMEOUT_MS)
        return true
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        // Suspend rather than plain fun: withLock is a suspend function, and a
        // non-suspend caller could not use it. Waiting here also guarantees no
        // write is in flight when the GATT client is closed.
        writeLock.withLock {
            gatt?.let { closeGatt(it) }
            writeChar = null
        }
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Close a GATT client and drop our reference to it.
     *
     * `close()` is what releases the client registration; without it the client
     * leaks and stays in `gatt`. That matters because `connect()` treats a
     * non-null `gatt` as "already connected" and refuses — so a single dropped
     * link left the app unable to reconnect for the life of the process, and
     * the only recovery was force-stopping it. Observed on hardware: the cluster
     * dropped with status 8 after ~2 minutes, and every later Connect was
     * refused with "link already active".
     *
     * Called from the binder callback as well as from [disconnect], so the
     * identity check matters — a stale callback must not clear a newer client.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun closeGatt(g: BluetoothGatt) {
        if (gatt === g) {
            _ready.value = false
            pendingWrite?.takeIf { it.client === g }?.result?.complete(-1)
            writeChar = null
            synchronized(cccdLock) { cccdQueue.clear() }
            mainHandler.removeCallbacksAndMessages(null)
            gatt = null
            discoveryStarted = false
            setupCompleted = false
        }
        runCatching { g.disconnect() }
        runCatching { g.close() }
            .onFailure { Log.w(TAG, "gatt close: ${it.message}") }
    }

    /** Serialise through response completion; ambiguous timeout blocks later writes on this link. */
    @SuppressLint("MissingPermission")
    suspend fun write(bytes: ByteArray): Boolean {
        val seq = writeSequence.incrementAndGet()
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        val id = bytes.getOrNull(1)?.toInt()?.and(0xFF)
        fun trace(message: String) {
            if (frameObserver != null || dev.tvsopen.log.RaiderDiagnostics.enabled) Log.i(TAG,
                "tx t=${android.os.SystemClock.elapsedRealtime()} connection=$connectionGeneration seq=$seq id=$id $message")
        }
        trace("enqueued")
        return writeLock.withLock {
            val g = gatt
            val ch = writeChar
            if (g == null || ch == null || !isReady || _state.value != ConnectionState.CONNECTED) {
                trace("rejected reason=not-ready")
                if (!dropWarned) {
                    dropWarned = true
                    Log.w(TAG, "link not ready, dropping writes")
                }
                return@withLock false
            }
            val type = when (GattWritePolicy.mode(ch.properties)) {
                GattWriteMode.RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                GattWriteMode.NO_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                null -> { trace("rejected reason=unsupported-properties"); return@withLock false }
            }
            val pending = if (type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                PendingWrite(g, ch.uuid, seq, CompletableDeferred()) else null
            pendingWrite = pending
            trace("submit waitMs=${android.os.SystemClock.elapsedRealtime() - queuedAt} type=$type")
            var settled = false
            // Finish an accepted GATT operation even when a nav loop is cancelled;
            // stopping a route must not desynchronise or reset the BLE connection.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    val accepted = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeCharacteristic(ch, bytes, type) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            ch.value = bytes
                            @Suppress("DEPRECATION")
                            ch.writeType = type
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(ch)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "write refused (permission)")
                        false
                    }
                    trace("accepted=$accepted (not render ACK)")
                    if (!accepted) { settled = true; return@withContext false }
                    emit(BleFrame(BleFrame.Direction.OUTBOUND, bytes.copyOf(), System.currentTimeMillis()))
                    val status = if (pending != null) {
                        withTimeoutOrNull(WRITE_TIMEOUT_MS) { pending.result.await() }
                    } else {
                        // Preserve the previous no-response path only where the peripheral advertises it.
                        kotlinx.coroutines.delay(50)
                        BluetoothGatt.GATT_SUCCESS
                    }
                    settled = status != null
                    if (status == null) {
                        _ready.value = false
                        Log.w(TAG, "write timeout t=${android.os.SystemClock.elapsedRealtime()} seq=$seq; " +
                            "readiness lost, ignoring late completion; no automatic GATT restart")
                    }
                    val ok = status == BluetoothGatt.GATT_SUCCESS && g === gatt && isReady
                    trace("complete status=$status success=$ok (not render ACK)")
                    if (ok) dropWarned = false
                    ok
                } finally {
                    // Cancellation after submission is also ambiguous: never let its late
                    // response complete another operation on the same characteristic.
                    if (!settled && pending != null && g === gatt) _ready.value = false
                    if (pendingWrite === pending) pendingWrite = null
                }
            }
        }
    }

    private fun emit(frame: BleFrame) {
        _lastFrameAt.value = frame.atMillis
        frameObserver?.invoke(frame)
        _frames.tryEmit(frame)
        // Bytes go in, hex comes out inside Log.frame, which also skips the
        // formatting cost entirely when capture is disabled.
        Log.frame(
            if (frame.direction == BleFrame.Direction.INBOUND) "IN " else "OUT",
            frame.bytes,
            frame.atMillis,
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            synchronized(this@ScooterBleService) {
                if (g !== gatt) return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "GATT connected connection=$connectionGeneration t=${android.os.SystemClock.elapsedRealtime()} (status=$status)")
                        _state.value = ConnectionState.CONNECTED
                        try {
                            // Discovery must NOT be issued while the MTU exchange is
                            // in flight: the stack answers it with BUSY and
                            // `onServicesDiscovered` is then never called, so the
                            // link sits "connected" with no write characteristic
                            // forever. This was invisible on the Ntorq and showed up
                            // on the first peripheral that took the MTU seriously.
                            if (g.requestMtu(Gatt.REQUESTED_MTU)) {
                                mainHandler.postDelayed(
                                    { startDiscovery(g) },
                                    DISCOVERY_FALLBACK_MS,
                                )
                            } else {
                                startDiscovery(g)
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "post-connect setup refused: ${e.message}")
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "GATT disconnected connection=$connectionGeneration t=${android.os.SystemClock.elapsedRealtime()} (status=$status)")
                        // Must release the client here, not just clear the
                        // characteristic: `connect()` bails out while `gatt` is
                        // non-null, so leaving a dead client in place made every
                        // subsequent reconnect a silent no-op. See [closeGatt].
                        closeGatt(g)
                        _state.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt || status != BluetoothGatt.GATT_SUCCESS) return
            val svc = resolveVerifiedService(g)
            if (svc == null) {
                Log.w(TAG, "no verified SmartXonnect service")
                return
            }
            val ch = svc.getCharacteristic(UUID.fromString(Gatt.WRITE))
            if (ch == null) {
                // Unreachable after resolveVerifiedService, but the compiler
                // needs the check and an explicit refusal beats a crash.
                Log.w(TAG, "write characteristic ${Gatt.WRITE} missing")
                return
            }
            writeChar = ch
            Log.i(
                TAG,
                "service ready (${svc.uuid}), write char props=0x${ch.properties.toString(16)}",
            )
            // Listen before the CCCD write: the cluster can emit the 0x9A/0xF2
            // challenge the instant the descriptor is written, and anything
            // subscribed after that loses it. setCharacteristicNotification
            // only arms the local callback; the descriptor write follows.
            enableNotifications(g, svc)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== gatt) return
            emit(BleFrame(BleFrame.Direction.INBOUND, value, System.currentTimeMillis()))
        }

        @Deprecated("Superseded by the ByteArray overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (g !== gatt) return
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            emit(BleFrame(BleFrame.Direction.INBOUND, v, System.currentTimeMillis()))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            val pending = pendingWrite?.takeIf { it.client === g && it.characteristic == characteristic.uuid }
            if (frameObserver != null || dev.tvsopen.log.RaiderDiagnostics.enabled) Log.i(TAG,
                "tx callback t=${android.os.SystemClock.elapsedRealtime()} connection=$connectionGeneration seq=${pending?.sequence} " +
                    "status=$status (transport completion, not render ACK)")
            pending?.result?.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            Log.i(TAG, "MTU=$mtu status=$status")
            // The MTU exchange is done, so discovery is safe now. Calling this
            // here rather than from onConnectionStateChange is what makes
            // onServicesDiscovered actually fire.
            startDiscovery(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) cccdFailed = true
            Log.i(TAG, "descriptor t=${android.os.SystemClock.elapsedRealtime()} status=$status")
            // One CCCD write at a time: firing the next while one is pending
            // makes the stack refuse it, so the queue advances here.
            writeNextCccd(g)
        }
    }

    /** Pending CCCD writes, drained one per [onDescriptorWrite]. Guarded because
     * the callback arrives on a binder thread, not the caller's. */
    private val cccdQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val cccdLock = Any()

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun writeNextCccd(g: BluetoothGatt) {
        if (g !== gatt) return
        val next = synchronized(cccdLock) {
            if (cccdQueue.isNotEmpty()) cccdQueue.removeFirst() else null
        } ?: run {
            _ready.value = !cccdFailed
            setupCompleted = isReady
            Log.i(TAG, "BLE ready t=${android.os.SystemClock.elapsedRealtime()} ready=$isReady")
            return
        }
        // The two writeDescriptor overloads differ in return type: the API-33 one
        // returns an Int status code, the deprecated one a Boolean. An
        // if/else mixing them infers `Comparable & Serializable`, so
        // normalise both to Boolean here.
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                g.writeDescriptor(next, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        } else {
            @Suppress("DEPRECATION")
            next.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            try {
                @Suppress("DEPRECATION")
                g.writeDescriptor(next)
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        }
        if (!ok) {
            cccdFailed = true
            Log.w(TAG, "CCCD write refused; link remains not ready")
            writeNextCccd(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt): Boolean {
        val ok = try {
            g.discoverServices()
        } catch (e: SecurityException) {
            Log.e(TAG, "discoverServices refused: ${e.message}")
            return false
        }
        if (!ok) Log.w(TAG, "discoverServices refused")
        return ok
    }

    /**
     * Start service discovery exactly once per connection.
     *
     * Two callers race here on purpose: [onMtuChanged] in the normal case, and
     * the fallback timer posted at connect for stacks that never report an MTU.
     * A stale `g` from a previous connection is ignored so a reconnect can
     * never inherit its predecessor's attempt.
     */
    @SuppressLint("MissingPermission")
    private fun startDiscovery(g: BluetoothGatt) {
        if (g !== gatt || discoveryStarted) return
        discoveryStarted = true
        mainHandler.removeCallbacksAndMessages(null)
        if (!discoverServices(g)) discoveryStarted = false
    }

    /**
     * Resolve the service that actually carries this protocol.
     *
     * The Scoot spec calls this the "verified-open gate", and the ordering
     * matters:
     *
     *  1. The exact service UUID with both characteristics is the primary path.
     *  2. If the service UUID is present but a characteristic is missing, that
     *     is a **hard failure** — not a fallback. A cluster advertising the
     *     service without the write characteristic cannot be driven, and
     *     silently picking another service would drive the wrong one.
     *  3. Only when the service UUID is absent entirely do we fall back to any
     *     GATT service exposing both characteristic UUIDs. The characteristics
     *     are the true protocol fingerprint: a variant cluster that moves the
     *     service UUID still resolves.
     *
     * Observed on the owner's Ntorq: the cluster advertises **no service UUID**
     * at all in its advertising payload, which is why scanning must be
     * name-based. Discovery is a different question and this is where it is
     * answered.
     */
    @SuppressLint("MissingPermission")
    private fun resolveVerifiedService(g: BluetoothGatt): android.bluetooth.BluetoothGattService? {
        val serviceUuid = UUID.fromString(Gatt.SERVICE)
        val writeUuid = UUID.fromString(Gatt.WRITE)
        val readUuid = UUID.fromString(Gatt.READ)

        val exact = g.getService(serviceUuid)
        if (exact != null) {
            val hasBoth = exact.getCharacteristic(writeUuid) != null &&
                exact.getCharacteristic(readUuid) != null
            if (hasBoth) return exact
            Log.e(
                TAG,
                "service $serviceUuid present but a characteristic is missing; " +
                    "refusing the link (hard failure, no fallback)",
            )
            return null
        }

        val byCharacteristics = g.services?.firstOrNull {
            it.getCharacteristic(writeUuid) != null && it.getCharacteristic(readUuid) != null
        }
        if (byCharacteristics == null) {
            Log.e(TAG, "no service exposes both $writeUuid and $readUuid")
        } else {
            Log.w(
                TAG,
                "service UUID $serviceUuid absent; resolved by characteristics on ${byCharacteristics.uuid}",
            )
        }
        return byCharacteristics
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, svc: android.bluetooth.BluetoothGattService) {
        cccdFailed = false
        val pending = ArrayList<BluetoothGattDescriptor>()
        for (uuid in listOf(Gatt.READ, Gatt.DEVICE_INFO)) {
            val ch = svc.getCharacteristic(UUID.fromString(uuid)) ?: continue
            if (!g.setCharacteristicNotification(ch, true)) cccdFailed = true
            val cccd = ch.getDescriptor(CCCD)
            // Parentheses are required: `and` is an infix *function*, not a bitwise
            // operator, so `props and FLAG != 0` does not parse as intended.
            val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (cccd != null && canNotify) pending += cccd
            else Log.d(TAG, "no notifiable CCCD for $uuid")
        }
        if (pending.isEmpty()) {
            Log.w(TAG, "no notification descriptors; link remains not ready")
            return
        }
        synchronized(cccdLock) {
            cccdQueue.clear()
            cccdQueue.addAll(pending)
        }
        writeNextCccd(g)
    }

    companion object {
        private const val TAG = "ScooterBle"
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val CONNECT_SETUP_TIMEOUT_MS = 45_000L

        /**
         * How long to wait for `onMtuChanged` before starting discovery anyway.
         *
         * Long enough for a slow peripheral to answer the MTU exchange, short
         * enough that a stack which never reports it does not stall the link.
         */
        private const val DISCOVERY_FALLBACK_MS = 2500L

        /** Max wait for the write characteristic after a GATT connect. */
        private const val WRITE_READY_WAIT_MS = 2_000L

        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Cheap structural check for an inbound frame, model-agnostic.
         *
         * **Deliberately does not verify a checksum.** Decompiling the official
         * builders showed that the outbound mobile-data and navigation frames
         * carry none, and several inbound layouts (`ApacheBasicData`,
         * `NtorqWifiPasswordFrameData`) declare `CHECKSUM` at index 18 or 19
         * while others declare none. A checksum check here would reject valid
         * frames.
         *
         * Model-specific validation belongs in each [dev.tvsopen.protocol.ProtocolDriver],
         * which knows its own layout.
         */
        fun looksValid(bytes: ByteArray): Boolean {
            if (bytes.size < 3) return false
            val start = bytes[0].toInt() and 0xFF
            return start == 0x5A || start == 0x5B || start == 0x9A || start == 0x9B
        }

        /** Wrap a UUID string for scanning filters. */
        fun parcelUuid(uuid: String): ParcelUuid = ParcelUuid(UUID.fromString(uuid))
    }
}
