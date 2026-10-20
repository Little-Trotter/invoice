package de.littletrotter.shared.mail

import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

/**
 * `POST /mail/api/compose`'s body, host → component.
 *
 * The golden-JSON test below is the point of this class: the wire keys are written as literals in
 * [MailComposeRequest]'s companion precisely so a Kotlin property rename cannot silently rename a
 * field the other repository parses. Pinning the exact string here is what makes such a rename fail
 * loudly instead of at runtime, in someone's mail.
 */
class MailComposeRequestTest {

    private val request = MailComposeRequest(
        referenceKey = "RE-2026-0001",
        documentFamily = MailDocumentFamily.GERMAN,
        recipientAddress = "kunde@example.de",
        recipientName = "Beispiel GmbH",
        placeholderValues = linkedMapOf(
            "invoiceNumber" to MailPlaceholderValue.Text("RE-2026-0001"),
            "invoiceDate" to MailPlaceholderValue.Date(LocalDate.of(2026, 9, 7)),
            "grossTotal" to MailPlaceholderValue.Amount(Money.of(BigDecimal("1234.56"))),
        ),
        attachments = listOf(
            MailAttachmentHandover(
                filename = "Rechnung-2026-0001.pdf",
                contentType = "application/pdf",
                sizeBytes = 18_342L,
                sha256 = "a".repeat(64),
                downloadUrl = "https://rustfs.example/invoice-documents/RE-2026-0001.pdf?sig=abc",
            ),
        ),
    )

    private val goldenJson =
        """{"schemaVersion":5,"referenceKey":"RE-2026-0001","documentFamily":"GERMAN",""" +
            """"documentKind":"INVOICE_DISPATCH",""" +
            """"recipientAddress":"kunde@example.de","recipientName":"Beispiel GmbH",""" +
            """"placeholderValues":{"invoiceNumber":{"type":"STRING","value":"RE-2026-0001"},""" +
            """"invoiceDate":{"type":"DATE","value":"2026-09-07"},""" +
            """"grossTotal":{"type":"MONEY","value":"1234.56"}},""" +
            """"attachments":[{"filename":"Rechnung-2026-0001.pdf","contentType":"application/pdf",""" +
            """"sizeBytes":18342,"sha256":"${"a".repeat(64)}",""" +
            """"downloadUrl":"https://rustfs.example/invoice-documents/RE-2026-0001.pdf?sig=abc"}]}"""

    /** A schema-version-1 payload: no [de.littletrotter.shared.mail.MailComposeRequest.documentKind] field at all. */
    private val schemaVersion1Json =
        """{"schemaVersion":1,"referenceKey":"RE-2026-0001","documentFamily":"GERMAN",""" +
            """"recipientAddress":"kunde@example.de","recipientName":"Beispiel GmbH",""" +
            """"placeholderValues":{"invoiceNumber":{"type":"STRING","value":"RE-2026-0001"},""" +
            """"invoiceDate":{"type":"DATE","value":"2026-09-07"},""" +
            """"grossTotal":{"type":"MONEY","value":"1234.56"}},""" +
            """"attachments":[{"filename":"Rechnung-2026-0001.pdf","contentType":"application/pdf",""" +
            """"sizeBytes":18342,"sha256":"${"a".repeat(64)}",""" +
            """"downloadUrl":"https://rustfs.example/invoice-documents/RE-2026-0001.pdf?sig=abc"}]}"""

    // ── Round trip and golden shape ────────────────────────────────────────────

    @Test
    fun `encode-decode round-trips every field`() {
        assertEquals(request, MailComposeRequest.decode(MailComposeRequest.encode(request)))
    }

    @Test
    fun `the wire shape is pinned - an accidental field rename fails here`() {
        assertEquals(goldenJson, MailComposeRequest.encode(request))
    }

    @Test
    fun `the golden payload decodes back into the same request`() {
        assertEquals(request, MailComposeRequest.decode(goldenJson))
    }

