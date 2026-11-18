package dev.tvsopen.nav

/** Tracks only notifications that supplied usable guidance, never a package-wide removal. */
class NavNotificationLifecycle {
    private val keys = mutableSetOf<String>()
    var generation: Long = 0
        private set

    fun posted(key: String) {
        keys += key
        generation++
    }

    fun removed(key: String): Long? {
        if (!keys.remove(key)) return null
        generation++
        return generation.takeIf { keys.isEmpty() }
    }

    fun expire(expected: Long): Boolean = expected == generation && keys.isEmpty()
}

/** Hold one transient unknown/zero distance for the same instruction; a repeated zero is valid. */
class NavContinuity(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var previous: NavInstruction? = null
    private var zeroPending = false
    private var lastTotalAt = Long.MIN_VALUE

    fun accept(step: NavInstruction): NavInstruction {
        val old = previous
        val sameTurn = old != null && old.provider == step.provider && old.title == step.title &&
            old.street == step.street && old.pictogram == step.pictogram
        val result = if (sameTurn && old!!.distanceMeters > 0 &&
            (step.distanceMeters < 0 || (step.distanceMeters == 0 && !zeroPending))) {
            zeroPending = step.distanceMeters == 0
            step.copy(distanceMeters = old.distanceMeters)
        } else {
            zeroPending = false
            step
        }
        val total = step.remainingTripMeters
        val withTotal = if (sameTurn && total == null && old?.remainingTripMeters != null &&
            lastTotalAt != Long.MIN_VALUE && now() - lastTotalAt in 0L..6_000L) {
            result.copy(remainingTripMeters = old.remainingTripMeters, totalHeld = true)
        } else result.copy(totalHeld = false)
        if (total != null) lastTotalAt = now()
        else if (!sameTurn) lastTotalAt = Long.MIN_VALUE
        previous = withTotal
        return withTotal
    }

    fun reset() { previous = null; zeroPending = false; lastTotalAt = Long.MIN_VALUE }
}
