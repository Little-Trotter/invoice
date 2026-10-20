package de.littletrotter.application.reference

import de.littletrotter.application.dto.TaxRateDto
import de.littletrotter.application.reference.port.inbound.ManageTaxRateUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.reference.TaxRate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/** Implements [ManageTaxRateUseCase] on top of [TaxRateService]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManageTaxRate @Inject constructor(
    private val taxRateService: TaxRateService,
) : ManageTaxRateUseCase {

    private val rows = CatalogueWrites(taxRateService::findTaxRateById, TaxRate::version) { TaxRate() }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun saveTaxRate(rate: TaxRateDto): TaxRateDto {
        val target = rows.rowFor(rate.id, rate.version)
        rate.applyTo(target)
        return taxRateService.saveTaxRate(target).toDto()
    }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deleteTaxRate(id: Long) = taxRateService.deleteTaxRate(id)
}
