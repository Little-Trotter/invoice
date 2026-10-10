package de.littletrotter.application.invoice

import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.spi.form.FieldKind
import de.littletrotter.spi.form.FieldSpec
import de.littletrotter.spi.form.FormContext
import de.littletrotter.spi.form.FormSlot
import de.littletrotter.spi.form.InvoiceFormModule
import de.littletrotter.spi.form.XmlTarget
import jakarta.enterprise.context.ApplicationScoped

/**
 * The internal project reference (Ba-Auftrag / interne Projektnummer) on the invoice header,
 * docked into the [FormSlot.OVERVIEW_REFERENCES] strip next to the order/offer references.
 *
 * The value is the nullable `invoice.project_number` column: the storage and export layers stay
 * agnostic (null/blank &rarr; skipped), only this module collects it in the UI. Registration is
 * pure CDI — `@ApplicationScoped`, discovered automatically.
 *
 * Fixes a review finding: this field's [XmlTarget.businessTerm] used
 * to name [B2bTerm.BT_16] (the *Despatch advice reference*), not the project reference it
 * actually is — [B2bTerm.BT_11] (`projectNumber` already *is* BT-11). Typing
 * [XmlTarget.businessTerm] as [B2bTerm] makes exactly this class of typo a compile error going
 * forward.
 */
@ApplicationScoped
class ProjectNumberModule : InvoiceFormModule {

    override val id: String = "project-number"

    override fun fields(ctx: FormContext): List<FieldSpec> = listOf(
        FieldSpec(
            id = "projectNumber",
            i18nKey = "invoiceModule.projectNumber",
            kind = FieldKind.TEXT,
            slot = FormSlot.OVERVIEW_REFERENCES,
            xmlTarget = XmlTarget(businessTerm = B2bTerm.BT_11, xpath = "/Invoice/ProjectNumber"),
        ),
    )
}
