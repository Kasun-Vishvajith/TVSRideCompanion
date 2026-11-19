package dev.tvsopen.protocol

import org.junit.Assert.*
import org.junit.Test

class RaiderModeEvidenceTest {
    private fun frame(a: Int, b: Int, id: Int = 0x10) = ByteArray(20).apply {
        this[0] = 0x5A; this[1] = id.toByte()
        this[8] = a.toByte(); this[11] = b.toByte()
        this[18] = (255 - ((0 until 18).sumOf { this[it].toInt() and 255 } % 256)).toByte()
        this[19] = 0xFF.toByte()
    }

    @Test fun `variant candidates remain separate`() {
        val value = RaiderModeEvidence.read(frame(0, 1))!!
        assertEquals(RaiderModeEvidence.Candidate.ECO, value.ordinaryCandidate)
        assertEquals(RaiderModeEvidence.Candidate.POWER, value.baseVariantCandidate)
    }

    @Test fun `unexpected values are never invented Eco readings`() {
        for (raw in 2..255) assertEquals(RaiderModeEvidence.Candidate.UNKNOWN, RaiderModeEvidence.classify(raw))
        assertEquals(255, RaiderModeEvidence.read(frame(255, 9))!!.byte8)
        assertEquals(RaiderModeEvidence.Candidate.UNKNOWN, RaiderModeEvidence.read(frame(255, 9))!!.baseVariantCandidate)
    }

    @Test fun `only valid primary telemetry supplies evidence`() {
        assertNull(RaiderModeEvidence.read(frame(0, 1, 0x11)))
        assertNull(RaiderModeEvidence.read(frame(0, 1).apply { this[8] = 2 }))
        assertNull(RaiderModeEvidence.read(ByteArray(0)))
        assertNull(RaiderModeEvidence.read(frame(0, 1).copyOf(19)))
    }
}
