package dev.tvsopen.nav

/**
 * One turn-by-turn instruction, normalised across map providers.
 */
data class NavInstruction(
    /** "Turn left", "Roundabout, exit 2", … — the maneuver. */
    val title: String,
    /** "Onto NH44" — the street. */
    val street: String,
    /** Metres to the maneuver; -1 when unknown. */
    val distanceMeters: Int,
    val etaMinutes: Int = 0,
    /** Resolved cluster pictogram id, or null when unresolved. */
    val pictogram: Int?,
    val provider: String,
    val remainingTripMeters: Int? = null,
    val totalHeld: Boolean = false,
    val direction: ManeuverReading = ManeuverReading(ManeuverKind.UNKNOWN, ManeuverSource.NONE),
) {
    /**
     * Best single line for this cluster's display.
     *
     * The maneuver first, the street second. The provider that is actually in
     * use here (`com.tvsm.connect`) posts the maneuver as the title and trip
     * metrics (`"2.32 km • 3 min • 11:40 AM ETA"`) as the text — preferring the
     * street therefore showed an ETA fragment where the turn instruction
     * belongs, on both lines at once. The Google Maps shape is the mirror:
     * its title is distance-only (`"550 m"`), so [NavParsing] leaves the
     * maneuver empty and this falls through to the street, which is the road.
     *
     * The raw instruction is compressed to short codes ([NavShortCodes]) so a
     * 10/12-character row fits far more, prefixed with an ASCII arrow for
     * glanceability, and rolled ([RollingText]) when it still overflows so the
     * tail scrolls into view instead of being clipped. [tick] advances once
     * per bridge send; pass 0 for a static snapshot.
     *
     * The 17-byte payload is still sent; the bytes beyond the row are
     * zero-padded and the `0xFF` terminator stays at index 19, because that is
     * the frame shape the cluster expects. Only the character count changed.
     */
    fun displayLine(maxChars: Int = RowChars.NTORQ, tick: Int = 0): String {
        val combined = listOf(title, street)
            .firstOrNull { it.isNotBlank() } ?: "Navigating"
        val compressed = NavShortCodes.compress(combined)
        val arrow = NavShortCodes.asciiArrow(pictogram)
        val full = if (arrow.isNotEmpty()) "$arrow $compressed" else compressed
        return RollingText.window(full, maxChars, tick)
    }

    /** Raider secondary text describes the trip, never a duplicate next-turn distance. */
    fun tripLine(maxChars: Int, tick: Int = 0): String {
        val total = remainingTripMeters
        val value = when {
            total == null -> "--"
            total >= 1000 -> String.format(java.util.Locale.US, "%.1fKm", total / 1000.0)
            else -> "${total}m"
        }
        return RollingText.window("TRIP $value", maxChars, tick)
    }

    /**
     * The ETA / distance line, in TVS's own rendering.
     *
     * `10Km 700m` for multi-kilometre distances (as the manual prints it),
     * plain metres below that. Truncated to the row: without the take, a
     * `"ETA 3m 2Km 320m"` line overran the 10-character maneuver row and the
     * cluster clipped the distance off the end. Rolls like [displayLine] when
     * it still overflows.
     */
    fun metricsLine(maxChars: Int = RowChars.NTORQ_LINE_1, tick: Int = 0): String {
        val d = if (distanceMeters < 0) "" else if (distanceMeters >= 1000)
            "${distanceMeters / 1000}Km ${distanceMeters % 1000}m"
        else "${distanceMeters}m"
        val raw = listOf(if (etaMinutes > 0) "ETA ${etaMinutes}m" else "", d).filter { it.isNotEmpty() }.joinToString(" ")
        return RollingText.window(NavShortCodes.compress(raw), maxChars, tick)
    }
}
