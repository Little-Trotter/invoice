package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.ArticleDto
import de.littletrotter.application.dto.ArticleRecipientRefDto
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.component.orPlaceholder

/** EN16931 BT-156/BT-128 buyer-supplied item reference fields' shared max length. */
private const val BUYER_ITEM_FIELD_MAX_LENGTH = 50

// Shared helpers the goods/service item dialogs use, as plain functions over the item-entry port
// rather than members of the dialogs themselves.

/**
 * The item dialogs' shared branch between the German path and the seller-VAT-
 * registration path: when [de.littletrotter.domain.invoice.Invoice.sellerVatRegistrationCountry] is set, tax selection comes
 * from that country's selectable [TaxRateDto]s instead of [selectableTaxRatesFor], an
 * article prefills its variant ([ArticleDto.taxVariantRateIdFor]) instead of the resolver's proposal, and
 * [de.littletrotter.domain.invoice.InvoiceItem.applySellerCountryTaxVariant] — not [de.littletrotter
 * .domain.invoice.InvoiceItem.applyTaxRule] alone — is the save-time gate for an artikelgebundene
 * position (it throws when the article carries no variant, blocking the save; a free-text position
 * without an article keeps using the manually chosen rate from the country list, same as
 * [de.littletrotter.domain.invoice.InvoiceItem.applyTaxRule] always did).
 */
internal fun taxRatesForSellerCountry(itemEntryQueryPort: ItemEntryQueryPort, country: Country): List<TaxRateDto> =
    itemEntryQueryPort.findAllTaxRates().filter { it.country == country && it.isActive && it.isSelectable }

/** Copies [article]'s LINE-level B2B template values to position; no-op for free-text items.
 *  Takes [invoiceId] directly (persisted invoices always have non-null `id`). */
internal fun copyB2bTemplateFor(itemEntryQueryPort: ItemEntryQueryPort, invoiceId: Long, article: ArticleDto?, itemId: Long?) {
    val articleId = article?.id ?: return
    val id = itemId ?: return
    itemEntryQueryPort.copyB2bArticleTemplateToInvoiceItem(articleId, id, invoiceId)
}

/**
 * Resolves [invoiceId]'s B2B `LINE`-level fields once so [buildB2bItemSection] and
 * [buildBuyerItemNumberField] don't each re-query the profile/field list — a plain lookup shared
 * across the two calls a dialog makes (`findB2bProfileIdForInvoice` + `findB2bInvoiceItemFields`).
 * Empty (never `null`) when the invoice carries no profile.
 */
internal fun findB2bItemFieldsFor(
    itemEntryQueryPort: ItemEntryQueryPort,
    invoiceId: Long,
): List<B2bProfileFieldDto> {
    val profileId = itemEntryQueryPort.findB2bProfileIdForInvoice(invoiceId)
    return itemEntryQueryPort.findB2bInvoiceItemFields(profileId)
}

/**
 * Builds the B2B `LINE`-level fields section for an invoice item dialog from
 * [fields] (see [findB2bItemFieldsFor]). For an existing item ([itemId] != null) the current
 * values are loaded; for a new item (`null`) the widgets start empty. Returns `null` when there is
 * nothing to render.
 *
 * BT-156 is excluded here: it moved out of the profile-gated
 * catalog into [buildBuyerItemNumberField], a plain always-visible field, because the buyer's own
 * article number is useful on every invoice, not just B2B-profiled ones (PO decision). Both still
 * read/write the same `b2b_field_value` row, so a profile assigned later sees whatever was typed
 * here.
 */
internal fun ItemEntryQueryPort.buildB2bItemSection(
    tr: (String) -> String,
    fields: List<B2bProfileFieldDto>,
    itemId: Long?,
): B2bProfileFieldsSection? {
    val section = B2bProfileFieldsSection(tr)
    section.setFields(fields.filterNot { it.term == B2bTerm.BT_156 })
    section.readFrom(itemId?.let { findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, it) }.orEmpty())
    section.applyDefaults() // same "blank widget only" prefill as the header section
    return section.takeIf { it.hasContent }
}

/**
 * BT-156 (buyer's article number), always enterable
 * regardless of any B2B profile assignment (PO decision: entering the customer's/buyer's article
 * number matters more than gating it behind a profile). Reads/writes directly against `b2b_field_value` — the same
 * storage [buildB2bItemSection] would use for this term — so the value is picked up unchanged by
 * export mapping (CII `SpecifiedTradeProduct/BuyerAssignedID`) once a B2B profile applies.
 *
 * When [fields] (see [findB2bItemFieldsFor]) still carries a BT-156 entry despite it having no
 * widget of its own here, this field picks up that entry's requiredness and shows the same
 * required-indicator asterisk [de.littletrotter.ui.component.B2bFieldWidgetFactory.createWidget] would have rendered for it —
 * the profile can mark the buyer's article number MANDATORY even though it never gets a section
 * widget.
 */
