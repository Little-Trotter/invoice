package de.littletrotter.xpdf.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [mapSeverityFlag] in isolation, covering every branch: the real embedded Schematron
 * fixtures we have mutated so far only ever produce an absent `flag` attribute (the `else`
 * fallback), never `warning`/`information`/`fatal` — those flags do occur elsewhere in the
 * stylesheet (e.g. `PEPPOL-EN16931-R008`, `CII-SR-475`) but engineering a dedicated fixture per
 * flag value is unnecessary when the mapping itself is a pure function.
 */
class CiiSeverityMappingTest {

    @Test
    fun `fatal and error both map to error`() {
        assertEquals("error", mapSeverityFlag("fatal"))
        assertEquals("error", mapSeverityFlag("error"))
        assertEquals("error", mapSeverityFlag("ERROR"))
    }

    @Test
    fun `warning maps to warning`() {
        assertEquals("warning", mapSeverityFlag("warning"))
        assertEquals("warning", mapSeverityFlag("WARNING"))
    }

    @Test
    fun `information and info both map to info`() {
        assertEquals("info", mapSeverityFlag("information"))
        assertEquals("info", mapSeverityFlag("info"))
    }

    @Test
    fun `an unrecognized flag falls back to error, never dropped`() {
        assertEquals("error", mapSeverityFlag("something-unexpected"))
    }

    @Test
    fun `an absent flag falls back to error`() {
        assertEquals("error", mapSeverityFlag(null))
    }
}
