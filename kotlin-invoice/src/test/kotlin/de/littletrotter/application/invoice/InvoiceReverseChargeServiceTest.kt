package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.application.invoice.port.inbound.InvoiceB2bFieldsQueryPort
import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.test.FIXED_TIME_SOURCE
import jakarta.enterprise.event.Event
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Dedicated unit tests for [InvoiceReverseChargeService], covering its own responsibility separate
 * from `InvoiceLifecycleService`. Constructs the service directly (not through the facade); tests
 * cover happy path and boundaries with comprehensive validation logic.
 */
class InvoiceReverseChargeServiceTest {

    private fun support(taxRateRepo: TaxRateRepository = mock()) = InvoiceLifecycleSupport(
        addressManagementService = AddressManagementService(mock(), mock(), mock()),
        paymentTermRepo = mock(),
        taxRateRepo = taxRateRepo,
        b2bProfileRepo = mock(),
        invoiceB2bFieldsQueryPort = mock<InvoiceB2bFieldsQueryPort>(),
        articleRepo = mock<ArticleRepository>(),
        numberAssigner = mock(),
    )

    private fun service(
        invoiceRepo: InvoiceRepository = mock(),
        taxRateRepo: TaxRateRepository = mock(),
        auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider(),
        domainEventBus: Event<DomainEvent> = mock(),
    ) = InvoiceReverseChargeService(
        invoiceRepo = invoiceRepo,
        support = support(taxRateRepo),
        auditActorProvider = auditActorProvider,
        eventDispatcher = DomainEventDispatcher(domainEventBus, null), time = FIXED_TIME_SOURCE,
    )

    /** A recipient/sender pair for which [de.littletrotter.domain.compliance.reverseChargeEligibility]
     *  genuinely proposes Reverse Charge: French EU-B2B business customer, VERIFIED VAT id. */
    private fun rcEligibleInvoice(): Invoice {
        val recipient = aRecipient {
            id = 1L
            name = "Test Kunde SARL"
            customerNumber = CustomerNumber("KD-001")
            email = de.littletrotter.domain.value.Email("kunde@test-kunde.example")
            vatId = VatId("FR12345678901")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
        }
        val sender = aSenderProfile {
            id = 1L
            name = "Test Sender GmbH"
            invoicePrefix = "RE"
            email = de.littletrotter.domain.value.Email("billing@test-sender.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = de.littletrotter.domain.value.Iban("DE89370400440532013000")
        }
        val item = anInvoiceItem(
            positionId = "1",
            category = InvoiceItemCategory.SERVICE,
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.HOUR,
            unitPriceNet = Money(BigDecimal.TEN),
        )
        return anInvoice(
            id = 1L,
            invoiceDate = LocalDate.of(2026, 1, 1),
            recipient = recipient,
            senderProfile = sender,
            items = listOf(item),
        )
    }

    // ── setReverseChargeDecision ─────────────────────────────────────────

    @Test
    fun `setReverseChargeDecision happy path applies the Reverse-Charge rule to every item`() {
        val invoice = rcEligibleInvoice()
        val rcRate = de.littletrotter.domain.reference.TaxRate().edit {
            id = 99L
            code = "EU_B2B_RC_SERVICE"
            rate = TaxRatePercent(BigDecimal.ZERO)
        }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findAll(0, 1000)).thenReturn(listOf(rcRate))

        val saved = service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo)
            .setReverseChargeDecision(1L, expectedVersion = null, decision = true)

        assertTrue(saved.reverseCharge)
        assertEquals("EU_B2B_RC_SERVICE", saved.itemList.first().taxRuleCode)
    }

    @Test
    fun `setReverseChargeDecision throws InvoiceNotFoundException for an unknown invoice id`() {
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(99L)).thenReturn(null)

        assertThrows(InvoiceNotFoundException::class.java) {
            service(invoiceRepo = invoiceRepo).setReverseChargeDecision(99L, null, true)
        }
    }

    @Test
    fun `setReverseChargeDecision throws InvoiceImmutableException on a read-only invoice`() {
        val invoice = rcEligibleInvoice()
        invoice.forceStatusForTest(InvoiceStatus.ISSUED)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service(invoiceRepo = invoiceRepo).setReverseChargeDecision(1L, null, true)
        }
    }

    @Test
    fun `setReverseChargeDecision throws OptimisticLockException on a stale expectedVersion`() {
        val invoice = rcEligibleInvoice().also { PersistenceEmulation.assignVersion(it, 3L) }
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(OptimisticLockException::class.java) {
            service(invoiceRepo = invoiceRepo).setReverseChargeDecision(1L, expectedVersion = 1L, decision = true)
        }
    }

    @Test
    fun `setReverseChargeDecision ON throws InvoiceValidationException when the recipient is not RC-eligible`() {
        val invoice = anInvoice(
            id = 1L,
            invoiceDate = LocalDate.of(2026, 1, 1),
            recipient = aRecipient { id = 1L }, // no VAT id / customer type -> ineligible
            senderProfile = aSenderProfile { id = 1L },
        )
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceValidationException::class.java) {
            service(invoiceRepo = invoiceRepo).setReverseChargeDecision(1L, null, true)
        }
    }

    @Test
    fun `setReverseChargeDecision missing Reverse-Charge master data rule throws InvalidParameterException`() {
        val invoice = rcEligibleInvoice()
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        val taxRateRepo = mock<TaxRateRepository>()
        whenever(taxRateRepo.findAll(0, 1000)).thenReturn(emptyList())

        assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo, taxRateRepo = taxRateRepo).setReverseChargeDecision(1L, null, true)
        }
    }
}
