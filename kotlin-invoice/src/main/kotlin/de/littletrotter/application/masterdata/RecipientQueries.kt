package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.outbound.RecipientReadRepository
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [RecipientQueryPort] on top of [CustomerManagementService]'s reads,
 *  plus [AddressManagementService]'s address lookups and [MasterDataNumberService]'s
 *  customer-number proposal (billing address falls back to the recipient's own column),
 *  plus [findRecipientDetail], delegating to [RecipientDetailMapper] for the
 *  [RecipientDetailDto] assembly (entity fields, buyer's SPI extension pairs, both addresses)
 *  shared with [ManageRecipient]'s own return value. [findRecipientById]/[findRecipients]
 *  delegate to [RecipientReadRepository]'s projection instead — selecting/displaying a
 *  recipient never needs the entity. */
@ApplicationScoped
class RecipientQueries @Inject constructor(
    private val customerManagementService: CustomerManagementService,
    private val addressManagementService: AddressManagementService,
    private val masterDataNumberService: MasterDataNumberService,
    private val recipientDetailMapper: RecipientDetailMapper,
    private val recipientReadRepository: RecipientReadRepository,
) : RecipientQueryPort {

    override fun findRecipientById(id: Long): RecipientSummaryDto? = recipientReadRepository.findRecipientById(id)

    override fun findRecipients(
        pageIndex: Int,
        pageSize: Int,
        filterText: String?,
        sortField: String,
        sortDir: SortDirection,
    ): List<RecipientSummaryDto> = recipientReadRepository.findRecipients(pageIndex, pageSize, filterText, sortField, sortDir)

    override fun countRecipients(filterText: String?): Long = customerManagementService.count(filterText)

    override fun findBillingAddress(recipientId: Long): AddressDto? {
        val recipient = customerManagementService.findById(recipientId) ?: return null
        val address = addressManagementService.getBillingAddressForCustomer(recipient).orElse(null) ?: recipient.billingAddress
        return address?.toAddressDto()
    }

    override fun findDeliveryAddress(recipientId: Long): AddressDto? {
        val recipient = customerManagementService.findById(recipientId) ?: return null
        val address = addressManagementService.getDeliveryAddressForCustomer(recipient).orElse(null) ?: recipient.deliveryAddress
        return address?.toAddressDto()
    }

    override fun proposeCustomerNumber(name: String?): String? = masterDataNumberService.proposeCustomer(name)

    override fun findRecipientDetail(id: Long): RecipientDetailDto? {
        val recipient = customerManagementService.findById(id) ?: return null
        return recipientDetailMapper.toDetailDto(recipient)
    }

    private fun Address.toAddressDto(): AddressDto = AddressDto(
        id = id,
        street = street,
        postalCode = postalCode.value,
        city = city,
        countryAlpha2 = country.alpha2,
    )
}
