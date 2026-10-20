package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.invoice.adoptReceiptsOf
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.event.InvoiceCancelled
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceCancellationNotAllowedException
import de.littletrotter.domain.exception.InvoiceWithoutSenderProfileException
import de.littletrotter.domain.exception.CancellationRejection
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.PdfUnrenderableTextException
import de.littletrotter.domain.invoice.ensureTextRenderableInPdf
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.shared.stampForPersist
import de.littletrotter.shared.documents.DocumentDates
import de.littletrotter.shared.documents.DocumentTexts
import de.littletrotter.shared.documents.PdfRenderableText
import org.hibernate.exception.ConstraintViolationException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.time.LocalDate
import de.littletrotter.domain.invoice.CreditNoteDetails

/**
 * The cancellation process: cancelling an invoice that has already been sent.
 *
 * **Two documents, both ending in [InvoiceStatus.CANCELLED].** The sent invoice moves
 * `ISSUED → CANCELLED` — the one outgoing edge [de.littletrotter.domain.invoice.InvoiceStateMachine]
 * grants it — and keeps everything else it had: its number, its amounts, its tax snapshot. Beside
 * it a second document comes into being, a corrected invoice ([InvoiceTypeCode.CORRECTED_INVOICE],
 * UNTDID 1001 code `384` — PO decision 1, 2026-09-02; previously `381`/[InvoiceTypeCode
 * .CREDIT_NOTE], which stays reserved for a genuine credit note this system does not otherwise
 * produce), which is a full 1:1 copy of the original and refers back to it through
 * [Invoice.originalInvoiceNumber]/[Invoice.originalInvoiceDate] (BG-3). The pair reads as
 * "Rechnung — Storniert" and "Stornorechnung — Storniert"; the document type, not the status, is
 * what tells them apart.
 *
 * **The cancellation document is born terminal.** It does not walk `DRAFT → IN_REVIEW → ISSUED` like an
 * invoice a human writes: there is nothing on it to draft, review or correct — it is the system's
 * automatic copy of a document that was already reviewed and sent. So it is created directly in
 * [InvoiceStatus.CANCELLED], draws its number in that same moment (rather than at a `→ ISSUED` edge
 * it will never reach), and is immutable from birth
 * ([Invoice.isReadOnly] is true both for its status and for its type code).
 *
 * **Only an `ISSUED` invoice can be cancelled** — pinned in [cancellationRejection]/[CancellationRejection]. A
 * second cancellation of the same original is rejected twice over: by the pre-check here, and by
 * `uq_invoice_single_credit_note` for the race two concurrent calls would otherwise win together.
 *
 * **The cancellation carries no payment terms** (PO decision 2, 2026-09-02): no [Invoice.paymentTerm]
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
 * junction row on the cancellation document, while the original keeps its own untouched.
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
 * (`KositValidationIT`). A cancellation that stayed positive while also omitting payment terms (the
 * original draft of this change) would therefore have failed the real embedded KoSIT validator —
 * caught by cross-referencing that pre-existing negative fixture rather than left for
 * `ZugferdCreditNoteConformanceE2EIT`'s own re-run to discover (that
 * re-run is still outstanding as of this change, see that class's own KDoc). With BT-115 negative,
 * BR-CO-25's own precondition never fires: no payment terms are required, matching decision 2.
 */