internal fun buildBuyerItemNumberField(
    itemEntryQueryPort: ItemEntryQueryPort,
    tr: (String) -> String,
    fields: List<B2bProfileFieldDto>,
    itemId: Long?,
): TextField {
    val bt156Field = fields.find { it.term == B2bTerm.BT_156 }
    return TextField(tr("invoiceDetail.itemDialog.buyerItemNumber")).apply {
        setWidthFull()
        maxLength = BUYER_ITEM_FIELD_MAX_LENGTH
        isClearButtonVisible = true
        helperText = tr("invoiceDetail.itemDialog.buyerItemNumber.helper")
        isRequiredIndicatorVisible = bt156Field?.requirement == B2bFieldRequirement.MANDATORY
        if (itemId != null) {
            value = itemEntryQueryPort.findB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, itemId)[B2bTerm.BT_156.officialCode].orEmpty()
        }
    }
}

/**
 * Buyer's item description — an optional, recipient-specific label for the position,
 * mirroring [buildBuyerItemNumberField] (BT-156) in shape and wording but stored directly
 * on `invoice_item.buyer_item_description` (not in B2B key-value table, because it is not
 * a standard EN 16931 term). Always visible, never gated by a B2B profile.
 */
internal fun buildBuyerItemDescriptionField(
    tr: (String) -> String,
): TextField = TextField(tr("invoiceDetail.itemDialog.buyerItemDescription")).apply {
    setWidthFull()
    maxLength = BUYER_ITEM_FIELD_MAX_LENGTH
    isClearButtonVisible = true
    helperText = tr("invoiceDetail.itemDialog.buyerItemDescription.helper")
}

/** Persists [field]'s value for BT-156 against [itemId]; a no-op before the item has an id
 *  (mirrors [persistB2bItemValues]'s creation-only guard). */
internal fun persistBuyerItemNumber(itemEntryQueryPort: ItemEntryQueryPort, itemId: Long?, field: TextField) {
    val id = itemId ?: return
    itemEntryQueryPort.saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, id, mapOf(B2bTerm.BT_156.officialCode to field.value.trim().ifBlank { null }))
}

/**
 * Resolves the per-recipient identifier mapping for [articleId] on [invoice]'s
 * recipient, or `null` when either is unset or no mapping exists. Kept as a plain function taking
 * the facade explicitly (like the rest of this file) so the goods/service item dialogs' article-
 * selection listener stays a thin call site instead of embedding the lookup itself.
 */
internal fun resolveArticleRecipientRef(articleQueries: ItemEntryQueryPort, invoice: InvoiceDetailDto, articleId: Long?): ArticleRecipientRefDto? {
    val aId = articleId ?: return null
    val recipientId = invoice.recipient?.id ?: return null
    return articleQueries.findArticleRecipientRef(aId, recipientId)
}

/**
 * The description a **new** position gets when an article is applied: [mapping]'s
 * buyer article name if the recipient mapping carries one, [fallback] otherwise (each dialog's
 * own pre-existing default — `article.name` for goods, `article.description ?: article.name` for
 * services — unchanged behavior for an unmapped recipient/article). Never used for an existing
 * position — callers only invoke this when there is no
 * [de.littletrotter.domain.invoice.InvoiceItem] yet.
 */
internal fun newPositionDescriptionFor(mapping: ArticleRecipientRefDto?, fallback: String): String =
    mapping?.buyerArticleName.orPlaceholder(fallback)

/** [B2bProfileFieldsSection.prefillIfBlank]'s "never overwrite" semantics, for the
 *  plain [TextField] the buyer-item-number (BT-156) uses instead of that section's widget
 *  machinery (see [buildBuyerItemNumberField]'s KDoc for why). */
internal fun prefillIfBlank(field: TextField, value: String?) {
    if (value.isNullOrBlank()) return
    if (field.value.isNullOrBlank()) field.value = value
}

/**
 * Persists the B2B item field values. For a new item ([onlyNonBlank] = true) only
 * non-blank values are written so the article-template prefill (already copied via
 * [copyB2bTemplateFor]) survives for fields the user left untouched. For an existing item all
 * values are written so the user can clear a field (blank → delete).
 */
internal fun persistB2bItemValues(
    itemEntryQueryPort: ItemEntryQueryPort,
    itemId: Long?,
    section: B2bProfileFieldsSection?,
    onlyNonBlank: Boolean,
) {
    val id = itemId ?: return
    val sec = section ?: return
    val values = if (onlyNonBlank) sec.currentValues().filterValues { it?.isNotBlank() == true }
        else sec.currentValues()
    if (values.isNotEmpty()) {
        itemEntryQueryPort.saveB2bFieldValues(B2bFieldOwnerType.INVOICE_ITEM, id, values)
    }
}
