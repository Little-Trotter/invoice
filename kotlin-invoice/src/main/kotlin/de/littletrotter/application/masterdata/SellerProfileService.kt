package de.littletrotter.application.masterdata

import de.littletrotter.domain.masterdata.copyEditableFieldsFrom
import de.littletrotter.domain.masterdata.changeInvoicePrefix
import de.littletrotter.domain.shared.stampForPersist

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.exception.DuplicateInvoicePrefixException
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.LanguageRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.value.EmailValidator
import de.littletrotter.domain.value.PostalCodeValidator
import de.littletrotter.domain.value.IbanValidator
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Application service for managing sender profiles.
 * Consolidates all functions for managing seller profiles.
 */
@ApplicationScoped
class SellerProfileService @Inject constructor(
    private val senderProfileRepository: SenderProfileRepository,
    private val addressManagementService: AddressManagementService,
    private val auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider(),
    private val masterDataNumberService: MasterDataNumberService,
    // The delete-guard's invoice-usage check; see delete()'s own KDoc.
    private val invoiceLookup: InvoiceLookupRepository,
    // Resolves [save]'s [SellerDefaultSelections] ids against the real catalogue rows: the dialog
    // only ever hands over read-model rows (PaymentTermDto), never the relations.
    private val defaultReferences: SellerDefaultReferences,
    // Resolves [save]'s [invoiceNotesByLanguage] codes against the real Language rows, same reason.
    private val languageRepository: LanguageRepository,
    private val time: TimeSource,
) {
    fun findById(id: Long): SenderProfile? = senderProfileRepository.findById(id)

    /**
     * anbieter-improve A1.1 — the load path for the edit dialog: transactional and complete
     * (`addresses` + `defaultPaymentTerm`), so the form never reads an uninitialized relation off
     * a detached grid row. The grid keeps using [findAll], which stays lean on purpose.
     *
     * Fails loudly when the profile cannot be loaded — the caller must not open an empty dialog
     * over an existing record (A1.2).
     */
    @Transactional
    fun findByIdForEdit(id: Long): SenderProfile? = senderProfileRepository.findByIdForEdit(id)

    fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: de.littletrotter.domain.shared.GridSort): List<SenderProfile> =
        senderProfileRepository.findAllSorted(pageIndex, pageSize, filterText, sort)

    fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfile> =
        senderProfileRepository.findAll(pageIndex, pageSize, filterText)

    fun count(filterText: String?): Long = senderProfileRepository.count(filterText)

    /**
     * @param invoiceNotesByLanguage the seller's invoice-note text per language, keyed by
     *   language code (not the entity — the dialog only ever holds the read-model row); resolved
     *   here against [languageRepository] so [SenderProfile.changeInvoiceNote] can build a new entry
     *   directly. Deliberately outside [SenderProfile.copyEditableFieldsFrom]'s plain
     *   field-mirror, the same "separately synced" shape as [SenderProfile
     *   .updateVatRegistrations]: a blank value removes that language's entry, and
     *   removing the last entry removes the whole group (orphanRemoval) — logic that belongs in
     *   [SenderProfile.changeInvoiceNote], not in a copy loop. A code absent from this map is
     *   left untouched (e.g. the multilingual-texts feature flag hid its field this time; an
     *   already-captured text must not be silently deleted just because it was not offered for
     *   editing).
     * @param defaults the seller's own default payment term, by id,
     *   `null` clearing it. No default value: every caller states what the saved profile
     *   references, because a silent default would clear an existing relation. [profile] never carries those relations itself;
     *   they are resolved here through [defaultReferences] instead.
     */
    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    fun save(
        profile: SenderProfile,
        proposedSellerNumber: String? = null,
        invoiceNotesByLanguage: Map<String, String?> = emptyMap(),
        defaults: SellerDefaultSelections,
    ): SenderProfile {
        val isNewProfile = profile.id == null
        profile.changeInvoicePrefix(profile.invoicePrefix.ifBlank { "RE-" })

        // Pre-check (defined behaviour, user message) so `uq_sender_profile_invoice_prefix` never
        // logs a Postgres ERROR for it; runs before resolveToSave/revokeOtherDefaults, whose
        // flushes would otherwise hit the constraint first. A concurrent race still reaches the
        // DB constraint and stays visible as ERROR.
        assertInvoicePrefixAvailable(profile)

        // Must run before resolveToSave: for an existing profile that call copies the submitted
        // fields (including isDefault = true) onto the managed entity, and the JPQL query behind
        // findDefaults() then auto-flushes that dirty entity BEFORE the old default has been
        // revoked — two rows with is_default = TRUE at once, which the partial unique index on
        // sender_profile rejects. Revoking first flushes the old default's row to FALSE while the
        // profile being saved is still untouched in the persistence context.
        revokeOtherDefaults(profile)

        val toSave = resolveToSave(profile)
        toSave.changeDefaultPaymentTerm(defaultReferences.paymentTerm(defaults.paymentTermId))

        // Create/update/delete the seller's invoice-note group inside this same
        // transaction, on whichever entity is actually about to be persisted (see the KDoc above).
        invoiceNotesByLanguage.forEach { (code, value) ->
            languageRepository.findByCode(code)?.let { language -> toSave.changeInvoiceNote(language, value) }
        }
        toSave.invoiceNote?.validate()

        validate(toSave)

        toSave.ensureShortHash()

        if (toSave.id == null) masterDataNumberService.assignSellerNumber(toSave, proposedSellerNumber)

        toSave.stampForPersist(auditActorProvider.currentActor())
        val saved = if (isNewProfile) {
            masterDataNumberService.saveSellerWithNumber(toSave, proposedSellerNumber) {
                senderProfileRepository.saveAndFlush(toSave)
            }
        } else {
            senderProfileRepository.save(toSave)
        }

        return ensureAtLeastOneDefault(saved)
    }

    private fun assertInvoicePrefixAvailable(profile: SenderProfile) {
        if (!InvoiceNumber.isValidPrefix(profile.invoicePrefix)) {
            // Defence in depth behind the form binder: hostile values never reach the DB.
            throw InvalidParameterException("Invoice prefix must be 1-20 letters, digits or '-' and start with a letter or digit.")
        }
        val other = senderProfileRepository.findByInvoicePrefix(profile.invoicePrefix)
        if (other != null && other.id != profile.id) throw DuplicateInvoicePrefixException(profile.invoicePrefix)
    }

    // For existing profiles: reload from DB within this transaction so that Hibernate's
    // dirty-tracking (not em.merge with a potentially-stale @Version) drives the UPDATE. This
    // prevents OptimisticLockException caused by stale versions on detached entities. Extracted
    // out of [save] to keep that method's own cognitive complexity down.
    private fun resolveToSave(profile: SenderProfile): SenderProfile =
        if (profile.id != null) resolveExistingToSave(profile) else resolveNewToSave(profile)

    private fun resolveExistingToSave(profile: SenderProfile): SenderProfile {
        val managed = senderProfileRepository.findById(profile.id!!) ?: return profile
        if (profile.version == null || managed.version != profile.version) {
            throw InvalidParameterException("Das Anbieterprofil wurde inzwischen geändert. Bitte neu laden.")
        }
        // Must run before the additionalVatRegistrations sync below: it compares
        // the currently persisted registrations against [profile]'s submitted ones (the
        // "before" and "about to become" states), so it has to see the DB's still-true
        // "before" first.
        assertNoVatRegistrationRemovalWhileOpenInvoicesReference(managed, profile)
        // [profile] is the caller's own (typically detached, see
        // SellerVatRegistrationsModule.write / SenderProfileView.applyFormData) entity,
        // never [managed] itself once [profile.id] already exists: copyEditableFieldsFrom
        // below never touches additionalVatRegistrations (it isn't part of its mirror
        // contract), so without this explicit sync a caller's registration change would be
        // silently dropped on every UPDATE — the exact `copyEditableFieldsFrom limitation`
        // the V2 IT test already documents and works around by saving through the
        // repository directly instead of through this service.
        managed.updateVatRegistrations(
            profile.additionalVatRegistrations.map { SellerVatRegistrationCommand(it.country, it.vatId, it.taxNumber) },
            time.today(),
        )
        senderProfileRepository.forceVersionIncrement(managed)
        // The mirror contract with
        // SenderProfileFormData.applyTo lives in the entity now (one list instead of
        // two hand-synchronized ones; parity is test-pinned).
        managed.copyEditableFieldsFrom(profile)
        return managed
    }

    private fun resolveNewToSave(profile: SenderProfile): SenderProfile {
        profile.updateVatRegistrations(
            profile.additionalVatRegistrations.map { SellerVatRegistrationCommand(it.country, it.vatId, it.taxNumber) },
            time.today(),
        )
        return profile
    }

    private fun revokeOtherDefaults(submitted: SenderProfile) {
        if (!submitted.isDefault) return
        senderProfileRepository.findDefaults()
            .filter { it.id != submitted.id }
            .forEach {
                it.revokeDefault()
                it.stampForPersist(auditActorProvider.currentActor())
                senderProfileRepository.save(it)
            }
    }

    /**
     * ST-1 invariant 1 — this read-then-write is not atomic: two concurrent `create()` calls can
     * both read `findDefault() == null` and both try to claim it here. Deliberately left
     * unguarded by any lock (a `pg_advisory_xact_lock` spanning `save()`'s whole transaction was
     * tried and reverted — it serialized every concurrent sender write behind one global lock for
     * the entire method body, which then still nested a second pooled connection for the
     * number allocation, and starved the Agroal connection pool under ST-1's 100-thread load). The
     * partial unique index `uq_sender_profile_single_default` is the actual source of truth under
     * concurrency: Postgres blocks the losing writer's conflicting `UPDATE` on the pending index
     * entry, then raises the violation once the winner commits, which
     * [de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository.save] translates
     * into [de.littletrotter.domain.exception.DefaultSenderProfileAlreadySetException] instead of
     * a raw `ConstraintViolationException` — the loser's own `create()` rolls back and the caller
     * sees a readable, `InvoiceDomainException`-classified rejection instead of a crash.
     */
    private fun ensureAtLeastOneDefault(saved: SenderProfile): SenderProfile {
        if (saved.isDefault || senderProfileRepository.findDefault() != null) return saved
        saved.markAsDefault()
        saved.stampForPersist(auditActorProvider.currentActor())
        return senderProfileRepository.save(saved)
    }

    /**
     * Deleting a sender still referenced by an invoice (`invoice.sender_profile_id` has
     * no `ON DELETE` clause, i.e. Postgres default `NO ACTION`) used to reach the flush with no
     * guard at all: Hibernate's flush-ordering rejected it with a `TransientPropertyValueException`,
     * surfacing to the caller as a `RollbackException` at commit — a low-level infrastructure
     * error, not a readable business exception. Blocked here instead, same pattern as
     * [de.littletrotter.application.reference.PaymentTermService.deletePaymentTerm].
     */
    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    fun delete(id: Long) {
        if (invoiceLookup.findBySenderProfileId(id, 0, 1).isNotEmpty()) {
            throw InvalidParameterException(
                "Anbieter kann nicht gelöscht werden, weil er noch von mindestens einer Rechnung verwendet wird.",
            )
        }
        val toDelete = senderProfileRepository.findById(id)
        senderProfileRepository.delete(id)

        if (toDelete?.isDefault == true) {
            senderProfileRepository.findDefault() ?: senderProfileRepository.findAll(0, 1)
                .firstOrNull()?.let {
                    it.markAsDefault()
                    it.stampForPersist(auditActorProvider.currentActor())
                    senderProfileRepository.save(it)
                }
        }
    }

    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    fun saveAddress(
        senderId: Long,
        type: Address.AddressType,
        street: String,
        postalCode: String,
        city: String,
        country: String
    ): Address {
        val s = senderProfileRepository.findById(senderId) ?: throw IllegalArgumentException("Sender not found: $senderId")
        return addressManagementService.saveAddress(s, type, street, postalCode, city, country)
    }

    fun getDefaultSeller() = addressManagementService.defaultSeller

    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    fun setDefaultSeller(sellerId: Long) {
        val s = senderProfileRepository.findById(sellerId) ?: throw IllegalArgumentException("Sender not found: $sellerId")
        addressManagementService.setDefaultSeller(s)
    }

    /**
     * Removing an `additionalVatRegistration` is blocked
     * while a NON-finalized invoice (`DRAFT`/`IN_REVIEW`) still references that country via
     * [de.littletrotter.domain.invoice.Invoice.sellerVatRegistrationCountry]. A
     * `ISSUED`/`CANCELLED` invoice carries its own frozen party/tax snapshot and stays
     * correct regardless of what happens to the seller's master data afterwards, so it never blocks
     * a removal. Compares the currently PERSISTED registrations
     * ([SenderProfileRepository.findPersistedAdditionalVatCountries] — deliberately not
     * [existing].additionalVatRegistrations itself, see that method's KDoc for why the in-memory
     * collection can no longer be trusted as the "before" state here) against [incoming]'s
     * submitted ones — the countries present in the former but absent from the latter are the
     * ones about to be removed.
     */
    private fun assertNoVatRegistrationRemovalWhileOpenInvoicesReference(existing: SenderProfile, incoming: SenderProfile) {
        val senderId = existing.id ?: return
        val remainingCountries = incoming.additionalVatRegistrations.map { it.country }.toSet()
        val removedCountries = senderProfileRepository.findPersistedAdditionalVatCountries(senderId) - remainingCountries
        removedCountries.forEach { country ->
            val openInvoiceCount = invoiceLookup.countOpenInvoicesForSellerVatCountry(senderId, country)
            if (openInvoiceCount > 0) {
                throw InvalidParameterException(
                    "Die USt-Registrierung für Land ${country.alpha2} kann nicht entfernt werden: " +
                        "$openInvoiceCount nicht abgeschlossene Rechnung(en) verweisen noch auf dieses Land.",
                )
            }
        }
    }

    fun validate(profile: SenderProfile) {
        if (profile.name.isBlank()) {
            throw InvalidParameterException("Name is a required field.")
        }
        if (profile.email?.value.isNullOrBlank()) {
            throw InvalidParameterException("Email is a required field.")
        }
        if (profile.taxNumber == null) {
            throw InvalidParameterException("Tax number is a required field.")
        }
        if (profile.vatId == null) {
            throw InvalidParameterException("VAT ID is a required field.")
        }

        // Country-gated PLZ rule (strict DE shape only for German addresses, lenient
        // length rule otherwise); server-side backstop behind the dialog's inline validation.
        val countryAlpha2 = profile.billingAddress?.country?.alpha2
        PostalCodeValidator.check(countryAlpha2, profile.billingAddress?.postalCode?.value)?.let {
            throw InvalidParameterException(it.message)
        }
        // GermanTaxNumberValidator's format check used to run here too, deferred, only
        // logging a warning; TaxNumber's own init now runs it eagerly at construction, so a
        // format-invalid, non-blank tax number can no longer reach this point to warn about.

        // VatId's own init already runs VatIdValidator.validate eagerly at construction;
        // the deferred format re-check (a log-only warning) that used to run here is now
        // unreachable dead code, deleted.

        IbanValidator.validate(profile.iban?.value)?.let { throw InvalidParameterException(it) }
        EmailValidator.validate(profile.email?.value).let {
            if (it != null) throw InvalidParameterException(it)
        }
    }
}
