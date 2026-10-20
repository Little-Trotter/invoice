package de.littletrotter.xpdf

/**
 * Per-job switches of the export pipeline that only one stage cares about. Set by the dispatcher
 * around the export, read where the stage decides, so the export port's signature stays the one
 * every caller knows. An absent value reads as the default: everything on.
 */
object ExportSwitches {
    private val kositCheck = ThreadLocal<Boolean?>()

    /** Runs [block] with the KoSIT check switched [enabled] or off for the current thread's export. */
    fun <T> withKositCheck(enabled: Boolean, block: () -> T): T {
        val previous = kositCheck.get()
        kositCheck.set(enabled)
        try {
            return block()
        } finally {
            kositCheck.set(previous)
        }
    }

    /** Whether the embedded KoSIT validation may be skipped as "not enabled" is the stage's call
     *  (XRechnung is always validated); this is only the user's setting. */
    internal fun kositCheck(): Boolean = kositCheck.get() ?: true
}
