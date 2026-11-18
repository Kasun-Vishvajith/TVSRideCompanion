package dev.tvsopen.nav

import dev.tvsopen.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationBridgeTest {
    private val step = NavInstruction("Turn left onto Example Road", "Example Road", 500, 3, 0, "test.maps")
    private fun ByteArray.id() = this[1].toInt() and 0xFF

    @Test fun `static text and row count can be changed independently`() = runTest {
        val sent = mutableListOf<ByteArray>()
        var primary = false
        var static = false
        val bridge = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.RAIDER_125),
            { sent += it; true }, { true }, { primary }, { testScheduler.currentTime }, {}, { static })
        bridge.onInstruction(step)
        bridge.startNavigation(); runCurrent()
        advanceTimeBy(1500); runCurrent()
        assertEquals(2, sent.filter { it.id() == 0x4F }.map { it.toList() }.distinct().size)
        static = true
        sent.clear()
        repeat(3) { advanceTimeBy(1500); runCurrent() }
        assertTrue(sent.filter { it.id() == 0x4E }.all { it[10] == 2.toByte() })
        assertEquals(1, sent.filter { it.id() == 0x4F }.map { it.toList() }.distinct().size)
        primary = true
        static = false
        sent.clear()
        repeat(3) { advanceTimeBy(1500); runCurrent() }
        assertTrue(sent.filter { it.id() == 0x4E }.all { it[10] == 1.toByte() })
        assertFalse(sent.any { it.id() == 0x50 })
        assertTrue(sent.filter { it.id() == 0x4F }.map { it.toList() }.distinct().size > 1)
    }

    @Test fun `connected link keeps control cadence when switching row mode and receiving duplicates`() = runTest {
        val sent = mutableListOf<Pair<Long, ByteArray>>()
        var primary = false
        val bridge = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.RAIDER_125),
            { sent += testScheduler.currentTime to it; true }, { true }, { primary },
            { testScheduler.currentTime }, {}, stableText = { true })
        bridge.onInstruction(step)
        bridge.startNavigation()
        runCurrent()
        assertEquals(2, sent.first().second[10].toInt())
        primary = true
        repeat(3) {
            bridge.onInstruction(step)
            advanceTimeBy(1500)
            runCurrent()
        }
        val controls = sent.filter { it.second.id() == 0x4E }
        assertEquals(listOf(0L, 1500L, 3000L, 4500L), controls.map { it.first })
        assertTrue(controls.drop(1).all { it.second[10] == 1.toByte() && it.second[11] == 1.toByte() })
        assertFalse(sent.filter { it.first > 0 }.any { it.second.id() == 0x50 })
        val texts = sent.filter { it.first > 0 && it.second.id() == 0x4F }.map { it.second.toList() }
        assertEquals(1, texts.distinct().size)
        primary = false
        advanceTimeBy(1500); runCurrent()
        assertEquals(2, sent.last { it.second.id() == 0x4E }.second[10].toInt())
    }

    @Test fun `not ready never writes and true route stop sends one inactive control`() = runTest {
        val sent = mutableListOf<ByteArray>()
        var ready = false
        val bridge = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.RAIDER_125),
            { sent += it; true }, { ready }, now = { testScheduler.currentTime }, trace = {})
        bridge.onInstruction(step)
        bridge.startNavigation()
        runCurrent()
        advanceTimeBy(3000); runCurrent()
        assertTrue(sent.isEmpty())
        ready = true
        advanceTimeBy(1500); runCurrent()
        assertTrue(sent.any { it.id() == 0x4E })
        bridge.onInstruction(null)
        advanceTimeBy(3000); runCurrent()
        assertEquals(1, sent.count { it.id() == 0x4E && it[11] == 0.toByte() })
        ready = false
        sent.clear()
        bridge.onInstruction(step)
        advanceTimeBy(3000); runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test fun `Raider experiment does not change Ntorq row default and restart prevents stale exit`() = runTest {
        val sent = mutableListOf<ByteArray>()
        val bridge = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.NTORQ_125_RACE),
            { sent += it; true }, { true }, { true }, { testScheduler.currentTime }, {}, stableText = { true })
        bridge.onInstruction(step)
        bridge.startNavigation(); runCurrent()
        assertEquals(2, sent.first()[10].toInt())
        bridge.stopNavigation()
        bridge.onInstruction(step)
        bridge.startNavigation(); runCurrent()
        assertFalse(sent.any { it.id() == 0x4E && it[11] == 0.toByte() })
    }
}
