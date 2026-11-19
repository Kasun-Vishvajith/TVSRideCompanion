package dev.tvsopen.protocol

/** Shared vendor receiver candidates; neither offset is verified for this Raider. */
object RaiderModeEvidence {
    enum class Candidate { ECO, POWER, UNKNOWN }
    data class Observation(val byte8: Int, val byte11: Int) {
        val ordinaryCandidate: Candidate get() = classify(byte8)
        val baseVariantCandidate: Candidate get() = classify(byte11)
    }

    // Do not copy the vendor's fallback of treating every other value as Eco.
    fun classify(raw: Int): Candidate = when (raw) {
        0 -> Candidate.ECO
        1 -> Candidate.POWER
        else -> Candidate.UNKNOWN
    }

    fun read(bytes: ByteArray): Observation? {
        if (!RaiderFrames.validChecksum(bytes) || bytes[1] != 0x10.toByte()) return null
        return Observation(bytes[8].toInt() and 0xFF, bytes[11].toInt() and 0xFF)
    }
}
