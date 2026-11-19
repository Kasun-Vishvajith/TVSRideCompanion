package dev.tvsopen.nav

/** Each line maps an exact notification sender name to a rider-chosen nickname. */
fun messageNickname(entries: String, sender: String): String? = entries.lineSequence()
    .mapNotNull { line ->
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[0].trim()
        val nickname = parts[1].trim()
        if (name.isNotEmpty() && nickname.isNotEmpty() && name.equals(sender.trim(), ignoreCase = true)) nickname else null
    }
    .firstOrNull()
