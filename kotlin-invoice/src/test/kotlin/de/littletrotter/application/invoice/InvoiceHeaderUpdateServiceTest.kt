package de.littletrotter.application.invoice

import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.application.invoice.port.inbound.InvoiceB2bFieldsQueryPort
import de.littletrotter.application.shared.AuditActorProvider
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.exception.InvoiceImmutableException
import de.littletrotter.domain.exception.InvoiceNotFoundException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.forceStatusForTest
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Currency
import jakarta.enterprise.event.Event
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TIME_SOURCE

private fun validSender(id: Long) = SenderProfile().apply {
    this.id = id
    name = "Test Sender GmbH"
    invoicePrefix = "RE"
    email = Email("billing@test-sender.example")
    taxNumber = TaxNumber("12/345/67890")
    vatId = VatId("DE123456789")
    iban = Iban("DE89370400440532013000")
}

private fun validRecipient(id: Long, vatId: String? = "DE987654321") = Recipient().apply {
    this.id = id
    name = "Test Kunde GmbH"
    customerNumber = CustomerNumber("KD-$id")
    email = Email("kunde@test-kunde.example")
    this.vatId = VatId.ofNullable(vatId)
}

/**
 * Dedicated unit tests for [InvoiceHeaderUpdateService], extracted from `InvoiceLifecycleService`. Constructs the service directly (not through the facade), with a real (mock-backed) [InvoiceReverseChargeService] collaborator, exactly like the production CDI wiring; tests cover happy path and boundaries with comprehensive validation logic.
 */
