package dev.raider.companion;

import java.util.Locale;
import java.util.regex.*;

/** Conservative English title fallback. Street names never choose a turn direction. */
final class Navigation {
    private static final Pattern DISTANCE = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(km|m|mi|ft)\\b", Pattern.CASE_INSENSITIVE);
    static byte[] control(String title) {
        byte[] b = Protocol.navigation(true);
        String instruction = title.trim().toLowerCase(Locale.ROOT);
        Matcher distance = DISTANCE.matcher(instruction);
        if (distance.find()) {
            double value = Double.parseDouble(distance.group(1).replace(',', '.'));
            String unit = distance.group(2).toLowerCase(Locale.ROOT);
            double factor = unit.equals("km") ? 1000 : unit.equals("mi") ? 1609.344 : unit.equals("ft") ? .3048 : 1;
            int metres = (int)Math.min(65535, Math.round(value * factor));
            b[2] = (byte)(metres >>> 8); b[3] = (byte)metres;
            instruction = instruction.substring(distance.end()).replaceFirst("^[\\s·:–—-]+", "");
        }
        if (instruction.matches("^turn left\\b.*")) b[9] = 0;
        else if (instruction.matches("^turn right\\b.*")) b[9] = 3;
        else if (instruction.matches("^(make a u.turn|u.turn)\\b.*")) b[9] = 6;
        return b;
    }
    private Navigation() {}
}
