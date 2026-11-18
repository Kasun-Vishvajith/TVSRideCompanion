package dev.raider.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoveryTest {
    @Test public void transientButtonPressRemainsVisibleAfterRelease() {
        Discovery d = new Discovery(); byte[] b = ProtocolTest.frame(0x10);
        d.frame(b, 100); assertTrue(d.arm(200));
        b[12] = 4; d.frame(ProtocolTest.checked(b), 300);
        b[12] = 0; d.frame(ProtocolTest.checked(b), 400);
        String report = d.report(16000);
        assertTrue(report.contains("2 unexplained transitions")); assertTrue(report.contains("byte[12]"));
        assertTrue(report.contains("Did you change something?"));
    }
    @Test public void rpmGearAndChecksumDoNotBecomeDiscoveries() {
        Discovery d = new Discovery(); byte[] speed = ProtocolTest.frame(0x10), gear = ProtocolTest.frame(0x11);
        d.frame(speed, 100); d.frame(gear, 100); d.arm(200);
        speed[16] = 6; speed[17] = (byte)172; gear[5] = 3; gear[13] = 0;
        d.frame(ProtocolTest.checked(speed), 300); d.frame(ProtocolTest.checked(gear), 300);
        assertTrue(d.report(16000).contains("No unexplained byte changes"));
    }
    @Test public void noTrafficIsNotReportedAsNoChanges() {
        Discovery d = new Discovery(); d.frame(ProtocolTest.frame(0x11), 100); d.arm(200);
        assertTrue(d.report(16000).contains("No frames received"));
    }
    @Test public void staleBaselineCannotArmAndDisconnectedDataIsCleared() {
        Discovery d = new Discovery(); assertFalse(d.arm(100));
        d.frame(ProtocolTest.frame(0x11), 100); assertFalse(d.arm(20000));
        d.frame(ProtocolTest.frame(0x11), 21000); assertTrue(d.arm(21100));
        d.reset(); assertFalse(d.arm(22000)); assertFalse(d.started());
    }
    @Test public void lateInvalidAndIdentityFramesAreExcluded() {
        Discovery d = new Discovery(); d.frame(ProtocolTest.frame(0x11), 100); d.arm(200);
        byte[] b = ProtocolTest.frame(0x11); b[8] = 1;
        d.frame(b, 300); // invalid checksum
        d.frame(ProtocolTest.frame(0x42), 300);
        assertTrue(d.report(400).contains("No frames received"));
        d.frame(ProtocolTest.checked(b), 16000);
        assertFalse(d.report(16001).contains("byte[8]"));
    }
    @Test public void candidateOffsetsStayVisibleWithoutAssigningMeaning() {
        Discovery d = new Discovery(); byte[] b = ProtocolTest.frame(0x18);
        d.frame(b, 100); d.arm(200); b[8] = 80; d.frame(ProtocolTest.checked(b), 300);
        assertTrue(d.report(400).contains("5A18 byte[8]"));
        assertFalse(d.report(400).contains("temperature"));
    }
}
