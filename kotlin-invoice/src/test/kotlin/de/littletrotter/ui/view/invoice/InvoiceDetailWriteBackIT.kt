package de.littletrotter.ui.view.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.UpdateInvoiceHeaderCommand
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.domain.invoice.DocumentGenerationStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceDocument
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceLlmAnalysis
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.invoice.LlmAnalysisStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.shared.Currency
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.Money
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * Write-back path for the invoice detail view.
 *
 * Originally set out to reproduce silent data loss on `EntityManager.merge` when the UI
 * reconstructed a whole [Invoice] from a read model and passed it to `saveInvoice`. It found
 * something earlier and worse: the reconstruction dropped `SenderProfile.iban`, so
 * [de.littletrotter.domain.masterdata.SenderProfile.validate] rejected every save before
 * the merge was ever reached. `saveInvoice(invoice: Invoice)` has since been removed entirely —
 * every write path goes through [UpdateInvoiceHeaderCommand] or
 * [EditInvoiceItemsUseCase.updateInvoiceItem], which load the managed entity themselves and touch
 * only the fields named. This test now guards that property: fields absent from both commands
 * must survive a header edit and an item edit untouched.
 *
 * Each phase runs in its own transaction on purpose — inside a single transaction the persistence
 * context would hand back the very instances written in setup and hide the effect entirely.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceDetailWriteBackIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var editInvoiceHeaderUseCase: EditInvoiceHeaderUseCase

    @Inject
    lateinit var editInvoiceItemsUseCase: EditInvoiceItemsUseCase

    @Test
    fun `updateInvoiceHeader and updateInvoiceItem preserve fields neither command carries`() {
        val ids = QuarkusTransaction.requiringNew().call<Ids> { persistFullyPopulatedInvoice() }

        QuarkusTransaction.requiringNew().run {
            editInvoiceHeaderUseCase.updateInvoiceHeader(
                UpdateInvoiceHeaderCommand(
                    invoiceId = ids.invoiceId,
                    expectedVersion = null,
                    invoiceDate = LocalDate.of(2026, 8, 20),
                    servicePeriodFrom = null,
                    servicePeriodTo = null,
                    dueDate = LocalDate.of(2026, 9, 19),
                    freeText = "Updated via command",
                    buyerReference = "PO-UPDATED",
                    purchaseOrderReference = "BST-UPDATED",
                    orderReference = null,
                    orderReferenceDate = null,
                    currency = Currency.EUR,
                    recipientId = ids.recipientId,
                    senderProfileId = ids.senderId,
                    paymentTermId = ids.paymentTermId,
                ),
            )
        }

        QuarkusTransaction.requiringNew().run {
            val updateCommand = InvoiceItemCommand(
                itemId = ids.itemId,
                category = InvoiceItemCategory.SERVICE,
                description = "Consulting (revised)",
                deliveryNoteReference = null,
                quantity = BigDecimal("2"),
                unit = InvoiceItemUnit.HOUR,
                unitPriceNet = Money.of("120.00"),
                taxRateId = ids.taxRateId,
            )
            editInvoiceItemsUseCase.updateInvoiceItem(ids.invoiceId, updateCommand)
        }

        QuarkusTransaction.requiringNew().run {
            val reloaded = em.find(Invoice::class.java, ids.invoiceId)
            assertNotNull(reloaded, "invoice must still exist after both updates")

            // Changed by the header command.
            assertEquals(LocalDate.of(2026, 8, 20), reloaded.invoiceDate)
            assertEquals("PO-UPDATED", reloaded.references.buyerReference)
            // BT-13 — its own column, written from its own command field; the two must not be
            // able to overwrite each other again.
            assertEquals("BST-UPDATED", reloaded.references.purchaseOrderReference)

            // Fields present on neither command must survive untouched.
            assertEquals(30, reloaded.paymentTermsDays, "paymentTermsDays was reset even though no command carries it")
            assertNotNull(reloaded.paymentTerm, "paymentTerm reference was nulled")
            assertEquals(
                InvoiceTypeCode.COMMERCIAL_INVOICE,
                reloaded.typeCode,
                "typeCode was reset unexpectedly",
            )
            assertEquals(
                ValidationStatus.VALID,
                reloaded.validationStatus,
                "validationStatus was reset - the KoSIT result is gone",
            )
            assertEquals(
                "R-2026-0001",
                reloaded.originalInvoiceNumber,
                "originalInvoiceNumber was nulled",
            )
            assertNotNull(reloaded.selectedSeller, "selectedSeller was nulled")

            assertEquals(1, countRows("invoice_document", ids.invoiceId), "generated documents were lost")
            assertEquals(1, countRows("invoice_llm_analysis", ids.invoiceId), "LLM analyses were lost")

            // Changed by the item command.
            val item = reloaded.itemList.first { it.id == ids.itemId }
            assertEquals("Consulting (revised)", item.description)
            assertEquals(0, BigDecimal("2").compareTo(item.quantity))
        }
    }

    private fun countRows(table: String, invoiceId: Long): Long =
        (
            em.createNativeQuery("SELECT COUNT(*) FROM $table WHERE invoice_id = :id")
                .setParameter("id", invoiceId)
                .singleResult as Number
            ).toLong()

    private data class Ids(
        val invoiceId: Long,
        val itemId: Long,
        val recipientId: Long,
        val senderId: Long,
        val paymentTermId: Long,
        val taxRateId: Long,
    )

    /**
     * Builds an invoice that is complete enough to pass [Invoice.validate] and that populates every
     * field the assertions above check.
     */
    private fun persistFullyPopulatedInvoice(): Ids {
        val sender = SenderProfile().apply {
            name = "Sender GmbH"
            // "SG-"/"SS-" prefixes too short and collide with other tests; this constraint is table-wide.
            invoicePrefix = "WB-SG-"
            email = Email.ofNullable("billing@sender.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE123456789")
            iban = Iban.ofNullable("DE02120300000000202051")
            registerCourt = "Amtsgericht Berlin (Charlottenburg)"
        }
        val seller = SenderProfile().apply {
            name = "Selected Seller GmbH"
            invoicePrefix = "WB-SS-"
            email = Email.ofNullable("billing@seller.example")
            taxNumber = TaxNumber("98/765/43210")
            vatId = VatId("DE987654321")
            iban = Iban.ofNullable("DE02500105170137075030")
            registerCourt = "Amtsgericht Berlin (Charlottenburg)"
        }
        val recipient = Recipient().apply {
            // Unique per test class to avoid constraint collisions (same pattern as invoicePrefix).
            customerNumber = CustomerNumber("KD-WB-100")
            name = "Acme GmbH"
            email = Email.ofNullable("ap@acme.example")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE02100500000054540402")
        }
        val paymentTerm = PaymentTerm().apply {
            setName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "30 days net")
            dueInDays = 30
        }
        val taxRate = TaxRate().apply {
            name = "VAT 19"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
            code = "WRITEBACK-IT-DE-STANDARD-19"
            category = "S"
        }

        em.persist(sender)
        em.persist(seller)
        em.persist(recipient)
        em.persist(paymentTerm)
        em.persist(taxRate)

        val invoice = Invoice().apply {
            invoiceNumber = InvoiceNumber("IT-2026-08-00001")
            senderProfile = sender
            selectedSeller = seller
            this.recipient = recipient
            this.paymentTerm = paymentTerm
            invoiceDate = LocalDate.of(2026, 8, 1)
            dueDate = LocalDate.of(2026, 8, 31)
            paymentTermsDays = 30
            // Not CORRECTED_INVOICE: since the Annullierung/VOIDED PO decision (2026-08-31), a
            // storno document is read-only from creation (Invoice.isReadOnly()), so it can no
            // longer reach updateInvoiceHeader/updateInvoiceItem at all. COMMERCIAL_INVOICE still
            // proves the commands leave typeCode/originalInvoiceNumber untouched.
            typeCode = InvoiceTypeCode.COMMERCIAL_INVOICE
            originalInvoiceNumber = "R-2026-0001"
            validationStatus = ValidationStatus.VALID
        }
        val item = InvoiceItem().apply {
            positionId = "1"
            category = InvoiceItemCategory.SERVICE
            description = "Consulting"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.HOUR
            unitPriceNet = Money.of("100.00")
            taxRateRef = taxRate
            sortOrder = 0
        }
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()

        em.persist(
            InvoiceDocument().apply {
                this.invoice = invoice
                shortName = "invoice-1.pdf"
                documentType = "PDF"
                objectKey = "documents/2026/invoice-1.pdf"
                fileName = "invoice-1.pdf"
                mimeType = "application/pdf"
                sizeBytes = 1024
                // sha256_checksum is NOT NULL.
                sha256Checksum = "b".repeat(64)
                generationStatus = DocumentGenerationStatus.COMPLETED
                validationStatus = ValidationStatus.VALID
            },
        )
        em.persist(
            InvoiceLlmAnalysis().apply {
                this.invoice = invoice
                modelName = "test-model"
                status = LlmAnalysisStatus.COMPLETED
                rating = 5
                summary = "looks fine"
            },
        )
        em.flush()

        return Ids(
            invoiceId = invoice.id!!,
            itemId = item.id!!,
            recipientId = recipient.id!!,
            senderId = sender.id!!,
            paymentTermId = paymentTerm.id!!,
            taxRateId = taxRate.id!!,
        )
    }
}
