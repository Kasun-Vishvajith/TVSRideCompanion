package dev.tvsopen.nav

/** Semantic instruction, separate from any vehicle's numeric pictogram table. */
enum class ManeuverKind {
    LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    STRAIGHT, U_TURN, ROUNDABOUT, EXIT, DESTINATION, UNKNOWN,
}

enum class ManeuverSource { TITLE, TEXT, BIG_TEXT, TEXT_LINE, ICON_RESOURCE, CONFLICT, NONE }

data class ManeuverReading(val kind: ManeuverKind, val source: ManeuverSource)

object ManeuverText {
    private val rules = listOf(
        "\\b(?:u[- ]?turn|turn around)\\b" to ManeuverKind.U_TURN,
        "\\broundabout\\b" to ManeuverKind.ROUNDABOUT,
        "\\b(?:turn |bear |keep )?(?:sharp|sharply) left\\b" to ManeuverKind.SHARP_LEFT,
        "\\b(?:turn |bear |keep )?(?:sharp|sharply) right\\b" to ManeuverKind.SHARP_RIGHT,
        "\\b(?:turn |bear |keep )?(?:slight|slightly) left\\b|\\b(?:bear|keep) left\\b" to ManeuverKind.SLIGHT_LEFT,
        "\\b(?:turn |bear |keep )?(?:slight|slightly) right\\b|\\b(?:bear|keep) right\\b" to ManeuverKind.SLIGHT_RIGHT,
        "\\b(?:turn|take(?: the)?(?: next)?) left\\b" to ManeuverKind.LEFT,
        "\\b(?:turn|take(?: the)?(?: next)?) right\\b" to ManeuverKind.RIGHT,
        "\\b(?:continue|go|head|keep|proceed)(?: straight| ahead)\\b|^straight(?: ahead)?$" to ManeuverKind.STRAIGHT,
        "\\b(?:take (?:the )?exit|exit (?:the )?(?:highway|motorway))\\b" to ManeuverKind.EXIT,
        "\\b(?:arrive|arrived|destination reached|reach your destination)\\b" to ManeuverKind.DESTINATION,
    ).map { (pattern, kind) -> Regex(pattern, RegexOption.IGNORE_CASE) to kind }

    fun read(text: String): ManeuverKind {
        val current = Regex("\\s+then\\s+", RegexOption.IGNORE_CASE).split(text, limit = 2).first().trim()
        val symbol = when (current.firstOrNull()) {
            '←', '↰' -> ManeuverKind.LEFT
            '→', '↱' -> ManeuverKind.RIGHT
            '↑' -> ManeuverKind.STRAIGHT
            '↩', '↪' -> ManeuverKind.U_TURN
            else -> null
        }
        return symbol ?: rules.firstOrNull { it.first.containsMatchIn(current) }?.second ?: ManeuverKind.UNKNOWN
    }

    /** Only descriptive names of public notification icon resources; never guess bitmap pixels or numeric IDs. */
    fun fromIconResource(name: String): ManeuverKind {
        val words = name.replace('_', ' ').replace('-', ' ')
        val parsed = read(words)
        if (parsed != ManeuverKind.UNKNOWN) return parsed
        return when {
            Regex("(?:^|_)(?:arrow|maneuver)_left(?:_|$)", RegexOption.IGNORE_CASE).containsMatchIn(name) -> ManeuverKind.LEFT
            Regex("(?:^|_)(?:arrow|maneuver)_right(?:_|$)", RegexOption.IGNORE_CASE).containsMatchIn(name) -> ManeuverKind.RIGHT
            Regex("(?:^|_)(?:continue_)?straight(?:_|$)", RegexOption.IGNORE_CASE).containsMatchIn(name) -> ManeuverKind.STRAIGHT
            else -> ManeuverKind.UNKNOWN
        }
    }
}

/** Existing official-table candidates. Raider rendering still requires a parked comparison. */
object RaiderPictograms {
    fun candidate(kind: ManeuverKind): Int? = when (kind) {
        ManeuverKind.LEFT -> 0
        ManeuverKind.SHARP_LEFT -> 1
        ManeuverKind.SLIGHT_LEFT -> 2
        ManeuverKind.RIGHT -> 3
        ManeuverKind.SHARP_RIGHT -> 4
        ManeuverKind.SLIGHT_RIGHT -> 5
        ManeuverKind.U_TURN -> 41
        ManeuverKind.STRAIGHT -> 7
        ManeuverKind.DESTINATION -> 8
        ManeuverKind.EXIT -> 9
        // An unspecified roundabout has no verified exit bearing.
        ManeuverKind.ROUNDABOUT, ManeuverKind.UNKNOWN -> null
    }
}
