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
class NavContinuity {
    private var previous: NavInstruction? = null
    private var zeroPending = false

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
        previous = result
        return result
    }

    fun reset() { previous = null; zeroPending = false }
}
