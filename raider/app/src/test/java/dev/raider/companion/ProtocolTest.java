package dev.raider.companion;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Calendar;

public class ProtocolTest {
    static byte[] frame(int id) {
        byte[] b = new byte[20]; b[0] = 0x5a; b[1] = (byte)id; b[19] = (byte)255; return checked(b);
    }
    static byte[] checked(byte[] b) {
        int sum = 0; for (int i = 0; i < 18; i++) sum += b[i] & 255;
        b[18] = (byte)(255 - (sum & 255)); return b;
    }
    @Test public void knownFramesRequireFullShapeAndChecksum() {
        for (int id : new int[]{0x10, 0x11, 0x18, 0x19}) {
            byte[] b = frame(id); assertTrue(Protocol.telemetry(b));
            b[4]++; assertFalse(Protocol.telemetry(b)); checked(b);
            b[19] = 0; assertFalse(Protocol.telemetry(b));
        }
        assertFalse(Protocol.telemetry(new byte[0]));
        assertFalse(Protocol.telemetry(frame(0x12)));
        assertFalse(Protocol.telemetry(frame(0x42)));
    }
    @Test public void rpmDoesNotConsumeChecksumAndOdometerScalesOnce() {
        byte[] b = frame(0x10); b[16] = 6; b[17] = (byte)172;
        b[3] = 2; b[4] = (byte)136; b[5] = 42; checked(b);
        assertEquals(1708, Protocol.be(b, 16, 2));
        assertEquals(16593.0, Protocol.be(b, 3, 3) / 10.0, .00001);
        assertTrue(Protocol.telemetry(b));
    }
    @Test public void presenceUsesTwelveHourClockAndNoChecksum() {
        Calendar c = Calendar.getInstance(); c.set(2026, Calendar.NOVEMBER, 18, 0, 7, 9);
        byte[] b = Protocol.presence(c, 5);
        assertEquals(12, b[6]); assertEquals(7, b[7]); assertEquals(9, b[8]);
        assertEquals(0, b[9]); assertEquals(18, b[12]); assertEquals(11, b[13]);
        assertEquals(26, b[14]); assertEquals(0, b[18]); assertEquals(5, b[2]);
    }
    @Test public void greetingHasOwnIdAndFixedAsciiPayload() {
        byte[] b = Protocol.text(0x52, "Example Rider Long Name");
        assertEquals(20, b.length); assertEquals(0x5b, b[0]); assertEquals(0x52, b[1]); assertEquals(255, b[19] & 255);
        byte[] shortText = Protocol.text(0x4f, "Test"); assertEquals(0, shortText[6]);
        assertEquals(' ', Protocol.text(0x52, "é")[2]);
    }
    @Test public void navigationStopNeverSetsCalibrationOrInventsLeftArrow() {
        byte[] b = Protocol.navigation(false);
        assertEquals(0x4e, b[1]); assertEquals(0, b[11]); assertEquals(7, b[9]);
        for (int i = 2; i < 9; i++) assertEquals(0, b[i]);
    }
}
