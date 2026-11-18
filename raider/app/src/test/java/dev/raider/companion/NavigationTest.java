package dev.raider.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationTest {
    @Test public void basicTurnDistanceIsBigEndianAndSaturates() {
        byte[] b = Navigation.control("1.2 km · Turn right onto Example Road");
        assertEquals(1200, Protocol.be(b, 2, 2)); assertEquals(3, b[9]);
        assertEquals(65535, Protocol.be(Navigation.control("999 km - Turn left"), 2, 2));
        assertEquals(0, Navigation.control("200 m — Turn left")[9]);
    }
    @Test public void streetNamesAndUnrecognizedLanguageNeverInventLeftOrRight() {
        assertEquals(7, Navigation.control("Left Bank Road")[9]);
        assertEquals(7, Navigation.control("200 m - Right Avenue")[9]);
        assertEquals(7, Navigation.control("Turn leftish")[9]);
        assertEquals(0, Protocol.be(Navigation.control("Turn right onto Example Road"), 2, 2));
    }
}
