package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceHeaderUseCase
import de.littletrotter.application.invoice.port.inbound.EditInvoiceItemsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The contract between the detail view and the write ports: the version the read model hands out
 * is the version the next edit sends back as `expectedVersion`. Covers the first invoice of a new
 * tenant, a draft whose eight reference columns are all NULL. Reading such a draft used to
 * materialise its reference group and bump `@Version` on every page load, preview tick and reload,
 * so header save, add, update and delete all failed with "modified concurrently"; the existing
 * flows pass `expectedVersion = null` and never saw it.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceItemCrudVersionContractIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var createInvoice: CreateInvoiceUseCase

    @Inject
    lateinit var editHeader: EditInvoiceHeaderUseCase

    @Inject
    lateinit var editItems: EditInvoiceItemsUseCase

    @Inject
    lateinit var detailQuery: InvoiceDetailQueryPort

    private data class Draft(val invoiceId: Long, val taxRateId: Long)

    /** A recipient without a routing id and no other reference term: the first invoice of a new
     *  tenant, and the only shape whose reference columns are all NULL. */
    private fun draftWithoutReferences(tag: String): Draft {
        val (senderId, recipientId, taxRateId) = QuarkusTransaction.requiringNew().call {
            val sender = aSenderProfile {
                name = "$tag Sender GmbH"
                invoicePrefix = "$tag-"
                registerCourt = "AG Musterstadt"
                email = Email.ofNullable("billing@$tag.example")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE111111111")
                iban = Iban.ofNullable("DE89370400440532013000")
            }
            sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
            val recipient = aRecipient {
                customerNumber = CustomerNumber("$tag-100")
                name = "$tag Kunde ohne Leitweg-ID"
                email = Email.ofNullable("ap@$tag.example")
                vatId = VatId("DE222222222")
                routingId = null
            }
            recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
            em.persist(sender)
            em.persist(recipient)
            val taxRate = TaxRate().edit {
                code = "${tag}_STD19"
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
        val invoiceId = QuarkusTransaction.requiringNew().call { createInvoice.createInvoiceWithRecipient(senderId, recipientId).id }
        return Draft(invoiceId, taxRateId)
    }

    /** What the detail view holds between two clicks. */
    private fun detail(invoiceId: Long): InvoiceDetailDto = checkNotNull(detailQuery.findInvoiceDetailDto(invoiceId))

    /** Listeners of the creation event may legitimately move the version once; wait until it
     *  has stood still for three polls so a later move can only come from a read. */
    private fun awaitSettledVersion(invoiceId: Long): Long? {
        var last = detail(invoiceId).version
        var stable = 0
        repeat(SETTLE_POLLS) {
            Thread.sleep(POLL_MS)
            val now = detail(invoiceId).version
            stable = if (now == last) stable + 1 else 0
            last = now
            if (stable >= STABLE_POLLS) return last
        }
        return last
    }

    private fun assertReferencesStillNull(invoiceId: Long) = QuarkusTransaction.requiringNew().run {
        val refs = checkNotNull(em.find(Invoice::class.java, invoiceId)).references
        assertNull(refs.buyerReference, "the draft must keep its NULL reference group for the whole flow")
        assertNull(refs.projectNumber)
    }

    private fun headerCommand(invoiceId: Long, expectedVersion: Long?, freeText: String) = QuarkusTransaction.requiringNew().call {
        val created = checkNotNull(em.find(Invoice::class.java, invoiceId))
        UpdateInvoiceHeaderCommand(
            invoiceId = invoiceId,
            expectedVersion = expectedVersion,
            invoiceDate = created.invoiceDate,
            servicePeriodFrom = null,
            servicePeriodTo = null,
            dueDate = created.dueDate,
            freeText = freeText,
            buyerReference = null,
            orderReference = null,
            orderReferenceDate = null,
            currency = Currency.EUR,
            recipientId = created.recipient?.id,
            senderProfileId = created.senderProfile?.id,
            paymentTermId = created.paymentTerm?.id,
        )
    }

    private fun itemCommand(taxRateId: Long, description: String, quantity: BigDecimal = BigDecimal.ONE) = InvoiceItemCommand(
        category = InvoiceItemCategory.SERVICE,
        description = description,
        quantity = quantity,
        unit = InvoiceItemUnit.HOUR,
        unitPriceNet = Money.of("100.00"),
        taxRateId = taxRateId,
    )

    @Test
    fun `header save, add, update and delete each succeed with the version the read model shows`() {
        val draft = draftWithoutReferences("CRUD")
        awaitSettledVersion(draft.invoiceId)
        assertReferencesStillNull(draft.invoiceId)

        val fresh = detail(draft.invoiceId)
        repeat(3) { detail(draft.invoiceId) }
        assertEquals(fresh.version, detail(draft.invoiceId).version, "reading the detail view must not move the version")

        val afterHeader = editHeader.updateInvoiceHeader(headerCommand(draft.invoiceId, fresh.version, "Freitext vor der ersten Position"))
        assertEquals("Freitext vor der ersten Position", afterHeader.freeText)
        assertReferencesStillNull(draft.invoiceId)

        val afterAdd = editItems.addItemToInvoice(
            draft.invoiceId,
            itemCommand(draft.taxRateId, "Beratung").copy(expectedVersion = detail(draft.invoiceId).version),
        )
        val item = afterAdd.items.single()

        val afterUpdate = editItems.updateInvoiceItem(
            draft.invoiceId,
            itemCommand(draft.taxRateId, "Beratung, zweiter Tag", BigDecimal("3"))
                .copy(itemId = item.id, expectedVersion = detail(draft.invoiceId).version),
        )
        assertEquals("Beratung, zweiter Tag", afterUpdate.items.single().description)

        val afterDelete = editItems.deleteItemFromInvoiceByPosition(draft.invoiceId, item.positionId, detail(draft.invoiceId).version)
        assertTrue(afterDelete.items.isEmpty(), "the position is gone again")
        assertEquals(afterDelete.version, detail(draft.invoiceId).version, "the read model after a write is what the next edit sends back")
        assertReferencesStillNull(draft.invoiceId)
    }

    private companion object {
        const val POLL_MS = 100L
        const val SETTLE_POLLS = 30
        const val STABLE_POLLS = 3
    }
}
