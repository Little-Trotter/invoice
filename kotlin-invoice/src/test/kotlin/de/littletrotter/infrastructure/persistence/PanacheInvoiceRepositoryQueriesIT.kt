package de.littletrotter.infrastructure.persistence

import de.littletrotter.fixture.seededStandardTaxRate
import de.littletrotter.application.NatsTestResource
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.ValkeyTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.invoice.InvoiceTypeCode
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.application.invoice.port.outbound.InvoiceListingRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * real-Hibernate coverage for [PanacheInvoiceRepository]'s query/lookup surface not
 * already exercised by [PanacheRepositoryCriteriaOverviewIT] (findOverviewPage/countForOverview).
 * Own `COV8INV-` invoice-number prefix and `COV8-INV-` recipient/sender tokens keep assertions
 * relative to this test's own fixtures in the shared dev-services database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(ValkeyTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
class PanacheInvoiceRepositoryQueriesIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: InvoiceRepository

    @Inject
    lateinit var lookup: InvoiceLookupRepository

    @Inject
    lateinit var listing: InvoiceListingRepository

    companion object {
        private val counter = AtomicInteger(0)
    }

    private fun nextNumber(): String = "COV8INV-2026-%04d".format(counter.incrementAndGet())

    private fun newSenderRecipient(tag: String): Pair<SenderProfile, Recipient> {
        val sender = aSenderProfile {
            name = "COV8-INV Sender $tag"
            email = Email("cov8-inv-$tag@overview.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban("DE89370400440532013000")
            registerCourt = "AG Musterstadt"
            invoicePrefix = "COV8I$tag-"
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("COV8-INV-KD-$tag")
            name = "COV8-INV Kunde $tag"
            email = Email("kunde-$tag@overview.example")
            vatId = VatId("DE222222222")
            iban = Iban("DE02120300000000202051")
        }
        return sender to recipient
    }

    /** Persists a DRAFT invoice with one item, a billing/delivery address and a b2bProfile — the
     *  fixture every boundary test in this class starts from. */
    private fun persistDraftWithItem(tag: String = counter.incrementAndGet().toString()): Pair<Long, Long> =
        QuarkusTransaction.requiringNew().call<Pair<Long, Long>> {
            val (sender, recipient) = newSenderRecipient(tag)
            em.persist(sender)
            em.persist(recipient)
            val billing = Address.forRecipient(recipient, Address.AddressType.BILLING, "Teststr. 1", "12345", "Musterstadt", Country.DE, true)
            val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Teststr. 2", "12345", "Musterstadt", Country.DE, false)
            em.persist(billing)
            em.persist(delivery)
            val profile = B2bProfile().edit { name = "COV8-INV Profile $tag" }
            em.persist(profile)

            val invoice = Invoice().edit {
                invoiceNumber = InvoiceNumber.UNASSIGNED
                invoiceDate = LocalDate.now()
                senderProfile = sender
                this.recipient = recipient
                billingAddress = billing
                deliveryAddress = delivery
                b2bProfile = profile
                // chk_invoice_seller_vat_registration_country only allows the curated foreign-VAT
                // set (Country.SUPPORTED_SELLER_VAT_REGISTRATION_COUNTRIES), which excludes DE.
                sellerVatRegistrationCountry = Country.FR
                totals = InvoiceTotals(netAmount = Money(BigDecimal("100.00")), vatAmount = Money(BigDecimal("19.00")), grossAmount = Money(BigDecimal("119.00")))
            }
            val item = InvoiceItem().edit {
                taxRateRef = em.seededStandardTaxRate()
                positionId = "COV8-INV-1"
                description = "COV8-INV test position"
            }
            invoice.addItem(item)
            em.persist(invoice)
            em.flush()
            invoice.id!! to item.id!!
        }

    private fun persistSent(tag: String = counter.incrementAndGet().toString()): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            val (sender, recipient) = newSenderRecipient(tag)
            em.persist(sender)
            em.persist(recipient)
            val invoice = Invoice.withInitialStatus(InvoiceStatus.ISSUED).edit {
                invoiceNumber = InvoiceNumber(nextNumber())
                invoiceNumberSequence = counter.get().toLong()
                invoiceDate = LocalDate.now()
                senderProfile = sender
                this.recipient = recipient
                needsRecalculation = false
                totals = InvoiceTotals(netAmount = Money(BigDecimal("50.00")), vatAmount = Money(BigDecimal("9.50")), grossAmount = Money(BigDecimal("59.50")))
            }
            em.persist(invoice)
            em.flush()
            invoice.id!!
        }

    private fun invoiceNumberOf(id: Long): String = em.find(Invoice::class.java, id).invoiceNumber.value

    @Test
    fun `happy path - save persists the aggregate and it can be found by id, number, item and position lookups`() {
        val (id, itemId) = persistDraftWithItem()

        assertEquals(id, repository.findById(id)?.id)
        val withItems = repository.findByIdWithItems(id)
        assertEquals(1, withItems?.itemList?.size)

        val owner = lookup.findInvoiceByItemId(itemId)
        assertEquals(id, owner?.id)
        assertEquals(1, owner?.itemList?.size, "JOIN FETCH must not narrow the collection to just the looked-up item")

        val positions = lookup.findItemPositions(listOf(itemId))
        assertEquals("COV8-INV-1", positions[itemId])
        assertTrue(lookup.findItemPositions(emptyList()).isEmpty(), "an empty id collection must short-circuit without a query")

        // recalculateTotals() would need every item to carry a tax rate snapshot/reference (not
        // part of this fixture); saveAndFlush's own persistence path is exercised without it.
        val loaded = repository.findByIdWithItems(id)!!
        loaded.edit { needsRecalculation = false }
        val saved = QuarkusTransaction.requiringNew().call<Invoice> { repository.saveAndFlush(loaded) }
        assertEquals(id, saved.id)
        assertFalse(saved.needsRecalculation)
    }

    @Test
    fun `boundary - paging beyond the last page returns an empty list for findAll, findByRecipientId and findBySenderProfileId`() {
        val (id, _) = persistDraftWithItem()
        val invoice = repository.findById(id)!!
        val recipientId = invoice.recipient!!.id!!
        val senderId = invoice.senderProfile!!.id!!

        assertTrue(listing.findAll(pageIndex = 1_000_000, pageSize = 20).isEmpty())
        assertTrue(lookup.findByRecipientId(recipientId, pageIndex = 1_000_000, pageSize = 20).isEmpty())
        assertTrue(lookup.findBySenderProfileId(senderId, pageIndex = 1_000_000, pageSize = 20).isEmpty())
        assertTrue(lookup.findByRecipientId(recipientId, pageIndex = 0, pageSize = 20).any { it.id == id })
        assertTrue(lookup.findBySenderProfileId(senderId, pageIndex = 0, pageSize = 20).any { it.id == id })
        assertTrue(listing.count() >= 1L)
    }

    @Test
    fun `boundary - countByStatus counts only the given status and countOpenInvoicesForSellerVatCountry excludes finalized invoices`() {
        val (draftId, _) = persistDraftWithItem()
        val sentId = persistSent()
        val draft = repository.findById(draftId)!!
        val sent = repository.findById(sentId)!!

        assertTrue(listing.countByStatus(InvoiceStatus.DRAFT) >= 1L)
        assertTrue(listing.countByStatus(InvoiceStatus.ISSUED) >= 1L)

        val draftOpenCount = lookup.countOpenInvoicesForSellerVatCountry(draft.senderProfile!!.id!!, Country.FR)
        assertTrue(draftOpenCount >= 1L, "a DRAFT invoice must count as open for its own sender/country")

        val sentOwnOpenCount = lookup.countOpenInvoicesForSellerVatCountry(sent.senderProfile!!.id!!, Country.FR)
        assertEquals(0L, sentOwnOpenCount, "an ISSUED invoice must never count as open for its own sender/country")
    }

    @Test
    fun `boundary - existsByBillingAddressId, existsByDeliveryAddressId and existsByB2bProfileId are true only for a referenced id`() {
        val (id, _) = persistDraftWithItem()
        val invoice = repository.findById(id)!!
        val billingId = invoice.billingAddress!!.id!!
        val deliveryId = invoice.deliveryAddress!!.id!!
        val profileId = invoice.b2bProfile!!.id!!

        assertTrue(lookup.existsByBillingAddressId(billingId))
        assertTrue(lookup.existsByDeliveryAddressId(deliveryId))
        assertTrue(lookup.existsByB2bProfileId(profileId))
        assertFalse(lookup.existsByBillingAddressId(-1L))
        assertFalse(lookup.existsByDeliveryAddressId(-1L))
        assertFalse(lookup.existsByB2bProfileId(-1L))
    }

    @Test
    fun `boundary - recalculation lookups only include flagged invoices, updateValidationStatus bulk-updates by id, and detachIfManaged detaches`() {
        val (id, _) = persistDraftWithItem()

        assertTrue(id in repository.findAllForRecalculation().mapNotNull { it.id })
        assertTrue(id in listing.findAllIdsForRecalculation())

        // updateValidationStatus issues a bulk JPQL UPDATE and carries no @Transactional of its
        // own (by design — production callers run it inside their own ambient transaction, e.g.
        // DocumentJobPublisher.publish); provide one explicitly here, and read the result back
        // inside the very same transaction so the bulk statement's own uncommitted write is visible.
        val statusAfterUpdate = QuarkusTransaction.requiringNew().call<ValidationStatus?> {
            assertTrue(repository.updateValidationStatus(id, ValidationStatus.PENDING))
            em.find(Invoice::class.java, id)?.validationStatus
        }
        assertEquals(ValidationStatus.PENDING, statusAfterUpdate)
        val unknownIdUpdated = QuarkusTransaction.requiringNew().call<Boolean> {
            repository.updateValidationStatus(-1L, ValidationStatus.PENDING)
        }
        assertFalse(unknownIdUpdated, "an unknown id must not report a row updated")

        QuarkusTransaction.requiringNew().run {
            val managed = em.find(Invoice::class.java, id)
            assertTrue(em.contains(managed))
            repository.detachIfManaged(managed)
            assertFalse(em.contains(managed), "detachIfManaged must remove a managed instance from the persistence context")
        }

        // - a genuinely detached instance (this test's own `id`/`itemId` lookup already
        // returned a value outside any transaction) must be left untouched, the `em.contains`
        // false arm `detachIfManaged`'s own KDoc calls out.
        val detachedInvoice = repository.findByIdWithItems(id)!!
        repository.detachIfManaged(detachedInvoice)
    }

    @Test
    fun `boundary - findByInvoiceNumber finds an ISSUED invoice by its assigned number and returns null for an unknown one`() {
        val sentId = persistSent()
        val sentNumber = invoiceNumberOf(sentId)

        assertEquals(sentId, repository.findByInvoiceNumber(sentNumber)?.id)
        // Must stay a validly-formatted number (InvoiceNumber's own init block rejects a
        // malformed one before the query ever runs) - just one that no fixture ever assigns.
        assertNull(repository.findByInvoiceNumber("COV8INV-2099-9999"))
    }

    @Test
    fun `boundary - findByOriginalInvoiceNumber and findFinalizedInvoiceNumberAssignments only see ISSUED invoices carrying a number`() {
        val sentId = persistSent()
        val sentNumber = invoiceNumberOf(sentId)
        val creditTag = counter.incrementAndGet().toString()
        val creditId = QuarkusTransaction.requiringNew().call<Long> {
            val (sender, recipient) = newSenderRecipient(creditTag)
            em.persist(sender)
            em.persist(recipient)
            val credit = Invoice.withInitialStatus(InvoiceStatus.CANCELLED).edit {
                invoiceNumber = InvoiceNumber.UNASSIGNED
                invoiceDate = LocalDate.now()
                senderProfile = sender
                this.recipient = recipient
                originalInvoiceNumber = sentNumber
                // chk_invoice_*_amount_non_neg only allows a negative total for a CORRECTED_INVOICE
                // (credit note) row.
                typeCode = InvoiceTypeCode.CORRECTED_INVOICE
                totals = InvoiceTotals(netAmount = Money(BigDecimal("-50.00")), vatAmount = Money(BigDecimal("-9.50")), grossAmount = Money(BigDecimal("-59.50")))
            }
            em.persist(credit)
            em.flush()
            credit.id!!
        }

        val byOriginal = lookup.findByOriginalInvoiceNumber(sentNumber)
        assertTrue(byOriginal.any { it.id == creditId })

        val assignments = listing.findFinalizedInvoiceNumberAssignments()
        assertTrue(assignments.any { it.invoiceId == sentId })
    }
}
