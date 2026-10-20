package de.littletrotter.xpdf.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * The embedded Factur-X Schematron resources (`kotlin-xpdf/src/main/resources/schematron/facturx/`) must stay byte-identical to their sources: a ZUGFeRD package update without re-embedding the classpath copy must fail this test, not silently ship stale rules (same discipline as the codedb pinning tests). Release metadata (`1.09.2`, effective `2026-09-01`) is pinned separately so a version bump is a deliberate, visible change here too.
 */
class CiiSchematronResourceDriftTest {

    /** Mirrors [de.littletrotter.xpdf.impl.ComplianceMatrixFixtureTest]'s own repo-root resolution: tests run with `user.dir` at the module root (`kotlin-xpdf`), but the schema files live one level up, at the actual repo root. */
    private val repoRoot: File = File(System.getProperty("user.dir")).let { cwd ->
        if (cwd.name == "kotlin-xpdf") cwd.parentFile else cwd
    }

    private fun docsFile(path: String): File = File(repoRoot, path)

    private fun classpathBytes(resource: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "embedded resource not found on classpath: $resource"
        }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertByteIdentical(docsPath: String, classpathResource: String) {
        val docsBytes = docsFile(docsPath).readBytes()
        val embeddedBytes = classpathBytes(classpathResource)
        assertEquals(
            sha256(docsBytes),
            sha256(embeddedBytes),
            "embedded '$classpathResource' has drifted from its source '$docsPath' — re-embed it",
        )
    }

    @Test
    fun `the embedded EN16931 stylesheet and codedb are byte-identical to their docs source`() {
        assertByteIdentical(
            "docs/ZUGFeRD_2.5.2_EN/Schema/3_Factur-X_1.09.2_EN16931/_XSLT_EN16931/FACTUR-X_EN16931.xslt",
            "schematron/facturx/en16931/FACTUR-X_EN16931.xslt",
        )
        assertByteIdentical(
            "docs/ZUGFeRD_2.5.2_EN/Schema/3_Factur-X_1.09.2_EN16931/_XSLT_EN16931/FACTUR-X_EN16931_codedb.xml",
            "schematron/facturx/en16931/FACTUR-X_EN16931_codedb.xml",
        )
    }

    @Test
    fun `the embedded EXTENDED stylesheet and codedb are byte-identical to their docs source`() {
        assertByteIdentical(
            "docs/ZUGFeRD_2.5.2_EN/Schema/4_Factur-X_1.09.2_EXTENDED/_XSLT_EXTENDED/FACTUR-X_EXTENDED.xslt",
            "schematron/facturx/extended/FACTUR-X_EXTENDED.xslt",
        )
        assertByteIdentical(
            "docs/ZUGFeRD_2.5.2_EN/Schema/4_Factur-X_1.09.2_EXTENDED/_XSLT_EXTENDED/FACTUR-X_EXTENDED_codedb.xml",
            "schematron/facturx/extended/FACTUR-X_EXTENDED_codedb.xml",
        )
    }

    @Test
    fun `release metadata is pinned to 1_09_2, effective 2026-09-01, in both embedded stylesheets`() {
        for (resource in listOf("schematron/facturx/en16931/FACTUR-X_EN16931.xslt", "schematron/facturx/extended/FACTUR-X_EXTENDED.xslt")) {
            val text = String(classpathBytes(resource), Charsets.UTF_8)
            assertTrue(text.contains("Version: 1.09.2"), "$resource must carry the pinned release version, got header: ${text.take(200)}")
            assertTrue(text.contains("Effective date: 2026-09-01"), "$resource must carry the pinned effective date")
        }
    }
}
