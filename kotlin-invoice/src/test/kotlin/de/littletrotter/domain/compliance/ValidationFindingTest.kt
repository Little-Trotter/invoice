package de.littletrotter.domain.compliance

import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class ValidationFindingTest {

    @Test
    fun `carries every field it was given`() {
        val now = OffsetDateTime.now()
        val finding = ValidationFinding().edit {
            generationJobId = 42L
            invoiceId = 7L
            stage = "BUSINESS"
            validator = "compliance"
            ruleId = "CORE.INVOICE_DATE"
            severity = "error"
            location = "header"
            message = "Rechnungsdatum fehlt"
            validatorVersion = "1.0"
            createdAt = now
        }

        assertEquals(42L, finding.generationJobId)
        assertEquals(7L, finding.invoiceId)
        assertEquals("BUSINESS", finding.stage)
        assertEquals("compliance", finding.validator)
        assertEquals("CORE.INVOICE_DATE", finding.ruleId)
        assertEquals("error", finding.severity)
        assertEquals("header", finding.location)
        assertEquals("Rechnungsdatum fehlt", finding.message)
        assertEquals("1.0", finding.validatorVersion)
        assertEquals(now, finding.createdAt)
    }

    @Test
    fun `defaults to blank severity and message with no id assigned yet`() {
        val finding = ValidationFinding()

        assertNull(finding.id)
        assertEquals("", finding.severity)
        assertEquals("", finding.message)
        assertNull(finding.stage)
        assertNull(finding.ruleId)
    }
}
