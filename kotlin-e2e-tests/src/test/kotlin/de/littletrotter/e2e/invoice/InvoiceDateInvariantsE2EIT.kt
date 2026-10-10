package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * (PO 2026-09-02) — the three date invariants driven end to end through the real, CDI-injected [EditInvoiceHeaderUseCase]/[EditInvoiceItemsUseCase]/[GenerationJobService] (no mocks, no UI driver), the same shape [InvoiceCreateForCustomerE2EIT]/[InvoiceHeaderZeroItemsE2EIT]/[InvoiceAnnulmentE2EIT] already establish:
 *
 * 1. every invoice carries an invoice date from creation ([Invoice.invoiceDate] is non-nullable; `createInvoiceWithRecipient` defaults it to today).
 * 2. `dueDate >= invoiceDate` is enforced where the value is edited (`updateInvoiceHeader`), not only in the finalization checklist.
 * 3. every generation job carries a non-null print date ([de.littletrotter.domain.invoice.GenerationJob.createdAt]).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceDateInvariantsE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var editInvoiceHeader: EditInvoiceHeaderUseCase

    @Inject
    lateinit var editInvoiceItems: EditInvoiceItemsUseCase

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    // ─── Fixture — one sender/recipient combo per test, complete enough to save/generate ──────────

    private fun draftInvoiceId(tag: String): Long {
        val (senderId, recipientId) = QuarkusTransaction.requiringNew().call {
            val sender = SenderProfile().apply {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Empfänger AG"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                routingId = "PO-$tag-STANDING"
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            em.flush()
            sender.id!! to recipient.id!!
        }
        return QuarkusTransaction.requiringNew().call { createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId).id }
    }

    private fun findInvoice(invoiceId: Long): Invoice =
        QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }

    private fun headerCommand(
        invoiceId: Long,
        invoiceDate: LocalDate?,
        dueDate: LocalDate?,
    ): UpdateInvoiceHeaderCommand {
        val current = findInvoice(invoiceId)
        return UpdateInvoiceHeaderCommand(
            invoiceId = invoiceId,
            expectedVersion = null,
            invoiceDate = invoiceDate,
            servicePeriodFrom = null,
            servicePeriodTo = null,
            dueDate = dueDate,
            freeText = null,
            buyerReference = current.references.buyerReference,
            orderReference = null,
            orderReferenceDate = null,
            currency = Currency.EUR,
            recipientId = current.recipient?.id,
            senderProfileId = current.senderProfile?.id,
            paymentTermId = current.paymentTerm?.id,
        )
    }

    // ─── Invariant 1 — every invoice carries an invoice date from creation ─────────────────────────

    @Test
    fun `creating an invoice through the facade sets invoiceDate to today`() {
        val invoiceId = draftInvoiceId("DI1")

        val created = findInvoice(invoiceId)

        assertEquals(LocalDate.now(), created.invoiceDate, "every invoice must carry today's date from the moment it exists")
    }

    // ─── Invariant 2 — dueDate >= invoiceDate is enforced where the value is edited ────────────────

    @Test
    fun `a due date on or after the invoice date is accepted and persisted`() {
        val invoiceId = draftInvoiceId("DI2")
        val invoiceDate = findInvoice(invoiceId).invoiceDate
        val command = headerCommand(invoiceId, invoiceDate = invoiceDate, dueDate = invoiceDate.plusDays(14))

        val updated = QuarkusTransaction.requiringNew().call { editInvoiceHeader.updateInvoiceHeader(command) }

        assertEquals(invoiceDate.plusDays(14), updated.dueDate)
    }

    @Test
    fun `a due date exactly equal to the invoice date - the boundary - is accepted`() {
        val invoiceId = draftInvoiceId("DI3")
        val invoiceDate = findInvoice(invoiceId).invoiceDate
        val command = headerCommand(invoiceId, invoiceDate = invoiceDate, dueDate = invoiceDate)

        val updated = QuarkusTransaction.requiringNew().call { editInvoiceHeader.updateInvoiceHeader(command) }

        assertEquals(invoiceDate, updated.dueDate, "same-day is explicitly allowed, only strictly-before is rejected")
    }

    @Test
    fun `a due date before the invoice date is rejected at edit time, not silently accepted`() {
        val invoiceId = draftInvoiceId("DI4")
        val invoiceDate = findInvoice(invoiceId).invoiceDate
        val command = headerCommand(invoiceId, invoiceDate = invoiceDate, dueDate = invoiceDate.minusDays(1))

        val ex = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().call { editInvoiceHeader.updateInvoiceHeader(command) }
        }

        assertEquals(InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE, ex.error)
        // the rejected edit must never have reached the row: dueDate stays whatever it was before
        // this attempt (create() defaults it to invoiceDate + the default payment term).
        assertFalse(
            findInvoice(invoiceId).dueDate!!.isBefore(invoiceDate),
            "a rejected save must not leave the invoice with an inconsistent due date",
        )
    }

    @Test
    fun `changing the invoice date to a value after an existing due date is rejected on save`() {
        val invoiceId = draftInvoiceId("DI5")
        val original = findInvoice(invoiceId)
        // A due date consistent with the ORIGINAL invoice date first (so this save succeeds)...
        val setupCommand = headerCommand(invoiceId, invoiceDate = original.invoiceDate, dueDate = original.invoiceDate.plusDays(2))
        QuarkusTransaction.requiringNew().call { editInvoiceHeader.updateInvoiceHeader(setupCommand) }

        // ...then push the invoice date PAST that already-persisted due date in the same edit
        // path: the same DUE_DATE_BEFORE_INVOICE_DATE rule must fire here too, not just when the
        // due date itself moves.
        val pushCommand = headerCommand(invoiceId, invoiceDate = original.invoiceDate.plusDays(10), dueDate = original.invoiceDate.plusDays(2))

        val ex = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().call { editInvoiceHeader.updateInvoiceHeader(pushCommand) }
        }

        assertEquals(InvoiceValidationError.DUE_DATE_BEFORE_INVOICE_DATE, ex.error)
    }

    // ─── Invariant 3 — every generation job carries a non-null print date ──────────────────────────

    @Test
    fun `triggering a generation job records a non-null createdAt not before the invoice's creation instant`() {
        val invoiceId = draftInvoiceId("DI6")
        val invoiceCreatedAt = findInvoice(invoiceId).createdAt

        val taxRateId = QuarkusTransaction.requiringNew().call {
            val taxRate = TaxRate().apply {
                code = "DI6_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
            }
            em.persist(taxRate)
            em.flush()
            taxRate.id!!
        }
        QuarkusTransaction.requiringNew().run {
            editInvoiceItems.addItemToInvoice(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "Date invariants E2E Beratung",
                    quantity = BigDecimal("1"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = taxRateId,
                ),
            )
        }

        val job = QuarkusTransaction.requiringNew().call { generationJobCreationService.createJob(invoiceId, "PDF_EXPORT", DocumentJobLane.BULK) }

        assertNotNull(job.createdAt, "a print/generation job must always carry a print date")
        assertTrue(
            invoiceCreatedAt == null || !job.createdAt.isBefore(invoiceCreatedAt),
            "the job's createdAt must not be before the invoice's own creation instant",
        )
    }
}
