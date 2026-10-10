/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.masterdata

import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.exception.DuplicateCustomerNumberException
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.shared.stampForPersist
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.OptimisticLockException
import jakarta.transaction.Transactional

// StringLiteralDuplication — shared by [CustomerManagementService.save]'s pre-check,
// [CustomerManagementService.delete]'s pre-check, and [CustomerManagementService
// .saveOrReportConcurrentDelete]'s post-failure re-check: all three report the identical,
// already-user-facing wording for "this recipient no longer exists", so one constant instead of
// three copies of the same German sentence.
private const val RECIPIENT_DELETED_MEANWHILE = "Der Kunde wurde inzwischen gelöscht."

/**
 * Application service for managing recipients (customers).
 */
@ApplicationScoped
class CustomerManagementService @Inject constructor(
    private val recipientRepository: RecipientRepository,
    private val addressManagementService: AddressManagementService,
    private val auditActorProvider: AuditActorProvider = SystemAuditActorProvider(),
    private val masterDataNumberService: MasterDataNumberService,
    // The delete-guard's invoice-usage check; see delete()'s own KDoc.
    private val invoiceRepository: InvoiceRepository,
) {
    fun findById(id: Long): Recipient? = recipientRepository.findById(id)

    fun findAll(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<Recipient> =
        recipientRepository.findAll(pageIndex, pageSize, filterText, sortField, sortDir)

    fun count(filterText: String?): Long = recipientRepository.count(filterText)

    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    fun save(recipient: Recipient, proposedCustomerNumber: String? = null): Recipient {
        if (recipient.id == null) masterDataNumberService.assignCustomerNumber(recipient, proposedCustomerNumber)
        recipient.ensureShortHash()
        recipient.validate()
        recipient.stampForPersist(auditActorProvider.currentActor())

        // Held until commit: concurrent saves of the same number queue here, so the pre-checks
        // below see the winner's committed row instead of racing into the unique index.
        recipient.customerNumber?.value?.takeIf { it.isNotBlank() }?.let { recipientRepository.lockCustomerNumber(it) }

        if (recipient.id != null) {
            // Pre-check (defined behaviour) so `uq_recipient_customer_number` never logs a DB
            // ERROR for an edit into an existing number; a concurrent race still reaches the DB.
            recipient.customerNumber?.value?.takeIf { it.isNotBlank() }?.let { number ->
                val other = recipientRepository.findByCustomerNumberIgnoreCase(number)
                if (other != null && other.id != recipient.id) throw DuplicateCustomerNumberException(number)
            }
            // Reload the currently persisted row and compare its version against the one
            // the caller loaded (mirrors [SellerProfileService.save]/[ArticleManagementService
            // .save]): a caller working from a stale load is rejected here instead of silently
            // overwriting whatever was saved in the meantime. [forceVersionIncrement] below then
            // bumps this aggregate's own version on EVERY save, including one where only a child
            // [de.littletrotter.domain.masterdata.Address] row actually changed — otherwise a
            // genuinely concurrent pair of saves (each loaded before either committed) would still
            // pass this check and only be caught by the DB's own optimistic-lock check at commit.
            val managed = recipientRepository.findById(recipient.id!!)
                ?: throw InvalidParameterException(RECIPIENT_DELETED_MEANWHILE)
            if (managed.version != recipient.version) {
                throw OptimisticLockException("Der Kunde wurde inzwischen von jemand anderem geändert. Bitte neu laden.")
            }
            val saved = saveOrReportConcurrentDelete(recipient)
            recipientRepository.forceVersionIncrement(saved)
            return saved
        }

        // Case-insensitive duplicate rejected here, before ever reaching the DB's
        // functional unique index; see MasterDataNumberService.assertCustomerNumberAvailable's
        // KDoc for why the untouched-proposal case is exempt (retry, right below).
        masterDataNumberService.assertCustomerNumberAvailable(recipient.customerNumber?.value ?: "", proposedCustomerNumber) {
            recipientRepository.findByCustomerNumberIgnoreCase(it)
        }

        return masterDataNumberService.saveCustomerWithNumber(
            recipient,
            proposedCustomerNumber,
            isTaken = { recipientRepository.findByCustomerNumberIgnoreCase(it) != null },
        ) {
            recipientRepository.saveAndFlush(recipient)
        }
    }

    /**
     * ST-1 update-vs-delete race — [save]'s own version compare above only catches a recipient
     * deleted BEFORE its `findById` reload ran; a delete that commits in the window between that
     * reload and [recipientRepository]'s own immediate flush (`PanacheRecipientRepository.save`,
     * ST-B4) makes the UPDATE affect zero rows, which Hibernate reports as the SAME ambiguous
     * [OptimisticLockException] as a genuine version conflict — the same class of race
     * [ConcurrentCrudStressE2EIT]'s `recovery-after-optlock[article.update]` case (0.9.10) caught
     * for [ArticleManagementService.save]'s twin of this method (see that method's own KDoc).
     * `opRecipientDelete` races `opRecipientUpdate` on the same pool of rows in that same storm,
     * so this is the same fix, not a hypothetical one. Re-checking existence right after the
     * failure tells the two cases apart and reports the SAME readable "wurde inzwischen
     * gelöscht" rejection [save]'s own pre-check throws for the already-caught case, instead of
     * leaking the ambiguous conflict exception.
     */
    private fun saveOrReportConcurrentDelete(recipient: Recipient): Recipient =
        try {
            recipientRepository.save(recipient)
        } catch (ex: OptimisticLockException) {
            if (recipientRepository.findById(recipient.id!!) == null) {
                throw InvalidParameterException(RECIPIENT_DELETED_MEANWHILE, ex)
            }
            throw ex
        }

    /**
     * Deleting a recipient still referenced by an invoice used to reach the flush with no
     * service-level guard at all, surfacing as a raw persistence/rollback error instead of a
     * readable business exception. Blocked here instead, same pattern as [SellerProfileService
     * .delete].
     */
    /**
     * WP2 (0.9.10, concurrent-editing) — [expectedVersion] (the recipient's own version the
     * dialog captured on open, e.g. `RecipientDraft.version`) is checked, when given, against a
     * fresh reload the same way [save] already guards an update: a delete against a row changed
     * concurrently since the editor opened is rejected instead of silently discarding the other
     * session's edit. `null` (the default; internal/non-UI callers) skips the check. A row
     * already deleted meanwhile is reported the same way [save] reports it, not treated as
     * already-succeeded.
     */
    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    fun delete(id: Long, expectedVersion: Long? = null) {
        if (expectedVersion != null) {
            val managed = recipientRepository.findById(id)
                ?: throw InvalidParameterException(RECIPIENT_DELETED_MEANWHILE)
            if (managed.version != expectedVersion) {
                throw OptimisticLockException("Der Kunde wurde inzwischen von jemand anderem geändert. Bitte neu laden.")
            }
        }
        if (invoiceRepository.findByRecipientId(id, 0, 1).isNotEmpty()) {
            throw InvalidParameterException(
                "Kunde kann nicht gelöscht werden, weil er noch von mindestens einer Rechnung verwendet wird.",
            )
        }
        recipientRepository.delete(id)
    }

    /**
     * Coordinator finding (2026-09-18) — [save]'s own fields together with its billing/
     * delivery [Address] rows, in ONE guarded call: [save]'s reload+version-compare+
     * force-increment covers the address writes too, since [AddressManagementService.saveAddress]
     * below runs on the SAME managed, already-locked saved instance, before the eventual flush/
     * commit. Replaces the previous shape — the recipient save entry point used to call [save]
     * and a standalone,
     * unguarded per-address entry point separately, which only stayed correct because both
     * happened to run inside the same caller-managed transaction; that guarantee is now
     * structural (both writes originate from this one method), not incidental. The old
     * standalone entry point had no other caller — removed together with it, per the project's
     * "remove means delete" rule, rather than left behind unguarded and unused.
     */
    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    @Transactional
    fun saveWithAddresses(
        recipient: Recipient,
        addresses: RecipientAddressInput,
        proposedCustomerNumber: String? = null,
    ): Recipient {
        val saved = save(recipient, proposedCustomerNumber)
        addressManagementService.saveAddress(
            saved,
            Address.AddressType.BILLING,
            addresses.billingStreet,
            addresses.billingPostalCode,
            addresses.billingCity,
            addresses.billingCountry,
        )
        val deliveryStreet = addresses.deliveryStreet
        if (deliveryStreet != null) {
            addressManagementService.saveAddress(
                saved,
                Address.AddressType.DELIVERY,
                deliveryStreet,
                addresses.deliveryPostalCode ?: "",
                addresses.deliveryCity ?: "",
                addresses.deliveryCountry ?: "DE",
            )
        }
        return saved
    }
}
