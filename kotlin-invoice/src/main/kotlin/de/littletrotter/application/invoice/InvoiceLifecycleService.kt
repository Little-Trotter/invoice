package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.masterdata.invoiceNoteFor
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.event.InvoiceCreated
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceStatusTransitionException
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.RecipientNotFoundException
import de.littletrotter.domain.exception.SenderProfileNotFoundException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.compliance.assertReverseChargeRecipientEligibility
import de.littletrotter.domain.compliance.reverseChargeEligibility
import de.littletrotter.domain.compliance.reverseChargeIneligibilityReason
import de.littletrotter.domain.invoice.derivedDueDate
import de.littletrotter.domain.invoice.ensureBuyerReferenceForFinalization
import de.littletrotter.domain.invoice.ensureInvoiceDateNotBeforeServiceEnd
import de.littletrotter.domain.invoice.validateHeaderLinePeriodConsistency
import de.littletrotter.domain.invoice.validateReverseChargeItemConsistency
import de.littletrotter.domain.invoice.PartySnapshotRefreshPreview
import de.littletrotter.domain.invoice.deriveSellerVatRegistrationCountry
import de.littletrotter.domain.invoice.requiresReverseChargeDecision
import de.littletrotter.domain.invoice.previewPartySnapshotRefresh
import de.littletrotter.domain.invoice.initialInvoiceNoteSnapshot
import de.littletrotter.domain.event.PartySnapshotsRefreshed
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.stampForPersist
import de.littletrotter.domain.compliance.MixedTaxCategoryException
import de.littletrotter.domain.compliance.hasMixedReverseChargeAndStandard
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import de.littletrotter.domain.invoice.InvoiceDraft
import de.littletrotter.domain.invoice.InvoiceParties
import de.littletrotter.domain.invoice.InvoiceHeaderChange
import de.littletrotter.domain.invoice.InvoicePartySnapshot
import de.littletrotter.domain.invoice.freezeRegistrationTaxNumber
import de.littletrotter.domain.invoice.headerChange
import de.littletrotter.domain.invoice.canTransitionTo

/** The two master-data repositories [InvoiceLifecycleService]/[de.littletrotter.infrastructure
 *  .dev.DevDataFixtures] read sender/recipient identity from — bundled into one CDI bean
 *  (same idiom as [InvoiceLifecycleSupport]: a plain data class has no bean-defining annotation,
 *  so ARC cannot resolve it as a constructor injection point) purely to keep those constructors
 *  under detekt's `LongParameterList` threshold; each field is otherwise unrelated to the others. [consumerNoteResolver] rides along because
 *  it derives a note default from exactly these two parties. */
@ApplicationScoped
class PartyRepositories @Inject constructor(
    val senderRepo: SenderProfileRepository,
    val recipientRepo: RecipientRepository,
    val consumerNoteResolver: ConsumerNoteResolver,
)

