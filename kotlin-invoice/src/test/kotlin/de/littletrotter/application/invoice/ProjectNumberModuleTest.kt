package de.littletrotter.application.invoice

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.spi.form.FieldKind
import de.littletrotter.spi.form.FormContext
import de.littletrotter.spi.form.FormSlot
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The reference [ProjectNumberModule] describes the project-number field at the right slot. */
class ProjectNumberModuleTest {

    private val ctx = FormContext(InvoiceDto(), readOnly = false, Locale.GERMAN)

    @Test
    fun `module describes the project number field in the references slot`() {
        val module = ProjectNumberModule()
        val spec = module.fields(ctx).single()
        assertEquals("projectNumber", spec.id)
        assertEquals("invoiceModule.projectNumber", spec.i18nKey)
        assertEquals(FieldKind.TEXT, spec.kind)
        assertEquals(FormSlot.OVERVIEW_REFERENCES, spec.slot)
        assertEquals("/Invoice/ProjectNumber", spec.xmlTarget?.xpath)
        // The project number is BT-11 (Project reference), not BT-16 (Despatch advice
        // reference) as this field used to (wrongly) claim.
        assertEquals(B2bTerm.BT_11, spec.xmlTarget?.businessTerm)
        // FieldSpec.required/helperText are additive with defaults; a pre-existing
        // module built before they existed must keep behaving exactly as before.
        assertEquals(false, spec.required)
        assertEquals(null, spec.helperText)
    }
}
