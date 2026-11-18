package dev.tvsopen.nav

import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationAccuracyTest {
    @Test fun `expanded instruction is read even when ordinary text contains a street`() {
        val p = NavParsing.parse("250 m", "Example Road", "Turn right onto Example Road", "")
        assertEquals(ManeuverKind.RIGHT, p.direction.kind)
        assertEquals(ManeuverSource.BIG_TEXT, p.direction.source)
        assertEquals(3, p.pictogram)
    }

    @Test fun `text lines and public descriptive icon names can supply direction`() {
        val line = NavParsing.parse("250 m", "Example Road", "", "", textLines = listOf("Turn left"))
        assertEquals(ManeuverSource.TEXT_LINE, line.direction.source)
        assertEquals(ManeuverKind.LEFT, line.direction.kind)
        val icon = ManeuverText.fromIconResource("ic_notification_turn_right")
        val p = NavParsing.parse("250 m", "Example Road", "", "", iconManeuver = icon)
        assertEquals(ManeuverKind.RIGHT, p.direction.kind)
        assertEquals(ManeuverSource.ICON_RESOURCE, p.direction.source)
        assertEquals(ManeuverKind.UNKNOWN, ManeuverText.fromIconResource("a12345"))
    }

    @Test fun `street names and distance alone never resolve a maneuver`() {
        for (road in listOf("Example Road", "Leftbank Road", "Left Road", "Right Avenue")) {
            val p = NavParsing.parse("550 m", road, "", "")
            assertEquals(ManeuverKind.UNKNOWN, p.direction.kind)
            assertNull(p.pictogram)
        }
    }

    @Test fun `conflicting text and icon are explicitly unresolved`() {
        val p = NavParsing.parse("Turn left", "", "", "", iconManeuver = ManeuverKind.RIGHT)
        assertEquals(ManeuverSource.CONFLICT, p.direction.source)
        assertNull(p.pictogram)
    }

    @Test fun `explicit maneuver families are resolved without guessing roundabout exit bearing`() {
        val cases = mapOf("In 250 m, turn left" to ManeuverKind.LEFT,
            "Turn right onto Example Road" to ManeuverKind.RIGHT,
            "Make a U-turn in 600 m" to ManeuverKind.U_TURN,
            "Continue straight" to ManeuverKind.STRAIGHT,
            "Turn sharp left" to ManeuverKind.SHARP_LEFT,
            "Bear right" to ManeuverKind.SLIGHT_RIGHT,
            "At the roundabout take the second exit" to ManeuverKind.ROUNDABOUT,
            "You have arrived" to ManeuverKind.DESTINATION)
        cases.forEach { (text, kind) -> assertEquals(text, kind, NavParsing.parse(text, "", "", "").direction.kind) }
        assertNull(NavParsing.parse("At the roundabout", "", "", "").pictogram)
        assertEquals(ManeuverKind.LEFT, ManeuverText.read("Turn left then turn right"))
    }

    @Test fun `next and total distance are parsed independently`() {
        val a = NavParsing.parse("250 m", "Example Road", "", "11 min · 12.4 km · 3:40 PM ETA")
        assertEquals(250, a.distanceMeters)
        assertEquals(12400, a.remainingTripMeters)
        assertEquals(11, a.etaMinutes)
        val b = NavParsing.parse("90 m", "Example Road", "", "9 min • 6,5 km • 3:20 PM ETA")
        assertEquals(90, b.distanceMeters)
        assertEquals(6500, b.remainingTripMeters)
    }

    @Test fun `missing turn and total are never substituted for one another`() {
        val a = NavParsing.parse("250 m", "Example Road", "", "3:40 PM ETA")
        assertNull(a.remainingTripMeters)
        val b = NavParsing.parse("Turn left", "Example Road", "", "11 min · 12 km · ETA")
        assertEquals(-1, b.distanceMeters)
        assertEquals(12000, b.remainingTripMeters)
        val zero = NavParsing.parse("0 m", "", "", "4 min · 800 m · ETA")
        assertEquals(0, zero.distanceMeters)
        assertEquals(800, zero.remainingTripMeters)
    }

    @Test fun `mixed units and numeric separators are defensive`() {
        val p = NavParsing.parse("1000 ft", "", "", "11 min | 1 mi | ETA")
        assertEquals(304, p.distanceMeters)
        assertEquals(1609, p.remainingTripMeters)
        assertEquals(12400, NavParsing.extractDistance("12\u202F400 m"))
        assertEquals(1234, NavParsing.extractDistance("1,234.5 m"))
        assertEquals(1234, NavParsing.extractDistance("1.234,5 m"))
        assertEquals(-1, NavParsing.extractDistance("-250 m"))
    }

    @Test fun `total distance grace is bounded and does not cross instructions or reset`() {
        var clock = 1000L
        val continuity = NavContinuity { clock }
        val step = NavInstruction("Turn left", "Example Road", 250, 11, 0, "test.maps", 12400)
        continuity.accept(step)
        clock += 1000
        val held = continuity.accept(step.copy(remainingTripMeters = null))
        assertEquals(12400, held.remainingTripMeters)
        assertTrue(held.totalHeld)
        clock += 6001
        assertNull(continuity.accept(step.copy(remainingTripMeters = null)).remainingTripMeters)
        continuity.accept(step)
        assertNull(continuity.accept(step.copy(title = "Turn right", remainingTripMeters = null)).remainingTripMeters)
        continuity.reset()
        assertNull(continuity.accept(step.copy(remainingTripMeters = null)).remainingTripMeters)
    }

    @Test fun `bridge retains user chosen fallback and encodes distinct trip distance`() = runTest {
        val frames = mutableListOf<ByteArray>()
        val bridge = NavigationBridge(backgroundScope, NtorqDriver(VehicleModel.RAIDER_125),
            { frames += it; true }, { true }, now = { testScheduler.currentTime }, trace = {})
        bridge.onInstruction(NavInstruction("", "Example Road", 250, 11, null, "test.maps", 12400))
        bridge.startNavigation(); runCurrent()
        val control = frames.first { it[1] == 0x4E.toByte() }
        assertEquals(250, ((control[2].toInt() and 255) shl 8) or (control[3].toInt() and 255))
        assertEquals(12400, ((control[6].toInt() and 255) shl 16) or
            ((control[7].toInt() and 255) shl 8) or (control[8].toInt() and 255))
        assertEquals(7, control[9].toInt())
        assertTrue(bridge.hud.value.lastError!!.contains("placeholder"))
    }
}
