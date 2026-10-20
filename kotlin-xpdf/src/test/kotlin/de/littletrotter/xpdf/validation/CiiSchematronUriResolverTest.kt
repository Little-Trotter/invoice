package de.littletrotter.xpdf.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.xml.transform.TransformerException
import javax.xml.transform.stream.StreamSource

/**
 * [BundledCodedbUriResolver] restricts `document()` resolution to exactly the one bundled
 * codedb sibling file per profile; no other filename, no network URI, no filesystem path. Tested
 * directly (not only indirectly via a Schematron transform) so the refusal branch itself is
 * covered without needing to engineer a stylesheet mutation that calls `document()` on a foreign
 * URI.
 */
class CiiSchematronUriResolverTest {

    @Test
    fun `resolves the bundled codedb file for EN16931`() {
        val resolver = BundledCodedbUriResolver(CiiSchematronProfile.EN16931)
        val source = resolver.resolve("FACTUR-X_EN16931_codedb.xml", "irrelevant-base")
        assertEquals(StreamSource::class.java, source::class.java)
        val bytes = (source as StreamSource).inputStream.use { it.readBytes() }
        assertEquals(true, String(bytes, Charsets.UTF_8).contains("<codedb"))
    }

    @Test
    fun `resolves the bundled codedb file for EXTENDED`() {
        val resolver = BundledCodedbUriResolver(CiiSchematronProfile.EXTENDED)
        val source = resolver.resolve("FACTUR-X_EXTENDED_codedb.xml", null)
        val bytes = (source as StreamSource).inputStream.use { it.readBytes() }
        assertEquals(true, String(bytes, Charsets.UTF_8).contains("<codedb"))
    }

    @Test
    fun `refuses a different filename for the same profile`() {
        val resolver = BundledCodedbUriResolver(CiiSchematronProfile.EN16931)
        assertThrows(TransformerException::class.java) {
            resolver.resolve("FACTUR-X_EXTENDED_codedb.xml", null)
        }
    }

    @Test
    fun `refuses an external network URI`() {
        val resolver = BundledCodedbUriResolver(CiiSchematronProfile.EN16931)
        assertThrows(TransformerException::class.java) {
            resolver.resolve("https://example.com/evil_codedb.xml", null)
        }
    }

    @Test
    fun `refuses a filesystem path outside the bundled resource`() {
        val resolver = BundledCodedbUriResolver(CiiSchematronProfile.EN16931)
        assertThrows(TransformerException::class.java) {
            resolver.resolve("/etc/passwd", null)
        }
    }

    @Test
    fun `boundary - a missing bundled codedb or stylesheet is a packaging defect and fails loudly`() {
        org.junit.jupiter.api.assertThrows<javax.xml.transform.TransformerException> {
            CiiSchematronValidator.bundledCodedb({ null }, "facturx/schematron/missing/codedb.xml")
        }
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            CiiSchematronValidator.requireStylesheet({ null }, "facturx/schematron/missing/x.xsl")
        }
        org.junit.jupiter.api.Assertions.assertEquals("a/b", CiiSchematronValidator.bundledCodedb({ "x".byteInputStream() }, "a/b").systemId)
    }
}
