package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.GenerationJobCreationService
import de.littletrotter.application.invoice.GenerationJobQueryService
import de.littletrotter.application.invoice.port.inbound.ChangeInvoiceStatusUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.DocumentJobLane
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/** Header save must not block on NO_ITEMS; [InvoiceLifecycleService.updateHeader] is called
 *  before any positions exist. Tests the real facade to prove header-save-then-add-positions
 *  flow works end to end. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceHeaderZeroItemsE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var changeInvoiceStatus: ChangeInvoiceStatusUseCase

    @Inject
    lateinit var editInvoiceHeader: EditInvoiceHeaderUseCase

    @Inject
    lateinit var editInvoiceItems: EditInvoiceItemsUseCase

    @Inject
    lateinit var createInvoiceUseCase: CreateInvoiceUseCase

    @Inject
    lateinit var generationJobCreationService: GenerationJobCreationService

    @Inject
    lateinit var generationJobQueryService: GenerationJobQueryService

    private data class Fixture(val invoiceId: Long, val taxRateId: Long)

    private fun draftInvoiceId(tag: String): Long = draftFixture(tag).invoiceId

    private fun draftFixture(tag: String): Fixture {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
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
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            val taxRate = TaxRate().apply {
                code = "${tag}_ZERO_STD19"
                category = "S"
                name = "USt 19 %"
                rate = TaxRatePercent(BigDecimal("19.00"))
                country = Country.DE
                validFrom = LocalDate.of(2024, 1, 1)
                isSelectable = true
                isActive = true
            }
            em.persist(taxRate)
            em.flush()
            Triple(sender.id!!, recipient.id!!, taxRate.id!!)
        }
        val invoiceId = QuarkusTransaction.requiringNew().call { createInvoiceUseCase.createInvoiceWithRecipient(senderId, recipientId).id }
        return Fixture(invoiceId, taxRateId)
    }

    @Test
    fun `updateInvoiceHeader saves the header of a freshly created draft with zero positions`() {
        val invoiceId = draftInvoiceId("HDR1")
        val command = QuarkusTransaction.requiringNew().call {
            val created = em.find(de.littletrotter.domain.invoice.Invoice::class.java, invoiceId)
            assertTrue(created.itemList.isEmpty(), "Vorbedingung: ein frischer Entwurf traegt noch keine Positionen")
            UpdateInvoiceHeaderCommand(
                invoiceId = invoiceId,
                // null — this suite isn't exercising optimistic locking; an async listener
                // (e.g. validationStatus resolution off InvoiceCreated) can legitimately bump
                // the version between create() and this save, same as any real "Erfassen" pane
                // save shortly after creation.
                expectedVersion = null,
                invoiceDate = created.invoiceDate,
                servicePeriodFrom = null,
                servicePeriodTo = null,
                dueDate = created.dueDate,
                freeText = "Freitext beim ersten Kopf-Erfassen, vor jeder Position",
                buyerReference = created.references.buyerReference,
                orderReference = null,
                orderReferenceDate = null,
                currency = Currency.EUR,
                recipientId = created.recipient?.id,
                senderProfileId = created.senderProfile?.id,
                paymentTermId = created.paymentTerm?.id,
                sellerVatRegistrationCountry = null,
                invoiceFormat = null,
            )
        }

        val updated = QuarkusTransaction.requiringNew().call { editInvoiceHeader.updateInvoiceHeader(command) }

        assertEquals(invoiceId, updated.id)
        assertEquals(InvoiceStatus.DRAFT.ordinal, updated.status)
        assertTrue(updated.items.isEmpty(), "der Kopf bleibt speicherbar, auch ohne jede Position")
        assertEquals("Freitext beim ersten Kopf-Erfassen, vor jeder Position", updated.freeText)
    }

    private fun addOnePosition(fixture: Fixture, category: InvoiceItemCategory, unit: InvoiceItemUnit): String =
        QuarkusTransaction.requiringNew().call {
            editInvoiceItems.addItemToInvoice(
                fixture.invoiceId,
                InvoiceItemCommand(
                    category = category,
                    description = "T14 position",
                    quantity = BigDecimal.ONE,
                    unit = unit,
                    unitPriceNet = Money.of("100.00"),
                    taxRateId = fixture.taxRateId,
                ),
            ).items.single().positionId
        }

    @Test
    fun `T14 - deleting the only position leaves a zero-item invoice that finalize, send and generation refuse`() {
        val fixture = draftFixture("T14A")
        val positionId = addOnePosition(fixture, InvoiceItemCategory.SERVICE, InvoiceItemUnit.HOUR)
        QuarkusTransaction.requiringNew().run { changeInvoiceStatus.transitionInvoice(fixture.invoiceId, InvoiceStatus.IN_REVIEW) }

        val afterDelete = QuarkusTransaction.requiringNew().call { editInvoiceItems.deleteItemFromInvoiceByPosition(fixture.invoiceId, positionId) }

        assertTrue(afterDelete.items.isEmpty(), "the last position of a writable invoice can be deleted")
        assertEquals(InvoiceStatus.IN_REVIEW.ordinal, afterDelete.status)
        assertEquals(0, (afterDelete.netAmount ?: Money.ZERO).amount.compareTo(BigDecimal.ZERO))
        assertEquals(0, (afterDelete.grossAmount ?: Money.ZERO).amount.compareTo(BigDecimal.ZERO))

        val finalize = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().run { changeInvoiceStatus.transitionInvoice(fixture.invoiceId, InvoiceStatus.SENT) }
        }
        assertEquals(InvoiceValidationError.NO_ITEMS, finalize.error)
        val send = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().run { changeInvoiceStatus.requestSend(fixture.invoiceId) }
        }
        assertEquals(InvoiceValidationError.NO_ITEMS, send.error)
        val generate = assertThrows(InvoiceValidationException::class.java) {
            QuarkusTransaction.requiringNew().run { generationJobCreationService.createJob(fixture.invoiceId, "PRINT_JOB", DocumentJobLane.BULK) }
        }
        assertEquals(InvoiceValidationError.NO_ITEMS, generate.error)

        assertTrue(
            QuarkusTransaction.requiringNew().call { generationJobQueryService.findByInvoiceId(fixture.invoiceId) }.isEmpty(),
            "no generation job (and so no document) may exist for a zero-item invoice",
        )
        val status = QuarkusTransaction.requiringNew().call {
            em.find(de.littletrotter.domain.invoice.Invoice::class.java, fixture.invoiceId).status
        }
        assertEquals(InvoiceStatus.IN_REVIEW, status, "the refused finalize left the invoice a writable review invoice")
    }
}
