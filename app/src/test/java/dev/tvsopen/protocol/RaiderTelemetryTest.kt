package dev.tvsopen.protocol

import dev.tvsopen.session.Telemetry
import dev.tvsopen.session.Session
import dev.tvsopen.transport.ConnectionState
import org.junit.Assert.*
import org.junit.Test

class RaiderTelemetryTest {
    @Test fun `Raider unverified economy and FF firmware never become confirmed UI values`() {
        Session.reset()
        Session.onConnection(ConnectionState.CONNECTED, VehicleModel.RAIDER_125, null)
        val driver = NtorqDriver(VehicleModel.RAIDER_125)
        fun frame(id: Int) = ByteArray(20).apply {
            this[0] = 0x5A; this[1] = id.toByte(); this[19] = 0xFF.toByte()
        }
        fun checked(raw: ByteArray) = raw.apply {
            this[18] = (255 - ((0 until 18).sumOf { this[it].toInt() and 0xFF } % 256)).toByte()
        }
        Session.onFrame(driver.decode(checked(frame(0x19).apply { this[8] = 38; this[9] = 206.toByte() }))!!)
        Session.onFrame(driver.decode(checked(frame(0x11).apply { this[7] = 0xFF.toByte() }))!!)
        assertNull(Session.telemetry.value.avgEconomyKmpl)
        assertNull(Session.telemetry.value.instantEconomyKmpl)
        assertNull(Session.telemetry.value.softwareVersion)
        Session.reset()
        Session.onConnection(ConnectionState.DISCONNECTED, VehicleModel.UNKNOWN, null)
    }

    @Test fun `live speed cannot freshen old battery or status`() {
        val t = Telemetry(engineRpm = 1708, batteryVoltage = 125, milOn = true,
            updatedAtById = mapOf(0x10 to 20000L, 0x18 to 1000L, 0x11 to 1000L))
        val shown = t.forDisplay(21000)
        assertEquals(1708, shown.engineRpm)
        assertNull(shown.batteryVoltage)
        assertNull(shown.milOn)
        assertEquals("Stale", t.evidence(0x18, false, 21000))
        assertEquals("Candidate / unverified", t.evidence(0x10, false, 21000))
        assertEquals("Unknown", t.evidence(0x19, false, 21000))
    }
}
