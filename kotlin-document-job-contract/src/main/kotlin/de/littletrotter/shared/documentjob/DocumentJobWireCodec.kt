package de.littletrotter.shared.documentjob

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/** The one JSON codec of the document job contract; both sides read and write through it. */
object DocumentJobWireCodec {

  /** Requests must stay below this size; larger data travels by reference, never inline. */
  const val MAX_REQUEST_BYTES = 32 * 1024

  private val writer: ObjectMapper = jacksonObjectMapper()

  // A request from a not-yet-upgraded producer may carry a field this reader no longer declares;
  // it is ignored instead of rejected.
  private val requestReader: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  fun encodeRequest(request: DocumentJobRequest): String = writer.writeValueAsString(request)

  fun decodeRequest(json: String): DocumentJobRequest = requestReader.readValue(json)

  fun encodeResult(result: DocumentJobResult): String = writer.writeValueAsString(result)

  fun decodeResult(json: String): DocumentJobResult = writer.readValue(json)

  fun encodePdfCheckRequest(request: PdfCheckRequest): String = writer.writeValueAsString(request)

  fun decodePdfCheckRequest(json: String): PdfCheckRequest = requestReader.readValue(json)

  fun encodePdfCheckResult(result: PdfCheckResult): String = writer.writeValueAsString(result)

  fun decodePdfCheckResult(json: String): PdfCheckResult = requestReader.readValue(json)
}
