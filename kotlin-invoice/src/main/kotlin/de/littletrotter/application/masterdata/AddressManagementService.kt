package de.littletrotter.application.masterdata

import de.littletrotter.domain.shared.stampForPersist

import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.AddressBook
import de.littletrotter.domain.masterdata.AddressInput
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.*

@ApplicationScoped
class AddressManagementService @Inject constructor(
    val recipientRepository: RecipientRepository,
    val senderRepository: SenderProfileRepository,
    val invoiceLookup: InvoiceLookupRepository,
    private val auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider(),
) {

    /**
     * Get billing address for customer, fallback to first billing or default
     */
    fun getBillingAddressForCustomer(customer: Recipient): Optional<Address> {
        val billing = customer.addressList.filter { it.addressType == Address.AddressType.BILLING }
        return Optional.ofNullable(
            billing.firstOrNull { it.isDefault }
                ?: billing.firstOrNull()
                ?: customer.addressList.firstOrNull { it.isDefault },
        )
    }

    /**
     * Get delivery address for customer, fallback to billing address
     */
    fun getDeliveryAddressForCustomer(customer: Recipient): Optional<Address> {
        val delivery = customer.addressList.filter { it.addressType == Address.AddressType.DELIVERY }
        return Optional.ofNullable(
            delivery.firstOrNull { it.isDefault }
                ?: delivery.firstOrNull()
                ?: getBillingAddressForCustomer(customer).orElse(null),
        )
    }

    /**
     * Get default seller
     */
    val defaultSeller: Optional<SenderProfile>
        get() = Optional.ofNullable(senderRepository.findDefault())

    private fun addressIsAssignedToInvoice(address: Address): Boolean {
        val id = address.id ?: return false
        return invoiceLookup.existsByBillingAddressId(id) || invoiceLookup.existsByDeliveryAddressId(id)
    }

    /**
     * Create or update a customer address
     */
    @Transactional
    fun saveAddress(
        customer: Recipient,
        type: Address.AddressType,
        street: String,
        postalCode: String,
        city: String,
        country: String,
    ): Address {
        val address = replaceDefault(
            customer.addressBook, AddressInput(type, street, postalCode, city, Country.fromAlpha2(country) ?: Country.DE),
        ) { recipientRepository.saveAndFlush(customer) }
        address.stampForPersist(auditActorProvider.currentActor())
        recipientRepository.save(customer)
        return address
    }

    /**
     * Find billing address for sender.
     */
    fun getBillingAddressForSender(sender: SenderProfile): Optional<Address> {
        val billing = sender.addressList.filter { it.addressType == Address.AddressType.BILLING }
        return Optional.ofNullable(
            billing.firstOrNull { it.isDefault }
                ?: billing.firstOrNull()
                ?: sender.addressList.firstOrNull { it.isDefault },
        )
    }

    /**
     * Get delivery addresses for sender (possibly multiple).
     */
    fun getDeliveryAddressesForSender(sender: SenderProfile): List<Address> =
        sender.addressList.filter { it.addressType == Address.AddressType.DELIVERY }

    /**
     * Create or update a sender address (billing or delivery)
     */
    @Transactional
    fun saveAddress(
        sender: SenderProfile,
        type: Address.AddressType,
        street: String,
        postalCode: String,
        city: String,
        country: String,
    ): Address {
        val address = replaceDefault(
            sender.addressBook, AddressInput(type, street, postalCode, city, Country.fromAlpha2(country) ?: Country.DE),
        ) { senderRepository.saveAndFlush(sender) }
        sender.stampForPersist(auditActorProvider.currentActor())
        address.stampForPersist(auditActorProvider.currentActor())
        senderRepository.save(sender)
        return address
    }

    /**
     * The old default is cleared and flushed BEFORE the new default row is inserted, so the
     * partial unique index on the default flag never sees two defaults of one type.
     */
    private fun replaceDefault(book: AddressBook, input: AddressInput, flushOwner: () -> Unit): Address {
        val previous = book.revokeDefaults(input.type)
        if (previous != null) flushOwner()
        return book.saveDefault(input, previous, ::addressIsAssignedToInvoice)
    }

    /**
     * Set a seller as default (only one default allowed).
     *
     * ST-1 invariant 1 — same non-atomic read-then-write shape as
     * [de.littletrotter.application.masterdata.SellerProfileService.save]/`.delete`'s own
     * `ensureAtLeastOneDefault`/`revokeOtherDefaults` (see that KDoc for why this is deliberately
     * NOT guarded by an advisory lock: one spanning the whole call was tried and reverted after it
     * starved the connection pool under ST-1 load). The partial unique index
     * `uq_sender_profile_single_default` is what actually prevents two rows ending up default; a
     * residual violation is translated into `DefaultSenderProfileAlreadySetException`
     * ([de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository.save]) rather
     * than a raw `ConstraintViolationException`.
     */
    @Transactional
    fun setDefaultSeller(seller: SenderProfile) {
        senderRepository.findDefault()?.let {
            it.revokeDefault()
            it.stampForPersist(auditActorProvider.currentActor())
            senderRepository.save(it)
        }
        seller.markAsDefault()
        seller.stampForPersist(auditActorProvider.currentActor())
        senderRepository.save(seller)
    }
}
