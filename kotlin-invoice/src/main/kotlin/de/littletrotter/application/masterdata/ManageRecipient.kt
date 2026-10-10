package de.littletrotter.application.masterdata

import de.littletrotter.shared.document.DocumentLanguage
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientDraft
import de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase
import de.littletrotter.application.reference.B2bProfileLifecycleService
import de.littletrotter.domain.masterdata.LegalRegistrationIdentifier
import de.littletrotter.domain.masterdata.PartyIdentifier
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.shared.time.TimeSource
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.VatId
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.OptimisticLockException
import jakarta.transaction.Transactional

/**
 * Implements [ManageRecipientUseCase] on top of [CustomerManagementService]'s existing writes.
 * Turns the dialog's [RecipientDraft] into the `Recipient` entity: a new one, or the persisted
 * row loaded by [RecipientDraft.id] — the version carried on the draft is checked against that
 * loaded row's own version here too (in addition to whatever check the caller already ran), so a
 * stale write is rejected regardless of caller. The buyer's SPI extension pairs are written via
 * [PartyFormExtensionRegistry]/[de.littletrotter.spi.party.PartyFormExtension.write], and the
 * VAT-id verification status/timestamp are reconciled from [RecipientDraft
 * .vatIdValidatedByUser]/[RecipientDraft.pendingVatIdVerification] against the loaded row — the
 * same reconciliation the dialog used to run for itself, moved here so the entity it operates on
 * is always the one actually about to be persisted.
 *
 * Both address writes happen inside ONE call to
 * [CustomerManagementService.saveWithAddresses], on the SAME managed, already-guarded instance
 * [CustomerManagementService.save] returns; see that method's own KDoc.
 */
@ApplicationScoped
class ManageRecipient @Inject constructor(
    private val customerManagementService: CustomerManagementService,
    private val recipientDetailMapper: RecipientDetailMapper,
    private val partyFormExtensionRegistry: PartyFormExtensionRegistry,
    private val b2bProfileService: B2bProfileLifecycleService,
    private val time: TimeSource,
) : ManageRecipientUseCase {

    @Transactional
    override fun saveRecipientWithAddresses(draft: RecipientDraft): RecipientDetailDto {
        val normalized = draft.addresses.trimmed()
        require(
            normalized.billingStreet.isNotBlank() &&
                normalized.billingPostalCode.isNotBlank() &&
                normalized.billingCity.isNotBlank(),
        ) { "Recipient billing address must include street, postal code and city." }

        val loaded = draft.id?.let { id ->
            val existing = customerManagementService.findById(id)
                ?: throw OptimisticLockException("Der Kunde wurde inzwischen gelöscht.")
            if (existing.version != draft.version) {
                throw OptimisticLockException("Der Kunde wurde inzwischen von jemand anderem geändert. Bitte neu laden.")
            }
            existing
        }
        val target = loaded ?: Recipient()

        applyDraft(target, draft)
        reconcileVatIdVerification(target, draft, loaded)
        partyFormExtensionRegistry.enabledAspects(PartyKind.BUYER).forEach { (spec, extension) ->
            extension.write(target, PartyKind.BUYER, spec, draft.partyPairs[spec.id] ?: PartyPairState())
        }

        val saved = customerManagementService.saveWithAddresses(target, normalized, draft.proposedCustomerNumber)
        return recipientDetailMapper.toDetailDto(saved)
    }

    private fun applyDraft(target: Recipient, draft: RecipientDraft) {
        target.customerNumber = CustomerNumber.ofNullable(draft.customerNumber)
        target.name = draft.name
        target.vatId = VatId.ofNullable(draft.vatId)
        target.vatIdVerificationNote = draft.vatIdValidationNote?.trim()?.ifBlank { null }
        target.iban = Iban.ofNullable(draft.iban)
        target.routingId = draft.routingId
        target.email = Email.ofNullable(draft.email)
        target.phone = draft.phone
        target.contactPerson = draft.contactPerson
        target.customerType = draft.customerType
        target.invoiceFormat = draft.invoiceFormat
        target.documentLanguage = DocumentLanguage.fromCode(draft.documentLanguage).code
        target.reverseChargePreference = draft.reverseChargePreference
        target.buyerIdentifier = toPartyIdentifier(draft.buyerIdentifierValue, draft.buyerIdentifierSchemeId)
        target.legalRegistrationIdentifier = toLegalRegistrationIdentifier(draft.legalRegistrationValue, draft.legalRegistrationSchemeId)
        target.b2bProfile = draft.b2bProfileId?.let { b2bProfileService.findProfileById(it) }
    }

    private fun toPartyIdentifier(value: String?, schemeId: String?): PartyIdentifier? =
        if (value.isNullOrBlank() && schemeId.isNullOrBlank()) null else PartyIdentifier.of(value?.trim().orEmpty(), schemeId?.trim().orEmpty())

    private fun toLegalRegistrationIdentifier(value: String?, schemeId: String?): LegalRegistrationIdentifier? =
        if (value.isNullOrBlank() && schemeId.isNullOrBlank()) {
            null
        } else {
            LegalRegistrationIdentifier.of(value?.trim().orEmpty(), schemeId?.trim().orEmpty())
        }

    /**
     * Three cases, in order, mirroring the dialog's former in-memory reconciliation:
     *  1. [RecipientDraft.pendingVatIdVerification] was recorded for the exact `vatId` now on
     *     [target] → apply it.
     *  2. Otherwise, if [loaded] exists and its vat id differs from [target]'s new one → the id
     *     changed since it was loaded (or [loaded] is `null`, a brand-new recipient): any prior
     *     verification no longer belongs to it, reset to `UNVERIFIED`/`null`.
     *  3. Otherwise (unchanged id, no fresh automated verification this session) — the manual
     *     checkbox: a transition to checked sets `VERIFIED` and stamps `verifiedAt = now`; a
     *     transition to unchecked sets `UNVERIFIED` and clears it; no transition leaves the
     *     loaded verification columns untouched (`FAILED` must not be overwritten with
     *     `UNVERIFIED` just because the box was never checked).
     */
    private fun reconcileVatIdVerification(target: Recipient, draft: RecipientDraft, loaded: Recipient?) {
        val pending = draft.pendingVatIdVerification
        if (pending != null && pending.vatId == (target.vatId?.value ?: "")) {
            target.vatIdVerificationStatus = pending.status
            target.vatIdVerifiedAt = pending.verifiedAt
            return
        }
        if (loaded != null && target.vatId != loaded.vatId) {
            target.vatIdVerificationStatus = VatIdVerificationStatus.UNVERIFIED
            target.vatIdVerifiedAt = null
            return
        }
        val wasChecked = loaded?.vatIdVerificationStatus == VatIdVerificationStatus.VERIFIED
        val isChecked = draft.vatIdValidatedByUser
        if (isChecked && !wasChecked) {
            target.vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            target.vatIdVerifiedAt = time.nowUtc()
        } else if (!isChecked && wasChecked) {
            target.vatIdVerificationStatus = VatIdVerificationStatus.UNVERIFIED
            target.vatIdVerifiedAt = null
        }
    }

    override fun deleteRecipient(id: Long, expectedVersion: Long?) = customerManagementService.delete(id, expectedVersion)
}
