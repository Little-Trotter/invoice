package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.AddressDto
import de.littletrotter.application.dto.SenderProfileDetailDto
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.application.dto.SenderProfileSort
import de.littletrotter.application.masterdata.port.inbound.SenderProfileQueryPort
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.shared.GridSort
import de.littletrotter.spi.party.PartyKind
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/** Implements [SenderProfileQueryPort] on top of [SellerProfileService]'s reads. */
@ApplicationScoped
class SenderProfileQueries @Inject constructor(
    private val sellerProfileService: SellerProfileService,
    private val partyFormExtensionRegistry: PartyFormExtensionRegistry,
    private val sellerVatRegistrationsExtensionRegistry: SellerVatRegistrationsExtensionRegistry,
) : SenderProfileQueryPort {

    @Transactional
    override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfileRowDto> =
        sellerProfileService.findAll(pageIndex, pageSize, filterText).map { it.toRowDto() }

    @Transactional
    override fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: SenderProfileSort): List<SenderProfileRowDto> =
        sellerProfileService.findAllSorted(pageIndex, pageSize, filterText, GridSort(sort.field, sort.direction)).map { it.toRowDto() }

    override fun count(filterText: String?): Long = sellerProfileService.count(filterText)

    @Transactional
    override fun findRowById(id: Long): SenderProfileRowDto? = sellerProfileService.findById(id)?.toRowDto()

    @Transactional
    override fun findForEdit(id: Long): SenderProfileDetailDto? = sellerProfileService.findByIdForEdit(id)?.toDetailDto()

    private fun SenderProfile.toRowDto(): SenderProfileRowDto = SenderProfileRowDto(
        id = requireNotNull(id) { "SenderProfile must be persisted before it can be mapped to SenderProfileRowDto." },
        version = version,
        name = name,
        isDefault = isDefault,
        invoicePrefix = invoicePrefix,
        sellerNumber = sellerNumber,
        shortHash = shortHash,
        billingCity = billingAddress?.city,
        billingCountryAlpha2 = billingAddress?.country?.alpha2,
    )

    private fun SenderProfile.toDetailDto(): SenderProfileDetailDto {
        val address = billingAddress
        return SenderProfileDetailDto(
            id = requireNotNull(id) { "SenderProfile must be persisted before it can be mapped to SenderProfileDetailDto." },
            version = version,
            name = name,
            billingAddress = address?.let {
                AddressDto(
                    id = it.id,
                    street = it.street,
                    postalCode = it.postalCode.value,
                    city = it.city,
                    countryAlpha2 = it.country.alpha2,
                )
            },
            taxNumber = taxNumber?.value,
            vatId = vatId?.value,
            iban = iban?.value,
            bic = bic?.value,
            bankName = bankName,
            defaultPaymentTerm = defaultPaymentTerm?.toDto(),
            invoicePrefix = invoicePrefix,
            invoiceNotesByLanguageCode = invoiceNote?.entries
                ?.mapNotNull { entry -> entry.language?.code?.let { it to entry.value } }
                ?.toMap()
                .orEmpty(),
            phone = phone,
            email = email?.value,
            website = website,
            registeredSeat = registeredSeat,
            managingDirector = managingDirector,
            courtOfJurisdiction = courtOfJurisdiction,
            registerCourt = registerCourt,
            commercialRegisterNumber = commercialRegisterNumber,
            sellerIdentifierValue = sellerIdentifier?.value,
            sellerIdentifierSchemeId = sellerIdentifier?.schemeId,
            legalRegistrationSchemeId = legalRegistrationSchemeId,
            isDefault = isDefault,
            legalForm = legalForm,
            craftsChamberRegistered = craftsChamberRegistered,
            sellerNumber = sellerNumber,
            shortHash = shortHash,
            partyPairs = partyFormExtensionRegistry.enabledAspects(PartyKind.SELLER)
                .associate { (spec, extension) -> spec.id to extension.read(this, PartyKind.SELLER, spec) },
            vatRegistrations = sellerVatRegistrationsExtensionRegistry.enabledExtension()?.read(this).orEmpty(),
        )
    }
}
