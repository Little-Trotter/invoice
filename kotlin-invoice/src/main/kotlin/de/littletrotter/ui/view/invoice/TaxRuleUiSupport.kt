package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.ItemDto
import de.littletrotter.application.dto.SellerVatRegistrationDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.shared.country.Country
import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.tax.ServiceType
import de.littletrotter.shared.tax.ExplicitExemption
import de.littletrotter.shared.tax.TaxRuleResolution
import de.littletrotter.shared.tax.TaxRuleResolutionRequest
import de.littletrotter.ui.component.orPlaceholder
import java.time.LocalDate

/**
 * Filters the reference-data tax rate list down to what a new invoice position may
 * actually select: active, selectable ([TaxRateDto.isSelectable] — legacy/foreign rows
 * excluded), German (only a DE seller is resolved at all by the tax rule resolver) and valid on
 * [date]. The
 * legacy foreign rates (FR/LU/BE/NL/GB) never appear here regardless of [TaxRateDto.isActive],
 * since their `country` is never DE.
 *
 * [date] is required rather than defaulting to a fixed anchor: a plain UI function may not read
 * the clock itself (TimeSourceArchitectureTest), so every caller threads its own
 * `TimeSource.today()` through.
 */
fun selectableTaxRates(all: List<TaxRateDto>, date: LocalDate): List<TaxRateDto> =
    all.filter { it.country == Country.DE && it.isActive && it.isSelectable && it.isApplicableFor(date) }

/**
 * "Name – Kurzgrund (Satz %, Kategorie)": the label a rule-aware tax ComboBox shows,
 * replacing the old bare "19 % (DE)".
 */
fun taxRuleLabel(rate: TaxRateDto): String {
    val percent = rate.rate.value.stripTrailingZeros().toPlainString()
    val category = rate.categoryEnum
    val reason = rate.exemptionReasonText.orPlaceholder(category.shortReasonFallback())
    return "${rate.name} – $reason ($percent %, ${category.code})"
}

// ── Wiring TaxRuleResolver into the UI ──────────────────────────────────────────────────────
//
// The resolver is pure (domain/validation/TaxRuleResolver.kt) and was already fully correct;
// nothing in kotlin-invoice ever called it (see G1UiApiBlockingTest). These functions build its
// input from what a dialog actually has and turn its output into something a ComboBox
// pre-selection / hint text can use.

/** A goods position is never resolved automatically (a goods case is
 *  an UNSUPPORTED_TAX_CASE error, never a silent default) — everything else on an invoice is treated
 *  as the one supported case, [ServiceType.GENERAL_SERVICE]. */
fun InvoiceItemCategory.toServiceType(): ServiceType =
    if (this == InvoiceItemCategory.PRODUCT) ServiceType.UNSUPPORTED else ServiceType.GENERAL_SERVICE

/**
 * Builds [TaxRuleResolutionRequest] from live master data rather than [invoice]'s own read-model
 * fields — [InvoiceDetailDto.recipient] never carries `customerType`/`vatIdVerificationStatus`/
 * country, only what [de.littletrotter.application.dto.PartyDto] projects (name/vatId/contact
 * fields). Both are plain columns [RecipientQueryPort.findRecipientById] already projects onto
 * [de.littletrotter.application.dto.RecipientSummaryDto], reachable through [recipientQueryPort]
 * without any new port method.
 *
 * Takes [recipientQueryPort] as an explicit parameter rather than an `InvoiceDetailView` receiver
 * so both this package's callers — [InvoiceCockpitHero]'s tax-rule status strip and
 * [TaxRuleProposalPanel] (via the goods/service item dialogs, which still hold a full
 * `InvoiceDetailView` reference) — can call it without depending on the view itself.
 *
 * Returns `null` when there isn't enough master data yet to resolve anything (no recipient, or
 * one whose Kundentyp/Land the user hasn't filled in) — callers fall back to the earlier
 * behavior of a plain, unassisted rate list. The seller side is always [Country.DE]: this
 * application has no multi-seller-country support (README Zielbild — "ein deutsches
 * Unternehmen"), and the resolver itself blocks on anything else as its very first check.
 *
 * Also `null` while the invoice is billed under a foreign seller VAT registration
 * ([InvoiceDetailDto.sellerVatRegistrationCountry] non-null): the tax rule
 * resolver's recipient-context derivation must make no claim at all there — items pull their tax from the
 * article's country variants instead. Mirrors the domain twin
 * `Invoice.taxRuleResolutionRequestOrNull` (TaxCategoryConsistency.kt), which
 * carries the same guard for the same reason.
 */