    @Test
    fun `a request without placeholders or attachments round-trips with explicit empty containers`() {
        val bare = MailComposeRequest(
            referenceKey = "MA-2026-0007",
            documentFamily = MailDocumentFamily.INTERNATIONAL,
            recipientAddress = "customer@example.com",
            recipientName = "Example Ltd",
        )
        val json = MailComposeRequest.encode(bare)
        assertTrue(json.contains(""""placeholderValues":{}"""))
        assertTrue(json.contains(""""attachments":[]"""))
        assertEquals(bare, MailComposeRequest.decode(json))
    }

    // ── senderBlock — additive optional object ─────────────────────────────────

    private val block = MailSenderBlock(
        name = "Little Trotter GmbH",
        phone = "+49 30 123456",
        email = "info@example.de",
        website = "https://example.de",
        legalLines = listOf("Amtsgericht Berlin HRB 1", "USt-IdNr. DE123456789"),
    )

    @Test
    fun `a request with a sender block round-trips`() {
        val withBlock = request.copy(senderBlock = block)
        val json = MailComposeRequest.encode(withBlock)
        assertTrue(json.contains(""""senderBlock":{"name":"Little Trotter GmbH""""))
        assertEquals(withBlock, MailComposeRequest.decode(json))
    }

    @Test
    fun `an absent sender block is omitted on write and decodes to null`() {
        assertTrue(!MailComposeRequest.encode(request).contains("senderBlock"))
        assertNull(MailComposeRequest.decode(goldenJson).senderBlock)
    }

    @Test
    fun `a sender block with only a name decodes with empty optional parts`() {
        val json = goldenJson.dropLast(1) + ""","senderBlock":{"name":"Solo"}}"""
        assertEquals(MailSenderBlock(name = "Solo"), MailComposeRequest.decode(json).senderBlock)
    }

    @Test
    fun `unknown fields inside the sender block are ignored`() {
        val json = goldenJson.dropLast(1) + ""","senderBlock":{"name":"Solo","fax":"123","legalLines":["a"]}}"""
        assertEquals(MailSenderBlock(name = "Solo", legalLines = listOf("a")), MailComposeRequest.decode(json).senderBlock)
    }

    @Test
    fun `a sender block without a name fails loudly`() {
        val json = goldenJson.dropLast(1) + ""","senderBlock":{"phone":"1"}}"""
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(json) }
    }

    @Test
    fun `a sender block that is not an object fails loudly`() {
        val json = goldenJson.dropLast(1) + ""","senderBlock":"Solo"}"""
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(json) }
    }

    @Test
    fun `a JSON null sender block decodes to null`() {
        val json = goldenJson.dropLast(1) + ""","senderBlock":null}"""
        assertNull(MailComposeRequest.decode(json).senderBlock)
    }

