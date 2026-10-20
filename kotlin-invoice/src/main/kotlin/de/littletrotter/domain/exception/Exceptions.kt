/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.domain.exception

sealed class InvoiceDomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Common supertype for "the requested resource does not exist" — lets
 *  [de.littletrotter.application.error.ApplicationExceptionTranslator] map all of them to
 *  a 404-equivalent [de.littletrotter.application.error.ApplicationNotFoundException] in
 *  one branch instead of one `is` check per resource type. */
sealed class InvoiceDomainNotFoundException(message: String, cause: Throwable? = null) : InvoiceDomainException(message, cause)

class InvoiceNotFoundException(id: Long) :
    InvoiceDomainNotFoundException("Invoice not found: $id")

class InvoiceItemNotFoundException(id: Long) :
    InvoiceDomainNotFoundException("Invoice item not found: $id")

/** [de.littletrotter.infrastructure.messaging.GenerationJobResultConsumer.processAttempt]
 *  throws this instead of a bare `RuntimeException` when the result message names a job id no
 *  longer present in the table; [consume]'s own retry loop retries and eventually records this
 *  as a durable failure exactly as it already did for any other exception. */
class GenerationJobNotFoundException(id: Long) :
    InvoiceDomainNotFoundException("GenerationJob not found: $id")

/** Positions are addressed by their user-facing position id, not the numeric row id, in contexts
 *  (e.g. delete-by-position) that never load the item and so never learn it. */
class InvoicePositionNotFoundException(positionId: String) :
    InvoiceDomainNotFoundException("Invoice item not found: position $positionId")

class RecipientNotFoundException(id: Long) :
    InvoiceDomainNotFoundException("Recipient not found: $id")

class SenderProfileNotFoundException(id: Long) :
    InvoiceDomainNotFoundException("Sender profile not found: $id")

/**
 * An invoice that reached this point without a sender profile relation at
 * all. Distinct from [SenderProfileNotFoundException] on purpose: that one names a sender
 * profile id that was looked up and not found, whereas here the invoice's `senderProfile`
 * relation is simply `null` — no id was ever available to look up. The two call sites both
 * used to fake one (`SenderProfileNotFoundException(-1L)`), and `-1` then read like a real,
 * meaningful sender profile id in the message instead of what it actually was: a sentinel for
 * "no id exists here". Naming the invoice instead says the true thing.
 *
 * Both known call sites ([de.littletrotter.application.invoice.InvoiceLifecycleService]'s
 * finalization path, [de.littletrotter.application.invoice.InvoiceCancellationService.createCreditNote])
 * document this as unreachable through the application — [de.littletrotter.domain.invoice.Invoice.validate] requires a sender
 * profile before `ISSUED` — so the case this guards is a row that reached the database past
 * the application's own writes (e.g. demo data).
 */
class InvoiceWithoutSenderProfileException(invoiceId: Long?) :
    InvoiceDomainNotFoundException("Invoice ${invoiceId ?: "(unpersisted)"} has no sender profile")

/** [cause] carries the store-backend failure ([java.io.IOException]/
 *  [software.amazon.awssdk.core.exception.SdkException]) that made the object unreadable, when
 *  [de.littletrotter.application.invoice.ReceiptPoolService.getBytes] translates it; `null` for
 *  every other call site (a genuinely missing row, no store call involved). */
class ReceiptNotFoundException(id: Long, cause: Throwable? = null) :
    InvoiceDomainNotFoundException("Receipt not found: $id", cause)

class InvoiceImmutableException(invoiceNumber: String) :
    InvoiceDomainException("Invoice '$invoiceNumber' is immutable (finalized or cancelled)")

/** A receipt's description/documentDate become
 *  immutable once any invoice it is attached to is issued (ISSUED/CANCELLED); still-draft links
 *  do not lock it. */
class ReceiptLockedByIssuedInvoiceException(val invoiceNumbers: List<String>) :
    InvoiceDomainException(
        "Receipt cannot be edited: attached to issued invoice(s) " + invoiceNumbers.joinToString(", ") { "#$it" },
    )

class InvoiceStatusTransitionException(from: String, to: String) :
    InvoiceDomainException("Cannot transition invoice from $from to $to")

/**
 * PO decision 2026-08-31 (Annullierung) — thrown when a document-generation job (the
 * "E-Rechnungs-Auftrag") is requested for a [de.littletrotter.domain.invoice.InvoiceStatus.VOIDED]
 * invoice: it was never sent and never will be, so no e-invoice document has any legal role left
 * to play for it. Message is readable German directly, like [InvoiceCancellationNotAllowedException] —
 * a business rejection shown as-is, not a technical dev hint.
 */
class InvoiceVoidedGenerationBlockedException(invoiceNumber: String) :
    InvoiceDomainException("Rechnung '$invoiceNumber' ist annulliert und kann keinen E-Rechnungs-Auftrag mehr erzeugen.")

