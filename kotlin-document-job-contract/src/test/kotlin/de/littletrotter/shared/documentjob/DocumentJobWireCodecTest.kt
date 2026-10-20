package de.littletrotter.shared.documentjob

import de.littletrotter.shared.KositReportRefDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pins the JSON the producer and the worker have always exchanged: field names, order and omission rules. */
class DocumentJobWireCodecTest {

  // Verbatim shape of the invoice producer's map-built message (fully populated, MUSTANG engine).
  private val goldenFullRequest =
    """{"id":"42","type":"PDF_EXPORT","correlationId":"c-1","engine":"MUSTANG",""" +
      """"payload":{"invoice":{"number":"RE-1"}},""" +
      """"options":{"llmOptimize":true,"skipValidation":true,"veraPdfCheck":true,"outputIntent":"SRGB"}}"""

  // MUSTANG omits the engine, a producer without options omits those too.
  private val goldenMinimalRequest = """{"id":"7","type":"PDF_EXPORT","correlationId":"c-2","payload":{"invoice":{"number":"RE-2"}}}"""

  private val goldenResult =
    """{"id":"42","status":"COMPLETED","resultRefs":{"objectKey":"k"},"kositReportRef":{"key":"lt:kosit:42"},"correlationId":"c-1"}"""

  @Test
  fun `full request serializes to the historic producer JSON`() {
    val request = DocumentJobRequest(
      id = "42",
      type = "PDF_EXPORT",
      correlationId = "c-1",
      engine = "MUSTANG",
      payload = mapOf("invoice" to mapOf("number" to "RE-1")),
      options = DocumentJobOptions(llmOptimize = true, skipValidation = true, veraPdfCheck = true, outputIntent = "SRGB"),
    )
    assertEquals(goldenFullRequest, DocumentJobWireCodec.encodeRequest(request))
  }

  @Test
  fun `minimal request omits absent engine options and result subject`() {
    val request = DocumentJobRequest("7", "PDF_EXPORT", "c-2", payload = mapOf("invoice" to mapOf("number" to "RE-2")))
    assertEquals(goldenMinimalRequest, DocumentJobWireCodec.encodeRequest(request))
  }

  @Test
  fun `historic request JSON decodes with defaults`() {
    val decoded = DocumentJobWireCodec.decodeRequest(goldenMinimalRequest)
    assertEquals("7", decoded.id)
    assertNull(decoded.engine)
    assertNull(decoded.options)
    assertNull(decoded.resultSubject)
    assertEquals(DocumentJobSubjects.RESULTS, decoded.resultSubjectOrDefault())
  }

  @Test
  fun `full request round-trips and decoding ignores unknown fields`() {
    val decoded = DocumentJobWireCodec.decodeRequest(goldenFullRequest.dropLast(1) + ""","legacyHeadline":"x"}""")
    assertEquals("MUSTANG", decoded.engine)
    assertEquals(DocumentJobOptions(llmOptimize = true, skipValidation = true, veraPdfCheck = true, outputIntent = "SRGB"), decoded.options)
    assertEquals(goldenFullRequest, DocumentJobWireCodec.encodeRequest(decoded.copy()))
  }

  @Test
  fun `legacy ollama option still activates reading`() {
    val decoded = DocumentJobWireCodec.decodeRequest("""{"id":"1","type":"T","options":{"ollamaOptimize":true}}""")
    assertTrue(decoded.options!!.ollamaOptimize)
  }

  @Test
  fun `explicit result subject is written and read back`() {
    val request = DocumentJobRequest("9", "QUOTE_PDF", resultSubject = "quote-job-results")
    val json = DocumentJobWireCodec.encodeRequest(request)
    assertEquals("""{"id":"9","type":"QUOTE_PDF","resultSubject":"quote-job-results"}""", json)
    assertEquals("quote-job-results", DocumentJobWireCodec.decodeRequest(json).resultSubjectOrDefault())
  }

