package de.littletrotter.application.invoice.port.inbound

/** Input port answering whether this deployment offers multilingual invoice texts. */
interface MultilingualTextsAvailabilityQueryPort {
    /** `true` when the multilingual text fields are offered. */
    val enabled: Boolean

    companion object {
        /** The answer for a deployment without multilingual texts, e.g. when a view is built without a container. */
        val DISABLED: MultilingualTextsAvailabilityQueryPort = object : MultilingualTextsAvailabilityQueryPort {
            override val enabled: Boolean = false
        }
    }
}