/**
 * Thrown when a document-generation job is requested for an already-issued invoice
 * (status `ISSUED`, or `CANCELLED` — which also covers the cancellation/corrected-invoice document, type
 * code 384, that is born there) that already has one successfully completed generation job.
 * Export object keys are derived deterministically per invoice
 * ([de.littletrotter.shared.DocumentKeyRules.deriveExportKeys]), so a second job would
 * silently overwrite the archived PDF/XML in the S3 store — this is the revision-safety guard
 * that prevents exactly that. A failed/aborted job never counts as "completed", so a retry after
 * a genuine failure still succeeds; `DRAFT`/`IN_REVIEW` invoices are unaffected (see
 * [de.littletrotter.domain.invoice.isIssued]).
 */
class InvoiceImmutableGenerationBlockedException(invoiceNumber: String) :
    InvoiceDomainException("Rechnung '$invoiceNumber' wurde bereits final erzeugt und kann keinen weiteren E-Rechnungs-Auftrag mehr erzeugen.")

/**
 * "Guard against concurrent/duplicate sends" (PO decision 2026-09-13) — thrown when a
 * `SEND`-triggered [de.littletrotter.domain.invoice.GenerationJob] is requested
 * ([de.littletrotter.application.invoice.InvoiceSendService.requestSend]) for an invoice that
 * already has one non-terminal (PENDING/DELEGATED/RUNNING) `SEND` job in flight. A second
 * concurrent "Senden" click must be rejected outright, not silently ignored or queued twice —
 * mirrors [InvoiceImmutableGenerationBlockedException]'s shape.
 */
class InvoiceSendAlreadyInProgressException(invoiceNumber: String) :
    InvoiceDomainException("Rechnung '$invoiceNumber' hat bereits einen laufenden Sendevorgang.")

/**
 * Thrown when a second `PRINT_JOB` of the same invoice and variant (document engine and LLM
 * option) is requested while an earlier one is still PENDING/DELEGATED/RUNNING. The running job
 * already produces exactly that document; a parallel one would only race it for the invoice's
 * document rows, so the request is rejected with a defined message instead of being queued.
 */
class InvoiceGenerationAlreadyRunningException(invoiceNumber: String) :
    InvoiceDomainException("Für Rechnung '$invoiceNumber' läuft bereits ein E-Rechnungs-Auftrag; bitte dessen Abschluss abwarten.")

/**
 * A manual document regeneration was requested for an invoice that has nothing to regenerate:
 * it is not issued yet, or its latest print job did not end FAILED. Refusing keeps the action a
 * pure repair for a failed job, never a second path to a new issued document.
 */
class InvoiceDocumentRegenerationNotApplicableException(invoiceNumber: String) :
    InvoiceDomainException("Für Rechnung '$invoiceNumber' ist keine Neuerzeugung möglich: Sie ist nicht versendet oder der letzte Erzeugungsauftrag ist nicht fehlgeschlagen.")

/**
 * The UI-facing classification of why [de.littletrotter.application.invoice
 * .GenerationJobService.createJob] would currently reject a new generation job for an invoice,
 * mirroring [InvoiceVoidedGenerationBlockedException]/[InvoiceImmutableGenerationBlockedException]
 * one-to-one so a panel can render a disabled button with the right tooltip without triggering and
 * catching one of those exceptions just to find out. `null` (returned by
 * [de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort.generationBlockReason]) means
 * generation is currently allowed.
 */
enum class GenerationBlockReason {
    VOIDED,
    IMMUTABLE,
}

/** Mirrors [InvoiceStatusTransitionException] for [de.littletrotter.domain.shared
 *  .ValidationStatus]; thrown by [de.littletrotter.domain.invoice.Invoice
 *  .transitionValidationStatusTo] on an edge not present in [de.littletrotter.domain.shared
 *  .ValidationStatus.canTransitionTo]'s table. */
class InvoiceValidationStatusTransitionException(from: String, to: String) :
    InvoiceDomainException("Cannot transition invoice validation status from $from to $to")

/**
 * Cancellation was requested for an invoice that is not eligible: a draft (discarded, not
 * cancelled), a document that is already itself a cancellation/corrected invoice (cancellation of a
 * cancellation is not a supported business operation), or already cancelled. [reason] classifies
 * the case so the UI can distinguish without string matching.
 */
class InvoiceCancellationNotAllowedException(
    val reason: CancellationRejection,
    invoiceNumber: String,
) : InvoiceDomainException(reason.readableMessage(invoiceNumber))

