package de.littletrotter.ui.format

import de.littletrotter.shared.receipt.DocumentSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * IR-3/IR-5 — [DocumentSizeFormatter] is the one place a binary-MiB [DocumentSize] becomes the
 * decimal "MB" text a reader sees; boundary values pin the size-unit convention documented on
 * [DocumentSizeFormatter]'s own KDoc.
 */
class DocumentSizeFormatterTest {

    @Test
    fun `happy path - a mid-range size renders with one decimal and the German comma plus unit`() {
        assertEquals("1,5 MB", DocumentSizeFormatter.format(DocumentSize.ofMebiBytes(1) + DocumentSize.ofKibiBytes(512)))
    }

    @Test
    fun `boundary - zero bytes renders as zero point zero MB`() {
        assertEquals("0,0 MB", DocumentSizeFormatter.format(DocumentSize.ZERO))
    }

    @Test
    fun `boundary - exactly 1024 KiB (one MiB) rounds to a clean one point zero`() {
        assertEquals("1,0 MB", DocumentSizeFormatter.format(DocumentSize.ofKibiBytes(1024)))
    }

    @Test
    fun `boundary - a size over 1 GiB still renders in MB, not switching units`() {
        val overOneGibiByte = DocumentSize.ofMebiBytes(1024) + DocumentSize.ofMebiBytes(1)
        assertEquals("1.025,0 MB", DocumentSizeFormatter.format(overOneGibiByte))
    }

    @Test
    fun `formatMebibytes returns the bare number without the unit suffix`() {
        assertEquals("1,5", DocumentSizeFormatter.formatMebibytes(DocumentSize.ofMebiBytes(1) + DocumentSize.ofKibiBytes(512)))
    }

    @Test
    fun `an explicit English locale uses the decimal point instead of the comma`() {
        assertEquals("1.5 MB", DocumentSizeFormatter.format(DocumentSize.ofMebiBytes(1) + DocumentSize.ofKibiBytes(512), Locale.ENGLISH))
    }

    @Test
    fun `compact - a size below one MiB reads in KB rounded up`() {
        assertEquals("245 KB", DocumentSizeFormatter.formatCompact(DocumentSize(250_000)))
        assertEquals("1 KB", DocumentSizeFormatter.formatCompact(DocumentSize(1)))
    }

    @Test
    fun `compact - zero bytes read as zero KB`() {
        assertEquals("0 KB", DocumentSizeFormatter.formatCompact(DocumentSize.ZERO))
    }

    @Test
    fun `compact - one MiB and more switch to MB`() {
        assertEquals("1,0 MB", DocumentSizeFormatter.formatCompact(DocumentSize.ofKibiBytes(1024)))
        assertEquals("1.023 KB", DocumentSizeFormatter.formatCompact(DocumentSize.ofKibiBytes(1023)))
    }
}