fun buildTaxRuleResolutionRequest(
    recipientQueryPort: RecipientQueryPort,
    invoice: InvoiceDetailDto,
    today: LocalDate,
    serviceType: ServiceType = ServiceType.GENERAL_SERVICE,
    explicitExemption: ExplicitExemption? = null,
): TaxRuleResolutionRequest? {
    if (invoice.sellerVatRegistrationCountry != null) return null
    val recipientId = invoice.recipient?.id ?: return null
    val recipient = recipientQueryPort.findRecipientById(recipientId) ?: return null
    val customerType = recipient.customerType ?: return null
    val buyerCountry = Country.fromVatIdPrefix(recipient.vatId) ?: return null
    val sellerVatId = invoice.sender?.vatId
    return TaxRuleResolutionRequest(
        serviceType = serviceType,
        // A draft invoice's date can be genuinely unset yet; falls back to the real "today"
        // (threaded in by the caller) rather than a frozen anchor.
        serviceDate = invoice.invoiceDate ?: today,
        sellerCountry = Country.DE,
        buyerCountry = buyerCountry,
        customerType = customerType,
        sellerVatId = sellerVatId,
        buyerVatId = recipient.vatId,
        buyerVatIdVerificationStatus = recipient.vatIdVerificationStatus,
        explicitExemption = explicitExemption,
    )
}

/** The [TaxRateDto] row a [TaxRuleResolution.Proposed] refers to — `null` for [TaxRuleResolution
 *  .Blocked], or if the proposed rule code isn't (yet) among [available] (e.g. deactivated). */
fun TaxRuleResolution.matchingRate(available: List<TaxRateDto>): TaxRateDto? =
    (this as? TaxRuleResolution.Proposed)?.let { proposed -> available.find { it.code == proposed.ruleCode } }

/** `E`/`O` never inherit a one-size-fits-all reason from the [TaxRateDto] seed row
 *  alone; the dialog must collect a per-invoice reason and legal basis before the selection is
 *  usable. */
fun requiresExplicitExemptionDetails(rate: TaxRateDto?): Boolean =
    rate?.categoryEnum == VatCategory.EXEMPT || rate?.categoryEnum == VatCategory.OUTSIDE_SCOPE

fun exemptionDetailsIncomplete(reason: String, legalBasis: String): Boolean =
    reason.isBlank() || legalBasis.isBlank()

/** Snapshotted items are stale when resolver proposes different category; compares category not code
 *  because resolver only knows one rate per category. */
fun isProposalStale(item: ItemDto, resolution: TaxRuleResolution?): Boolean {
    val storedCategory = item.taxCategoryEnum() ?: return false
    return resolution is TaxRuleResolution.Proposed && resolution.category != storedCategory
}

/** The RC rule for a trip preview, independent of a main-service position. */
internal fun reverseChargeTaxRateFor(items: List<ItemDto>, allRates: List<TaxRateDto>): TaxRateDto? {
    val storedRateIds = items.mapNotNull { it.taxRateRefId }.toSet()
    return allRates.firstOrNull { it.id in storedRateIds && it.categoryEnum == VatCategory.REVERSE_CHARGE }
        ?: allRates.singleOrNull { it.isActive && it.categoryEnum == VatCategory.REVERSE_CHARGE }
}

