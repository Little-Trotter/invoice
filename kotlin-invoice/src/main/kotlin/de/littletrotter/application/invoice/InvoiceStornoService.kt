package de.littletrotter.application.invoice

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.event.InvoiceCancelled
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceStornoNotAllowedException
import de.littletrotter.domain.exception.InvoiceWithoutSenderProfileException
import de.littletrotter.domain.exception.StornoRejection
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.PdfUnrenderableTextException
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.ensureTextRenderableInPdf
import de.littletrotter.domain.invoice.snapshotAsBuyer
import de.littletrotter.domain.invoice.snapshotAsSeller
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.stampForPersist
import de.littletrotter.shared.documents.DocumentDates
import de.littletrotter.shared.documents.DocumentTexts
import de.littletrotter.domain.invoice.applyConsumerNoteSnapshot
import de.littletrotter.domain.invoice.consumerNoteSnapshot
import de.littletrotter.shared.documents.PdfRenderableText
import org.hibernate.exception.ConstraintViolationException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.LocalDate

/**
 * The storno process: cancelling an invoice that has already been sent.
 *
 * **Two documents, both ending in [InvoiceStatus.CANCELLED].** The sent invoice moves
 * `SENT → CANCELLED` — the one outgoing edge [de.littletrotter.domain.invoice.InvoiceStateMachine]
 * grants it — and keeps everything else it had: its number, its amounts, its tax snapshot. Beside
 * it a second document comes into being, a corrected invoice ([InvoiceTypeCode.CORRECTED_INVOICE],
 * UNTDID 1001 code `384` — PO decision 1, 2026-09-02; previously `381`/[InvoiceTypeCode
 * .CREDIT_NOTE], which stays reserved for a genuine credit note this system does not otherwise
 * produce), which is a full 1:1 copy of the original and refers back to it through
 * [Invoice.originalInvoiceNumber]/[Invoice.originalInvoiceDate] (BG-3). The pair reads as
 * "Rechnung — Storniert" and "Stornorechnung — Storniert"; the document type, not the status, is
 * what tells them apart.
 *
 * **The storno document is born terminal.** It does not walk `DRAFT → IN_REVIEW → SENT` like an
 * invoice a human writes: there is nothing on it to draft, review or correct — it is the system's
 * automatic copy of a document that was already reviewed and sent. So it is created directly in
 * [InvoiceStatus.CANCELLED], draws its number in that same moment (rather than at a `→ SENT` edge
 * it will never reach), and is immutable from birth
 * ([Invoice.isReadOnly] is true both for its status and for its type code).
 *
 * **Only a `SENT` invoice can be cancelled** — pinned in [stornoRejection]/[StornoRejection]. A
 * second storno of the same original is rejected twice over: by the pre-check here, and by
 * `uq_invoice_single_credit_note` for the race two concurrent calls would otherwise win together.
 *
 * **The storno carries no payment terms** (PO decision 2, 2026-09-02): no [Invoice.paymentTerm]
 * reference, no [Invoice.paymentTermSnapshot], no [Invoice.dueDate], and [Invoice.paymentTermsDays]
 * is stamped `0` rather than copied — there is nothing to pay and nothing falls due on a document
 * that only cancels a receivable. `paymentTermsDays = 0` is not cosmetic: both the Mustang exporter
 * (`MustangTransactionBuilder.getPaymentTerms()`) and the PDF report
 * (`InvoiceReportContextBuilder.buildContext`'s `hasPaymentTerms`) fall back to deriving a due
 * date/payment-terms line from a positive day count when [Invoice.dueDate] is `null` — leaving the
 * `Invoice`-entity default of `14` here would silently reintroduce a due date on the wire and on
 * the printed page. [de.littletrotter.domain.compliance.DueDateRules]/[de.littletrotter.domain
 * .compliance.CoreCompletenessRules] already treat an absent due date as fully compliant, never a
 * finding — no change was needed there.
 *
 * **The copy is full.** All items with their full amounts and tax snapshots, line-level service
 * periods (BG-26/D-R1), the per-diem entries of a PER_DIEM line, and the receipt links on invoice
 * AND line level: the same [de.littletrotter.domain.masterdata.Receipt] rows gain an additional
 * junction row on the storno document, while the original keeps its own untouched.
 *
 * **The amounts are the exact negative of the original — via the unit price, not the quantity**
 * (PO decision 2026-09-02, sign logic; empirically forced by BR-CO-25, see below).
 * [InvoiceItem.copyForCreditNote] negates [InvoiceItem.unitPriceNet], never [InvoiceItem.quantity]:
 * `quantity` is never negative for any item regardless of invoice type (`B1` —
 * [InvoiceItem.validate]'s own "the sign lives in the price, not the quantity" invariant, the exact
 * mechanism a discount/rebate line already uses). [de.littletrotter.xpdf.impl.mustang
 * .MustangExportModelMapper] already flips a negative [InvoiceItem.unitPriceNet] back to a positive
 * BT-146 with the sign moved onto BT-129 at export time (built for the discount-line case,
 * reused here unchanged) — so a negated `unitPriceNet` here is what actually produces the BT-129
 * negative / BT-146 positive / BT-131 negative wire shape the doc's sign-logic table calls for,
 * with no exporter change needed. [InvoiceItem.calculateAmounts] then derives negative
 * net/vat/gross per line from the negated price, and [Invoice.recalculateTotals] sums those into
 * negative header totals — which [Invoice.validate] allows specifically for
 * [InvoiceTypeCode.CORRECTED_INVOICE] (its NET/VAT/GROSS_AMOUNT_NEGATIVE checks stay in force for
 * every other type).
 *
 * Negating the total is not optional once payment terms are gone (PO decision 2): EN 16931
 * BR-CO-25 requires either a due date (BT-9) or a payment-terms text (BT-20) whenever the payable
 * amount (BT-115) is **positive** — confirmed by this codebase's own real-KoSIT-run negative
 * fixture, `kotlin-xpdf/.../fixtures/negative/fehlende-zahlungsbedingungen-br-co-25.xml`
 * (`KositValidationIT`). A storno that stayed positive while also omitting payment terms (the
 * original draft of this change) would therefore have failed the real embedded KoSIT validator —
 * caught by cross-referencing that pre-existing negative fixture rather than left for
 * `ZugferdCreditNoteConformanceE2EIT`'s own re-run to discover (that
 * re-run is still outstanding as of this change, see that class's own KDoc). With BT-115 negative,
 * BR-CO-25's own precondition never fires: no payment terms are required, matching decision 2.
 */
