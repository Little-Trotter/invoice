package de.littletrotter.shared.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The attachment handover. Size and checksum are required on purpose: the component
 * verifies the fetched bytes against both, and a missing checksum must fail rather than silently
 * disable that verification.
 */
class MailAttachmentHandoverTest {

    private val handover = MailAttachmentHandover(
        filename = "Rechnung-2026-0001.pdf",
        contentType = "application/pdf",
        sizeBytes = 18_342L,
        sha256 = "a".repeat(64),
        downloadUrl = "https://rustfs.example/invoice-documents/2026/RE-2026-0001.pdf?X-Amz-Signature=abc",
    )

    @Test
    fun `encode-decode round-trips every field`() {
        assertEquals(handover, MailAttachmentHandover.decode(MailAttachmentHandover.encode(handover)))
    }

    @Test
    fun `the wire shape is pinned field by field`() {
        assertEquals(
            """{"filename":"Rechnung-2026-0001.pdf","contentType":"application/pdf","sizeBytes":18342,""" +
                """"sha256":"${"a".repeat(64)}",""" +
                """"downloadUrl":"https://rustfs.example/invoice-documents/2026/RE-2026-0001.pdf?X-Amz-Signature=abc"}""",
            MailAttachmentHandover.encode(handover),
        )
    }

    @Test
    fun `unknown fields are ignored on read`() {
        val decoded = MailAttachmentHandover.decode(
            """{"filename":"a.pdf","contentType":"application/pdf","sizeBytes":1,"sha256":"ab",""" +
                """"downloadUrl":"https://x/y","bucket":"invoice-documents","expiresAt":"2026-09-07T11:00:00Z"}"""
        )
        assertEquals("a.pdf", decoded.filename)
        assertEquals(1L, decoded.sizeBytes)
    }

    @Test
    fun `a missing required field fails loudly instead of defaulting to an empty string`() {
        val withoutSha = """{"filename":"a.pdf","contentType":"application/pdf","sizeBytes":1,"downloadUrl":"https://x/y"}"""
        val thrown = assertThrows<MailContractViolationException> { MailAttachmentHandover.decode(withoutSha) }
        assertEquals("MailAttachmentHandover: required field 'sha256' is missing or blank", thrown.message)
    }

    @Test
    fun `a blank required field fails loudly`() {
        val blankFilename =
            """{"filename":"  ","contentType":"application/pdf","sizeBytes":1,"sha256":"ab","downloadUrl":"https://x/y"}"""
        assertThrows<MailContractViolationException> { MailAttachmentHandover.decode(blankFilename) }
    }

    @Test
    fun `a missing size fails loudly instead of defaulting to zero`() {
        val withoutSize = """{"filename":"a.pdf","contentType":"application/pdf","sha256":"ab","downloadUrl":"https://x/y"}"""
        val thrown = assertThrows<MailContractViolationException> { MailAttachmentHandover.decode(withoutSize) }
        assertEquals(
            "MailAttachmentHandover: required field 'sizeBytes' is missing or not an integer",
            thrown.message,
        )
    }

    @Test
    fun `a size that is not an integer fails loudly`() {
        val textualSize =
            """{"filename":"a.pdf","contentType":"application/pdf","sizeBytes":"1","sha256":"ab","downloadUrl":"https://x/y"}"""
        assertThrows<MailContractViolationException> { MailAttachmentHandover.decode(textualSize) }
    }

    @Test
    fun `a required field of the wrong JSON type fails loudly`() {
        val numericFilename =
            """{"filename":42,"contentType":"application/pdf","sizeBytes":1,"sha256":"ab","downloadUrl":"https://x/y"}"""
        assertThrows<MailContractViolationException> { MailAttachmentHandover.decode(numericFilename) }
    }

    @Test
    fun `a JSON null size fails loudly, exactly like an absent one`() {
        val nullSize =
            """{"filename":"a.pdf","contentType":"application/pdf","sizeBytes":null,"sha256":"ab","downloadUrl":"https://x/y"}"""
        assertThrows<MailContractViolationException> { MailAttachmentHandover.decode(nullSize) }
    }

    @Test
    fun `a JSON null in a required field fails loudly`() {
        val nullSha =
            """{"filename":"a.pdf","contentType":"application/pdf","sizeBytes":1,"sha256":null,"downloadUrl":"https://x/y"}"""
        assertThrows<MailContractViolationException> { MailAttachmentHandover.decode(nullSha) }
    }
}