// Every mutating method here writes invoice/invoice_item, both captured by
// invoice_audit_journal; SetsAuditGuc/AuditGucInterceptor sets the app.actor/app.actor_name/
// app.audit_action GUCs the capture trigger reads before each method body's own @Transactional
// work runs. The action moved to each individual method below so every one declares its
// own business action instead of sharing one class-level marker.
@ApplicationScoped
class InvoiceLifecycleService @Inject constructor(
    private val invoiceRepo: InvoiceRepository,
    private val parties: PartyRepositories,
    // Aggregate events go out after commit so async observers read the committed row, not the pre-commit one.
    private val eventDispatcher: DomainEventDispatcher,
    private val auditActorProvider: AuditActorQueryPort,
    // Z5 — the narrow, occasionally-used lookups (address resolution, payment term/tax
    // rate/per-diem rate/b2b profile repositories, the UI paging default) this service needs but
    // that aren't part of its own core identity. See InvoiceLifecycleSupport's own KDoc.
    private val support: InvoiceLifecycleSupport,
    // The finalization-gate orchestration this class's own transition() still owns
    // directly (create/transition/finalization is the lifecycle logic this class keeps); the
    // item-mutation/header-update/reverse-charge-decision collaborators it used to delegate to
    // moved out entirely (see class KDoc) with InvoiceLifecycleCollaborators removed.
    private val finalizationPolicy: FinalizationPolicy,
    private val time: TimeSource,
) {
    private val senderRepo get() = parties.senderRepo
    private val recipientRepo get() = parties.recipientRepo

    fun findById(id: Long): Invoice? = invoiceRepo.findByIdWithItems(id)

    // Return bounded lists for reference data to avoid accidental full-table loads in UI
    fun findAllRecipients(): List<Recipient> = recipientRepo.findAll(0, defaultPageSize())

    fun findAllSenders(): List<SenderProfile> = senderRepo.findAll(0, defaultPageSize())

    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.INVOICE_CREATED)
    fun create(senderProfileId: Long, recipientId: Long, reverseChargeDecision: Boolean? = null): Invoice {
        val sender = senderRepo.findById(senderProfileId)
            ?: throw SenderProfileNotFoundException(senderProfileId)
        val recipient = recipientRepo.findById(recipientId)
            ?: throw RecipientNotFoundException(recipientId)
        val registrationCountries = sender.additionalVatRegistrations.map { it.country }.toSet()
        if (requiresReverseChargeDecision(sender.vatId?.value, recipient.vatId?.value, registrationCountries) && reverseChargeDecision == null) {
            throw InvalidParameterException("Für unterschiedliche USt-IdNr.-Länder muss Reverse Charge ausdrücklich mit Ja oder Nein beantwortet werden.")
        }

        val invoice = draftInvoice(sender, recipient, registrationCountries)

        // Only validate skeleton fields at creation – items are added later in the detail view.
        requireNotNull(invoice.senderProfile) { "Sender profile is required." }
        requireNotNull(invoice.recipient) { "Recipient is required." }

        // P2 — freezes the seller/buyer identity as it stood at creation (Compliance-Matrix
        // Checkpoint 4); a later master-data edit must never leak into this invoice.
        invoice.applyPartySnapshots(
            InvoicePartySnapshot.forSeller(sender),
            InvoicePartySnapshot.forBuyer(recipient, invoice.references.buyerReference),
        )

        if (reverseChargeDecision == true) {
            if (invoice.reverseChargeEligibility(time.today()) == null) {
                throw InvoiceValidationException(
                    InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE,
                    listOf(invoice.reverseChargeIneligibilityReason(time.today())),
                )
            }
            invoice.decideReverseCharge(true)
        }

        // N5 — the actual assignment now happens here, not at the ISSUED edge (see the class-level
        // note above and support.numberAssigner's own KDoc). senderProfile is already set, which
        // is all assignIfNeeded needs; it is idempotent, so this is safe even if a future caller
        // ever pre-assigns a number before calling create().
        support.numberAssigner.assignIfNeeded(invoice)

        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        eventDispatcher.publishAfterCommit(InvoiceCreated(saved.id!!, saved.invoiceNumber.value))
        return saved
    }

    /**
     * Resolves everything a new draft derives from its sender and recipient (addresses, document
     * language, payment term, due date, seller VAT registration, note defaults) and creates it
     * through [Invoice.draft].
     */
    private fun draftInvoice(sender: SenderProfile, recipient: Recipient, registrationCountries: Set<Country>): Invoice {
        // N5 — every invoice is numbered from the moment it exists, mirroring the ST-3
        // cancellation/credit-note precedent (InvoiceCancellationService.createCreditNote), which has
        // always numbered its document at creation. Gaplessness no longer comes from
        // deferring the draw to ISSUED (a superseded argument) — it comes from
        // InvoiceLifecycleService no longer offering a hard delete: a discarded draft is
        // voided, never removed, so its number and row stay on record.
        val invoiceDate = time.today()

        // In this domain model, billingAddress belongs to the supplier (sender).
        val billingAddressCandidate = support.addressManagementService.getBillingAddressForSender(sender).orElse(sender.billingAddress)
        val deliveryAddressCandidate = support.addressManagementService.getDeliveryAddressForCustomer(recipient).orElse(recipient.deliveryAddress)

        // Taken from the recipient's flag once here and never touched again; see the KDoc on
        // Invoice.documentLanguage for why a later master-data change must not affect it.
        val recipientCountryValue = (recipient.billingAddress ?: deliveryAddressCandidate)?.country
        val documentLanguage = recipient.documentLanguage

        // Anbieter-Standardzahlungsbedingung: the sender's own default, falling back to the
        // single global PaymentTerm.isDefault row for senders that never configured one —
        // same "overridable afterwards, master data untouched" shape as buyerReference below.
        val paymentTerm = sender.defaultPaymentTerm ?: support.paymentTermRepo.findDefault()

        // dueDate must be derived from whichever
        // PaymentTerm just got resolved above, not from Invoice.paymentTermsDays' class
        // default (14): a sender whose own default term carries a different dueInDays (e.g.
        // 30) used to get a dueDate computed against the wrong day-count from the very first
        // save, disagreeing with invoice.paymentTerm from the moment of creation (reproduced
        // in InvoiceCreateForCustomerE2EIT's own fixture). The class default is only the fallback for
        // the one case no term resolves at all (no sender default, no global default row).
        // derivedDueDate never returns null here: invoiceDate (just set above) is
        // non-nullable, and derivedDueDate only returns null for a null invoiceDate.
        val dueDate = derivedDueDate(invoiceDate, paymentTerm?.dueInDays ?: Invoice.DEFAULT_PAYMENT_TERMS_DAYS)!!

        return Invoice.draft(
            InvoiceDraft(
                parties = InvoiceParties(
                    senderProfile = sender,
                    recipient = recipient,
                    billingAddress = billingAddressCandidate,
                    deliveryAddress = deliveryAddressCandidate,
                    // Rule 1 — derives the invoice's own B2B profile from the recipient's standing
                    // default the moment it is first assigned; `null` (no customer default) is a fully
                    // valid outcome (rule 3, Community behavior).
                    b2bProfile = recipient.b2bProfile,
                    paymentTerm = paymentTerm,
                    documentLanguage = documentLanguage,
                ),
                header = InvoiceHeaderChange(
                    invoiceDate = invoiceDate,
                    dueDate = dueDate,
                    // R-1 — automatic adoption of the seller's foreign VAT registration: if
                    // the sender already holds a registration for the recipient's country, this invoice
                    // (and every article-bound position added to it) is taxed under that registration
                    // from the start, no manual header edit needed. Reuses recipientCountryValue, the
                    // exact same "recipient country" documentLanguage above just resolved. No items exist
                    // yet at creation, so no re-application of item taxes is needed here (applySeller
                    // CountryTaxVariant only ever runs once an article-bound position exists).
                    sellerVatRegistrationCountry = deriveSellerVatRegistrationCountry(
                        recipientCountry = recipientCountryValue,
                        sellerRegistrationCountries = registrationCountries,
                    ),
                ),
                invoiceNote = initialInvoiceNoteSnapshot(sender.id, documentLanguage, sender.invoiceNoteFor(documentLanguage)),
                consumerNote = parties.consumerNoteResolver.initialSnapshot(sender, documentLanguage, recipient.customerType),
                // P2 — BT-10 defaults from the recipient's standing Leitweg-ID, overridable per
                // invoice afterwards (updateHeader) without ever touching the recipient's own field.
                buyerReference = recipient.routingId,
            ),
        )
    }

    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.HEADER_CHANGED)
    fun save(invoice: Invoice): Invoice {
        invoice.recalculateTotals()
        // finding-fixes (2026-08-31, PO order) — a draft save must stay possible before the
        // first position exists (see Invoice.validate's own KDoc); document generation still
        // requires items, via its own validate() call (default requireItems = true).
        invoice.validate(requireItems = false)
        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoice.pullDomainEvents().forEach { eventDispatcher.publishAfterCommit(it) }
        return saved
    }

    /**
     * Recomputes and persists item/invoice totals without gating on [Invoice.validate].
     *
     * Task A.2: a rechnung with an unrelated validation problem (e.g. a missing recipient
     * address) must still be recalculable — [save] intentionally still rejects an invalid
     * invoice outright, so this is a separate, narrower operation rather than a relaxation
     * of [save]'s contract.
     *
     * Task S: this is the single place a stored invoice's totals get recomputed and persisted
     * — [de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService]'s
     * bulk recalculation loops over invoices and calls this per invoice rather than duplicating
     * the recalc-and-save logic.
     *
     * N15 (PO decision 2026-09-11 evening) — an issued invoice is never a legitimate target: a
     * correctly-issued invoice's totals never need a post-issue rewrite, since
     * [Invoice.recalculateTotals] already ran before the freeze applied. There used to be an
     * `allowReadOnly` escape hatch for the bulk/demo path; that path now skips read-only invoices
     * itself instead ([de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService.performRecalculateTotals]),
     * so this method always enforces the guard, same as every other mutating method here.
     *
     * TS-8 — deliberately not [RequiresPermission]-guarded, unlike this class's other mutating
     * methods: [PostgresDatabaseMaintenanceService][de.littletrotter.infrastructure.persistence.PostgresDatabaseMaintenanceService]'s
     * `performRecalculateTotals` (guarded with [AppPermission.DATABASE_MAINTENANCE], an admin-only
     * permission) calls this per invoice — gating it on [AppPermission.INVOICE_FULL_EDIT] too would
     * make bulk recalculation impossible for an admin-only caller with no invoicing role.
     */
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECALCULATED)
    fun recalculate(invoiceId: Long): Invoice {
        val invoice = invoiceRepo.findByIdWithItems(invoiceId)
            ?: throw InvoiceNotFoundException(invoiceId)
        invoice.recalculateTotals()
        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoice.pullDomainEvents().forEach { eventDispatcher.publishAfterCommit(it) }
        return saved
    }

    /**
     * [overrideReason] is only meaningful (and only ever consulted) when the invoice's
     * `ValidationStatus` is `UNAVAILABLE`; see [FinalizationPolicy.enforceFinalizationGate]. Every
     * other target status ignores it entirely — the gate itself only runs for `target ==
     * ISSUED` (an already-`ISSUED` invoice never reaches here because
     * `canTransitionTo` is already `false` for it, RV-02). A plain `String?` rather than a full
     * `FinalizationOverride` at this API boundary: the actor is this app's existing
     * [auditActorProvider] (no user-identity system exists to ask otherwise), the caller only
     * supplies the begründung.
     */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.STATUS_CHANGED)
    fun transition(invoiceId: Long, target: InvoiceStatus, overrideReason: String? = null): Invoice {
        // Row lock: a user transition and the SEND job's transition to ISSUED serialise here, so the
        // second one reads the first one's committed status and fails the legality check below
        // instead of both passing it on the same stale state.
        val invoice = invoiceRepo.findByIdWithItemsForStatusChange(invoiceId)
            ?: throw InvoiceNotFoundException(invoiceId)
        if (!invoice.canTransitionTo(target)) throw InvoiceStatusTransitionException(invoice.status.name, target.name)
        if (target != InvoiceStatus.ISSUED) finalizationPolicy.enforceNoSendInFlight(invoice)
        if (target == InvoiceStatus.ISSUED) {
            // Any position of a draft can be deleted, so an invoice without positions is a
            // reachable state; it must never be issued. Checked before the first mutation below so
            // the rejection leaves nothing stamped (the transaction rolls back regardless).
            if (invoice.itemList.isEmpty()) throw InvoiceValidationException(InvoiceValidationError.NO_ITEMS)
            // PO decision 2026-08-31 (finding-fixes) — captured before the stamp below overwrites
            // invoiceDate: recomputeDueDateForSend (called once the payment-term snapshot exists,
            // a few lines down) needs both the draft's own invoiceDate/dueDate to either re-derive
            // dueDate from the frozen payment term or preserve the draft's own day-gap.
            val previousInvoiceDate = invoice.invoiceDate
            val previousDueDate = invoice.dueDate
            // PO decision 2026-08-31 — the invoice date (BT-2) is stamped with "today" exactly
            // once, right here at the → ISSUED transition, overwriting whatever the draft carried
            // (a future-dated or stale draft date is legal right up until send, see
            // ensureInvoiceDateNotBeforeServiceEnd's own KDoc). Deliberately the very first thing
            // this branch does: every check below it — most of all the send-date gate two lines
            // down — must see the FINAL invoiceDate this invoice is actually sent with, not
            // whatever the entry pane last saved.
            invoice.updateHeader(invoice.headerChange().copy(invoiceDate = time.today()))
            // W7 — freezes the payment-term values (name, discount terms, …) this invoice was
            // finalized with. A later edit to the master-data PaymentTerm row must not leak into
            // this now-ISSUED document (same rule as the P2 party snapshots). No-op when no payment
            // term was ever chosen. finding-fixes (2026-08-31) — moved up from right next to the
            // number assignment below to right after the invoiceDate stamp: recomputeDueDateForSend
            // needs it, and BOTH must be fully consistent BEFORE the number assignment runs
            // its native counter-table query, which forces Hibernate to auto-flush this entity's
            // current dirty state — the exact ordering NumberJournalReportServiceIT caught, back
            // when a DB check constraint still rejected a dueDate that preceded an already-stamped
            // invoiceDate. The constraint is gone (the due date is a plausibility rule, not a
            // statutory one), but the ordering still matters: the two dates must be consistent
            // before the flush, or the sent invoice carries a stale due date.
            invoice.freezePaymentTermSnapshot()
            invoice.freezeRegistrationTaxNumber()
            // The due date follows the invoice date: stamping invoiceDate forward to "today"
            // above would otherwise leave dueDate at whatever the draft computed against its OLD
            // invoiceDate — wrong for any invoice whose draft is older than its send day, and now
            // merely a warning rather than a rejection, which makes recomputing it here the only
            // thing that keeps it right. Runs right after freezePaymentTermSnapshot so it can prefer the
            // just-frozen payment-term day-count; see recomputeDueDateForSend's own KDoc for the
            // three-case derivation (snapshot days / preserved day-gap / never had a due date).
            invoice.recomputeDueDateForSend(previousInvoiceDate, previousDueDate)
            // BR-DE-15/PEPPOL-EN16931-R008 — checked first: a missing BT-10 is a structural
            // data-completeness defect (like the mixed-tax check below), not a validation-run
            // outcome, so it blocks regardless of gateStage and before the gate even runs.
            invoice.ensureBuyerReferenceForFinalization()
            // N5 — defense-in-depth only: since create() now draws the number immediately
            // (InvoiceLifecycleService.create()), every invoice reaching this transition already
            // carries one, and assignIfNeeded's own idempotency check (never re-numbers an
            // already-numbered document) makes this call a no-op in the normal case. Kept as the
            // safety net for any row that reached ISSUED still unnumbered (e.g. pre-N5 data).
            // invoiceDate/dueDate are already fully consistent by this point (see the
            // finding-fixes note above) — this call's own native counter query is exactly what
            // forces the auto-flush that would otherwise catch them half-updated.
            support.numberAssigner.assignIfNeeded(invoice)
            // Checked ahead of the gate itself: a category mix is a structural
            // invoice defect, not a validation-run outcome, so it blocks regardless of gateStage.
            if (invoice.itemList.hasMixedReverseChargeAndStandard()) throw MixedTaxCategoryException()
            // The invoice header's own Reverse-Charge decision is now the leading fact;
            // positions must follow it (validateReverseChargeItemConsistency, naming any position
            // that disagrees) and, when the decision is ON, the recipient must still qualify
            // (assertReverseChargeRecipientEligibility, e.g. after a later master-data edit). Same
            // structural nature as the mix check above, so also ahead of the gate and independent
            // of gateStage. Supersedes the earlier recipient-context-derived check this replaced:
            // that one inferred the expected category from the recipient and enforced it
            // regardless of user choice, which is exactly the behavior this inverted.
            invoice.validateReverseChargeItemConsistency()
            invoice.assertReverseChargeRecipientEligibility(time.today())
            // Same structural nature as the two checks above: a header period that
            // contradicts the line periods is a data-entry defect, not a validation-run outcome,
            // so it blocks unconditionally and ahead of the staged gates below (see
            // validateHeaderLinePeriodConsistency's own KDoc for why this isn't a
            // ComplianceRuleSet entry).
            invoice.validateHeaderLinePeriodConsistency()
            // PO decision 2026-08-31 / § 14 Abs. 4 Nr. 6 UStG — same structural nature as the
            // period-consistency check right above: a service end that lies after the (now
            // just-stamped, see the top of this branch) invoice date is a real, unconditional
            // legal defect, not a validation-run outcome, so it blocks the same way — regardless
            // of gateStage/complianceStage and before either staged gate below runs.
            invoice.ensureInvoiceDateNotBeforeServiceEnd()
            // Moved here from the removed sender-form save-time check (SenderProfileView
            // no longer has a profile selector to check against, decision 12): a mandatory
            // SELLER-placed field of this invoice's own profile is a structural data-completeness
            // defect just like the buyer-reference/tax checks above, so it blocks the same way —
            // unconditionally, ahead of the staged compliance gates.
            // Last line of defence behind the entry-form checks: the PDF engine refuses a character
            // its fonts lack, which would leave the already issued invoice without a document.
            finalizationPolicy.enforceRenderableText(invoice)
            finalizationPolicy.enforceMandatorySellerFields(invoice)
            // N9 — BT-42 (BR-DE-6/BR-DE-27): same structural nature as the checks above, so also
            // unconditional and ahead of the staged compliance gates below.
            finalizationPolicy.enforceSellerTelephoneNumber(invoice)
            finalizationPolicy.enforceFinalizationGate(invoice, overrideReason, auditActorProvider.currentActor())
            finalizationPolicy.enforceComplianceRules(invoice)
        }
        invoice.transitionTo(target)
        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoice.pullDomainEvents().forEach { eventDispatcher.publishAfterCommit(it) }
        return saved
    }

    /**
     * P2 — "Stammdaten erneut übernehmen", read-only step: what would change if [invoiceId]'s
     * frozen seller/buyer snapshot were replaced from its CURRENT senderProfile/recipient master
     * data. Computing this never writes anything, so the UI can show it before the user confirms.
     */
    fun previewPartySnapshotRefresh(invoiceId: Long): PartySnapshotRefreshPreview {
        val invoice = invoiceRepo.findByIdWithItems(invoiceId) ?: throw InvoiceNotFoundException(invoiceId)
        return invoice.previewPartySnapshotRefresh()
    }

    /**
     * P2 — actually replaces [invoiceId]'s seller/buyer snapshot rows with a fresh snapshot of
     * its current master data. Blocked on a finalized/cancelled invoice the same way any other
     * header edit is (GoBD) — see [InvoiceHeaderUpdateService.updateHeader]'s identical guard.
     * Fires [PartySnapshotsRefreshed] as the audit trail ("audit the result").
     */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.RECIPIENT_CHANGED)
    fun refreshPartySnapshots(invoiceId: Long): Invoice {
        val invoice = invoiceRepo.findByIdWithItems(invoiceId) ?: throw InvoiceNotFoundException(invoiceId)
        val sender = requireNotNull(invoice.senderProfile) { "Invoice $invoiceId has no senderProfile." }
        val recipient = requireNotNull(invoice.recipient) { "Invoice $invoiceId has no recipient." }
        val preview = invoice.previewPartySnapshotRefresh()

        invoice.applyPartySnapshots(
            InvoicePartySnapshot.forSeller(sender),
            InvoicePartySnapshot.forBuyer(recipient, invoice.references.buyerReference),
        )
        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        val changedFields = (preview.sellerChanges + preview.buyerChanges).map { it.field }
        eventDispatcher.publishAfterCommit(PartySnapshotsRefreshed(saved.id!!, saved.invoiceNumber.value, changedFields))
        return saved
    }

    private fun defaultPageSize(): Int = if (support.configuredDefaultPageSize > 0) support.configuredDefaultPageSize else FALLBACK_PAGE_SIZE

    private companion object {
        /** Fallback list-page size when no configured default page size is set. */
        const val FALLBACK_PAGE_SIZE = 60
    }
}
