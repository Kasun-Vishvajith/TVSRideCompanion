package dev.tvsopen.protocol

import org.junit.Assert.*
import org.junit.Test

class RaiderDistanceTest {
    private fun frame(next: Int, total: Int) = NtorqDriver(VehicleModel.RAIDER_125)
        .buildNavControl(NavStep(next, 11, total, 0, "Example Road"))!!
    private fun uint(b: ByteArray, offset: Int, length: Int): Int =
        (offset until offset + length).fold(0) { acc, i -> (acc shl 8) or (b[i].toInt() and 255) }

    @Test fun `Raider total zero never copies next turn but legacy model retains its behavior`() {
        assertEquals(0, uint(frame(250, 0), 6, 3))
        assertEquals(0, uint(frame(250, -1), 6, 3))
        val legacy = NtorqDriver(VehicleModel.NTORQ_125_RACE).buildNavControl(NavStep(250, 11, 0, 0, ""))!!
        assertEquals(250, uint(legacy, 6, 3))
    }
    @Test fun `distance fields saturate independently without changing liveness`() {
        val b = frame(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(65535, uint(b, 2, 2))
        assertEquals(16777215, uint(b, 6, 3))
        assertEquals(1, b[11].toInt())
        assertEquals(255, b[19].toInt() and 255)
    }
}
