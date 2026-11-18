package dev.tvsopen.protocol

import dev.tvsopen.session.Session
import dev.tvsopen.session.Telemetry
import dev.tvsopen.transport.ConnectionState
import org.junit.Assert.*
import org.junit.Test

/** Invented payloads: no owner's counters, routes or device identifiers. */
class RaiderDashboardFieldsTest {
    private val raider = NtorqDriver(VehicleModel.RAIDER_125)
    private fun packet(id: Int, fill: (ByteArray) -> Unit) = ByteArray(20).apply {
        this[0] = 0x5A; this[1] = id.toByte(); this[19] = 0xFF.toByte()
        fill(this)
        this[18] = (255 - ((0 until 18).sumOf { this[it].toInt() and 255 } % 256)).toByte()
    }
    private fun connect() {
        Session.onConnection(ConnectionState.DISCONNECTED, VehicleModel.UNKNOWN, null)
        Session.reset()
        Session.onConnection(ConnectionState.CONNECTED, VehicleModel.RAIDER_125, null)
    }
    private fun finish() {
        Session.onConnection(ConnectionState.DISCONNECTED, VehicleModel.UNKNOWN, null)
        Session.reset()
    }

    @Test fun `trip counters decode once in km and never populate economy`() {
        connect()
        val raw = packet(0x19) { it[5] = 0x04; it[6] = 0xD2.toByte(); it[8] = 0x16; it[9] = 0x2E }
        val decoded = raider.decode(raw)!!
        assertNull(decoded.field(Field.AVERAGE_FUEL_ECONOMY))
        assertNull(decoded.field(Field.INSTANTANEOUS_FUEL_ECONOMY))
        Session.onFrame(decoded)
        assertEquals(123.4, Session.telemetry.value.tripAKm!!, 0.00001)
        assertEquals(567.8, Session.telemetry.value.tripBKm!!, 0.00001)
        assertNull(Session.telemetry.value.averageEconomyRaw)
        assertNull(Session.telemetry.value.instantEconomyRaw)
        assertNull(Session.telemetry.value.distanceToEmptyKm)
        finish()
    }

    @Test fun `gear sequence requires valid neutral agreement and bounds`() {
        connect()
        for (gear in listOf(0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0)) {
            Session.onFrame(raider.decode(packet(0x11) {
                it[5] = gear.toByte(); it[13] = if (gear == 0) 9 else 8
            })!!)
            assertEquals(gear, Session.telemetry.value.gear)
        }
        Session.onFrame(raider.decode(packet(0x11) { it[5] = 1; it[13] = 9 })!!)
        assertNull(Session.telemetry.value.gear)
        Session.onFrame(raider.decode(packet(0x11) { it[5] = 6; it[13] = 8 })!!)
        assertNull(Session.telemetry.value.gear)
        finish()
    }

    @Test fun `Raider has five fuel segments and rejects a sixth while Ntorq retains six`() {
        assertEquals(5, Telemetry(raiderProfile = true).fuelBarMax)
        assertEquals(6, Telemetry().fuelBarMax)
        connect()
        Session.onFrame(raider.decode(packet(0x10) { it[6] = 2 })!!)
        assertEquals(2, Session.telemetry.value.fuelBars)
        Session.onFrame(raider.decode(packet(0x10) { it[6] = 6 })!!)
        assertNull(Session.telemetry.value.fuelBars)
        finish()
    }

    @Test fun `candidate average field is not a confirmed economy or acceleration time`() {
        connect()
        val decoded = raider.decode(packet(0x10) { it[13] = 52 })!!
        assertNull(decoded.field(Field.TIME_60KMPH))
        Session.onFrame(decoded)
        assertEquals(52, Session.telemetry.value.averageEconomyCandidate)
        assertNull(Session.telemetry.value.avgEconomyKmpl)
        assertNull(Session.telemetry.value.timeTo60Sec)
        finish()
    }

    @Test fun `unverified Raider find action is disabled at the actual packet boundary`() {
        assertFalse(raider.capabilities.findMyScooter)
        assertEquals(0, raider.buildPing(DashState(0, findMyScooter = true))!![17].toInt())
        val ntorq = NtorqDriver(VehicleModel.NTORQ_125_RACE)
        assertEquals(1, ntorq.buildPing(DashState(0, findMyScooter = true))!![17].toInt())
        assertFalse(ntorq.confidence().keys.any { it.contains("Raider") })
    }
}
