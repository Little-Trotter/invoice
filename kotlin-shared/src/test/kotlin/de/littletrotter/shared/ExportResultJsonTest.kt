package de.littletrotter.shared

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExportResultJsonTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `serialize and deserialize ExportResult`() {
        val r = ExportResult("bucket/key.pdf", "bucket/key.xml", true, "report", "abc123")
        val json = mapper.writeValueAsString(r)
        val parsed: ExportResult = mapper.readValue(json)
        assertEquals(r, parsed)
    }
}
