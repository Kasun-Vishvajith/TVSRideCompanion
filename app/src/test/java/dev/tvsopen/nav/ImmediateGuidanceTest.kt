package dev.tvsopen.nav

import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImmediateGuidanceTest {
    private val step = NavInstruction("Turn left", "Example Road", 200, 5, 0, "test.maps", 3000)
    private fun ByteArray.control() = this[1] == 0x4E.toByte()
    private fun ByteArray.distance() = ((this[2].toInt() and 255) shl 8) or (this[3].toInt() and 255)

    @Test fun `distance update sends now and restarts idle heartbeat without a duplicate`() = runTest {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        val b = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.RAIDER_125),
            { frames += testScheduler.currentTime to it; true }, { true },
            now = { testScheduler.currentTime }, trace = {})
        b.onInstruction(step); b.startNavigation(); runCurrent()
        advanceTimeBy(500); runCurrent()
        b.onInstruction(step.copy(distanceMeters = 190)); runCurrent()
        assertEquals(listOf(0L, 500L), frames.filter { it.second.control() }.map { it.first })
        assertEquals(190, frames.last { it.second.control() }.second.distance())
        b.onInstruction(step.copy(distanceMeters = 190,
            direction = ManeuverReading(ManeuverKind.LEFT, ManeuverSource.TEXT_LINE)))
        runCurrent()
        advanceTimeBy(1499); runCurrent()
        assertEquals(2, frames.count { it.second.control() })
        advanceTimeBy(1); runCurrent()
        assertEquals(2000L, frames.last { it.second.control() }.first)
    }

    @Test fun `notification burst keeps latest guidance with bounded writes and no text flooding`() = runTest {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        val b = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.RAIDER_125),
            { frames += testScheduler.currentTime to it; true }, { true },
            now = { testScheduler.currentTime }, trace = {})
        b.onInstruction(step); b.startNavigation(); runCurrent()
        advanceTimeBy(10)
        repeat(100) { b.onInstruction(step.copy(distanceMeters = 199 - it)) }
        runCurrent()
        assertEquals(1, frames.count { it.second.control() })
        advanceTimeBy(140); runCurrent()
        assertEquals(2, frames.count { it.second.control() })
        assertEquals(100, frames.last { it.second.control() }.second.distance())
        assertEquals(150L, frames.last { it.second.control() }.first)
        assertEquals(1, frames.count { it.second[1] == 0x4F.toByte() })
    }

    @Test fun `ETA and pictogram changes send promptly without restarting GATT or NAV mode`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val b = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.RAIDER_125),
            { sent += it; true }, { true }, now = { testScheduler.currentTime }, trace = {})
        b.onInstruction(step); b.startNavigation(); runCurrent()
        advanceTimeBy(500)
        b.onInstruction(step.copy(etaMinutes = 4, pictogram = 3)); runCurrent()
        val controls = sent.filter { it.control() }
        assertEquals(2, controls.size)
        assertEquals(4, controls.last()[5].toInt())
        assertEquals(3, controls.last()[9].toInt())
        assertTrue(controls.all { it[11] == 1.toByte() })
    }
}
