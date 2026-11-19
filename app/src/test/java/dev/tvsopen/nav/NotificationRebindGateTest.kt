package dev.tvsopen.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRebindGateTest {
    @Test fun `granted access can recover a disconnected reader`() {
        val gate = NotificationRebindGate()
        assertTrue(gate.shouldRequest(accessGranted = true, connected = false, now = 0L))
    }

    @Test fun `connected reader and revoked access never request rebind`() {
        val gate = NotificationRebindGate()
        assertFalse(gate.shouldRequest(accessGranted = false, connected = false, now = 0L))
        assertFalse(gate.shouldRequest(accessGranted = true, connected = true, now = 0L))
        // Skipped calls must not consume the first recovery attempt.
        assertTrue(gate.shouldRequest(accessGranted = true, connected = false, now = 0L))
    }

    @Test fun `repeated resumes are throttled but a later retry is allowed`() {
        val gate = NotificationRebindGate()
        assertTrue(gate.shouldRequest(accessGranted = true, connected = false, now = 100L))
        assertFalse(gate.shouldRequest(accessGranted = true, connected = false, now = 101L))
        assertFalse(gate.shouldRequest(accessGranted = true, connected = false, now = 10_099L))
        assertTrue(gate.shouldRequest(accessGranted = true, connected = false, now = 10_100L))
    }

    @Test fun `a new disconnect after a successful binding recovers immediately`() {
        val gate = NotificationRebindGate()
        assertTrue(gate.shouldRequest(accessGranted = true, connected = false, now = 100L))
        gate.onConnected()
        assertTrue(gate.shouldRequest(accessGranted = true, connected = false, now = 200L))
    }
}