    @Test
    fun `sender block legalLines that are not an array or not text fail loudly`() {
        val notArray = goldenJson.dropLast(1) + ""","senderBlock":{"name":"S","legalLines":"x"}}"""
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(notArray) }
        val notText = goldenJson.dropLast(1) + ""","senderBlock":{"name":"S","legalLines":[1]}}"""
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(notText) }
    }

    // ── Independent deployment: unknown in, required out ───────────────────────

    @Test
    fun `unknown fields are ignored on read`() {
        val newerProducer = goldenJson.dropLast(1) +
            ""","callbackHint":"https://host/invoices/1","locale":"de-DE","priority":3}"""
        assertEquals(request, MailComposeRequest.decode(newerProducer))
    }

    @Test
    fun `an unknown schemaVersion is recorded, not rejected`() {
        val newer = goldenJson.replace(""""schemaVersion":5""", """"schemaVersion":9""")
        assertEquals(9, MailComposeRequest.decode(newer).schemaVersion)
    }

    // ── documentKind — additive field, schema 1 vs. schema 2 ───────────────────

    @Test
    fun `a schema-version-1 payload without documentKind defaults to INVOICE_DISPATCH`() {
        val decoded = MailComposeRequest.decode(schemaVersion1Json)
        assertEquals(MailDocumentKind.INVOICE_DISPATCH, decoded.documentKind)
        assertEquals(1, decoded.schemaVersion)
    }

    @Test
    fun `a schema-version-2 payload without documentKind also defaults to INVOICE_DISPATCH`() {
        val without = goldenJson.replace(""""documentKind":"INVOICE_DISPATCH",""", "")
        val decoded = MailComposeRequest.decode(without)
        assertEquals(MailDocumentKind.INVOICE_DISPATCH, decoded.documentKind)
    }

    @Test
    fun `an explicit QUOTE_DISPATCH documentKind round-trips`() {
        val quote = request.copy(documentKind = MailDocumentKind.QUOTE_DISPATCH)
        assertEquals(quote, MailComposeRequest.decode(MailComposeRequest.encode(quote)))
        assertEquals(MailDocumentKind.QUOTE_DISPATCH, MailComposeRequest.decode(MailComposeRequest.encode(quote)).documentKind)
    }

    @Test
    fun `an unknown documentKind fails loudly rather than picking a default kind`() {
        val unknown = goldenJson.replace(""""documentKind":"INVOICE_DISPATCH"""", """"documentKind":"REMINDER_DISPATCH"""")
        val thrown = assertThrows<MailContractViolationException> { MailComposeRequest.decode(unknown) }
        assertTrue(thrown.message!!.contains("REMINDER_DISPATCH"))
    }

    @Test
    fun `a blank documentKind fails loudly rather than defaulting`() {
        val blank = goldenJson.replace(""""documentKind":"INVOICE_DISPATCH"""", """"documentKind":"   """")
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(blank) }
    }

    @Test
    fun `a missing recipientAddress fails loudly instead of defaulting to an empty string`() {
        val without = goldenJson.replace(""""recipientAddress":"kunde@example.de",""", "")
        val thrown = assertThrows<MailContractViolationException> { MailComposeRequest.decode(without) }
        assertEquals("MailComposeRequest: required field 'recipientAddress' is missing or blank", thrown.message)
    }

    @Test
    fun `a blank recipientName fails loudly`() {
        val blank = goldenJson.replace(""""recipientName":"Beispiel GmbH"""", """"recipientName":"   """")
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(blank) }
    }

    @Test
    fun `a missing referenceKey fails loudly - the host could not join the result back`() {
        val without = goldenJson.replace(""""referenceKey":"RE-2026-0001",""", "")
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(without) }
    }

    @Test
    fun `a missing schemaVersion fails loudly instead of defaulting to the current version`() {
        val without = goldenJson.replace(""""schemaVersion":5,""", "")
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(without) }
    }

    @Test
    fun `a missing attachments array fails loudly instead of decoding as no attachments`() {
        val without = goldenJson.substringBefore(""","attachments":""") + "}"
        val thrown = assertThrows<MailContractViolationException> { MailComposeRequest.decode(without) }
        assertEquals("MailComposeRequest: required field 'attachments' is missing or not an array", thrown.message)
    }

    @Test
    fun `a missing placeholderValues object fails loudly instead of decoding as no placeholders`() {
        val without = """{"schemaVersion":1,"referenceKey":"RE-1","documentFamily":"GERMAN",""" +
            """"recipientAddress":"a@b.de","recipientName":"A","attachments":[]}"""
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(without) }
    }

    @Test
    fun `an attachment element that is not an object fails loudly`() {
        val broken = goldenJson.replace("""[{"filename":""", """["RE-2026-0001.pdf",{"filename":""")
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(broken) }
    }

    @Test
    fun `an unknown documentFamily fails loudly rather than picking a default family`() {
        val unknown = goldenJson.replace(""""documentFamily":"GERMAN"""", """"documentFamily":"SWISS"""")
        val thrown = assertThrows<MailContractViolationException> { MailComposeRequest.decode(unknown) }
        assertTrue(thrown.message!!.contains("SWISS"))
    }

    @Test
    fun `a payload that is not a JSON object fails loudly`() {
        assertThrows<MailContractViolationException> { MailComposeRequest.decode(null) }
        assertThrows<MailContractViolationException> { MailComposeRequest.decode("") }
        assertThrows<MailContractViolationException> { MailComposeRequest.decode("\"RE-2026-0001\"") }
    }
}