enum class CancellationRejection {
    NOT_SENT,
    // PO decision 1 (2026-09-02) — renamed from ALREADY_A_CREDIT_NOTE: the cancellation flow now stamps
    // InvoiceTypeCode.CORRECTED_INVOICE (`384`), not CREDIT_NOTE (`381`), so this rejection now
    // fires on that type instead.
    ALREADY_A_CORRECTED_INVOICE,
    ALREADY_CANCELLED,
    ORIGINAL_NUMBER_MISSING,
}

private fun CancellationRejection.readableMessage(invoiceNumber: String): String = when (this) {
    CancellationRejection.NOT_SENT ->
        "Nur versandte Rechnungen können storniert werden — Rechnung '$invoiceNumber' hat einen anderen Status."
    CancellationRejection.ALREADY_A_CORRECTED_INVOICE ->
        "Rechnung '$invoiceNumber' ist bereits eine Stornorechnung und kann nicht erneut storniert werden."
    CancellationRejection.ALREADY_CANCELLED ->
        "Zu Rechnung '$invoiceNumber' existiert bereits eine Stornorechnung."
    CancellationRejection.ORIGINAL_NUMBER_MISSING ->
        "Rechnung '$invoiceNumber' trägt keine vergebene Rechnungsnummer und kann nicht storniert werden."
}

/**
 * Exception wrapper for an [InvoiceValidationError] code. Human-readable text is resolved
 * in the UI layer from i18n bundles; [args] carry positional parameters for the message.
 */
class InvoiceValidationException(
    val error: InvoiceValidationError,
    val args: List<Any> = emptyList(),
    /** English developer hint — never shown to end users. */
    message: String = error.name,
    cause: Throwable? = null,
) : InvoiceDomainException(message, cause)

/** Machine-readable validation failure codes. Human-readable text lives in i18n bundles. */
enum class InvoiceValidationError {
    INVOICE_NUMBER_MISSING,
    SENDER_PROFILE_MISSING,
    RECIPIENT_MISSING,
    SERVICE_PERIOD_END_BEFORE_START,
    DUE_DATE_BEFORE_INVOICE_DATE,
    PAYMENT_TERM_DAYS_NEGATIVE,
    NET_AMOUNT_NEGATIVE,
    VAT_AMOUNT_NEGATIVE,
    GROSS_AMOUNT_NEGATIVE,
    NO_ITEMS,
    ITEM_POSITION,
    // item-level
    ITEM_POSITION_ID_BLANK,
    ITEM_DESCRIPTION_BLANK,
    ITEM_QUANTITY_NEGATIVE,
    ITEM_TAX_RATE_REFERENCE_MISSING,
    // Seller VAT registration path
    ITEM_ARTICLE_TAX_VARIANT_MISSING,
    // masterdata/sender profile — SenderProfile.validate() field-level checks (previously all
    // misreported as INVOICE_NUMBER_MISSING through the deprecated String constructor)
    SENDER_NAME_BLANK,
    SENDER_INVOICE_PREFIX_BLANK,
    SENDER_EMAIL_MISSING,
    SENDER_TAX_NUMBER_MISSING,
    SENDER_VAT_ID_MISSING,
    SENDER_IBAN_MISSING,
    SENDER_NUMBER_BLANK,
    // masterdata/recipient — Recipient.validate() field-level checks
    RECIPIENT_CUSTOMER_NUMBER_BLANK,
    RECIPIENT_NAME_BLANK,
    RECIPIENT_EMAIL_MISSING,
    RECIPIENT_VAT_ID_MISSING,
    // reference/travel expense entry — TravelExpenseEntry.validate()
    TRAVEL_DESCRIPTION_BLANK,
    // The per-entry traveler, split out of the former single description field.
    TRAVEL_PARTICIPANT_BLANK,
    TRAVEL_DATE_MISSING,
    TRAVEL_FULL_DAY_RATE_NEGATIVE,
    TRAVEL_ARRIVAL_DEPARTURE_RATE_NEGATIVE,
    TRAVEL_CALCULATED_AMOUNT_NEGATIVE,
    TRAVEL_EMPLOYER_CONTRIBUTION_NEGATIVE,
    // invoice/InvoiceNumber — format checks on the value object itself
    INVOICE_NUMBER_INVALID_FORMAT,
    // Header/line service period consistency, checked only right before
    // ISSUED (see de.littletrotter.domain.invoice.validateHeaderLinePeriodConsistency)
    HEADER_LINE_PERIOD_MISMATCH,
    // Reverse charge is now the invoice header's leading decision; positions must
    // follow it (see de.littletrotter.domain.invoice.validateReverseChargeItemConsistency) and
    // the recipient must still qualify for it (see de.littletrotter.domain.compliance
    // .assertReverseChargeRecipientEligibility). All three checked only right before ISSUED.
    REVERSE_CHARGE_ITEM_NOT_AE,
    REVERSE_CHARGE_ITEM_UNEXPECTED_AE,
    REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE,
    // Creating a per-diem position while the "Verpflegungspauschale" feature switch is off
    // (feature_setting, System → Einstellungen). Creation-only; edits of existing items never hit it.
    PER_DIEM_FEATURE_DISABLED,
    // InvoiceItem.validate()'s TRIP-only field invariants (business-trip position).
    ITEM_TRIP_FIXED_PRICE_WITHOUT_TRIP_CATEGORY,
    ITEM_TRIP_FIXED_PRICE_NEGATIVE,
    ITEM_PER_DIEM_ENTRIES_WITHOUT_TRIP_CATEGORY,
    // TripExpense.validate() (a TRIP position's expense line)
    TRIP_EXPENSE_DESCRIPTION_BLANK,
    TRIP_EXPENSE_DESCRIPTION_TOO_LONG,
    TRIP_EXPENSE_QUANTITY_NEGATIVE,
    // TripCommand/TripService validation (application layer)
    TRIP_EXPENSE_RECEIPT_NOT_ATTACHED,
    TRIP_CATEGORY_SWITCH_NOT_SUPPORTED,
    // A per-diem entry's own mandatory description (PerDiemEntryCommand); this constant is
    // repurposed as the block-level description shared by every entry of the per-diem block.
    TRIP_PER_DIEM_DESCRIPTION_BLANK,
    // The per-entry traveler on a PerDiemEntryCommand, split out of the former single
    // per-entry description field.
    TRIP_PER_DIEM_PARTICIPANT_BLANK,
}

