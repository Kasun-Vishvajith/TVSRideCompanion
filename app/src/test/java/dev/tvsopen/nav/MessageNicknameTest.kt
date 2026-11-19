package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageNicknameTest {
    @Test fun `matches exact sender and returns chosen nickname`() {
        assertEquals("Pal", messageNickname("Alex Silva = Pal\nSam = S", "alex silva"))
        assertNull(messageNickname("Alex Silva = Pal", "Alex"))
    }

    @Test fun `incomplete entries do not enable forwarding`() {
        assertNull(messageNickname("Alex Silva\nSam = ", "Alex Silva"))
        assertNull(messageNickname("Alex Silva\nSam = ", "Sam"))
    }
}
