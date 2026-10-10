package de.littletrotter.application.mail.port.inbound

/** Reads the archive hand-over setting kept by the mail component. */
fun interface ArchiveHandoverSettingsQueryPort {

    /** The current setting; the component owns it, so it is read afresh on every call. */
    fun settings(): ArchiveHandoverSettings
}
