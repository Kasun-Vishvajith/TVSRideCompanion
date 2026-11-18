package dev.tvsopen.protocol

import dev.tvsopen.session.Telemetry
import dev.tvsopen.session.Session
import dev.tvsopen.session.TelemetryClock
import dev.tvsopen.transport.ConnectionState
import org.junit.Assert.*
import org.junit.Test

class TelemetryFreshnessRegressionTest {
    @Test fun `diagnostic packet merges without erasing speedometer fields`() {
        Session.reset()
        Session.onConnection(ConnectionState.CONNECTED, VehicleModel.RAIDER_125, null)
        val driver = NtorqDriver(VehicleModel.RAIDER_125)
        fun checked(id: Int, fill: (ByteArray) -> Unit): ByteArray = ByteArray(20).apply {
            this[0] = 0x5A; this[1] = id.toByte(); this[19] = 0xFF.toByte()
            fill(this)
            this[18] = (255 - ((0 until 18).sumOf { this[it].toInt() and 255 } % 256)).toByte()
        }
        Session.onFrame(driver.decode(checked(0x10) { it[9] = 70; it[16] = 6; it[17] = 0xAC.toByte() })!!)
        Session.onFrame(driver.decode(checked(0x18) { it[11] = 120 })!!)
        val shown = Session.telemetry.value.forDisplay(TelemetryClock.nowMillis())
        assertEquals(1708, shown.engineRpm)
        assertEquals(70, shown.topSpeedKmh)
        assertEquals(120, shown.batteryVoltage)
        Session.reset()
        Session.onConnection(ConnectionState.DISCONNECTED, VehicleModel.UNKNOWN, null)
    }
    @Test fun `sample newer than cached UI tick is fresh rather than blank`() {
        val t = Telemetry(engineRpm = 1708, topSpeedKmh = 70, updatedAtById = mapOf(0x10 to 1100L))
        assertEquals(1708, t.forDisplay(1000).engineRpm)
        assertEquals(70, t.forDisplay(1000).topSpeedKmh)
        assertEquals("Candidate / unverified", t.evidence(0x10, false, 1000))
    }
    @Test fun `repeated telemetry between UI ticks never oscillates but true expiry blanks`() {
        repeat(180) { tick ->
            val time = tick * 1000L
            val t = Telemetry(engineRpm = 1708, batteryVoltage = 120,
                updatedAtById = mapOf(0x10 to time + 100, 0x18 to time + 200))
            assertEquals(1708, t.forDisplay(time).engineRpm)
            assertEquals(120, t.forDisplay(time).batteryVoltage)
            assertNull(t.forDisplay(time + 11000).engineRpm)
        }
    }
    @Test fun `raw economy is retained when stale and never promoted to calibrated units`() {
        val t = Telemetry(raiderProfile = true, averageEconomyRaw = 38, instantEconomyRaw = 206,
            updatedAtById = mapOf(0x19 to 1000L))
        assertEquals(38, t.forDisplay(20000).averageEconomyRaw)
        assertEquals(206, t.forDisplay(20000).instantEconomyRaw)
        assertNull(t.forDisplay(20000).avgEconomyKmpl)
        assertEquals("Stale", t.evidence(0x19, false, 20000))
    }
}
