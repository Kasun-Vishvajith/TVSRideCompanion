package dev.tvsopen.session

/** Same monotonic timebase for receipt and UI freshness; unrelated to wall-clock corrections. */
object TelemetryClock {
    fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}
