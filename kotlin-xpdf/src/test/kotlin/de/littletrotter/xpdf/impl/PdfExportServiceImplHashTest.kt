package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `sha256Hex` moved from a private [PdfExportServiceImpl] method to the shared top-level
 * function in `Sha256.kt` (same package), so the pdf check compares the stored object against the
 * identical digest instead of a second implementation. No more reflection needed: the function is
 * `internal`, visible to this test directly.
 */
class PdfExportServiceImplHashTest {

    @Test
    fun `sha256 helper produces deterministic lowercase hex`() {
        val hash = sha256Hex("hello".toByteArray())

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }
}