// Creates/cancels invoice rows (both storno participants), captured by
// invoice_audit_journal; the action moved onto createCreditNote itself (STORNO).
@ApplicationScoped
class InvoiceStornoService @Inject constructor(
    private val invoiceRepo: InvoiceRepository,
    private val domainEventBus: Event<DomainEvent>,
    // The storno document draws its number here rather than at a SENT edge it never reaches, so
    // this service needs the same assigner InvoiceLifecycleService uses — one implementation, not
    // a second copy of the allocation logic.
    private val numberAssigner: InvoiceNumberAssigner,
    private val time: TimeSource,
    private val auditActorProvider: AuditActorProvider = SystemAuditActorProvider(),
) {
    /**
     * Cancels [originalInvoiceId] and returns the storno document that replaces it.
     *
     * Everything below happens in **one** transaction. If any step throws, neither the original's
     * status change nor the number allocation survives — which is exactly why the allocation lives
     * here rather than at a later step: a storno that fails consumes no number and tears no gap in
     * the sequence.
     *
     * @param reason free-text storno reason, in the original's own language. Composed, together
     *   with the reference sentence below, into the credit note's [Invoice.freeText].
     *   **Mandatory** — the document is immutable from the moment it exists, so a missing or wrong
     *   reason can never be corrected afterwards, and a credit note that gives no hint of its
     *   cause is unusable for the recipient.
     * @param reasonEn (PO decision 3, 2026-09-06) — an optional English rendering of
     *   [reason], used **only** when [Invoice.documentLanguage] of the original is `"en"` and this
     *   is non-blank; a German original ignores it entirely, and a blank/absent value on an
     *   English original falls back to [reason] verbatim (today's behaviour, unchanged). Never
     *   itself persisted — only the composed [Invoice.freeText] survives.
     */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.STORNO)
    fun createCreditNote(originalInvoiceId: Long, reason: String, reasonEn: String? = null): Invoice {
        require(reason.isNotBlank()) { "Storno-Grund darf nicht leer sein." }
        // A storno is born terminal, so the status-change guard never sees it: an unrenderable
        // character here would leave a cancelled credit note whose PDF can never be produced.
        ensureReasonRenderable(reason, reasonEn)

        val original = invoiceRepo.findByIdWithItems(originalInvoiceId)
            ?: throw InvoiceNotFoundException(originalInvoiceId)

        assertStornoAllowed(original)

        // Cannot be null on a sent invoice (Invoice.validate() enforces the sender before the
        // document may be issued) — this throw only covers a row that came into being past the
        // database, e.g. through demo data. No SenderProfileNotFoundException(-1L): no sender id
        // was looked up and missed, the relation is simply absent, and originalInvoiceId is the
        // only meaningful identifier here.
        val sender = original.senderProfile ?: throw InvoiceWithoutSenderProfileException(originalInvoiceId)
        val originalNumber = original.invoiceNumber.value

        // The storno reference (BT-22) follows the ORIGINAL's document_language, the same
        // language the credit note itself will carry (adoptPartiesAndTaxContextFrom below copies
        // it verbatim). "storno.reference"/"storno.reason" (DocumentTexts, kotlin-shared) supply
        // both sentences pre-translated; only the user's own reason text is never translated.
        val stornoLanguage = original.documentLanguage
        val stornoReason = if (stornoLanguage == "en" && !reasonEn.isNullOrBlank()) reasonEn.trim() else reason.trim()
        val composedFreeText = composeStornoFreeText(originalNumber, original.invoiceDate, stornoLanguage, stornoReason)

        val creditNote = buildCreditNoteDocument(original, sender, originalNumber, composedFreeText)
        creditNote.ensureTextRenderableInPdf()
        val saved = persistNewCreditNote(creditNote, originalNumber)

        // PO decision 2 (2026-09-02) — no freezePaymentTermSnapshot() call here any more: the
        // storno never carries a paymentTerm to freeze (adoptPartiesAndTaxContextFrom no longer
        // adopts one), so the call would be a no-op anyway — dropped rather than kept as dead code.
        //
        // Only now, with an id in hand: the allocation records that id in the number journal, and
        // an entry that cannot name its document is worth little in an audit. Ordering matters —
        // invoiceDate must be final before this runs, because the allocation's native counter query
        // forces Hibernate to flush this entity's current dirty state.
        numberAssigner.assignIfNeeded(saved)
        // Validated after numbering, not before: an issued document must carry a number
        // (Invoice.validate()'s isIssued rule), and until the line above ran, this one did not.
        saved.validate()
        val numbered = invoiceRepo.saveAndFlush(saved)

        // Deliberately last: the original is only cancelled once the document replacing it exists,
        // is numbered and is persisted.
        original.transitionTo(InvoiceStatus.CANCELLED)
        original.stampForPersist(auditActorProvider.currentActor())
        invoiceRepo.saveAndFlush(original)

        // The item-level domain events of building the copy (InvoiceItemAdded) are discarded: they
        // describe filling in a draft, not the business event here. The original's own
        // InvoiceStatusChanged is published like any other status change.
        numbered.pullDomainEvents()
        original.pullDomainEvents().forEach { domainEventBus.fireAsync(it) }
        domainEventBus.fireAsync(
            InvoiceCancelled(
                creditNoteId = numbered.id!!,
                creditNoteNumber = numbered.invoiceNumber.value,
                originalInvoiceId = originalInvoiceId,
                originalInvoiceNumber = originalNumber,
            ),
        )
        return numbered
    }

    private fun ensureReasonRenderable(reason: String, reasonEn: String?) {
        val fields = linkedMapOf<String, List<String>>()
        PdfRenderableText.firstOffending(reason).takeIf { it.isNotEmpty() }?.let { fields["Storno-Grund"] = it }
        PdfRenderableText.firstOffending(reasonEn).takeIf { it.isNotEmpty() }?.let { fields["Storno-Grund (EN)"] = it }
        if (fields.isNotEmpty()) throw PdfUnrenderableTextException(fields)
    }

    // Born terminal (see the class KDoc); Invoice.withInitialStatus is the sanctioned
    // construction-time-only path around the private status setter. The number
    // follows further down, once this document has an id to record in the number journal.
    // Extracted out of [createCreditNote] to keep that method's own LongMethod down.
    private fun buildCreditNoteDocument(original: Invoice, sender: SenderProfile, originalNumber: String, composedFreeText: String): Invoice {
        val creditNote = Invoice.withInitialStatus(InvoiceStatus.CANCELLED).apply {
            // PO decision 1 (2026-09-02) — 384 only, never 381 (see the class KDoc).
            typeCode = InvoiceTypeCode.CORRECTED_INVOICE
            this.originalInvoiceNumber = originalNumber
            // BT-26 — no guess: the original is in hand and its issue date is exactly known.
            // Stays null if the original itself carries none.
            this.originalInvoiceDate = original.invoiceDate

            // Both parties and the whole tax context come from the original: a credit note naming
            // a different party or a different tax case than the document it annuls does not annul
            // it. Named as a domain step so every new context dimension travels along by itself.
            adoptPartiesAndTaxContextFrom(original, sender)
            // INV-B — this immutable counter-document carries the original's frozen note.
            applyInvoiceNoteSnapshot(original.invoiceNoteSnapshot())
            applyConsumerNoteSnapshot(original.consumerNoteSnapshot())

            // The original's references: BT-10, BT-13 and the order/offer references still apply —
            // the recipient books the credit note under the same transaction.
            references.copyFrom(original.references)

            // Dates: the storno carries its own issue date (BT-2 = today, it may never precede the
            // original), but the original's service period and delivery date (BT-72/73/74) — it
            // annuls exactly that service, no other.
            invoiceDate = time.today()
            servicePeriodFrom = original.servicePeriodFrom
            servicePeriodTo = original.servicePeriodTo
            deliveryDate = original.deliveryDate
            // PO decision 2 (2026-09-02) — no payment terms on a storno: dueDate stays null (the
            // Invoice default), and paymentTermsDays is explicitly 0, not the Invoice-entity
            // default of 14 — see the class KDoc for why 0 (not just "not copied") matters here.
            paymentTermsDays = 0

            // Composed above, once, from DocumentTexts + the resolved stornoReason; not
            // re-derived at render time (BT-22 is document content, persisted from birth like
            // everything else on this immutable document).
            freeText = composedFreeText
        }

        // The order is the original's (Invoice.items is sorted by sortOrder) and so are the
        // position ids — document and counter-document can be compared line by line. addItem()
        // assigns sortOrder afresh and only fills in a positionId where the original had none.
        original.itemList.forEach { creditNote.addItem(it.copyForCreditNote()) }
        creditNote.recalculateTotals()

        // Receipt ASSIGNMENTS are duplicated, not moved: the same Receipt rows gain an additional
        // junction row on the credit note, the original keeps its own untouched. Invoice level
        // here; line level already in copyForCreditNote().
        creditNote.receipts.addAll(original.receipts)

        // P2 — the credit note freezes its own party snapshots. Deliberately from master data and
        // not from the original's snapshots: it is its own document, issued today, and must carry
        // today's address.
        val recipient = creditNote.recipient
        if (recipient != null) {
            creditNote.applyPartySnapshots(
                sender.snapshotAsSeller(),
                recipient.snapshotAsBuyer(creditNote.references.buyerReference),
            )
        }
        return creditNote
    }

    // Split out of createCreditNote to keep that method's own LongMethod down: builds and
    // saves the credit note entity only; createCreditNote itself still does the numbering/
    // original-cancellation/domain-event tail (see its own body), unchanged either side of this
    // merge.
    private fun persistNewCreditNote(creditNote: Invoice, originalNumber: String): Invoice {
        creditNote.stampForPersist(auditActorProvider.currentActor())
        return saveCreditNoteWithSingleIndexGuard(creditNote, originalNumber)
    }

    // The save-and-race-guard step (ThrowsCount) split out into its own function so each
    // of [persistNewCreditNote]'s and this one's own throw expressions stays under ThrowsCount on
    // its own; behaviour is unchanged.
    //
    // The pre-check in assertStornoAllowed is a check-then-act and cannot tell two concurrent
    // storno calls apart: both pass it before either writes. The partial unique index decides
    // that race, and the loser gets the same business answer here as someone who simply
    // clicked too late — not a bare database error.
    private fun saveCreditNoteWithSingleIndexGuard(creditNote: Invoice, originalNumber: String): Invoice =
        try {
            invoiceRepo.saveAndFlush(creditNote)
        } catch (ex: jakarta.persistence.PersistenceException) {
            // AP-EXC-1: narrowed to PersistenceException — the only thing em.flush() (inside
            // saveAndFlush) throws, and specifically what the race documented above surfaces as.
            // Always rethrown, either translated to the business exception or unchanged.
            if (violatesSingleCreditNoteIndex(ex)) {
                throw InvoiceStornoNotAllowedException(StornoRejection.ALREADY_CANCELLED, originalNumber)
            }
            throw ex
        }

    /** Detects a violation of `uq_invoice_single_credit_note` anywhere in the cause chain —
     *  Hibernate wraps it at varying depth depending on the call path. */
    private fun violatesSingleCreditNoteIndex(ex: Throwable?): Boolean {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is ConstraintViolationException && cause.constraintName == UQ_SINGLE_CREDIT_NOTE) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Whether [invoice] can be cancelled — the same conditions as in [createCreditNote], just
     * without throwing. For the UI, which should hide the button instead of running into an
     * exception.
     */
    fun canCreateCreditNote(invoice: Invoice): Boolean = stornoRejection(invoice) == null

    private fun assertStornoAllowed(invoice: Invoice) {
        stornoRejection(invoice)?.let {
            throw InvoiceStornoNotAllowedException(it, invoice.invoiceNumber.value)
        }
    }

    private fun stornoRejection(invoice: Invoice): StornoRejection? = when {
        // PO decision 1 (2026-09-02) — the storno flow stamps CORRECTED_INVOICE now, not
        // CREDIT_NOTE; this guard moved with it.
        invoice.typeCode == InvoiceTypeCode.CORRECTED_INVOICE -> StornoRejection.ALREADY_A_CORRECTED_INVOICE
        // Checked before the generic not-SENT guard: an already-cancelled original must be
        // rejected with the truthful reason, not the generic status complaint — the loser of two
        // concurrent storno attempts reads the winner's CANCELLED here and otherwise got a
        // misleading NOT_SENT depending purely on interleaving.
        invoice.status == InvoiceStatus.CANCELLED -> StornoRejection.ALREADY_CANCELLED
        invoice.status != InvoiceStatus.SENT -> StornoRejection.NOT_SENT
        !invoice.invoiceNumber.isAssigned() -> StornoRejection.ORIGINAL_NUMBER_MISSING
        invoiceRepo.findByOriginalInvoiceNumber(invoice.invoiceNumber.value).isNotEmpty() ->
            StornoRejection.ALREADY_CANCELLED
        else -> null
    }

    companion object {
        /** Name of the partial unique index from `V073__invoice_single_credit_note.sql`. */
        const val UQ_SINGLE_CREDIT_NOTE = "uq_invoice_single_credit_note"
    }
}