// Creates/cancels invoice rows (both cancellation participants), captured by
// invoice_audit_journal; the action moved onto createCreditNote itself (CANCELLATION).
@ApplicationScoped
class InvoiceCancellationService @Inject constructor(
    private val invoiceRepo: InvoiceRepository,
    private val invoiceLookup: InvoiceLookupRepository,
    private val eventDispatcher: DomainEventDispatcher,
    // The cancellation document draws its number here rather than at an ISSUED edge it never reaches, so
    // this service needs the same assigner InvoiceLifecycleService uses — one implementation, not
    // a second copy of the allocation logic.
    private val numberAssigner: InvoiceNumberAssigner,
    private val time: TimeSource,
    private val auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider(),
) {
    /**
     * Cancels [originalInvoiceId] and returns the cancellation document that replaces it.
     *
     * Everything below happens in **one** transaction. If any step throws, neither the original's
     * status change nor the number allocation survives — which is exactly why the allocation lives
     * here rather than at a later step: a cancellation that fails consumes no number and tears no gap in
     * the sequence.
     *
     * @param reason free-text cancellation reason, in the original's own language. Composed, together
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
    @SetsAuditGuc(action = InvoiceAuditAction.CANCELLATION)
    fun createCreditNote(originalInvoiceId: Long, reason: String, reasonEn: String? = null): Invoice {
        require(reason.isNotBlank()) { "Storno-Grund darf nicht leer sein." }
        // A cancellation is born terminal, so the status-change guard never sees it: an unrenderable
        // character here would leave a cancelled credit note whose PDF can never be produced.
        ensureReasonRenderable(reason, reasonEn)

        val original = invoiceRepo.findByIdWithItems(originalInvoiceId)
            ?: throw InvoiceNotFoundException(originalInvoiceId)

        assertCancellationAllowed(original)

        // Cannot be null on a sent invoice (Invoice.validate() enforces the sender before the
        // document may be issued) — this throw only covers a row that came into being past the
        // database, e.g. through demo data. No SenderProfileNotFoundException(-1L): no sender id
        // was looked up and missed, the relation is simply absent, and originalInvoiceId is the
        // only meaningful identifier here.
        val sender = original.senderProfile ?: throw InvoiceWithoutSenderProfileException(originalInvoiceId)
        val originalNumber = original.invoiceNumber.value

        // The cancellation reference (BT-22) follows the ORIGINAL's document_language, the same
        // language the credit note itself will carry (Invoice.creditNoteFor copies
        // it verbatim). "cancellation.reference"/"cancellation.reason" (DocumentTexts, kotlin-shared) supply
        // both sentences pre-translated; only the user's own reason text is never translated.
        val cancellationLanguage = original.documentLanguage.code
        val cancellationReason = if (cancellationLanguage == "en" && !reasonEn.isNullOrBlank()) reasonEn.trim() else reason.trim()
        val composedFreeText = composeCancellationFreeText(originalNumber, original.invoiceDate, cancellationLanguage, cancellationReason)

        val creditNote = buildCreditNoteDocument(original, sender, originalNumber, composedFreeText)
        creditNote.ensureTextRenderableInPdf()
        val saved = persistNewCreditNote(creditNote, originalNumber)

        // PO decision 2 (2026-09-02) — no freezePaymentTermSnapshot() call here any more: the
        // cancellation never carries a paymentTerm to freeze (creditNoteFor does not
        // adopt one), so the call would be a no-op anyway — dropped rather than kept as dead code.
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
        original.pullDomainEvents().forEach { eventDispatcher.publishAfterCommit(it) }
        eventDispatcher.publishAfterCommit(
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

    // Born terminal (see the class KDoc); Invoice.creditNoteFor builds the document through the
    // sanctioned construction-time-only path around the private status setter. The number
    // follows further down, once this document has an id to record in the number journal.
    // Extracted out of [createCreditNote] to keep that method's own LongMethod down.
    private fun buildCreditNoteDocument(original: Invoice, sender: SenderProfile, originalNumber: String, composedFreeText: String): Invoice {
        // Both parties and the whole tax context come from the original: a credit note naming
        // a different party or a different tax case than the document it annuls does not annul
        // it. Named as a domain step so every new context dimension travels along by itself.
        // The cancellation carries its own issue date (BT-2 = today, it may never precede the original);
        // the free text (BT-22) is composed once from DocumentTexts + the resolved cancellationReason and
        // persisted from birth like everything else on this immutable document.
        val creditNote = Invoice.creditNoteFor(
            original,
            sender,
            CreditNoteDetails(originalNumber, time.today(), composedFreeText),
        )

        // Receipt ASSIGNMENTS are duplicated, not moved: the same Receipt rows gain an additional
        // junction row on the credit note, the original keeps its own untouched. Invoice level
        // here; line level already in InvoiceItem.copyForCorrection().
        creditNote.adoptReceiptsOf(original)

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
    // The pre-check in assertCancellationAllowed is a check-then-act and cannot tell two concurrent
    // cancellation calls apart: both pass it before either writes. The partial unique index decides
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
                throw InvoiceCancellationNotAllowedException(CancellationRejection.ALREADY_CANCELLED, originalNumber)
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
    fun canCreateCreditNote(invoice: Invoice): Boolean = cancellationRejection(invoice) == null

    private fun assertCancellationAllowed(invoice: Invoice) {
        cancellationRejection(invoice)?.let {
            throw InvoiceCancellationNotAllowedException(it, invoice.invoiceNumber.value)
        }
    }

    private fun cancellationRejection(invoice: Invoice): CancellationRejection? = when {
        // PO decision 1 (2026-09-02) — the cancellation flow stamps CORRECTED_INVOICE now, not
        // CREDIT_NOTE; this guard moved with it.
        invoice.typeCode == InvoiceTypeCode.CORRECTED_INVOICE -> CancellationRejection.ALREADY_A_CORRECTED_INVOICE
        // Checked before the generic not-ISSUED guard: an already-cancelled original must be
        // rejected with the truthful reason, not the generic status complaint — the loser of two
        // concurrent cancellation attempts reads the winner's CANCELLED here and otherwise got a
        // misleading NOT_SENT depending purely on interleaving.
        invoice.status == InvoiceStatus.CANCELLED -> CancellationRejection.ALREADY_CANCELLED
        invoice.status != InvoiceStatus.ISSUED -> CancellationRejection.NOT_SENT
        !invoice.invoiceNumber.isAssigned() -> CancellationRejection.ORIGINAL_NUMBER_MISSING
        invoiceLookup.findByOriginalInvoiceNumber(invoice.invoiceNumber.value).isNotEmpty() ->
            CancellationRejection.ALREADY_CANCELLED
        else -> null
    }

    companion object {
        /** Name of the partial unique index from `V073__invoice_single_credit_note.sql`. */
        const val UQ_SINGLE_CREDIT_NOTE = "uq_invoice_single_credit_note"
    }
}

