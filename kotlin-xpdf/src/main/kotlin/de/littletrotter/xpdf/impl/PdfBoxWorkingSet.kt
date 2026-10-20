package de.littletrotter.xpdf.impl

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.io.ScratchFile
import org.apache.pdfbox.pdmodel.PDDocument
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.OutputStream

/**
 * Keeps PDFBox's intermediate data off the heap once a document gets large: stream data a document
 * accumulates (new image streams, rewritten content) lives in memory up to [thresholdBytes] per
 * document and in scratch files beyond, and a saved document is collected into one array of its
 * final size instead of a growing buffer plus a copy. Small invoices stay fully in memory, so the
 * common case pays no file I/O.
 */
@ApplicationScoped
class PdfBoxWorkingSet @Inject constructor(
    // `@param:` keeps the config annotation on the constructor parameter only, not on the field.
    @param:ConfigProperty(name = "xpdf.pdfbox.memory-threshold-bytes", defaultValue = "$DEFAULT_THRESHOLD_BYTES")
    private val thresholdBytes: Long,
) {

    constructor() : this(DEFAULT_THRESHOLD_BYTES)

    private val scratchDir by lazy { ScratchDirectories.create("pdfbox-") }

    /** Opens [bytes] with a stream cache that spills into the scratch directory above the threshold. */
    fun load(bytes: ByteArray): PDDocument =
        Loader.loadPDF(bytes, "", null, null) { newCache() }

    /** Runs [write] and returns everything it wrote as one array; large results pass through a scratch file. */
    fun collect(write: (OutputStream) -> Unit): ByteArray =
        SpillingOutputStream(thresholdBytes, scratchDir).use { out ->
            write(out)
            out.toByteArray()
        }

    /** Saves [document] into one array, see [collect]. */
    fun save(document: PDDocument): ByteArray = collect { document.save(it) }

    private fun newCache(): ScratchFile =
        ScratchFile(MemoryUsageSetting.setupMixed(thresholdBytes).setTempDir(scratchDir.toFile()))

    companion object {
        const val DEFAULT_THRESHOLD_BYTES: Long = 8L * 1024 * 1024
    }
}
