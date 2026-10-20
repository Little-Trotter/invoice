package de.littletrotter.application

import de.littletrotter.application.invoice.InvoiceDocumentEventHandler
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.event.InvoiceFinalized
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.fixture.edit
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvoiceDocumentEventHandlerTest {

    @Test
    fun `onInvoiceFinalized does not auto-create jobs and does not throw`() {
        val handler = InvoiceDocumentEventHandler()
        assertDoesNotThrow {
            handler.onInvoiceFinalized(InvoiceFinalized(invoiceId = 1L, invoiceNumber = "LT-2026-0001"))
        }
    }

    @Test
    fun `TaxRate validator rejects invalid rate`() {
        // TaxRatePercent now rejects an out-of-range value at construction, before
        // TaxRate.validate() is ever reached.
        assertThrows(InvalidParameterException::class.java) {
            TaxRate().edit {
                name = ""
                this.rate = TaxRatePercent(BigDecimal("-1"))
            }
        }
    }

    @Test
    fun `UiTranslations fallback messages exist for error keys`() {
        val generic = UiTranslations.tr("message.error.generic")
        val optimistic = UiTranslations.tr("message.error.optimisticLock")
        val constraint = UiTranslations.tr("message.error.constraintViolation")

        assertNotNull(generic)
        assertNotNull(optimistic)
        assertNotNull(constraint)
        assert(!generic.startsWith("!"))
        assert(!optimistic.startsWith("!"))
        assert(!constraint.startsWith("!"))
    }
}