  @Test
  fun `result serializes with nulls and round-trips`() {
    val result = DocumentJobResult("42", "COMPLETED", mapOf("objectKey" to "k"), KositReportRefDto("lt:kosit:42"), "c-1")
    assertEquals(goldenResult, DocumentJobWireCodec.encodeResult(result))
    assertEquals(result, DocumentJobWireCodec.decodeResult(goldenResult))
    assertEquals(
      """{"id":"1","status":"FAILED","resultRefs":null,"kositReportRef":null,"correlationId":null}""",
      DocumentJobWireCodec.encodeResult(DocumentJobResult("1", "FAILED", null, null, null)),
    )
  }

  @Test
  fun `subjects keep their historic names`() {
    assertEquals(
      listOf("document-jobs", "document-job-results", "document-jobs-dlq", "document-job-results-dlq"),
      listOf(DocumentJobSubjects.JOBS, DocumentJobSubjects.RESULTS, DocumentJobSubjects.JOBS_DLQ, DocumentJobSubjects.RESULTS_DLQ),
    )
  }

  @Test
  fun `request size limit is 32 KiB and a typical request stays below it`() {
    assertEquals(32_768, DocumentJobWireCodec.MAX_REQUEST_BYTES)
    assertTrue(goldenFullRequest.toByteArray().size < DocumentJobWireCodec.MAX_REQUEST_BYTES)
  }

  @Test
  fun `kosit check is written only when switched off and reads as on when absent`() {
    val off = DocumentJobRequest("5", "PDF_EXPORT", options = DocumentJobOptions(kositCheck = false))
    assertEquals("""{"id":"5","type":"PDF_EXPORT","options":{"kositCheck":false}}""", DocumentJobWireCodec.encodeRequest(off))
    assertTrue(DocumentJobWireCodec.decodeRequest("""{"id":"5","type":"PDF_EXPORT","options":{"veraPdfCheck":true}}""").options!!.kositCheck)
    assertEquals(false, DocumentJobWireCodec.decodeRequest(DocumentJobWireCodec.encodeRequest(off)).options!!.kositCheck)
  }

  @Test
  fun `pdf check request carries a claim check only and round-trips`() {
    val request = PdfCheckRequest(checkId = "verapdf-42", jobId = "42", objectKey = "k/42.pdf", sha256 = "ab", correlationId = "c-1")
    val json = DocumentJobWireCodec.encodePdfCheckRequest(request)
    assertEquals(
      """{"schemaVersion":1,"checkId":"verapdf-42","jobId":"42","objectKey":"k/42.pdf","sha256":"ab","correlationId":"c-1"}""",
      json,
    )
    assertEquals(request, DocumentJobWireCodec.decodePdfCheckRequest(json.dropLast(1) + ""","future":1}"""))
  }

  @Test
  fun `pdf check result round-trips and the check subjects keep their names`() {
    val result = PdfCheckResult(
      checkId = "verapdf-42", jobId = "42", objectKey = "k", sha256 = "ab", outcome = "ERROR",
      profile = "PDF/A-3B", validator = "veraPDF 1.30.2", engineError = "timeout",
    )
    assertEquals(result, DocumentJobWireCodec.decodePdfCheckResult(DocumentJobWireCodec.encodePdfCheckResult(result)))
    assertEquals(listOf("pdf-checks", "pdf-check-results"), listOf(DocumentJobSubjects.PDF_CHECKS, DocumentJobSubjects.PDF_CHECK_RESULTS))
    assertEquals(
      listOf(DocumentJobSubjects.PDF_CHECKS, DocumentJobSubjects.PDF_CHECK_RESULTS),
      listOf(de.littletrotter.shared.NatsStreamSetup.PDF_CHECK_SUBJECT, de.littletrotter.shared.NatsStreamSetup.PDF_CHECK_RESULT_SUBJECT),
    )
  }
}
