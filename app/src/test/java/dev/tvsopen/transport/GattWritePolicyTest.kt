package dev.tvsopen.transport

import org.junit.Assert.*
import org.junit.Test

class GattWritePolicyTest {
    @Test fun `captured Raider 08 requires write with response`() {
        assertEquals(GattWriteMode.RESPONSE, GattWritePolicy.mode(0x08))
    }
    @Test fun `existing no response mode is preserved where advertised`() {
        assertEquals(GattWriteMode.NO_RESPONSE, GattWritePolicy.mode(0x0C))
        assertEquals(GattWriteMode.NO_RESPONSE, GattWritePolicy.mode(0x04))
    }
    @Test fun `notify and read alone do not authorize writes`() {
        assertNull(GattWritePolicy.mode(0x12))
    }
}