class InvalidParameterException(message: String, cause: Throwable? = null) :
    InvoiceDomainException(message, cause)

/**
 * Thrown when [de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository
 * .save] hits `uq_sender_profile_invoice_prefix` (`V1__baseline_schema.sql`) on flush: two
 * concurrent sender-profile saves picked the same invoice prefix. The DB unique index is the
 * source of truth under concurrency — this only turns its raw
 * `org.hibernate.exception.ConstraintViolationException` into a message the UI can show instead
 * of a technical SQL error (any client-side pre-check for the same rule remains a convenience
 * only, never a substitute for this catch).
 */
class DuplicateInvoicePrefixException(invoicePrefix: String, cause: Throwable? = null) :
    InvoiceDomainException("Der Rechnungspräfix '$invoicePrefix' wird bereits von einem anderen Anbieterprofil verwendet.", cause)

/**
 * Thrown when [de.littletrotter.infrastructure.persistence.PanacheRecipientRepository
 * .save] hits `uq_recipient_customer_number` (case-insensitive, `V1__baseline_schema.sql`) on
 * flush: two concurrent recipient saves picked the same customer number. Mirrors
 * [DuplicateInvoicePrefixException]'s reasoning.
 */
class DuplicateCustomerNumberException(customerNumber: String, cause: Throwable? = null) :
    InvoiceDomainException("Die Kundennummer '$customerNumber' wird bereits von einem anderen Kunden verwendet.", cause)

/**
 * Thrown when [de.littletrotter.infrastructure.persistence.PanachePaymentTermRepository
 * .save] hits `uq_payment_term_single_default` (a partial unique index on `is_default = TRUE`,
 * `V1__baseline_schema.sql`) on flush: two concurrent saves both tried to mark a payment term as
 * the default one. Mirrors [DuplicateInvoicePrefixException]'s reasoning.
 */
class DefaultPaymentTermAlreadySetException(paymentTermName: String, cause: Throwable? = null) :
    InvoiceDomainException("Zahlungsbedingung '$paymentTermName' konnte nicht als Standard gesetzt werden — eine andere wurde inzwischen bereits als Standard markiert.", cause)

/**
 * Thrown when [de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository
 * .save] hits `uq_sender_profile_single_default` (a partial unique index on `is_default = TRUE`,
 * `V1__baseline_schema.sql`) on flush: two concurrent sender-profile writes both tried to mark a
 * profile as the default one. Mirrors [DefaultPaymentTermAlreadySetException]'s reasoning — and,
 * unlike that sibling, IS the primary fix here, not a backstop: an advisory lock serializing
 * [de.littletrotter.application.masterdata.SellerProfileService.save]/`.delete` was tried and
 * reverted (it starved the connection pool under ST-1's 100-thread load — see
 * `ensureAtLeastOneDefault`'s own KDoc). The unique index is left to do the actual serializing;
 * this only turns its violation into a readable, `InvoiceDomainException`-classified (EXPECTED)
 * rejection instead of a raw `ConstraintViolationException`.
 */
class DefaultSenderProfileAlreadySetException(senderProfileName: String, cause: Throwable? = null) :
    InvoiceDomainException("Anbieterprofil '$senderProfileName' konnte nicht als Standard gesetzt werden — ein anderes wurde inzwischen bereits als Standard markiert.", cause)