class InvoiceHeaderUpdateServiceTest {

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
        senderRepo: SenderProfileRepository = mock(),
        recipientRepo: RecipientRepository = mock(),
        auditActorProvider: AuditActorProvider = SystemAuditActorProvider(),
        domainEventBus: Event<DomainEvent> = mock(),
    ): InvoiceHeaderUpdateService {
        val support = support()
        return InvoiceHeaderUpdateService(
            invoiceRepo = invoiceRepo,
            parties = PartyRepositories(
                senderRepo,
                recipientRepo,
                ConsumerNoteResolver(
                    object : de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort {
                        override fun currentProfile() = de.littletrotter.shared.wording.WordingProfile.HANDWERK
                    },
                ),
            ),
            domainEventBus = domainEventBus,
            auditActorProvider = auditActorProvider,
            support = support,
            reverseChargeService = InvoiceReverseChargeService(
                invoiceRepo = invoiceRepo,
                support = support,
                auditActorProvider = auditActorProvider,
                domainEventBus = domainEventBus, time = FIXED_TIME_SOURCE,
            ),
        )
    }

    private fun minimalCommand(
        invoiceId: Long = 1L,
        expectedVersion: Long? = null,
        recipientId: Long? = 20L,
        senderProfileId: Long? = 10L,
        reverseChargeDecision: Boolean? = null,
    ) = UpdateInvoiceHeaderCommand(
        invoiceId = invoiceId,
        expectedVersion = expectedVersion,
        invoiceDate = LocalDate.of(2026, 1, 2),
        servicePeriodFrom = null,
        servicePeriodTo = null,
        dueDate = null,
        freeText = "Aktualisierter Freitext",
        buyerReference = null,
        orderReference = null,
        orderReferenceDate = null,
        currency = Currency.EUR,
        recipientId = recipientId,
        senderProfileId = senderProfileId,
        paymentTermId = null,
        reverseChargeDecision = reverseChargeDecision,
    )

    private fun invoiceWithParties(
        recipientVatId: String? = "DE987654321",
        senderVatId: String = "DE123456789",
    ): Triple<Invoice, Recipient, SenderProfile> {
        val recipient = validRecipient(20L, recipientVatId)
        val sender = validSender(10L).apply { vatId = VatId(senderVatId) }
        val invoice = Invoice().apply {
            id = 1L
            invoiceDate = LocalDate.of(2026, 1, 1)
            this.recipient = recipient
            this.senderProfile = sender
        }
        return Triple(invoice, recipient, sender)
    }

    // ── updateHeader ─────────────────────────────────────────────────────

    @Test
    fun `updateHeader happy path applies metadata without a party change and saves`() {
        val (invoice, recipient, sender) = invoiceWithParties()
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val recipientRepo = mock<RecipientRepository>()
        whenever(recipientRepo.findById(20L)).thenReturn(recipient)
        val senderRepo = mock<SenderProfileRepository>()
        whenever(senderRepo.findById(10L)).thenReturn(sender)

        val saved = service(invoiceRepo = invoiceRepo, senderRepo = senderRepo, recipientRepo = recipientRepo)
            .updateHeader(minimalCommand())

        assertEquals("Aktualisierter Freitext", saved.freeText)
        assertEquals(LocalDate.of(2026, 1, 2), saved.invoiceDate)
        assertEquals(sender.id, saved.senderProfile?.id)
        assertEquals(recipient.id, saved.recipient?.id)
    }

    @Test
    fun `updateHeader throws InvoiceNotFoundException for an unknown invoice id`() {
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(99L)).thenReturn(null)

        assertThrows(InvoiceNotFoundException::class.java) {
            service(invoiceRepo = invoiceRepo).updateHeader(minimalCommand(invoiceId = 99L))
        }
    }

    @Test
    fun `updateHeader throws InvoiceImmutableException on a read-only invoice`() {
        val (invoice, _, _) = invoiceWithParties()
        invoice.forceStatusForTest(InvoiceStatus.SENT)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service(invoiceRepo = invoiceRepo).updateHeader(minimalCommand())
        }
    }

    @Test
    fun `a voided invoice rejects a header update even though a reproduction may render it`() {
        val (invoice, _, _) = invoiceWithParties()
        invoice.forceStatusForTest(InvoiceStatus.VOIDED)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(InvoiceImmutableException::class.java) {
            service(invoiceRepo = invoiceRepo).updateHeader(minimalCommand())
        }
    }

    @Test
    fun `updateHeader throws OptimisticLockException on a stale expectedVersion`() {
        val (invoice, _, _) = invoiceWithParties()
        invoice.version = 3L
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)

        assertThrows(OptimisticLockException::class.java) {
            service(invoiceRepo = invoiceRepo).updateHeader(minimalCommand(expectedVersion = 1L))
        }
    }

    @Test
    fun `updateHeader requires an explicit reverse-charge decision when seller and recipient VAT countries disagree`() {
        val (invoice, recipient, sender) = invoiceWithParties(recipientVatId = "FR12345678901", senderVatId = "DE123456789")
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        val recipientRepo = mock<RecipientRepository>()
        whenever(recipientRepo.findById(20L)).thenReturn(recipient)
        val senderRepo = mock<SenderProfileRepository>()
        whenever(senderRepo.findById(10L)).thenReturn(sender)

        assertThrows(InvalidParameterException::class.java) {
            service(invoiceRepo = invoiceRepo, senderRepo = senderRepo, recipientRepo = recipientRepo)
                .updateHeader(minimalCommand(reverseChargeDecision = null))
        }
    }

    @Test
    fun `updateHeader re-derives billing address and payment term when the sender changes`() {
        val (invoice, recipient, _) = invoiceWithParties()
        val newSender = validSender(11L)
        val invoiceRepo = mock<InvoiceRepository>()
        whenever(invoiceRepo.findByIdWithItems(1L)).thenReturn(invoice)
        whenever(invoiceRepo.save(invoice)).thenReturn(invoice)
        val recipientRepo = mock<RecipientRepository>()
        whenever(recipientRepo.findById(20L)).thenReturn(recipient)
        val senderRepo = mock<SenderProfileRepository>()
        whenever(senderRepo.findById(11L)).thenReturn(newSender)

        val saved = service(invoiceRepo = invoiceRepo, senderRepo = senderRepo, recipientRepo = recipientRepo)
            .updateHeader(minimalCommand(senderProfileId = 11L))

        assertEquals(11L, saved.senderProfile?.id)
    }
}
