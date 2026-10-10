package de.littletrotter.application.invoice

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.TRAVEL_LIKE_CATEGORIES
import de.littletrotter.domain.invoice.TripTaxContext
import de.littletrotter.domain.invoice.selectTripTaxRate
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.requireReferenceTaxRate
import de.littletrotter.domain.invoice.requiresReverseChargeDecision
import de.littletrotter.domain.compliance.reverseChargeEligibility
import de.littletrotter.domain.compliance.reverseChargeIneligibilityReason
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.stampForPersist
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.LocalDate

/**
 * Extracted from `InvoiceLifecycleService`: the Reverse-Charge decision workflow
 * (the standalone [setReverseChargeDecision] command and the shared re-tax logic
 * [InvoiceHeaderUpdateService.updateHeader] also needs when a header save resubmits the header's
 * current RC decision). [taxDecisionContext] is a pure snapshot of the fields that decide
 * whether the RC choice is even required/changed — shared verbatim by both callers so they can
 * never diverge on what "the tax context changed" means.
 */
/** Unpaged-enough scan size for the Reverse-Charge rule lookup by code — the tax-rate master
 *  data table never approaches this row count (see [InvoiceReverseChargeService]'s two callers). */
private const val RC_RATE_LOOKUP_PAGE_SIZE = 1000