/**
 * Composes the storno document's [Invoice.freeText] (BT-22): the reference sentence to
 * the cancelled invoice ([originalNumber]/[originalInvoiceDate]) followed by [reason], both in
 * [language] (`"de"`/`"en"`) via [DocumentTexts]/[DocumentDates] (kotlin-shared).
 *
 * Extracted as a pure top-level function — rather than kept inline in [InvoiceStornoService
 * .createCreditNote] — so the no-date branch stays directly unit-testable: [Invoice.invoiceDate]
 * has been mandatory (non-nullable) since the 2026-09-02 date invariants, so
 * [originalInvoiceDate] (read from `original.invoiceDate` at the one call site) can no longer
 * actually be null through the public API today. [Invoice.originalInvoiceDate] (BT-26) itself
 * stays nullable regardless — EN 16931 never requires it — so this function keeps handling `null`
 * correctly rather than assuming the current caller's invariant will never change.
 */
internal fun composeStornoFreeText(originalNumber: String, originalInvoiceDate: LocalDate?, language: String, reason: String): String {
    val referenceSentence = originalInvoiceDate?.let { date ->
        DocumentTexts.format("storno.reference", language, originalNumber, DocumentDates.format(date, language))
    } ?: DocumentTexts.format("storno.reference.noDate", language, originalNumber)
    return "$referenceSentence ${DocumentTexts.format("storno.reason", language, reason)}"
}

