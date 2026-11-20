package dev.tvsopen.nav

/**
 * How many characters each cluster text line actually shows.
 *
 * The protocol's text payload is 17 bytes and the Jupiter app fills all 17, which
 * is where the original assumption came from. TVS's Ntorq 125 manual documents
 * what this cluster renders. The rider confirms the display is asymmetric:
 * **10 characters on line 1, 12 on line 2**. The manual's line-2 examples
 * corroborate the longer limit with two visible cuts:
 *
 * - `DISTANCE REMAINING` -> `DISTANCE REM`
 * - `STAY SAME ROAD` -> `STAY SAME RD`
 *
 * Sending 17 characters means the tail is clipped on both rows. Line 1 must be
 * capped at 10; line 2 at 12. See [RowChars].
 */
object RowChars {
    /** Ntorq physical line 1, confirmed by the rider. */
    const val NTORQ_LINE_1 = 10

    /** Ntorq physical line 2, confirmed by the rider and shown in TVS's manual. */
    const val NTORQ_LINE_2 = 12

    /** Longest Ntorq row, retained for the legacy single-row path. */
    const val NTORQ = NTORQ_LINE_2

    /**
     * The Jupiter's row, kept for the Jupiter driver.
     *
     * The 17-byte payload is the frame shape both clusters accept; only the
     * usable characters differ, so this changes how much text is *sent*, never
     * how the frame is laid out.
     */
    const val JUPITER = 17
}
