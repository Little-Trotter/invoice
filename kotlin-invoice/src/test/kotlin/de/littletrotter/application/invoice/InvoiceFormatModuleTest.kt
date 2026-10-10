package de.littletrotter.application.invoice

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceMetaDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.spi.form.FieldKind
import de.littletrotter.spi.form.FormContext
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * R-CB — the invoice-format field. PO decision (2026-08-25): the field is now always
 * rendered (unlike the module's original shape, which contributed no [FieldSpec] at all
 * while ineligible); [FieldSpec.enabled] instead gates it, mirroring
 * [de.littletrotter.domain.invoice.isInvoiceFormatEditable] — non-null seller VAT registration OR
 * reverseCharge, exactly the same predicate `InvoiceLifecycleService.updateHeader` enforces.
 */
class InvoiceFormatModuleTest {

    private val module = InvoiceFormatModule()

    private fun ctx(sellerVatRegistrationCountry: String? = null, reverseCharge: Boolean = false) = FormContext(
        invoice = InvoiceDto(
            meta = InvoiceMetaDto(sellerVatRegistrationCountry = sellerVatRegistrationCountry),
            totals = InvoiceTotalsDto(reverseCharge = reverseCharge),
        ),
        readOnly = false,
        locale = Locale.GERMAN,
    )

    @Test
    fun `still contributes exactly one ENUM field with both options while ineligible, just disabled`() {
        val fields = module.fields(ctx(sellerVatRegistrationCountry = null, reverseCharge = false))

        assertEquals(1, fields.size)
        val spec = fields.single()
        assertEquals("invoiceFormat", spec.id)
        assertEquals(FieldKind.ENUM, spec.kind)
        assertEquals(setOf("GERMAN", "INTERNATIONAL"), spec.options.map { it.value }.toSet())
        assertFalse(spec.enabled)
        // The module hands over a message-bundle key, not a resolved string: it lives in the
        // `application` layer, which must not depend on `ui.i18n.UiTranslations`
        // (ArchitectureRulesTest) — ModuleFieldSection resolves helperTextKey at render time.
        assertEquals("invoiceModule.invoiceFormat.forcedGerman", spec.helperTextKey)
        assertNull(spec.helperText)
    }

    @Test
    fun `is enabled with no helper text once a seller VAT registration is active`() {
        val spec = module.fields(ctx(sellerVatRegistrationCountry = "LU")).single()

        assertTrue(spec.enabled)
        assertNull(spec.helperText)
        assertNull(spec.helperTextKey)
    }

    @Test
    fun `is enabled with no helper text when reverseCharge is true, even without a seller VAT registration`() {
        val spec = module.fields(ctx(sellerVatRegistrationCountry = null, reverseCharge = true)).single()

        assertTrue(spec.enabled)
        assertNull(spec.helperText)
        assertNull(spec.helperTextKey)
    }

    // ── getter/setter lambdas — bind against InvoiceHeaderFormState, never a raw Invoice ────────

    @Test
    fun `getter reads the current invoiceFormat name off an InvoiceHeaderFormState bean`() {
        val spec = module.fields(ctx()).single()
        val bean = de.littletrotter.application.invoice.InvoiceHeaderFormState(invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL)

        assertEquals("INTERNATIONAL", spec.getter?.invoke(bean))
    }

    @Test
    fun `getter returns null for a bean of the wrong type instead of throwing`() {
        val spec = module.fields(ctx()).single()

        assertNull(spec.getter?.invoke("not-a-form-state"))
    }

    @Test
    fun `setter applies a known InvoiceFormat name onto the bean`() {
        val spec = module.fields(ctx()).single()
        val bean = de.littletrotter.application.invoice.InvoiceHeaderFormState()

        spec.setter?.invoke(bean, "INTERNATIONAL")

        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.INTERNATIONAL, bean.invoiceFormat)
    }

    @Test
    fun `setter is a no-op for a bean of the wrong type instead of throwing`() {
        val spec = module.fields(ctx()).single()

        assertDoesNotThrow { spec.setter?.invoke("not-a-form-state", "INTERNATIONAL") }
    }

    @Test
    fun `setter is a no-op when the value is not a String instead of throwing`() {
        val spec = module.fields(ctx()).single()
        val bean = de.littletrotter.application.invoice.InvoiceHeaderFormState(invoiceFormat = de.littletrotter.domain.shared.InvoiceFormat.GERMAN)

        spec.setter?.invoke(bean, 42)

        assertEquals(de.littletrotter.domain.shared.InvoiceFormat.GERMAN, bean.invoiceFormat, "an unrecognised value type must not touch the bean")
    }
}
