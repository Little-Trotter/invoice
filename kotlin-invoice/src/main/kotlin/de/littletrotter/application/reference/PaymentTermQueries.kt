package de.littletrotter.application.reference

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Implements [PaymentTermQueryPort] on top of [PaymentTermService]. */
@ApplicationScoped
class PaymentTermQueries @Inject constructor(
    private val paymentTermService: PaymentTermService,
    @param:ConfigProperty(name = "littletrotter.page-size.reference-data.lookup", defaultValue = "1000")
    private val configuredReferenceLookupPageSize: Int = 1000,
) : PaymentTermQueryPort {

    private val referenceLookupPageSize = if (configuredReferenceLookupPageSize > 0) configuredReferenceLookupPageSize else 1000

    override fun findAllPaymentTerms(): List<PaymentTermDto> =
        paymentTermService.findAllPaymentTerms(0, referenceLookupPageSize, null, "name", SortDirection.ASC).map { it.toDto() }

    override fun findDefaultPaymentTerm(): PaymentTermDto? = paymentTermService.findDefaultPaymentTerm()?.toDto()

    override fun findPaymentTermById(id: Long): PaymentTermDto? = paymentTermService.findPaymentTermById(id)?.toDto()

    override fun findPaymentTerms(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<PaymentTermDto> =
        paymentTermService.findAllPaymentTerms(pageIndex, pageSize, filterText, sortField, sortDir).map { it.toDto() }

    override fun countPaymentTerms(filterText: String?): Long = paymentTermService.countPaymentTerms(filterText)

    override fun countInactivePaymentTerms(filterText: String?): Long = paymentTermService.countInactivePaymentTerms(filterText)

    override fun findPaymentTermUsageCounts(): Map<Long, Long> = paymentTermService.findPaymentTermUsageCounts()
}