/**
 * Composes the cancellation document's [Invoice.freeText] (BT-22): the reference sentence to
 * the cancelled invoice ([originalNumber]/[originalInvoiceDate]) followed by [reason], both in
 * [language] (`"de"`/`"en"`) via [DocumentTexts]/[DocumentDates] (kotlin-shared).
 *
 * Extracted as a pure top-level function — rather than kept inline in [InvoiceCancellationService
 * .createCreditNote] — so the no-date branch stays directly unit-testable: [Invoice.invoiceDate]
 * has been mandatory (non-nullable) since the 2026-09-02 date invariants, so
 * [originalInvoiceDate] (read from `original.invoiceDate` at the one call site) can no longer
 * actually be null through the public API today. [Invoice.originalInvoiceDate] (BT-26) itself
 * stays nullable regardless — EN 16931 never requires it — so this function keeps handling `null`
 * correctly rather than assuming the current caller's invariant will never change.
 */
internal fun composeCancellationFreeText(originalNumber: String, originalInvoiceDate: LocalDate?, language: String, reason: String): String {
    val referenceSentence = originalInvoiceDate?.let { date ->
        DocumentTexts.format("storno.reference", language, originalNumber, DocumentDates.format(date, language))
    } ?: DocumentTexts.format("storno.reference.noDate", language, originalNumber)
    return "$referenceSentence ${DocumentTexts.format("storno.reason", language, reason)}"
}
