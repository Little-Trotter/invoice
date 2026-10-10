package de.littletrotter.application.masterdata

import de.littletrotter.domain.shared.stampForPersist

import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.AddressRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.value.PostalCode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.util.*

@ApplicationScoped
class AddressManagementService @Inject constructor(
    val customerAddressRepository: AddressRepository,
    val senderRepository: SenderProfileRepository,
    val invoiceRepository: de.littletrotter.domain.repository.InvoiceRepository,
    private val auditActorProvider: AuditActorProvider = SystemAuditActorProvider(),
) {

    /**
     * Get billing address for customer, fallback to first billing or default
     */
    fun getBillingAddressForCustomer(customer: Recipient): Optional<Address> {
        val billing = customerAddressRepository
            .findByRecipientAndType(customer, Address.AddressType.BILLING)
            .firstOrNull { it.isDefault }
            ?: customerAddressRepository.findByRecipientAndType(customer, Address.AddressType.BILLING).firstOrNull()
            ?: customerAddressRepository.findDefaultByRecipient(customer)
        return Optional.ofNullable(billing)
    }

    /**
     * Get delivery address for customer, fallback to billing address
     */
    fun getDeliveryAddressForCustomer(customer: Recipient): Optional<Address> {
        val delivery = customerAddressRepository
            .findByRecipientAndType(customer, Address.AddressType.DELIVERY)
            .firstOrNull { it.isDefault }
            ?: customerAddressRepository.findByRecipientAndType(customer, Address.AddressType.DELIVERY).firstOrNull()
            ?: getBillingAddressForCustomer(customer).orElse(null)
        return Optional.ofNullable(delivery)
    }

    /**
     * Get default seller
     */
    val defaultSeller: Optional<SenderProfile>
        get() = Optional.ofNullable(senderRepository.findDefault())

    /**
     * Create or update a customer address
     */
    private fun addressIsAssignedToInvoice(address: Address): Boolean {
        val id = address.id ?: return false
        return invoiceRepository.existsByBillingAddressId(id) || invoiceRepository.existsByDeliveryAddressId(id)
    }

    @Transactional
    fun saveAddress(
        customer: Recipient,
        type: Address.AddressType,
        street: String,
        postalCode: String,
        city: String,
        country: String,
    ): Address {
        val existing = customerAddressRepository.findDefaultByRecipientAndType(customer, type)

        // Clear is_default for all addresses of this type BEFORE any INSERT so that
        // Hibernate's INSERT-before-UPDATE flush order cannot trigger the partial unique index.
        customerAddressRepository.clearDefaultByRecipientAndType(customer, type)

        val address = when {
            existing == null ->
                Address.forRecipient(customer, type, street, postalCode, city, Country.fromAlpha2(country) ?: Country.DE, true)
            addressIsAssignedToInvoice(existing) ->
                // Keep existing as historical reference (is_default already cleared above).
                // Create a fresh address with the new data.
                Address.forRecipient(customer, type, street, postalCode, city, Country.fromAlpha2(country) ?: Country.DE, true)
            else ->
                existing.apply {
                    val resolvedCountry = Country.fromAlpha2(country) ?: Country.DE
                    this.street = street
                    this.postalCode = PostalCode.forCountry(resolvedCountry.alpha2, postalCode)
                    this.city = city
                    this.country = resolvedCountry
                }
        }

        address.isDefault = true
        address.validate()
        address.stampForPersist(auditActorProvider.currentActor())
        return customerAddressRepository.save(address)
    }

    /**
     * Find billing address for sender.
     */
    fun getBillingAddressForSender(sender: SenderProfile): Optional<Address> {
        val billing = customerAddressRepository
            .findDefaultBySenderAndType(sender, Address.AddressType.BILLING)
            ?: customerAddressRepository.findBySenderAndType(sender, Address.AddressType.BILLING).firstOrNull()
            ?: customerAddressRepository.findDefaultBySender(sender)
        return Optional.ofNullable(billing)
    }

    /**
     * Get delivery addresses for sender (possibly multiple).
     */
    fun getDeliveryAddressesForSender(sender: SenderProfile): List<Address> {
        val deliveries = customerAddressRepository.findBySenderAndType(sender, Address.AddressType.DELIVERY)
        return deliveries.ifEmpty { emptyList() }
    }

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
        val existing = customerAddressRepository.findDefaultBySenderAndType(sender, type)

        // Clear is_default for all addresses of this type BEFORE any INSERT so that
        // Hibernate's INSERT-before-UPDATE flush order cannot trigger the partial unique index.
        customerAddressRepository.clearDefaultBySenderAndType(sender, type)

        val address = when {
            existing == null ->
                Address.forSender(sender, type, street, postalCode, city, Country.fromAlpha2(country) ?: Country.DE, true)
            addressIsAssignedToInvoice(existing) ->
                // Keep existing as historical reference (is_default already cleared above).
                Address.forSender(sender, type, street, postalCode, city, Country.fromAlpha2(country) ?: Country.DE, true)
            else ->
                existing.apply {
                    val resolvedCountry = Country.fromAlpha2(country) ?: Country.DE
                    this.street = street
                    this.postalCode = PostalCode.forCountry(resolvedCountry.alpha2, postalCode)
                    this.city = city
                    this.country = resolvedCountry
                }
        }

        address.isDefault = true
        address.validate()
        sender.stampForPersist(auditActorProvider.currentActor())
        senderRepository.save(sender)
        address.stampForPersist(auditActorProvider.currentActor())
        return customerAddressRepository.save(address)
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
        senderRepository.findAll().filter { it.isDefault }.forEach {
            it.revokeDefault()
            it.stampForPersist(auditActorProvider.currentActor())
            senderRepository.save(it)
        }
        seller.markAsDefault()
        seller.stampForPersist(auditActorProvider.currentActor())
        senderRepository.save(seller)
    }
}
