package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationJobResultRefsTest {

    @Test
    fun `encode-decode round-trips every field`() {
        val refs = GenerationJobResultRefs(
            objectKey = "invoices/1.pdf",
            downloadUrl = "https://example/invoices/1.pdf",
            zugferdObjectKey = "invoices/1.xml",
            formatGuideline = "urn:factur-x.eu:1p0:extended",
            llmStatus = "COMPLETED",
            llmModel = "qwen",
            llmReportKey = "invoices/1-llm-report.md",
            llmError = null,
            llmDurationMs = "4200",
            error = null,
            sha256 = "a".repeat(64),
            zugferdXmlSha256 = "b".repeat(64),
        )

        val decoded = GenerationJobResultRefs.decode(GenerationJobResultRefs.encode(refs))

        assertEquals(refs, decoded)
    }

    // ── sha256/zugferdXmlSha256 ─────────────────────────────────────────

    @Test
    fun `RS-10 - toStringMap includes sha256 and zugferdXmlSha256 only when present`() {
        val withChecksums = GenerationJobResultRefs(objectKey = "invoices/1.pdf", sha256 = "abc", zugferdXmlSha256 = "def")
        assertEquals(mapOf("objectKey" to "invoices/1.pdf", "sha256" to "abc", "zugferdXmlSha256" to "def"), withChecksums.toStringMap())

        val withoutChecksums = GenerationJobResultRefs(objectKey = "invoices/1.pdf")
        assertEquals(mapOf("objectKey" to "invoices/1.pdf"), withoutChecksums.toStringMap())
    }

    @Test
    fun `RS-10 - fromStringMap reads sha256 and zugferdXmlSha256 back`() {
        val map = mapOf("objectKey" to "invoices/1.pdf", "sha256" to "abc", "zugferdXmlSha256" to "def")

        val refs = GenerationJobResultRefs.fromStringMap(map)

        assertEquals("abc", refs.sha256)
        assertEquals("def", refs.zugferdXmlSha256)
    }

    @Test
    fun `RS-10 - a legacy result carrying neither checksum decodes with both null`() {
        val legacyJson = """{"objectKey":"invoices/4.pdf","zugferdObjectKey":"invoices/4.xml"}"""

        val decoded = GenerationJobResultRefs.decode(legacyJson)!!

        assertNull(decoded.sha256)
        assertNull(decoded.zugferdXmlSha256)
    }

    @Test
    fun `toStringMap only includes present fields, never a literal null`() {
        val refs = GenerationJobResultRefs(objectKey = "invoices/1.pdf", llmStatus = "PENDING")

        val map = refs.toStringMap()

        assertEquals(mapOf("objectKey" to "invoices/1.pdf", "llmStatus" to "PENDING"), map)
    }

    @Test
    fun `fromStringMap only reads known keys, ignoring the rest`() {
        val map = mapOf("objectKey" to "invoices/2.pdf", "pdf" to "legacy-key-should-be-ignored")

        val refs = GenerationJobResultRefs.fromStringMap(map)

        assertEquals("invoices/2.pdf", refs.objectKey)
        assertNull(refs.downloadUrl)
    }

    @Test
    fun `fromStringMap of null or empty yields an all-null instance, never null itself`() {
        assertEquals(GenerationJobResultRefs(), GenerationJobResultRefs.fromStringMap(null))
        assertEquals(GenerationJobResultRefs(), GenerationJobResultRefs.fromStringMap(emptyMap()))
    }

    @Test
    fun `decode returns null for blank or non-JSON input`() {
        assertNull(GenerationJobResultRefs.decode(null))
        assertNull(GenerationJobResultRefs.decode(""))
        assertNull(GenerationJobResultRefs.decode("not json"))
    }

    @Test
    fun `boundary - decode returns null for input that starts with a brace but is not valid JSON`() {
        assertNull(GenerationJobResultRefs.decode("{not valid json"))
    }

    @Test
    fun `decode ignores an unknown field instead of failing (tolerant reader)`() {
        val futureJson = """{"objectKey":"invoices/3.pdf","somethingFromALaterTask":"whatever"}"""

        val decoded = GenerationJobResultRefs.decode(futureJson)

        assertNotNull(decoded)
        assertEquals("invoices/3.pdf", decoded!!.objectKey)
    }

    @Test
    fun `decode of a legacy pre-H27-10 map-shaped JSON still reads the known keys`() {
        val legacyJson = """{"objectKey":"invoices/4.pdf","llmStatus":"PENDING"}"""

        val decoded = GenerationJobResultRefs.decode(legacyJson)

        assertNotNull(decoded)
        assertEquals("invoices/4.pdf", decoded!!.objectKey)
        assertEquals("PENDING", decoded.llmStatus)
        assertNull(decoded.error)
    }

    @Test
    fun `onlyLlmStatus narrows to just that field, or null when absent`() {
        val withStatus = GenerationJobResultRefs(llmStatus = "FAILED", error = "should not leak")
        assertEquals(GenerationJobResultRefs(llmStatus = "FAILED"), withStatus.onlyLlmStatus())

        val withoutStatus = GenerationJobResultRefs(error = "boom")
        assertNull(withoutStatus.onlyLlmStatus())
    }

    @Test
    fun `isEmpty is true only when every field is absent`() {
        assertTrue(GenerationJobResultRefs().isEmpty())
        assertTrue(!GenerationJobResultRefs(error = "boom").isEmpty())
    }

    // ── archiveObjectKey/archiveSha256 ───────────────────────────────────

    @Test
    fun `RS-9 - toStringMap includes archiveObjectKey and archiveSha256 only when present`() {
        val withArchive = GenerationJobResultRefs(objectKey = "invoices/1.pdf", archiveObjectKey = "invoices/1.tar.gz", archiveSha256 = "abc")
        assertEquals(
            mapOf("objectKey" to "invoices/1.pdf", "archiveObjectKey" to "invoices/1.tar.gz", "archiveSha256" to "abc"),
            withArchive.toStringMap(),
        )

        val withoutArchive = GenerationJobResultRefs(objectKey = "invoices/1.pdf")
        assertEquals(mapOf("objectKey" to "invoices/1.pdf"), withoutArchive.toStringMap())
    }

    @Test
    fun `RS-9 - fromStringMap reads archiveObjectKey and archiveSha256 back`() {
        val map = mapOf("objectKey" to "invoices/1.pdf", "archiveObjectKey" to "invoices/1.tar.gz", "archiveSha256" to "def")

        val refs = GenerationJobResultRefs.fromStringMap(map)

        assertEquals("invoices/1.tar.gz", refs.archiveObjectKey)
        assertEquals("def", refs.archiveSha256)
    }

    @Test
    fun `RS-9 - a draft-export or legacy result carrying no archive fields decodes with both null`() {
        val legacyJson = """{"objectKey":"invoices/4.pdf","zugferdObjectKey":"invoices/4.xml"}"""

        val decoded = GenerationJobResultRefs.decode(legacyJson)!!

        assertNull(decoded.archiveObjectKey)
        assertNull(decoded.archiveSha256)
    }

    @Test
    fun `RS-9 - encode-decode round-trips archiveObjectKey and archiveSha256`() {
        val refs = GenerationJobResultRefs(
            objectKey = "invoices/1.pdf",
            archiveObjectKey = "invoices/1.tar.gz",
            archiveSha256 = "c".repeat(64),
        )

        val decoded = GenerationJobResultRefs.decode(GenerationJobResultRefs.encode(refs))

        assertEquals(refs, decoded)
    }
}
