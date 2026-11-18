package dev.tvsopen.log

import android.os.SystemClock
import dev.tvsopen.session.Session
import java.io.File

/** Opt-in local recorder. Unknown IDs record shape only; never store credential/text payloads. */
object RaiderDiagnostics {
    @Volatile var enabled = false
    private data class Row(val time: Long, val kind: String, val id: Int, val payload: String,
        val values: String = "", val confidence: String = "candidate")
    private val ring = ArrayDeque<Row>()
    private val capture = ArrayDeque<Row>()
    private val lastById = mutableMapOf<Int, Long>()
    private var until = 0L

    @Synchronized fun frame(bytes: ByteArray) {
        if (!enabled || bytes.size < 2) return
        val now = SystemClock.elapsedRealtime()
        val id = bytes[1].toInt() and 0xFF
        if (now - (lastById[id] ?: -1000L) < 100) return
        lastById[id] = now
        val safe = bytes[0] == 0x5A.toByte() && id in setOf(0x10, 0x11, 0x18, 0x19)
        fun u(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
        fun pair(index: Int): Int? {
            val high = u(index) ?: return null
            val low = u(index + 1) ?: return null
            return (high shl 8) or low
        }
        val valid = safe && dev.tvsopen.protocol.RaiderFrames.validChecksum(bytes)
        val values = if (!valid) "" else when (id) {
            0x10 -> "rpm=${pair(16)};speed_raw=${u(2)};throttle_raw=${u(10)}"
            0x11 -> "service_raw=${u(4)};mil_raw=${u(8)};status_raw=${u(10)}"
            0x18 -> "load_raw=${u(2)};map_raw=${u(6)};temp_raw=${u(8)};" +
                "temp_minus40_candidate=${u(8)?.minus(40)};battery_div10_candidate=${u(11)?.div(10.0)};" +
                "injection_raw=${pair(9)}"
            0x19 -> "economy_raw8=${u(8)};economy_raw9=${u(9)}"
            else -> ""
        }
        val row = Row(now, if (safe && !valid) "invalid_checksum" else "received", id,
            if (safe) Log.hex(bytes) else "size=${bytes.size}", values,
            if (valid) "RAIDER_RX;CANDIDATE" else "UNVERIFIED")
        while (ring.isNotEmpty() && (now - ring.first().time > 10_000 || ring.size >= 2000)) ring.removeFirst()
        ring.addLast(row)
        if (now <= until && capture.size < 6000) capture.addLast(row)
    }

    @Synchronized fun mark(visible: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val event = if (visible) "NAV_VISIBLE" else "NAV_HIDDEN"
        Log.i("RaiderObserver", "t=$now event=$event observer=manual " +
            "ble=${Session.connection.value.state} ready=${Session.connection.value.writeReady} phoneGuidance=${Session.hud.value.active}")
        if (!enabled) return
        if (now > until) {
            capture.clear()
            capture.addAll(ring.filter { now - it.time <= 10_000 })
        }
        if (capture.size < 6000) capture.addLast(Row(now, event, -1, "manual",
            "ble=${Session.connection.value.state};ready=${Session.connection.value.writeReady};phoneGuidance=${Session.hud.value.active}", "DISPLAY_OBSERVED"))
        until = now + 20_000
    }

    @Synchronized fun clear() {
        ring.clear(); capture.clear(); lastById.clear(); until = 0
    }

    @Synchronized fun export(dir: File): File {
        dir.mkdirs()
        return File(dir, "raider-diagnostics-${System.currentTimeMillis()}.csv").also { file ->
            file.bufferedWriter().use { out ->
                out.appendLine("elapsed_ms,event,data_id,raw_candidate_not_calibrated,decoded_candidates,confidence,age_ms")
                capture.forEach { out.appendLine("${it.time},${it.kind},${it.id},${it.payload},${it.values},${it.confidence},0") }
            }
        }
    }
}
