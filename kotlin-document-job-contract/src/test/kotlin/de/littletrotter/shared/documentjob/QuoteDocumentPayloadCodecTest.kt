package de.littletrotter.shared.documentjob

import de.littletrotter.shared.NatsStreamSetup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QuoteDocumentPayloadCodecTest {

  private val payload = QuoteDocumentPayload(
    quoteId = "8f6c0f2e-0000-4000-8000-000000000001",
    quoteNumber = "AN-2026-00001",
    quoteDate = "2026-10-05",
    validUntil = "2026-11-04",
    mark = QuoteDocumentMark.REVIEW,
    language = "de",
    grossPrices = false,
    seller = QuoteDocumentParty(name = "Muster GmbH", city = "Trier"),
    customer = QuoteDocumentParty(name = "Kunde AG", customerNumber = "K-1"),
    items = listOf(QuoteDocumentItem(1, "Beratung", "2", "HOUR", "100.00", "200.00", "19", customerReference = "KD-9")),
    totals = QuoteDocumentTotals("200.00", "38.00", "238.00", listOf(QuoteDocumentVatGroup("19", "200.00", "38.00"))),
    references = listOf(QuoteDocumentReference(10, "REF")),
  )

  @Test
  fun `payload survives the trip through a job request`() {
    val request = DocumentJobRequest("j1", QuoteDocumentJob.TYPE, payload = QuoteDocumentPayloadCodec.toMap(payload))
    val decoded = DocumentJobWireCodec.decodeRequest(DocumentJobWireCodec.encodeRequest(request))
    assertEquals(payload, QuoteDocumentPayloadCodec.fromMap(decoded.payload))
  }

  @Test
  fun `an attachment reference carries only key, checksum and descriptive fields and survives the trip`() {
    val ref = QuoteDocumentAttachmentRef("quotes/q/quotation.ubl.xml", "a".repeat(64), "quotation.ubl.xml", "text/xml", "Offer data")
    val withRef = payload.copy(attachment = ref)

    val decoded = QuoteDocumentPayloadCodec.fromMap(QuoteDocumentPayloadCodec.toMap(withRef))

    assertEquals(ref, decoded.attachment)
    assertEquals(null, QuoteDocumentPayloadCodec.fromMap(QuoteDocumentPayloadCodec.toMap(payload)).attachment)
  }

  @Test
  fun `a missing payload is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { QuoteDocumentPayloadCodec.fromMap(null) }
  }

  @Test
  fun `an unknown field of a newer producer is ignored`() {
    val map = QuoteDocumentPayloadCodec.toMap(payload) + ("futureField" to "x")
    assertEquals(payload, QuoteDocumentPayloadCodec.fromMap(map))
  }

  @Test
  fun `the contract names the offer subjects`() {
    assertEquals("QUOTE_PDF", QuoteDocumentJob.TYPE)
    assertEquals(NatsStreamSetup.OFFER_RESULTS_SUBJECT, QuoteDocumentJob.RESULT_SUBJECT)
  }
}