/**
 * Copies the line item for the credit note. The **tax snapshot is carried over field by
 * field**, not re-derived via [InvoiceItem.applyTaxRule]: the referenced
 * [de.littletrotter.domain.reference.TaxRate] may have changed since the invoice was sent, and
 * a credit note must carry the same rate and the same exemption reason as the document it
 * annuls (R1/GoBD, see [InvoiceItem.snapshotTaxRule]).
 *
 * **Prices are negated via [InvoiceItem.negateForStorno], quantities are not** (PO decision 2,
 * 2026-09-02 sign logic — see [InvoiceStornoService]'s own class KDoc for the full reasoning and
 * the BR-CO-25 empirical finding behind it). [InvoiceItem.negateForStorno] negates
 * [InvoiceItem.unitPriceNet] and, for a TRIP position, also [InvoiceItem.tripFixedNetPrice]
 * and every [de.littletrotter.domain.invoice.TripExpense.unitPriceNet] — one shared negation
 * rule instead of this function reimplementing it per field. [InvoiceItem.validate]'s
 * quantity-never-negative invariant (`B1`) is deliberately left untouched for every item, storno
 * or not — this reuses the exact discount/rebate-line sign convention that already exists, it
 * does not add a special case for it. A rebate line already carrying a negative price on the
 * original therefore comes back **positive** here — negating twice — which is correct: reversing
 * a rebate must increase the storno's total exactly as much as the rebate had decreased the
 * original's.
 *
 * The line item is now a **full** copy: the [InvoiceItem.perDiemEntries] of a trip (each as its
 * own, new [de.littletrotter.domain.reference.TravelExpenseEntry] row — references to the same
 * row would make the orphanRemoval cascade of both line items collide), the
 * [InvoiceItem.tripExpenses] of a trip (same reasoning — each a new
 * [de.littletrotter.domain.invoice.TripExpense] row, receipt link included) and the
 * [InvoiceItem.receipts] associations (the same [de.littletrotter.domain.masterdata.Receipt]
 * rows, with an additional `invoice_item_receipt` row on the copy) are carried along instead of
 * being left out, as they used to be: a credit note that fully reverses the same service must
 * also carry its supporting evidence (the credit note's PDF attachment then shows the same
 * receipts as the original).
 */
