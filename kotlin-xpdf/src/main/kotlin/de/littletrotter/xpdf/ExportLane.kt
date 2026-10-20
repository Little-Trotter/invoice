package de.littletrotter.xpdf

/**
 * The job lane a worker thread is currently serving. Set by the job consumer around dispatch, read
 * when the job timer starts, so the export pipeline needs no lane parameter of its own.
 */
object ExportLane {
    const val UNKNOWN = "unknown"
    private val current = ThreadLocal<String?>()

    fun <T> within(lane: String, block: () -> T): T {
        val previous = current.get()
        current.set(lane)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    internal fun current(): String = current.get() ?: UNKNOWN
}
