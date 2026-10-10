package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.InvoiceB2bFieldsQueryPort
import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.TaxRateRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Z5 — the narrow, occasionally-used lookups [InvoiceLifecycleService] needs but that aren't
 * part of its own core identity (the invoice/sender/recipient repositories and the audit/event
 * plumbing every method touches). Bundled into one injected collaborator purely to keep
 * [InvoiceLifecycleService]'s own constructor within a sane parameter budget — each field stays
 * individually overridable via `InvoiceLifecycleServiceTestBuilder` in the test sources.
 *
 * W4 — [articleRepo] was added for [InvoiceLifecycleService.addItem]'s command→entity resolution
 * (article-bound tax variants), the same boundary the other lookups here already serve. The
 * per-diem rate repository/service this bundle used to also carry moved to
 * [de.littletrotter.application.travel.PerDiemCalculator]/[TripService], the only collaborators
 * that still need them once addItem/updateItem stopped handling TRIP (grep-proof: no remaining
 * read of either field in [InvoiceLifecycleService]).
 *
 * [numberAssigner] joined this bundle to keep [InvoiceLifecycleService]'s own constructor under
 * detekt's parameter budget — it has a single call site there ([InvoiceLifecycleService.transition]),
 * the fewest of any field on that constructor.
 *
 * [invoiceB2bFieldsQueryPort] collapses the B2B field-value/definition lookups this bundle's sole
 * consumer ([InvoiceHeaderUpdateService]) needs into one narrow input port — one role
 * interface per use case, not a services bag, not a baseline entry.
 */
@ApplicationScoped
class InvoiceLifecycleSupport @Inject constructor(
    val addressManagementService: AddressManagementService,
    val paymentTermRepo: PaymentTermRepository,
    val taxRateRepo: TaxRateRepository,
    val b2bProfileRepo: B2bProfileRepository,
    val invoiceB2bFieldsQueryPort: InvoiceB2bFieldsQueryPort,
    val articleRepo: ArticleRepository,
    val numberAssigner: InvoiceNumberAssigner,
    @param:ConfigProperty(name = "littletrotter.page-size.default", defaultValue = "60")
    val configuredDefaultPageSize: Int = 60,
)
