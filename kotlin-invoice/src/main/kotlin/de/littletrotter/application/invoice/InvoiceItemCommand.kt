package de.littletrotter.application.invoice

import de.littletrotter.application.command.Command
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import java.math.BigDecimal

/**
 * The goods/service item dialogs' write payload for [de.littletrotter.application.invoice.port
 * .inbound.EditInvoiceItemsUseCase.addItemToInvoice]/[de.littletrotter.application.invoice.port
 * .inbound.EditInvoiceItemsUseCase.updateInvoiceItem], the item-level counterpart to
 * [UpdateInvoiceHeaderCommand]. Carries plain values and reference ids only, never a JPA entity:
 * [InvoiceLifecycleService.addItem] builds a fresh [de.littletrotter.domain.invoice.InvoiceItem]
 * from it, and [InvoiceLifecycleService.updateItem] applies it onto the managed item it loads
 * itself within the managed invoice it loads itself.
 *
 * [category] may only ever be [InvoiceItemCategory.SERVICE] or
 * [InvoiceItemCategory.PRODUCT] here; both write methods reject [InvoiceItemCategory.TRIP]. A
 * business trip goes exclusively through [TripCommand] and [TripService.addTrip]/[TripService
 * .updateTrip] — it never picks its own tax rule either way (it always inherits the invoice's
 * main-service rate, [de.littletrotter.domain.invoice.mainServiceTaxRate]), which is why
 * [taxRateId]/[articleId] here are meaningful only for the two categories this command still
 * carries.
 */
data class InvoiceItemCommand(
    /** Set only for [InvoiceLifecycleService.updateItem] — the managed item's id to find within the
     *  invoice; left `null` for [InvoiceLifecycleService.addItem], which always creates a new position. */
    val itemId: Long? = null,
    val category: InvoiceItemCategory,
    val description: String,
    /** BT-154 *Item description* — the optional, longer counterpart to [description] (BT-153).
     *  See [de.littletrotter.domain.invoice.InvoiceItem.itemDescription] for why there is no
     *  fallback to [description] on export. */
    val itemDescription: String? = null,
    /** Buyer's item description — recipient-specific label for this position. */
    val buyerItemDescription: String? = null,
    val deliveryNoteReference: String? = null,
    val quantity: BigDecimal,
    val unit: InvoiceItemUnit,
    val unitPriceNet: Money,
    /** The bound [de.littletrotter.domain.masterdata.Article], if this position was
     *  created/edited from one (PRODUCT/SERVICE only). */
    val articleId: Long? = null,
    val taxRateId: Long? = null,
    /** User-entered override for an E/O rate's generic seed reason/legal-basis text,
     *  collected by the dialog only while the selected rate requires it
     *  ([de.littletrotter.ui.view.invoice.requiresExplicitExemptionDetails]); `null` leaves the
     *  rate's own seed text untouched. Applied only on creation: unlike [taxRateId] (which
     *  [InvoiceLifecycleService.updateItem] re-applies and re-snapshots when it names a genuinely
     *  different rate — monkey finding 2026-08-30), this text override has no update-time
     *  counterpart, since [InvoiceLifecycleService.updateItem] never re-derives it from a rate. */
    val exemptionReasonTextOverride: String? = null,
    val taxLegalBasisOverride: String? = null,
    /** WP1 (0.9.10) — the invoice's [de.littletrotter.domain.invoice.Invoice.version] the UI
     *  session saw (`InvoiceDetailDto.version`); items/trips carry no version of their own, so
     *  the check runs one level up, via [de.littletrotter.domain.invoice.Invoice
     *  .requireExpectedVersion]. `null` skips the check — same convention as
     *  [UpdateInvoiceHeaderCommand.expectedVersion] — for internal/non-UI callers (datagen, dev
     *  fixtures, tests) that never carry one. */
    val expectedVersion: Long? = null,
) : Command
