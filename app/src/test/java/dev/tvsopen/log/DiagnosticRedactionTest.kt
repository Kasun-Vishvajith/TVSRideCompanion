package dev.tvsopen.log

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactionTest {
    @Test fun `legacy route and device identity are removed before export`() {
        val route = DiagnosticRedaction.redact("I/MapsNavListener: nav from test.maps: 'Turn left' / 'Example Road' d=500m")
        assertFalse(route.contains("Example Road"))
        assertFalse(route.contains("500"))
        assertFalse(DiagnosticRedaction.redact("connect to 02:00:00:00:00:01").contains("02:00"))
        assertTrue(DiagnosticRedaction.redact("D/BLE: IN  5B 42 01 02 03 FF").endsWith("[private payload redacted]"))
    }
    @Test fun `control bytes are private but structured timing and status survive`() {
        assertFalse(DiagnosticRedaction.redact("D/BLE: OUT 5A 4E 2A F8 00 FF").contains("2A F8"))
        val event = "tx t=1234 connection=2 seq=3 id=78 complete status=0 (not render ACK)"
        assertEquals(event, DiagnosticRedaction.redact(event))
        val row = "OUT 5A 4E [distance redacted] rows=2 active=1"
        assertTrue(DiagnosticRedaction.redact(row).contains("redacted"))
    }
}
