package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.domain.masterdata.invoiceNoteFor
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SetsAuditGuc
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoicePartySnapshot
import de.littletrotter.domain.shared.InvoiceFormat
import de.littletrotter.domain.invoice.isInvoiceFormatEditable
import de.littletrotter.domain.invoice.NoteSubmission
import de.littletrotter.domain.invoice.correctedInvoiceDate
import de.littletrotter.domain.invoice.derivedDueDate
import de.littletrotter.domain.invoice.resolveServiceEnd
import de.littletrotter.domain.invoice.deriveSellerVatRegistrationCountry
import de.littletrotter.domain.invoice.updateInvoiceNoteSnapshot
import de.littletrotter.domain.invoice.mergeUnmappedB2bHeaderValues
import de.littletrotter.domain.invoice.UnmappedB2bHeaderValue
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.stampForPersist
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import de.littletrotter.domain.invoice.headerChange
import de.littletrotter.domain.invoice.parties

/**
 * Extracted from `InvoiceLifecycleService`: the Erfassen (entry) pane's header-save
 * command. Delegates the Reverse-Charge re-tax decision to [reverseChargeService], since
 * it must apply the exact same [InvoiceReverseChargeService.taxDecisionContext]/
 * [InvoiceReverseChargeService.applySubmittedReverseChargeDecision] logic
 * [InvoiceReverseChargeService.setReverseChargeDecision] uses standalone.
 */
