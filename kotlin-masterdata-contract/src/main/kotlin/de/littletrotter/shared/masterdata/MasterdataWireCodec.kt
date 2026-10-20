package de.littletrotter.shared.masterdata

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** The one JSON codec of the master data contract; producer and consumers read and write through it. */
object MasterdataWireCodec {

  /** An envelope must stay below this size; larger data is split (snapshot) or sent by reference. */
  const val MAX_ENVELOPE_BYTES = 32 * 1024

  // A consumer must survive an additive field of a newer producer.
  private val mapper: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

  fun encode(event: Any): String = mapper.writeValueAsString(event)

  fun sizeInBytes(json: String): Int = json.toByteArray(Charsets.UTF_8).size

  fun <T : Any> decode(json: String, type: Class<T>): T = mapper.readValue(json, type)
}
