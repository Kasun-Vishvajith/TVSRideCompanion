package dev.tvsopen.nav

import org.junit.Assert.*
import org.junit.Test

class NavContinuityTest {
    private fun step(distance: Int, street: String = "Example Road") =
        NavInstruction("Turn left", street, distance, 3, 0, "test.maps")

    @Test fun `replacement cancels stale clear generation`() {
        val state = NavNotificationLifecycle()
        state.posted("old")
        val old = state.removed("old")!!
        state.posted("new")
        assertFalse(state.expire(old))
        assertNull(state.removed("unrelated"))
        val end = state.removed("new")!!
        assertTrue(state.expire(end))
    }

    @Test fun `removing one active key leaves other guidance alive`() {
        val state = NavNotificationLifecycle()
        state.posted("a")
        state.posted("b")
        assertNull(state.removed("a"))
        assertNotNull(state.removed("b"))
    }

    @Test fun `duplicate updates invalidate old timers and true stop still expires`() {
        val state = NavNotificationLifecycle()
        state.posted("a")
        val stale = state.removed("a")!!
        repeat(10) { state.posted("a") }
        assertFalse(state.expire(stale))
        assertTrue(state.expire(state.removed("a")!!))
    }

    @Test fun `one transient zero is held but repeated zero can reach cluster`() {
        val continuity = NavContinuity()
        continuity.accept(step(11000))
        assertEquals(11000, continuity.accept(step(0)).distanceMeters)
        assertEquals(0, continuity.accept(step(0)).distanceMeters)
    }

    @Test fun `unknown distance preserves same turn but never leaks into new turn`() {
        val continuity = NavContinuity()
        continuity.accept(step(500))
        assertEquals(500, continuity.accept(step(-1)).distanceMeters)
        assertEquals(0, continuity.accept(step(0, "Sample Avenue")).distanceMeters)
        continuity.reset()
        assertEquals(-1, continuity.accept(step(-1)).distanceMeters)
    }
}