@ApplicationScoped
class InvoiceHeaderUpdateService @Inject constructor(
    private val invoiceRepo: InvoiceRepository,
    private val parties: PartyRepositories,
    private val eventDispatcher: DomainEventDispatcher,
    private val auditActorProvider: AuditActorQueryPort,
    private val support: InvoiceLifecycleSupport,
    private val reverseChargeService: InvoiceReverseChargeService,
) {
    private val senderRepo get() = parties.senderRepo
    private val recipientRepo get() = parties.recipientRepo

    /**
     * Applies the Erfassen (entry) pane's header fields onto the managed invoice this method
     * loads itself. Replaces the earlier pattern of reconstructing an [Invoice] from a read model
     * and passing it to `save`, which ended in `EntityManager.merge` and silently reset every
     * field the read model didn't carry — `paymentTerm`, `typeCode`, `validationStatus`,
     * `originalInvoiceNumber`, `documents`, `llmAnalyses` among them (arc-improvements AR1).
     */
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    @Transactional
    @SetsAuditGuc(action = InvoiceAuditAction.HEADER_CHANGED)
    fun updateHeader(command: UpdateInvoiceHeaderCommand): Invoice {
        val invoice = invoiceRepo.findByIdWithItems(command.invoiceId)
            ?: throw InvoiceNotFoundException(command.invoiceId)
        invoice.beginEdit(command.expectedVersion)

        val previousNote = invoice.texts.invoiceNoteSnapshot()
        val previousConsumerNote = invoice.texts.consumerNoteSnapshot()
        val previousTaxContext = reverseChargeService.taxDecisionContext(invoice)
        applyHeaderMetadata(invoice, command)
        val recipientChanged = applyPartyChanges(invoice, command)
        applyInvoiceNoteChange(invoice, command, previousNote)
        applyConsumerNoteChange(invoice, command, previousConsumerNote)
        mergeUnmappedB2bHeaderData(invoice, command)
        // DATE: rules 2/4 run only now, not inside applyHeaderMetadata itself — applyPartyChanges
        // (right above) is what actually applies command.paymentTermId onto invoice.paymentTerm;
        // running the correction earlier would derive rule 4's due date from whatever payment
        // term the invoice had BEFORE this save, not the one the user just chose alongside the
        // date correction.
        applyInvoiceDateAutoCorrection(invoice)
        val (newSellerCountry, previousSellerCountry) = applySellerVatRegistration(invoice, command, recipientChanged)
        reverseChargeService.applySubmittedReverseChargeDecision(invoice, command.reverseChargeDecision, previousTaxContext)
        applyInvoiceFormat(invoice, command, newSellerCountry, previousSellerCountry)
        val saved = finalizeHeaderUpdate(invoice)
        command.b2bFieldValues?.let { values ->
            support.invoiceB2bFieldsQueryPort.saveFieldValues(B2bFieldOwnerType.INVOICE, saved.id!!, values)
        }
        return saved
    }

    /**
     * Applies metadata fields from [command] onto [invoice]: dates (invoiceDate, service period,
     * dueDate), free text, references, and currency. Does not touch party/seller/format fields.
     */
    private fun applyHeaderMetadata(invoice: Invoice, command: UpdateInvoiceHeaderCommand) {
        // date invariants (PO 2026-09-02) — Invoice.invoiceDate is mandatory from creation
        // onward, so a `null` here means "not part of this update", not "clear it": the invoice
        // keeps whatever date it already has rather than losing a value the domain no longer
        // allows to be absent. The entry pane's own DatePicker becomes a required field as part
        // of this same invariant, so in practice a real UI save never submits one.
        val header = invoice.headerChange()
        invoice.updateHeader(
            header.copy(
                invoiceDate = command.invoiceDate ?: header.invoiceDate,
                servicePeriodFrom = command.servicePeriodFrom,
                servicePeriodTo = command.servicePeriodTo,
                dueDate = command.dueDate,
                currency = command.currency,
            ),
        )
        invoice.texts.changeFreeText(command.freeText)
        invoice.references.update(
            invoice.references.asChange().copy(
                buyerReference = command.buyerReference,
                purchaseOrderReference = command.purchaseOrderReference,
                orderReference = command.orderReference,
                projectNumber = command.projectNumber,
                orderReferenceDate = command.orderReferenceDate,
            ),
        )
    }

    /**
     * DATE: rules 2 and 4 (PO decision 2026-09-01) — the
     * header-edit half of the decision, called from [updateHeader] right after
     * [applyPartyChanges] (not from [applyHeaderMetadata] itself, and deliberately not merged
     * into it): [applyPartyChanges] is what actually writes the command's `paymentTermId` onto
     * [Invoice.paymentTerm], and rule 4 below needs that already-updated value, not whatever the
     * invoice had before this save. Rule 1 (ERROR, `ensureInvoiceDateNotBeforeServiceEnd`) only
     * runs at the `→ ISSUED` transition; a draft is free to carry an invoice date before the
     * service end while it is still being edited. This method is rule 2's own place to spare the
     * user the arithmetic on every save instead: an invoice date the user (or an import) left
     * before the resolved service end is moved forward to it automatically here, exactly the way
     * `ensureInvoiceDateNotBeforeServiceEnd` would otherwise reject it later at send time.
     *
     * Rule 4 only cascades when rule 2 actually moved the date: a due date the user entered
     * independently of the invoice date is at most a rule-3 WARNING
     * ([de.littletrotter.domain.compliance.DueDateRules]), never silently overwritten by this
     * method. Only when the invoice date itself changes here does the due date need to follow it
     * to stay consistent — [derivedDueDate] uses the invoice's own live [Invoice.paymentTerm]
     * (not [Invoice.paymentTermsDays], a `create()`-time-only default never written again once a
     * payment term is actually chosen — see `recomputeDueDateForSend`'s own KDoc for the same
     * distinction at the send transition).
     */
    private fun applyInvoiceDateAutoCorrection(invoice: Invoice) {
        val original = invoice.invoiceDate
        val corrected = correctedInvoiceDate(original, invoice.resolveServiceEnd())
        if (corrected == original) return
        // date invariants (PO 2026-09-02) — correctedInvoiceDate's own signature stays
        // LocalDate? (InvoiceDateRules.kt is a generic pure-function module, tested with null
        // inputs too), but it only ever returns null when its own invoiceDate param is null
        // ("never invents a date that was not there to begin with"). original is
        // Invoice.invoiceDate itself, non-nullable, so corrected is provably non-null here.
        val header = invoice.headerChange()
        val paymentTermDays = invoice.paymentTerm?.dueInDays ?: invoice.paymentTermsDays
        invoice.updateHeader(
            header.copy(
                invoiceDate = corrected!!,
                dueDate = derivedDueDate(corrected, paymentTermDays) ?: header.dueDate,
            ),
        )
    }

    /**
     * Applies party changes from [command] onto [invoice]: recipient and senderProfile, plus
     * everything `InvoiceLifecycleService.create` itself defaults from each party — b2bProfile,
     * deliveryAddress, buyerReference (recipient side) and paymentTerm, billingAddress (sender
     * side) — re-derived only when that party actually *changes*; an unchanged
     * party leaves its dependent fields exactly as the entry pane submitted them. A party change
     * also re-freezes the seller/buyer snapshots (ADR-025: `applyPartySnapshots` is the only
     * mutation path), so the snapshots never name the previous party. Returns whether
     * the recipient changed (used by caller to determine subsequent seller VAT registration
     * re-derivation).
     */
    private fun applyPartyChanges(invoice: Invoice, command: UpdateInvoiceHeaderCommand): Boolean {
        // A recipient *change* re-derives invoice.b2bProfile from the new
        // recipient's standing default and overwrites whatever was chosen before, manual override
        // included: changing the customer is the bigger event, and silently keeping the previous
        // customer's B2B catalog would be the surprising outcome. An *unchanged* recipient instead
        // applies whatever the entry pane currently submits (command.b2bProfileId) — this is the
        // "editable afterwards, any time while draft" half of the same behavior; `null` there
        // clears an override back to no profile, exactly like every other nullable field on this
        // command.
        val current = invoice.parties()
        val previousRecipientId = current.recipient?.id
        val newRecipient = command.recipientId?.let { recipientRepo.findById(it) }
        // Shared by the b2bProfile re-derivation right below and the
        // sellerVatRegistrationCountry re-derivation in applySellerVatRegistration: both mirror
        // the same recipient-change pattern ("did the recipient change"), so both must agree on
        // the same answer.
        val recipientChanged = newRecipient?.id != previousRecipientId
        val b2bProfile = if (recipientChanged) {
            newRecipient?.b2bProfile
        } else {
            command.b2bProfileId?.let { support.b2bProfileRepo.findById(it) }
        }
        // The same recipient-change shape as b2bProfile above: a
        // recipient *change* re-derives the delivery address and BT-10 buyer reference
        // from the NEW recipient's own master data, exactly like InvoiceLifecycleService.create's
        // own defaults, overwriting whatever the entry pane had before — the editor can still
        // edit either afterwards, same as every other re-derived field here. An *unchanged*
        // recipient leaves deliveryAddress untouched (this command never sets it otherwise) and
        // buyerReference already took command.buyerReference in applyHeaderMetadata above.
        val deliveryAddress = if (recipientChanged) {
            newRecipient?.let {
                support.addressManagementService.getDeliveryAddressForCustomer(it).orElse(it.deliveryAddress)
            }
        } else {
            current.deliveryAddress
        }
        if (recipientChanged) {
            invoice.references.assignBuyerReference(newRecipient?.routingId)
        }

        val previousSenderId = current.senderProfile?.id
        val newSender = command.senderProfileId?.let { senderRepo.findById(it) }
        val senderChanged = newSender?.id != previousSenderId
        // finding-fixes (2026-08-31, PO order) — mirrors the recipient-change re-derivation right
        // above, sender side: a sender *change* re-derives the billing address and the sender's
        // own default payment term (same defaults create() applies at invoice creation),
        // overwriting whatever was set before. An *unchanged* sender keeps the existing
        // "editable afterwards" behavior for paymentTerm (whatever the entry pane submits); this
        // command never sets billingAddress otherwise.
        val paymentTerm = if (senderChanged) {
            newSender?.defaultPaymentTerm ?: support.paymentTermRepo.findDefault()
        } else {
            command.paymentTermId?.let { support.paymentTermRepo.findById(it) }
        }
        val billingAddress = if (senderChanged) {
            newSender?.let {
                support.addressManagementService.getBillingAddressForSender(it).orElse(it.billingAddress)
            }
        } else {
            current.billingAddress
        }
        invoice.assignParties(
            current.copy(
                senderProfile = newSender,
                recipient = newRecipient,
                billingAddress = billingAddress,
                deliveryAddress = deliveryAddress,
                b2bProfile = b2bProfile,
                paymentTerm = paymentTerm,
            ),
        )
        if (recipientChanged || senderChanged) {
            // assignParties may leave a party null (command without an id); the snapshot pair is
            // all-or-nothing, so it is only re-frozen once both parties are present.
            val sender = invoice.senderProfile
            val recipient = invoice.recipient
            if (sender != null && recipient != null) {
                invoice.applyPartySnapshots(
                    InvoicePartySnapshot.forSeller(sender),
                    InvoicePartySnapshot.forBuyer(recipient, invoice.references.buyerReference),
                )
            }
        }
        return recipientChanged
    }

    private fun applyInvoiceNoteChange(
        invoice: Invoice,
        command: UpdateInvoiceHeaderCommand,
        previous: de.littletrotter.domain.invoice.InvoiceNoteSnapshot,
    ) {
        val sender = invoice.senderProfile
        invoice.texts.applyInvoiceNote(
            updateInvoiceNoteSnapshot(
                current = previous,
                submission = NoteSubmission(
                    submitted = command.invoiceNoteSubmitted,
                    value = command.invoiceNote,
                    resetToSellerDefault = command.resetInvoiceNoteToSellerDefault,
                ),
                source = de.littletrotter.domain.invoice.SellerNoteDefault(
                    senderProfileId = sender?.id,
                    language = invoice.documentLanguage.code,
                    sellerDefault = sender?.invoiceNoteFor(invoice.documentLanguage.code),
                ),
            ),
        )
    }

    /**
     * Same snapshot rules as the invoice note, plus the recipient type as source context: a
     * Firma/Privat switch recomputes an untouched copy and keeps an edited one.
     */
    private fun applyConsumerNoteChange(
        invoice: Invoice,
        command: UpdateInvoiceHeaderCommand,
        previous: de.littletrotter.domain.invoice.InvoiceNoteSnapshot,
    ) {
        invoice.texts.applyConsumerNote(
            updateInvoiceNoteSnapshot(
                current = previous,
                submission = NoteSubmission(
                    submitted = command.consumerNoteSubmitted,
                    value = command.consumerNote,
                    resetToSellerDefault = command.resetConsumerNoteToSellerDefault,
                ),
                source = parties.consumerNoteResolver.sourceFor(
                    invoice.senderProfile,
                    invoice.documentLanguage.code,
                    invoice.recipient?.customerType,
                ),
            ),
        )
    }

    private fun mergeUnmappedB2bHeaderData(invoice: Invoice, command: UpdateInvoiceHeaderCommand) {
        if (command.effectiveB2bHeaderValues.isEmpty()) return
        val profileId = invoice.b2bProfile?.id ?: return
        val entries = support.invoiceB2bFieldsQueryPort.findFields(profileId).mapNotNull { field ->
            val term = field.term ?: return@mapNotNull null
            if (term.binding != B2bTermBinding.FREE || term.level != B2bTermLevel.HEADER) return@mapNotNull null
            UnmappedB2bHeaderValue(
                code = term.officialCode,
                value = command.effectiveB2bHeaderValues[term.officialCode],
            )
        }
        // B3 — a term the office manager already clicked as a free-text chip this save counts as
        // already imported too, so the automatic merge below never appends the same bare value
        // line a second time underneath the one the chip click already wrote.
        val alreadyImported = invoice.texts.importedB2bFreeTextCodes() + command.manuallyImportedB2bCodes
        val result = mergeUnmappedB2bHeaderValues(invoice.texts.freeText, alreadyImported, entries)
        invoice.texts.changeFreeText(result.freeText)
        invoice.texts.replaceImportedB2bFreeTextCodes(result.importedCodes)
    }

    /**
     * Applies seller VAT registration country from [command] onto [invoice], re-deriving it from
     * the new recipient if [recipientChanged] is true, or from the command itself otherwise.
     * Updates article-bound items' tax variants if the country changed.
     * Returns a Pair of (newSellerCountry, previousSellerCountry) for use in format application.
     */
    private fun applySellerVatRegistration(
        invoice: Invoice,
        command: UpdateInvoiceHeaderCommand,
        recipientChanged: Boolean,
    ): Pair<Country?, Country?> {
        // Switching to a (new) non-null seller VAT registration re-derives every
        // article-bound position's tax from that country's Article variant; a position without
        // a bound article, or whose article lacks a variant for the new country, throws
        // (InvoiceItem.applySellerCountryTaxVariant) and aborts the whole header update — "cannot
        // be saved", not a silent partial save. Switching BACK to null (German default)
        // deliberately leaves existing positions' already-frozen tax snapshot untouched:
        // re-running the German tax-resolver path would need buyer context this command doesn't
        // carry, and is out of this method's write scope (TaxRuleResolver's own decision table
        // stays unchanged). applySubmittedReverseChargeDecision (called right after this method)
        // must not undo that either — it only re-normalizes main items on an actual
        // reverse-charge transition.
        val previousSellerCountry = invoice.sellerVatRegistrationCountry
        val effectiveSeller = invoice.selectedSeller ?: invoice.senderProfile
        // Mirrors the same recipient-change behavior as the b2bProfile re-derivation in
        // applyPartyChanges: a recipient *change* re-derives sellerVatRegistrationCountry from
        // the new recipient's country (the same deriveSellerVatRegistrationCountry unit uses
        // at creation) and the command's own sellerVatRegistrationCountry is ignored for this
        // save. An *unchanged* recipient instead applies whatever the entry pane submitted —
        // including the "seller has no registration for X" rejection.
        val newSellerCountry = if (recipientChanged) {
            // Reads straight off newRecipient's own addresses, not invoice.effectiveRecipient
            // BillingAddress()/effectiveRecipientDeliveryAddress() — the latter's delivery half
            // falls back to invoice.deliveryAddress, a header-level field this method never
            // touches, so it could resolve to a stale, previously-set delivery address rather
            // than the new recipient's own. The invoice-creation path has the identical shape:
            // recipient's billing address, then its delivery address.
            val newRecipient = invoice.recipient
            val newRecipientCountry = (
                newRecipient?.billingAddress
                    ?: newRecipient?.let {
                        support.addressManagementService.getDeliveryAddressForCustomer(it).orElse(it.deliveryAddress)
                    }
                )?.country
            deriveSellerVatRegistrationCountry(
                recipientCountry = newRecipientCountry,
                sellerRegistrationCountries = effectiveSeller?.additionalVatRegistrations
                    ?.map { it.country }?.toSet() ?: emptySet(),
            )
        } else {
            command.sellerVatRegistrationCountry?.let {
                val country = Country.fromAlpha2(it)
                    ?: throw InvalidParameterException("Unbekanntes Länderkürzel '$it' für sellerVatRegistrationCountry.")
                val hasRegistration = effectiveSeller?.additionalVatRegistrations?.any { reg -> reg.country == country } == true
                if (!hasRegistration) {
                    throw InvalidParameterException(
                        "Verkäufer '${effectiveSeller?.name}' hat keine USt-IdNr.-Registrierung für ${country.alpha2}."
                    )
                }
                country
            }
        }
        invoice.updateHeader(invoice.headerChange().copy(sellerVatRegistrationCountry = newSellerCountry))
        if (newSellerCountry != null && newSellerCountry != previousSellerCountry) {
            // Blocking semantics — every item here is article-bound (the filter below), so
            // there is no reference-rate fallback to offer: a missing ArticleTaxVariant for
            // newSellerCountry must throw (InvoiceItem.applySellerCountryTaxVariant) and abort the
            // whole save, never silently substitute a generic reference rate for a real article.
            invoice.itemList.filter { it.articleRef != null }
                .forEach { item -> item.applySellerCountryTaxVariant(newSellerCountry) }
        }
        return Pair(newSellerCountry, previousSellerCountry)
    }

    /**
     * Applies invoice format to [invoice] based on [newSellerCountry] and [previousSellerCountry]
     * from [command], respecting editability constraints. Detects and applies
     * the one-time registration-activation default: switching to a
     * non-null seller VAT registration preselects INTERNATIONAL format (unless the recipient's
     * country is German-business-language).
     */
    private fun applyInvoiceFormat(
        invoice: Invoice,
        command: UpdateInvoiceHeaderCommand,
        newSellerCountry: Country?,
        previousSellerCountry: Country?,
    ) {
        // ACTIVATING a foreign seller VAT registration
        // ("Leistungserbringung in diesem Land") preselects the English document except for the
        // German-business-language countries — the sibling of setReverseChargeDecision's
        // Reverse-Charge preselect, same one-time semantics: it fires only on the null→set
        // transition, i.e. while the format field was still force-GERMAN and the command can
        // only carry that forced value; every later explicit choice flows through the block
        // below untouched.
        val registrationJustActivated = newSellerCountry != null && previousSellerCountry == null

        // invoiceFormat is only meaningful while a seller VAT registration is active;
        // null/GERMAN sellerCountry forces it back to GERMAN regardless of what the command asked
        // for, so INTERNATIONAL can never survive into a state where choosing it made no sense
        // ("on switching back to Germany, the field falls back to GERMAN").
        //
        // A Reverse-Charge invoice is the one other case
        // where a null sellerVatRegistrationCountry is entirely expected (Reverse-Charge's own
        // precondition, see Invoice.reverseChargeEligibility()) yet an English document is exactly
        // as sensible as for the seller-registration path — a real invoice had to stay
        // GERMAN for a French recipient purely because of this coupling, with no domain reason.
        // invoice.reverseCharge is read here, not derived: setReverseChargeDecision() is its only
        // writer, so by the time a later updateHeader() call runs, it already reflects whatever
        // the header toggle last decided. Both conditions are the single predicate
        // isInvoiceFormatEditable() also backs the entry pane's ComboBox with (InvoiceFormatModule)
        // — kept as one function so the two enforcement points cannot silently diverge.
        var format = if (!isInvoiceFormatEditable(newSellerCountry, invoice.reverseCharge)) InvoiceFormat.GERMAN
            else command.invoiceFormat?.let {
                runCatching { InvoiceFormat.valueOf(it) }.getOrElse {
                    throw InvalidParameterException("Unbekannte Rechnungsart '${command.invoiceFormat}'.")
                }
            } ?: InvoiceFormat.GERMAN
        if (registrationJustActivated && newSellerCountry !in Country.GERMAN_BUSINESS_LANGUAGE_COUNTRIES) {
            format = InvoiceFormat.INTERNATIONAL
        }
        invoice.updateHeader(invoice.headerChange().copy(invoiceFormat = format))
    }

    /**
     * Finalizes header update: recalculates totals, validates the invoice, stamps audit info,
     * saves to repository, and fires domain events. Returns the saved invoice.
     */
    private fun finalizeHeaderUpdate(invoice: Invoice): Invoice {
        invoice.recalculateTotals()
        // finding-fixes (2026-08-31, PO order) — same draft-save exemption as save() above.
        invoice.validate(requireItems = false)
        invoice.stampForPersist(auditActorProvider.currentActor())
        val saved = invoiceRepo.save(invoice)
        invoice.pullDomainEvents().forEach { eventDispatcher.publishAfterCommit(it) }
        return saved
    }
}
