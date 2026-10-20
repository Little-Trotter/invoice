package de.littletrotter.xpdf.impl

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Collects written bytes in memory up to [threshold] and moves them into a file in [dir] beyond
 * that. [toByteArray] then hands out exactly one array of the final size; a plain
 * [ByteArrayOutputStream] would hold its growing buffer and a second full copy at that point.
 * [close] removes the file; the stream must be closed by its creator.
 */
internal class SpillingOutputStream(private val threshold: Long, private val dir: Path) : OutputStream() {
    private var memory: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var file: Path? = null
    private var fileOut: OutputStream? = null

    override fun write(b: Int) {
        target(1).write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        target(len).write(b, off, len)
    }

    private fun target(incoming: Int): OutputStream {
        val buffer = memory ?: return checkNotNull(fileOut) { "stream is closed" }
        if (buffer.size() + incoming.toLong() <= threshold) return buffer
        val spill = Files.createTempFile(dir, "spill-", ".bin")
        val out = Files.newOutputStream(spill).buffered()
        buffer.writeTo(out)
        file = spill
        fileOut = out
        memory = null
        return out
    }

    /** True once the content no longer fits the in-memory part. */
    val spilled: Boolean get() = file != null

    fun toByteArray(): ByteArray {
        memory?.let { return it.toByteArray() }
        val out = checkNotNull(fileOut) { "stream is closed" }
        out.flush()
        return Files.readAllBytes(checkNotNull(file))
    }

    override fun close() {
        fileOut?.close()
        fileOut = null
        file?.let { Files.deleteIfExists(it) }
        file = null
        memory = null
    }
}
