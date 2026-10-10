package de.littletrotter.application.invoice

import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.invoice.isInvoiceFormatEditable
import de.littletrotter.shared.country.Country
import de.littletrotter.spi.form.EnumeratedOption
import de.littletrotter.spi.form.FieldKind
import de.littletrotter.spi.form.FieldSpec
import de.littletrotter.spi.form.FormContext
import de.littletrotter.spi.form.FormSlot
import de.littletrotter.spi.form.InvoiceFormModule
import jakarta.enterprise.context.ApplicationScoped

/**
 * R-CB — the legal invoice format (German/international) `ComboBox`. Always rendered
 * (unlike the module's original shape, which hid the field entirely via an empty
 * [fields] result): PO decision 2026-08-25 lets the user *see* the choice and understand why it is
 * locked, rather than never suspecting a control exists. [FieldSpec.enabled] instead gates whether
 * it accepts input, decided by [isInvoiceFormatEditable] — the exact same predicate
 * `InvoiceLifecycleService.updateHeader` enforces server-side, so the widget's enabled/disabled
 * state can never promise something the save would then reject. Disabled state gets an explanatory
 * helper text (via [FieldSpec.helperTextKey] — this `application`-layer module must not depend on
 * `ui.i18n.UiTranslations` itself, per `ArchitectureRulesTest`'s layering rule, so it hands over
 * the message-bundle key and lets `ModuleFieldSection` resolve it) instead of silently vanishing.
 *
 * The field binds via the spec's custom getter/setter lambdas, translating between the
 * [InvoiceFormat] enum and the `ComboBox<String>` that `ModuleFieldSection` renders for
 * [FieldKind.ENUM] — the aggregate needs no String bridge property for this.
 *
 * Binds against [InvoiceHeaderFormState], the entry pane's `Binder` bean, not a domain
 * `Invoice` (the entry pane no longer holds one at all — `dtoToInvoice` is gone).
 */
@ApplicationScoped
class InvoiceFormatModule : InvoiceFormModule {

    override val id: String = "invoice-format"

    override fun fields(ctx: FormContext): List<FieldSpec> {
        val sellerVatRegistrationCountry = Country.fromAlpha2(ctx.invoice.meta.sellerVatRegistrationCountry)
        val reverseCharge = ctx.invoice.totals.reverseCharge
        val editable = isInvoiceFormatEditable(sellerVatRegistrationCountry, reverseCharge)
        return listOf(
            FieldSpec(
                id = "invoiceFormat",
                i18nKey = "invoiceModule.invoiceFormat",
                kind = FieldKind.ENUM,
                slot = FormSlot.OVERVIEW_REFERENCES,
                options = listOf(
                    EnumeratedOption(value = "GERMAN", i18nKey = "invoiceModule.invoiceFormat.german"),
                    EnumeratedOption(value = "INTERNATIONAL", i18nKey = "invoiceModule.invoiceFormat.international"),
                ),
                enabled = editable,
                helperTextKey = if (!editable) "invoiceModule.invoiceFormat.forcedGerman" else null,
                getter = { bean -> (bean as? InvoiceHeaderFormState)?.invoiceFormat?.name },
                setter = { bean, value ->
                    val formState = bean as? InvoiceHeaderFormState
                    val name = value as? String
                    if (formState != null && name != null) formState.invoiceFormat = InvoiceFormat.valueOf(name)
                },
            ),
        )
    }
}