/**
 * Which rule a position should carry, given the recipient context (the tax rule resolver's proposal) and the
 * article the user picked. Resolves the collision between two orthogonal dimensions that used to
 * fight over one field:
 *
 * - **Taxability** (`S`/`AE`/`E`/`O`) follows the *recipient* — the same service is 19 % at home
 *   and Reverse Charge for a verified EU business.
 * - **Rate within `S`** (19 % vs. 7 %) follows the *supply itself* (§ 12 Abs. 2 UStG) and is
 *   therefore a property of the article.
 *
 * Before this, picking an article overwrote the resolver's proposal outright
 * (`taxGroup.value = article.defaultTaxRateRef`), so a 19 % article silently cancelled a Reverse
 * Charge proposal — the requirement that "ArticleView darf eine Regel als Vorschlag speichern,
 * umgeht aber die Kontextprüfung nicht" was not met. Simply letting the resolver always win
 * would have been just as wrong: it would force every domestic invoice to 19 % and destroy the
 * reduced rate.
 */
/** Under detekt's `LongParameterList` threshold — the three candidate rates [effectiveTaxRuleFor] ranks against each
 *  other: [proposed] (the tax rule resolver's own proposal), [articleDefault] (the selected article's
 *  catalogue rate) and [fallback] (the last resort when neither applies). One ranked set, not an
 *  arbitrary bag — every branch below reads exactly this priority order. */
data class TaxRateCandidates(
    val proposed: TaxRateDto?,
    val articleDefault: TaxRateDto?,
    val fallback: TaxRateDto?,
)

fun effectiveTaxRuleFor(
    category: InvoiceItemCategory,
    resolution: TaxRuleResolution?,
    candidates: TaxRateCandidates,
): TaxRateDto? {
    val proposedRate = candidates.proposed
    val articleDefault = candidates.articleDefault
    val fallback = candidates.fallback
    return when {
        // Goods: the resolver covers general services only and blocks every Warenfall by
        // construction (ServiceType.UNSUPPORTED). That is not a data gap the user could close, so
        // it must not clear the field — the article/default decides, and the panel's hint
        // explains why no proposal exists.
        category == InvoiceItemCategory.PRODUCT -> articleDefault ?: fallback
        // No proposal at all (recipient master data incomplete) — nothing to enforce, keep the
        // pre-existing behavior of trusting the article.
        resolution == null -> articleDefault ?: fallback
        // The resolver blocked a case it *should* have resolved (third country, unverified VAT id, special
        // place of supply): no silent default. The blocking hint is already on screen.
        resolution is TaxRuleResolution.Blocked -> null
        // Domestic/EU-B2C standard rate: the article decides 19 % vs. 7 % (§ 12 Abs. 2 UStG).
        proposedRate?.categoryEnum == VatCategory.STANDARD && articleDefault?.categoryEnum == VatCategory.STANDARD ->
            articleDefault
        // AE/E/O: taxability wins outright, the article's rate is irrelevant — those categories
        // are 0 % by definition (chk_tax_rate_zero_rate_categories).
        else -> proposedRate ?: articleDefault ?: fallback
    }
}

/**
 * Which rules a position of [category] may pick at all — so the tax ComboBox never offers a rule
 * that cannot apply to this kind of position.
 *
 * Goods see only the domestic standard/reduced rates. The four EU/exemption rules seeded as
 * reference data are
 * service rules by construction (`EU_B2B_RC_SERVICE`, `EU_B2C_DE_STANDARD_19`,
 * `DE_EXEMPT_SERVICE`, `OUTSIDE_SCOPE_SERVICE` — and the resolver blocks goods outright), and
 * offering "EU-B2C Dienstleistung" for a hydraulic pump is exactly the confusion this filter
 * removes. Referencing the codes here mirrors what [de.littletrotter.shared.tax.TaxRuleResolver] already does — it decides on
 * the same literal codes; a data-driven successor would be a `scope` column on `tax_rate` (the
 * reference-data seed's own area, not this one's).
 *
 * Travel-like positions are not covered here at all: they resolve the invoice's selected seller
 * country reference, or the explicit Reverse-Charge rule, through the trip workflow.
 */