@ApplicationScoped
class InvoiceReverseChargeService @Inject constructor(
    private val invoiceRepo: InvoiceRepository,
    private val support: InvoiceLifecycleSupport,
    private val auditActorProvider: AuditActorProvider,
    private val domainEventBus: Event<DomainEvent>,
    private val time: TimeSource,
) {

    /**
     * Applies the header toggle's decision: [decision] `true` converts every position to
     * the Reverse-Charge rule (0 % VAT), `false` restores article rules for the selected seller
     * country and otherwise that country's dated reference rate. Travel positions resolve the
     * same country reference independently of any main-service position. The UI already asked
     * "N Positionen werden umgestellt" before calling this; this method itself re-checks
     * eligibility server-side regardless (defense in depth — the toggle is disabled client-side,
     * but master data can change between page load and click).
     *
     * Mirrors [InvoiceHeaderUpdateService.updateHeader]'s optimistic-locking/read-only guards.
     * [Invoice.reverseCharge] itself is written only here and by demo/dev fixtures — never derived,
     * per the field's own KDoc.
     *
     * Per a PO decision (2026-08-30, option a), this method deliberately does NOT touch
     * [Invoice.invoiceFormat] (it used to, as a one-time INTERNATIONAL default; that coupling is
     * removed). [Invoice.invoiceFormat] is the marker for "invoiced under a foreign seller VAT
     * registration" — the DB CHECK constraint `chk_invoice_format_requires_seller_vat_registration`
     * (`V1__baseline_schema.sql`) demands [Invoice.sellerVatRegistrationCountry] be non-null
     * whenever the format is INTERNATIONAL. Reverse Charge is the exact opposite case:
     * [Invoice.reverseChargeEligibility] requires that same field be `null`. Setting INTERNATIONAL
     * here therefore made every real RC decision for RC's own supported target countries
     * (`Country.SUPPORTED_EU_B2B_TARGETS` = FR/BE/NL/LU, all outside
     * `Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES`) throw `ConstraintViolationException` on save —
     * reproduced empirically in `EInvoiceGenerationE2EIT`. The English-document need this
     * once served is still real and still solvable — via [InvoiceHeaderUpdateService.updateHeader],
     * whose `isInvoiceFormatEditable` gate already allows INTERNATIONAL while
     * [Invoice.reverseCharge] is `true` (R-CB) — it is simply no longer auto-applied from here.
     */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.REVERSE_CHARGE_DECIDED)
    fun setReverseChargeDecision(invoiceId: Long, expectedVersion: Long?, decision: Boolean): Invoice {
        val invoice = invoiceRepo.findByIdWithItems(invoiceId)
            ?: throw InvoiceNotFoundException(invoiceId)
        if (invoice.isReadOnly()) throw InvoiceImmutableException(invoice.invoiceNumber.value)
        if (expectedVersion != null && invoice.version != null && invoice.version != expectedVersion) {
            throw jakarta.persistence.OptimisticLockException(
                "Invoice $invoiceId was modified concurrently (expected version $expectedVersion, found ${invoice.version})",
            )
        }

        applyReverseChargeDecision(invoice, decision)

        invoice.recalculateTotals()
        // finding-fixes (2026-08-31, PO order) — same draft-save exemption as save() above.
        invoice.validate(requireItems = false)
        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoice.pullDomainEvents().forEach { domainEventBus.fireAsync(it) }
        return saved
    }

    /**
     * Called from [InvoiceHeaderUpdateService.updateHeader] with the tax-decision snapshot taken
     * before this save's party/seller-country changes were applied ([previousContext]), to decide
     * whether an actual RC transition (not merely an unchanged echo of the header's current
     * decision) happened this save.
     */
    fun applySubmittedReverseChargeDecision(
        invoice: Invoice,
        submitted: Boolean?,
        previousContext: TaxDecisionContext,
    ) {
        val currentContext = taxDecisionContext(invoice)
        val decisionRequired = requiresReverseChargeDecision(
            currentContext.sellerVatId,
            currentContext.recipientVatId,
            currentContext.registrationCountries,
        )
        if (decisionRequired && submitted == null) {
            throw InvalidParameterException(
                "Für unterschiedliche USt-IdNr.-Länder muss Reverse Charge ausdrücklich mit Ja oder Nein beantwortet werden.",
            )
        }
        if (submitted != true && (invoice.recipient == null || (invoice.selectedSeller ?: invoice.senderProfile) == null)) {
            invoice.reverseCharge = false
            return
        }
        val contextChanged = previousContext != currentContext
        // Precedence trap (2026-09-21): `?:` binds tighter than `&&`, so the unparenthesized form
        // parsed as `(submitted ?: !contextChanged) && invoice.reverseCharge` — an explicit YES on
        // an RC-off invoice silently computed to NO and re-normalized every position instead
        // (red InvoiceLifecycleServiceTest, wk39 dup-refactor verification run).
        val effectiveDecision = submitted ?: (!contextChanged && invoice.reverseCharge)
        // Callers (the real UI included) resubmit the invoice's current reverseCharge value on
        // every save, not just on an actual toggle, so `submitted != null` can't tell "explicit
        // decision" apart from "unchanged echo" — only an actual decision transition may trigger
        // the full re-tax below, which (correctly, for a real ON<->OFF flip) touches every item,
        // article-bound ones included.
        if (effectiveDecision != invoice.reverseCharge) {
            applyReverseChargeDecision(invoice, effectiveDecision)
        } else if (contextChanged) {
            // (R-2) — the RC decision itself didn't actually change (most commonly
            // because sellerVatRegistrationCountry alone moved), so article-bound items must NOT
            // be touched here: applySellerVatRegistration already left them exactly where V4/V8
            // require (freshly re-derived, or frozen when switching back to null). Non-article
            // main items and TRIP/fixed-price positions are the exception — they have no frozen
            // snapshot of their own and always follow the current country/RC context, so they
            // still need a refresh whenever that context changes.
            if (!effectiveDecision) refreshNonArticleMainItems(invoice)
            refreshFixedTripTaxes(invoice)
        }
    }

    /**
     * P2 — pure snapshot of the fields that decide whether an RC decision is required and
     * whether it has to be re-evaluated (invoked before and after a header save's party/seller
     * changes to detect an actual change). No dependencies: reads only [invoice]'s own current
     * state.
     */
    fun taxDecisionContext(invoice: Invoice): TaxDecisionContext {
        val seller = invoice.selectedSeller ?: invoice.senderProfile
        return TaxDecisionContext(
            sellerVatId = seller?.vatId?.value,
            recipientVatId = invoice.recipient?.vatId?.value,
            registrationCountries = seller?.additionalVatRegistrations?.map { it.country }?.toSet().orEmpty(),
            selectedRegistrationCountry = invoice.sellerVatRegistrationCountry,
            invoiceDate = invoice.invoiceDate,
        )
    }

    private fun refreshNonArticleMainItems(invoice: Invoice) {
        invoice.itemList.filter { it.category !in TRAVEL_LIKE_CATEGORIES && it.articleRef == null }
            .forEach { item -> item.applyTaxRule(resolveNormalTaxRule(invoice, item)) }
    }

    private fun applyReverseChargeDecision(invoice: Invoice, decision: Boolean) {
        if (decision) {
            val proposal = invoice.reverseChargeEligibility(time.today())
                ?: throw InvoiceValidationException(
                    InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE,
                    listOf(invoice.reverseChargeIneligibilityReason(time.today())),
                )
            val rcRate = support.taxRateRepo.findAll(0, RC_RATE_LOOKUP_PAGE_SIZE).find { it.code == proposal.ruleCode }
                ?: throw InvalidParameterException("Reverse-Charge-Steuerregel '${proposal.ruleCode}' fehlt in den Stammdaten.")
            invoice.itemList.forEach { it.applyTaxRule(rcRate) }
        } else {
            val mainItems = invoice.itemList.filter { it.category !in TRAVEL_LIKE_CATEGORIES }
            mainItems.forEach { item ->
                item.applyTaxRule(resolveNormalTaxRule(invoice, item))
            }
        }
        invoice.reverseCharge = decision
        if (!decision) refreshFixedTripTaxes(invoice)
        // invoiceFormat is deliberately left untouched here (see this method's own
        // KDoc); an INTERNATIONAL document for an RC invoice is still available, just via the
        // explicit, user-driven updateHeader path instead of an automatic default.
    }

    private fun resolveNormalTaxRule(invoice: Invoice, item: InvoiceItem): TaxRate {
        val country = invoice.sellerVatRegistrationCountry ?: Country.DE
        val articleRate = if (country == Country.DE) item.articleRef?.defaultTaxRateRef else item.articleRef?.taxVariantFor(country)
        return articleRate ?: support.taxRateRepo.requireReferenceTaxRate(country, invoice.invoiceDate)
    }

    private fun refreshFixedTripTaxes(invoice: Invoice) {
        val reverseChargeRate = if (invoice.reverseCharge) {
            val proposal = invoice.reverseChargeEligibility(time.today())
                ?: throw InvoiceValidationException(
                    InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE,
                    listOf(invoice.reverseChargeIneligibilityReason(time.today())),
                )
            support.taxRateRepo.findAll(0, RC_RATE_LOOKUP_PAGE_SIZE).find { it.code == proposal.ruleCode }
                ?: throw InvalidParameterException("Reverse-Charge-Steuerregel '${proposal.ruleCode}' fehlt in den Stammdaten.")
        } else {
            null
        }
        invoice.itemList.filter { it.category == InvoiceItemCategory.TRIP }
            .forEach { item ->
                item.applyTaxRule(
                    selectTripTaxRate(
                        TripTaxContext(invoice.reverseCharge, invoice.sellerVatRegistrationCountry, invoice.invoiceDate, reverseChargeRate),
                        support.taxRateRepo::requireReferenceTaxRate,
                    ),
                )
            }
    }
}

/** See [InvoiceReverseChargeService.taxDecisionContext]. */
data class TaxDecisionContext(
    val sellerVatId: String?,
    val recipientVatId: String?,
    val registrationCountries: Set<Country>,
    val selectedRegistrationCountry: Country?,
    val invoiceDate: LocalDate?,
)