private fun InvoiceItem.copyForCreditNote(): InvoiceItem {
    val source = this
    val copy = InvoiceItem().apply {
        positionId = source.positionId
        category = source.category
        description = source.description
        itemDescription = source.itemDescription
        servicePeriodFrom = source.servicePeriodFrom
        servicePeriodTo = source.servicePeriodTo
        deliveryNoteReference = source.deliveryNoteReference
        costCenter = source.costCenter
        quantity = source.quantity
        unit = source.unit
        unitPriceNet = source.unitPriceNet
        taxRateRef = source.taxRateRef
        articleRef = source.articleRef
        taxRuleCode = source.taxRuleCode
        taxCategory = source.taxCategory
        taxRateSnapshot = source.taxRateSnapshot
        exemptionReasonCode = source.exemptionReasonCode
        exemptionReasonText = source.exemptionReasonText
        taxLegalBasis = source.taxLegalBasis
        // TRIP-only fields; both stay null/false for every other category.
        tripDetailedOnInvoice = source.tripDetailedOnInvoice
        tripFixedNetPrice = source.tripFixedNetPrice
    }
    copy.perDiemEntries.addAll(source.perDiemEntries.map { it.copyForCreditNote(copy) })
    copy.tripExpenses.addAll(source.tripExpenses.map { it.copyForCreditNote(copy) })
    copy.receipts.addAll(source.receipts)
    // Negates unitPriceNet, tripFixedNetPrice and every tripExpenses line's unitPriceNet in one
    // call (see this function's own KDoc) — must run after both children lists above are in
    // place, since it iterates copy.tripExpenses itself.
    copy.negateForStorno()
    return copy
}

