package dev.raider.companion;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;

/** Raider 125 only. Offsets are zero based; decoded units are converted once. */
final class Protocol {
    static int u(byte[] b, int i) { return b[i] & 255; }
    static int be(byte[] b, int i, int n) {
        int value = 0;
        for (int j = 0; j < n; j++) value = (value << 8) | u(b, i + j);
        return value;
    }
    static boolean telemetry(byte[] b) {
        if (b.length != 20 || u(b, 0) != 0x5a || u(b, 19) != 255) return false;
        int id = u(b, 1);
        if (id != 0x10 && id != 0x11 && id != 0x18 && id != 0x19) return false;
        int sum = 0;
        for (int i = 0; i < 19; i++) sum += u(b, i);
        return (sum & 255) == 255;
    }
    // Explicitly omit known measurements/counters from discovery. Candidate fields stay visible.
    static boolean discoveryByte(int id, int offset) {
        if (offset < 2 || offset > 17) return false;
        if (id == 0x10) return !(offset >= 2 && offset <= 7) && offset != 9 && offset != 10 && offset < 16;
        if (id == 0x11) return offset != 5 && offset != 13;
        if (id == 0x18) return offset != 12 && offset != 13;
        if (id == 0x19) return offset != 5 && offset != 6 && offset != 8 && offset != 9;
        return id == 0x12;
    }
    static byte[] text(int id, String value) {
        byte[] b = new byte[20]; b[0] = 0x5b; b[1] = (byte)id; b[19] = (byte)255;
        byte[] text = value.replaceAll("[^\\x20-\\x7E]", " ").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, b, 2, Math.min(17, text.length));
        return b;
    }
    static byte[] presence(Calendar c, int batteryBars) {
        byte[] b = new byte[20]; b[0] = 0x5b; b[1] = 0x4a; b[2] = (byte)Math.max(0, Math.min(5, batteryBars)); b[5] = 2;
        b[6] = (byte)(c.get(Calendar.HOUR) == 0 ? 12 : c.get(Calendar.HOUR));
        b[7] = (byte)c.get(Calendar.MINUTE); b[8] = (byte)c.get(Calendar.SECOND);
        b[9] = (byte)c.get(Calendar.AM_PM); b[12] = (byte)c.get(Calendar.DAY_OF_MONTH);
        b[13] = (byte)(c.get(Calendar.MONTH) + 1); b[14] = (byte)(c.get(Calendar.YEAR) % 100);
        b[19] = (byte)255; return b;
    }
    static byte[] navigation(boolean active) {
        byte[] b = new byte[20]; b[0] = 0x5a; b[1] = 0x4e;
        b[9] = 7; b[10] = 2; b[11] = (byte)(active ? 1 : 0); b[19] = (byte)255; return b;
    }
    private Protocol() {}
}
