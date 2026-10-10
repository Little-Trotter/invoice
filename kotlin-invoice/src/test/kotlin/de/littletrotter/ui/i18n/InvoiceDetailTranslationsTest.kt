package de.littletrotter.ui.i18n

import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.ui.view.invoice.STATUS_ACTIONS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.ResourceBundle

class InvoiceDetailTranslationsTest {

    private val de = ResourceBundle.getBundle("i18n.messages", Locale.GERMAN)

    @Test
    fun `invoice detail action and validation keys exist in german bundle`() {
        val keys = listOf(
            "invoiceDetail.backToDraft",
            "invoiceDetail.validation.servicePeriod.order",
            "invoiceDetail.validation.dueDate.beforeInvoiceDate",
            "invoiceDetail.validation.dueDate.beforeServiceEnd",
        )
        keys.forEach { key ->
            assertFalse(de.getString(key).isBlank(), "German translation missing for $key")
        }
    }

    @Test
    fun `invoice detail back to draft translation is stable`() {
        assertEquals("Zurück zu Entwurf", de.getString("invoiceDetail.backToDraft"))
    }

    @Test
    fun `all status action label keys exist in german bundle`() {
        STATUS_ACTIONS.map { it.labelKey }.forEach { key ->
            assertFalse(de.getString(key).isBlank(), "German status action translation missing for $key")
        }
    }

    @Test
    fun `status action labels are stable`() {
        val expected = mapOf(
            "invoiceDetail.review"        to "Zur Prüfung",
            "invoiceDetail.backToDraft"   to "Zurück zu Entwurf",
            "invoiceDetail.finalize"      to "Versenden",
            "invoiceDetail.voidInvoice" to "Annullieren",
        )
        STATUS_ACTIONS.map { it.labelKey }.forEach { key ->
            assertEquals(expected.getValue(key), de.getString(key), "Status action text changed for $key")
        }
    }

    @Test
    fun `all invoice status label keys exist in german bundle`() {
        InvoiceStatus.entries.map { it.labelKey }.forEach { key ->
            assertFalse(de.getString(key).isBlank(), "German invoice status translation missing for $key")
        }
    }

    @Test
    fun `all invoice unit label keys exist in german bundle`() {
        InvoiceItemUnit.entries.map { it.labelKey }.forEach { key ->
            assertFalse(de.getString(key).isBlank(), "German unit translation missing for $key")
        }
    }

    @Test
    fun `recalculate keys exist in german bundle`() {
        assertFalse(de.getString("invoiceDetail.recalculate").isBlank(), "German translation missing for invoiceDetail.recalculate")
        assertFalse(de.getString("invoiceDetail.recalculate.success").isBlank(), "German translation missing for invoiceDetail.recalculate.success")
    }

    @Test
    fun `recalculate translations are stable`() {
        assertEquals("Neuberechnen", de.getString("invoiceDetail.recalculate"))
        assertEquals("Beträge wurden neu berechnet", de.getString("invoiceDetail.recalculate.success"))
    }
}