/** Deep copy of one expense line for [owner], the credit note's new trip line item. Same
 *  explicit-field-list philosophy as [InvoiceItem.copyForCreditNote]/[TravelExpenseEntry
 *  .copyForCreditNote] (QOD-3 triage, kept on purpose): a field added to [TripExpense] must not
 *  start being copied silently. [TripExpense.receipt] is carried along like the parent item's own
 *  [InvoiceItem.receipts] — the same receipt row, referenced from both the original's and the
 *  copy's expense line. [TripExpense.unitPriceNet] is copied as-is here (not yet negated); the
 *  caller negates the whole tree via [InvoiceItem.negateForStorno] afterward. */
private fun TripExpense.copyForCreditNote(owner: InvoiceItem): TripExpense {
    val source = this
    return TripExpense().apply {
        invoiceItem = owner
        kind = source.kind
        description = source.description
        travelDate = source.travelDate
        quantity = source.quantity
        unit = source.unit
        unitPriceNet = source.unitPriceNet
        netAmount = source.netAmount
        receipt = source.receipt
        sortOrder = source.sortOrder
    }
}

/** Deep copy of a per-diem entry for [owner], the credit note's new line item. See
 *  [InvoiceItem.copyForCreditNote]'s own KDoc.
 *
 *  Qodana flags this and [InvoiceItem.copyForCreditNote] as duplicated code, and the duplication
 *  is kept on purpose (QOD-3 triage, 2026-09-02). The two functions copy unrelated entity types
 *  that happen to share the shape `field = source.field`; a generic copier would need a lambda per
 *  field and would hide *which* fields cross into the credit note. That list is the audit surface
 *  of a legal document: a field added to the entity must not start being copied silently, it must
 *  be a visible decision in this diff. Explicitness is the safety property here, not an oversight. */
private fun TravelExpenseEntry.copyForCreditNote(owner: InvoiceItem): TravelExpenseEntry {
    val source = this
    return TravelExpenseEntry().apply {
        invoiceItem = owner
        shortName = source.shortName
        country = source.country
        description = source.description
        participant = source.participant
        travelDate = source.travelDate
        isArrivalDeparture = source.isArrivalDeparture
        breakfastDeduction = source.breakfastDeduction
        lunchDeduction = source.lunchDeduction
        dinnerDeduction = source.dinnerDeduction
        fullDayRateSnapshot = source.fullDayRateSnapshot
        arrivalDepartureRateSnapshot = source.arrivalDepartureRateSnapshot
        perDiemRate = source.perDiemRate
        calculatedAmount = source.calculatedAmount
        employerContribution = source.employerContribution
    }
}
