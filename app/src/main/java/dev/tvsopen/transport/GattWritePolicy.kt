package dev.tvsopen.transport

enum class GattWriteMode { RESPONSE, NO_RESPONSE }

/** Android property bits. Preserve no-response behavior where advertised; never force it on 0x08. */
object GattWritePolicy {
    fun mode(properties: Int): GattWriteMode? = when {
        (properties and 0x04) != 0 -> GattWriteMode.NO_RESPONSE
        (properties and 0x08) != 0 -> GattWriteMode.RESPONSE
        else -> null
    }
}