private val SERVICE_ONLY_RULE_CODES = setOf(
    "EU_B2B_RC_SERVICE",
    "EU_B2C_DE_STANDARD_19",
    "DE_EXEMPT_SERVICE",
    "OUTSIDE_SCOPE_SERVICE",
)

fun selectableTaxRatesFor(
    category: InvoiceItemCategory,
    all: List<TaxRateDto>,
    date: LocalDate,
): List<TaxRateDto> {
    val selectable = selectableTaxRates(all, date)
    return if (category == InvoiceItemCategory.PRODUCT) {
        selectable.filter { it.code !in SERVICE_ONLY_RULE_CODES }
    } else {
        selectable
    }
}

/**
 * The single Reverse-Charge item rule, looked up by category rather than a hardcoded
 * code string, in the *full* (unfiltered) catalog passed in: a goods dialog's own
 * [selectableTaxRatesFor] list excludes the service-only rule codes ([SERVICE_ONLY_RULE_CODES]),
 * but the invoice-level header decision overrides that scoping — every position, goods included,
 * is forced to this rule while the decision is ON. `null` only if reference data has no such row
 * at all (misconfigured seed data); dialogs then leave the tax ComboBox unset rather than guess.
 */
fun reverseChargeTaxRate(allTaxRates: List<TaxRateDto>): TaxRateDto? =
    allTaxRates.find { it.categoryEnum == VatCategory.REVERSE_CHARGE }

/**
 * VAT: which of the seller's [registrations] to pre-select for an invoice going to
 * [recipientCountry], or `null` when none should be — pure and UI-free so its acceptance table
 * is a plain unit test, not a Karibu one.
 *
 * `null` is not a fallback path here, it is the correct answer for the common case: a domestic
 * recipient, a recipient without a resolved billing-address country yet, or a recipient in a
 * country the seller holds no registration in. That last case matters beyond convenience — see
 * `InvoiceDetailViewOverview`'s `currentReverseChargeProposal`:
 * a non-null `sellerVatRegistrationCountry` disables reverse charge, so resolving unconditionally
 * (e.g. to the seller's home country) would silently switch cross-border B2B invoices off it.
 *
 * [registrations] is expected to carry at most one row per country (enforced when the seller's
 * registrations are saved and mirrored by a DB unique index) — [firstOrNull] is a defensive
 * tie-break, not a modeled case. Works on the read model ([SellerVatRegistrationDto]'s
 * ISO-alpha-2 `countryAlpha2`); `InvoiceEntryPartyCard` reads the sender only through
 * `SenderQueryPort`/`SenderSummaryDto`.
 */
fun resolveSellerVatRegistrationCountryFromDto(
    registrations: List<SellerVatRegistrationDto>,
    recipientCountry: Country?,
): Country? {
    if (recipientCountry == null) return null
    return registrations.firstOrNull { it.countryAlpha2.equals(recipientCountry.alpha2, ignoreCase = true) }
        ?.let { Country.fromAlpha2(it.countryAlpha2) }
}

/** `internal` (not `private`) since the B2B ENUM field's ComboBox (`B2bFieldWidgetFactory`) reuses these
 *  same short German category names as its VAT_CATEGORY_5305 curated favorites — the one place
 *  this project already curates a label per [VatCategory], not authored twice. */
internal fun VatCategory.shortReasonFallback(): String = when (this) {
    VatCategory.STANDARD -> "Regelsteuersatz"
    VatCategory.ZERO_RATED -> "Nullsatz"
    VatCategory.EXEMPT -> "Steuerbefreit"
    VatCategory.REVERSE_CHARGE -> "Reverse Charge"
    VatCategory.OUTSIDE_SCOPE -> "Nicht steuerbar"
    VatCategory.FREE_EXPORT_ITEM -> "Ausfuhr"
    VatCategory.INTRA_COMMUNITY_SUPPLY -> "Innergemeinschaftliche Lieferung"
}
