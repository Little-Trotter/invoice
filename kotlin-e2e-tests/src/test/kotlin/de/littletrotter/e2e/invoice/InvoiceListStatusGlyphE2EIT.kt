package de.littletrotter.e2e.invoice

import de.littletrotter.application.cache.InvoiceCachePort
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.application.invoice.InvoiceOverviewDimensions
import de.littletrotter.application.invoice.InvoiceOverviewQueryService
import de.littletrotter.application.invoice.InvoiceOverviewSort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Status glyphs in tables, B5/B6 at the service/view-model level: no
 * `kotlin-e2e-tests` suite asserted rendered status text before this package (there was nothing
 * status-specific to assert against a bare word), so this is a new suite rather than an
 * adaptation. B5's "no page reload" claim and B6's "sorting/filtering by status still works" are
 * both properties of [InvoiceOverviewQueryService] — the tile only changes how
 * [de.littletrotter.ui.view.invoice.InvoiceListView] renders the very same DTO fields this suite
 * reads directly, so a real Postgres round-trip proves the read path underneath the new tile is
 * unbroken.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceListStatusGlyphE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var overviewQueryService: InvoiceOverviewQueryService

    @Inject
    lateinit var cache: InvoiceCachePort

    /** Persists one invoice with the given [status]/[validationStatus], isolated from every other
     *  test's rows by [tag] (invoicePrefix/customerNumber/tax-rate code, and the [filterText] every
     *  test scopes its own query to — same fixture shape as [InvoiceOverviewAmountAndSortE2EIT]). */
    private fun persistInvoice(tag: String, number: String, status: InvoiceStatus, validationStatus: ValidationStatus): Long {
        val suffix = number.filter { it.isDigit() }.takeLast(4)
        return QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag$suffix-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag$suffix.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-${number.hashCode()}")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                iban = Iban.ofNullable("DE02120300000000202051")
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)

            val taxRate = TaxRate().apply {
                code = "${tag}_${number.hashCode()}_STD19"
                category = "S"
                name = "VAT 19"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 1, 1)
                isDefault = false
            }
            em.persist(taxRate)

            val invoice = Invoice.withInitialStatus(status).apply {
                senderProfile = sender
                this.recipient = recipient
                invoiceNumber = InvoiceNumber(number)
                invoiceDate = LocalDate.of(2026, 2, 10)
                dueDate = LocalDate.of(2026, 3, 12)
                this.validationStatus = validationStatus
                references.buyerReference = "PO-$tag"
            }
            invoice.addItem(
                InvoiceItem().apply {
                    positionId = "1"
                    category = InvoiceItemCategory.SERVICE
                    description = "Beratung"
                    quantity = BigDecimal.ONE
                    unit = InvoiceItemUnit.PIECE
                    unitPriceNet = Money(BigDecimal("50.00"))
                    taxRateRef = taxRate
                    sortOrder = 0
                },
            )
            invoice.totals.netAmount = Money(BigDecimal("50.00"))
            invoice.totals.vatAmount = Money(BigDecimal("9.50"))
            invoice.totals.grossAmount = Money(BigDecimal("59.50"))

            em.persist(invoice)
            em.flush()
            invoice.id!!
        }
    }

    // ── B5 — PENDING swaps to VALID on the very next read, no page reload needed ────────────────

    @Test
    fun `a PENDING validation status swaps to VALID on the next read, same query, no page reload`() {
        val tag = "GLY5"
        val id = persistInvoice(tag, "$tag-2026-0001", InvoiceStatus.SENT, ValidationStatus.PENDING)

        val beforeJobFinishes = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC)
        }
        assertEquals(ValidationStatus.PENDING, beforeJobFinishes.single().validationStatus)

        // The KoSIT job completing (DocumentGeneratedReactionHandler, out of this test's scope)
        // moves the invoice PENDING -> VALID and raises a DomainEvent that
        // InvoiceOverviewCacheEvictor reacts to; reproduced here as a direct transition plus an
        // explicit evictAllOverview() so this test exercises the real query path without pulling
        // the whole generation-job pipeline into a UI-glyph test.
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, id)
            invoice.transitionValidationStatusTo(ValidationStatus.VALID)
        }
        cache.evictAllOverview()

        // Same query, same parameters, no view reload of any kind -- the poll fallback
        // (InvoiceListView.onAttach) re-issues exactly this call and the grid re-renders whatever
        // it returns.
        val afterJobFinishes = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC)
        }
        assertEquals(ValidationStatus.VALID, afterJobFinishes.single().validationStatus)
    }

    // ── B6 — sorting/filtering by status/validationStatus is a regression guard, untouched ──────

    @Test
    fun `sorting by STATUS and by VALIDATION_STATUS still returns the real DB order`() {
        val tag = "GLY6"
        persistInvoice(tag, "$tag-2026-0001", InvoiceStatus.SENT, ValidationStatus.VALID)
        persistInvoice(tag, "$tag-2026-0002", InvoiceStatus.DRAFT, ValidationStatus.NOT_VALIDATED)

        val byStatusAsc = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.STATUS, SortDirection.ASC)
        }
        // DRAFT (ordinal 0) sorts before SENT (ordinal 2) -- InvoiceStatusConverter's plain
        // ordinal persistence, untouched by this package.
        assertEquals(listOf(InvoiceStatus.DRAFT, InvoiceStatus.SENT), byStatusAsc.map { it.status })

        val byValidationDesc = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, null, InvoiceOverviewSortField.VALIDATION_STATUS, SortDirection.DESC)
        }
        assertEquals(2, byValidationDesc.size)
    }

    @Test
    fun `filtering by status and by validationStatus still isolates the matching rows`() {
        val tag = "GLY7"
        persistInvoice(tag, "$tag-2026-0001", InvoiceStatus.SENT, ValidationStatus.VALID)
        persistInvoice(tag, "$tag-2026-0002", InvoiceStatus.DRAFT, ValidationStatus.NOT_VALIDATED)

        val onlySent = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(0, 10, tag, InvoiceStatus.SENT, InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC)
        }
        assertEquals(listOf("$tag-2026-0001"), onlySent.map { it.invoiceNumber })

        val onlyValid = QuarkusTransaction.requiringNew().call {
            overviewQueryService.findOverview(
                0, 10, tag, null,
                InvoiceOverviewSort(InvoiceOverviewSortField.INVOICE_DATE, SortDirection.DESC),
                InvoiceOverviewDimensions(validationStatus = ValidationStatus.VALID),
            )
        }
        assertEquals(listOf("$tag-2026-0001"), onlyValid.map { it.invoiceNumber })
    }
}
