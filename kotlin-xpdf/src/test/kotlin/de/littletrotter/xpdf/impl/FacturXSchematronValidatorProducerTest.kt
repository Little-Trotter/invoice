package de.littletrotter.xpdf.impl

import de.littletrotter.shared.KositOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * XPR-3 — [FacturXSchematronValidatorProducer] no longer relies on `@PostConstruct`/`@Startup`
 * on the produced class itself (see its own class doc); this test proves `produce()` really does
 * call [de.littletrotter.facturx.FacturXSchematronValidator.init] before handing the instance
 * back — a validator whose `init()` never ran reports `"not initialized"` (see
 * `FacturXSchematronValidator`'s own `runProfile` behavior), so validating any EN16931-routed
 * document and getting anything else already proves eager init happened.
 */
class FacturXSchematronValidatorProducerTest {

    @Test
    fun `produce returns an already-initialized validator, not a bare not-initialized one`() {
        val validator = FacturXSchematronValidatorProducer().produce()

        // A document whose guideline URN routes to EN16931 but is otherwise malformed: if init()
        // never ran, runProfile short-circuits with the fixed "engine not initialized" message
        // before ever attempting the transform — this proves init() already ran.
        val malformed = """<?xml version="1.0" encoding="UTF-8"?>
            <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                                       xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:cen.eu:en16931:2017</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
        """.trimIndent().toByteArray()

        val stage = validator.validate(malformed)

        assertEquals(KositOutcome.ERROR, stage.outcome)
        val finding = stage.findings.single()
        assertNotEquals(
            "Factur-X Schematron validator engine not initialized",
            finding.message,
            "produce() must have called init() eagerly — a bare, never-initialized validator " +
                "would report exactly this message instead of a real transform error",
        )
    }
}
