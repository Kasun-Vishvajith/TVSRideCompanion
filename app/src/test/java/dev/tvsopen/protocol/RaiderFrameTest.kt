package dev.tvsopen.protocol

import org.junit.Assert.*
import org.junit.Test

/** Synthetic non-identifying packets retaining the supplied fixtures' relevant field values. */
class RaiderFrameTest {
    private val driver = NtorqDriver(VehicleModel.RAIDER_125)
    private fun frame(rpm: Int, padding: Int = 0) = ByteArray(20).apply {
        this[0] = 0x5A; this[1] = 0x10
        this[16] = (rpm shr 8).toByte(); this[17] = rpm.toByte()
        this[14] = padding.toByte()
        this[18] = (255 - ((0 until 18).sumOf { this[it].toInt() and 0xFF } % 256)).toByte()
        this[19] = 0xFF.toByte()
    }

    @Test fun `engine off excludes checksum`() {
        assertEquals(0L, driver.decode(frame(0))!!.field(Field.ENGINE_RPM))
    }
    @Test fun `running 06AC is 1708 regardless of checksum`() {
        for (padding in listOf(0, 0x8E, 0xFF)) {
            assertEquals(1708L, driver.decode(frame(0x06AC, padding))!!.field(Field.ENGINE_RPM))
        }
    }
    @Test fun `corrupt standard telemetry checksum is rejected for each observed type`() {
        for (id in RaiderFrames.CHECKSUM_IDS) {
            val raw = frame(0).apply {
                this[1] = id.toByte()
                this[18] = (255 - ((0 until 18).sumOf { this[it].toInt() and 0xFF } % 256)).toByte()
            }
            assertNotNull(driver.decode(raw))
            raw[18] = (raw[18].toInt() xor 1).toByte()
            assertNull(driver.decode(raw))
        }
    }
    @Test fun `special frames are not rejected using standard checksum formula`() {
        assertNotNull(driver.decode(frame(0).apply { this[1] = 0x9D.toByte() }))
    }
    @Test fun `diagnostic fixture fields retain candidate raw values without checksum overlap`() {
        val raw = frame(0).apply {
            this[1] = 0x18; this[8] = 0x4A; this[11] = 0x78
            this[18] = (255 - ((0 until 18).sumOf { this[it].toInt() and 0xFF } % 256)).toByte()
        }
        val decoded = driver.decode(raw)!!
        assertEquals(0x4AL, decoded.field(Field.ENGINE_TEMPRATURE))
        assertEquals(0x78L, decoded.field(Field.BATTERY_VOLTAGE))
        assertEquals(raw[18].toLong() and 0xFF, decoded.field(Field.CHECKSUM))
        assertNull(decoded.field(Field.RESERVED))
    }
    @Test fun `overlapping trip is absent and payload never consumes checksum`() {
        assertNull(driver.decode(frame(1708))!!.field(Field.TRIP_F_METER))
        assertTrue(RaiderFrames.SM1.fields.filter { it.field != Field.END_BYTE }
            .all { it.offset + it.length <= 18 })
    }
    @Test fun `malformed known frame does not fall back to generic decode`() {
        assertNull(driver.decode(frame(0).copyOf(19)))
        assertNull(driver.decode(frame(0).apply { this[19] = 0 }))
    }
    @Test fun `Ntorq layout remains unchanged`() {
        assertEquals(17, NtorqFrames.SM1.fields.single { it.field == Field.ENGINE_RPM }.offset)
        assertEquals(16, RaiderFrames.SM1.fields.single { it.field == Field.ENGINE_RPM }.offset)
    }
}
