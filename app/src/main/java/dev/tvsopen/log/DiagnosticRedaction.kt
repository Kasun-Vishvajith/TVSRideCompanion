package dev.tvsopen.log

/** Redact known personal log shapes, including files written by earlier versions. */
object DiagnosticRedaction {
    private val address = Regex("""(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b""")
    private val privateFrame = Regex("""(?i)\b(IN|OUT)\s+5B\s*(42|29|4F|50|4C|63|43|52|53)\b.*""")
    private val control = Regex("""(?i)\bOUT\s+5A\s*4E\b.*""")
    private val personalMessage = Regex(
        """(nav from |call from |message from |cluster message alert: |cluster call alert: |parked spot saved: ).*""",
    )

    fun redact(line: String): String {
        var safe = address.replace(line, "<BLE_ADDRESS_REDACTED>")
        safe = personalMessage.replace(safe) { "${it.groupValues[1]}[content redacted]" }
        safe = privateFrame.replace(safe) {
            "${it.groupValues[1]} 5B ${it.groupValues[2]} [private payload redacted]"
        }
        return control.replace(safe, "OUT 5A 4E [navigation payload redacted]")
    }
}
