package de.littletrotter.domain.repository

import de.littletrotter.domain.invoice.asMutableForTest
import de.littletrotter.domain.invoice.forceStatusForTest

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.shared.ValidationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [InvoiceRepository]'s default method bodies (`countOpenInvoicesForSellerVatCountry`,
 * `existsByB2bProfileId`, `findFinalizedInvoiceNumberAssignments`, `updateValidationStatus`) are,
 * per each method's own KDoc, compatibility fallbacks for in-memory test doubles that predate
 * them — same "default body for test doubles, real query lives in PanacheInvoiceRepository"
 * pattern as [ArticleRepository.findAllInCategory] (see [ArticleRepositoryDefaultsTest]'s own KDoc). They
 * are new, real code with real branches, so — same call as `ArticleRepositoryDefaultsTest` — they get a
 * direct test here rather than relying on being exercised incidentally through some other
 * repository fake that overrides them anyway (every existing `InMemoryInvoiceRepository` in this
 * codebase does override them, which is exactly why they were otherwise never running).
 */
class InvoiceRepositoryDefaultsTest {

    private fun senderProfile(id: Long) = SenderProfile().apply { this.id = id }

    private fun b2bProfile(id: Long) = B2bProfile().apply { this.id = id }

    private fun invoice(
        id: Long,
        status: InvoiceStatus = InvoiceStatus.DRAFT,
        senderProfile: SenderProfile? = null,
        sellerVatRegistrationCountry: Country? = null,
        b2bProfile: B2bProfile? = null,
        validationStatus: ValidationStatus = ValidationStatus.NOT_VALIDATED,
        invoiceNumberSequence: Long? = null,
    ) = Invoice().apply {
        this.id = id
        this.forceStatusForTest(status)
        this.senderProfile = senderProfile
        this.sellerVatRegistrationCountry = sellerVatRegistrationCountry
        this.b2bProfile = b2bProfile
        this.validationStatus = validationStatus
        this.invoiceNumberSequence = invoiceNumberSequence
    }

    /** Minimal [InvoiceRepository] implementing only the non-default members — every default
     *  body under test here therefore runs as the interface's own implementation, exactly like
     *  [ArticleRepositoryDefaultsTest.repoWith]. */
    private fun repoWith(vararg invoices: Invoice): InvoiceRepository = object : InvoiceRepository {
        private val data = invoices.toList()
        override fun findById(id: Long) = data.find { it.id == id }
        override fun findByIdWithItems(id: Long) = data.find { it.id == id }
        override fun findByInvoiceNumber(number: String) = data.find { it.invoiceNumber.value == number }
        override fun findBySourceQuoteId(quoteId: java.util.UUID): Invoice? = null
        override fun findAll(pageIndex: Int, pageSize: Int) = data
        override fun findOverviewPage(pageIndex: Int, pageSize: Int, filterText: String?, status: InvoiceStatus?) = data
        override fun countForOverview(filterText: String?, status: InvoiceStatus?) = data.size.toLong()
        override fun count() = data.size.toLong()
        override fun countByStatus(status: InvoiceStatus) = data.count { it.status == status }.toLong()
        override fun findByRecipientId(recipientId: Long, pageIndex: Int, pageSize: Int) = emptyList<Invoice>()
        override fun findBySenderProfileId(senderProfileId: Long, pageIndex: Int, pageSize: Int) = emptyList<Invoice>()
        override fun existsByBillingAddressId(addressId: Long) = false
        override fun existsByDeliveryAddressId(addressId: Long) = false
        override fun findInvoiceByItemId(itemId: Long): Invoice? = data.find { invoice -> invoice.itemList.any { it.id == itemId } }
        override fun save(invoice: Invoice): Invoice = invoice
        override fun forceVersionIncrement(invoice: Invoice) { invoice.version = (invoice.version ?: 0L) + 1L }
        override fun findAllForRecalculation() = data
    }

    private fun item(id: Long, positionId: String) = InvoiceItem().apply {
        this.id = id
        this.positionId = positionId
    }

    // ── countOpenInvoicesForSellerVatCountry ────────────────────────────────────────────────

    @Test
    fun `countOpenInvoicesForSellerVatCountry counts a matching open invoice`() {
        val sender = senderProfile(1)
        val repo = repoWith(invoice(1, status = InvoiceStatus.DRAFT, senderProfile = sender, sellerVatRegistrationCountry = Country.DE))

        assertEquals(1L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry excludes an invoice with no senderProfile`() {
        val repo = repoWith(invoice(1, status = InvoiceStatus.DRAFT, senderProfile = null, sellerVatRegistrationCountry = Country.DE))

        assertEquals(0L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry excludes an invoice for a different senderProfile`() {
        val repo = repoWith(
            invoice(1, status = InvoiceStatus.DRAFT, senderProfile = senderProfile(2), sellerVatRegistrationCountry = Country.DE),
        )

        assertEquals(0L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry excludes an invoice for a different country`() {
        val sender = senderProfile(1)
        val repo = repoWith(invoice(1, status = InvoiceStatus.DRAFT, senderProfile = sender, sellerVatRegistrationCountry = Country.FR))

        assertEquals(0L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry excludes SENT invoices`() {
        val sender = senderProfile(1)
        val repo = repoWith(invoice(1, status = InvoiceStatus.SENT, senderProfile = sender, sellerVatRegistrationCountry = Country.DE))

        assertEquals(0L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry excludes CANCELLED invoices`() {
        val sender = senderProfile(1)
        val repo = repoWith(invoice(1, status = InvoiceStatus.CANCELLED, senderProfile = sender, sellerVatRegistrationCountry = Country.DE))

        assertEquals(0L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry excludes VOIDED invoices`() {
        val sender = senderProfile(1)
        val repo = repoWith(invoice(1, status = InvoiceStatus.VOIDED, senderProfile = sender, sellerVatRegistrationCountry = Country.DE))

        assertEquals(0L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry excludes an invoice with no sellerVatRegistrationCountry at all`() {
        val sender = senderProfile(1)
        val repo = repoWith(invoice(1, status = InvoiceStatus.DRAFT, senderProfile = sender, sellerVatRegistrationCountry = null))

        assertEquals(0L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    @Test
    fun `countOpenInvoicesForSellerVatCountry counts an IN_REVIEW invoice as still open`() {
        val sender = senderProfile(1)
        val repo = repoWith(invoice(1, status = InvoiceStatus.IN_REVIEW, senderProfile = sender, sellerVatRegistrationCountry = Country.DE))

        assertEquals(1L, repo.countOpenInvoicesForSellerVatCountry(1, Country.DE))
    }

    // ── existsByB2bProfileId ─────────────────────────────────────────────────────────────────

    @Test
    fun `existsByB2bProfileId is true when an invoice references the profile`() {
        val repo = repoWith(invoice(1, b2bProfile = b2bProfile(9)))

        assertTrue(repo.existsByB2bProfileId(9))
    }

    @Test
    fun `existsByB2bProfileId is false when no invoice references the profile`() {
        val repo = repoWith(invoice(1, b2bProfile = b2bProfile(9)))

        assertFalse(repo.existsByB2bProfileId(42))
    }

    @Test
    fun `existsByB2bProfileId is false when the invoice has no b2bProfile at all`() {
        val repo = repoWith(invoice(1, b2bProfile = null))

        assertFalse(repo.existsByB2bProfileId(9))
    }

    @Test
    fun `existsByB2bProfileId is false for an empty repository`() {
        val repo = repoWith()

        assertFalse(repo.existsByB2bProfileId(9))
    }

    @Test
    fun `existsByB2bProfileId is false when the referenced b2bProfile itself has no id yet`() {
        val repo = repoWith(invoice(1, b2bProfile = B2bProfile()))

        assertFalse(repo.existsByB2bProfileId(9))
    }

    // ── findFinalizedInvoiceNumberAssignments ───────────────────────────────────────────────

    @Test
    fun `findFinalizedInvoiceNumberAssignments returns an assignment for a SENT invoice with a sequence`() {
        val repo = repoWith(invoice(1, status = InvoiceStatus.SENT, invoiceNumberSequence = 42))

        val result = repo.findFinalizedInvoiceNumberAssignments()

        assertEquals(listOf(InvoiceNumberAssignment(1, 42)), result)
    }

    @Test
    fun `findFinalizedInvoiceNumberAssignments skips a SENT invoice without a sequence`() {
        val repo = repoWith(invoice(1, status = InvoiceStatus.SENT, invoiceNumberSequence = null))

        assertTrue(repo.findFinalizedInvoiceNumberAssignments().isEmpty())
    }

    @Test
    fun `findFinalizedInvoiceNumberAssignments skips a non-SENT invoice even with a sequence`() {
        val repo = repoWith(invoice(1, status = InvoiceStatus.DRAFT, invoiceNumberSequence = 42))

        assertTrue(repo.findFinalizedInvoiceNumberAssignments().isEmpty())
    }

    // ── updateValidationStatus ───────────────────────────────────────────────────────────────

    @Test
    fun `updateValidationStatus transitions an existing invoice and returns true`() {
        val target = invoice(1, validationStatus = ValidationStatus.VALID)
        val repo = repoWith(target)

        val result = repo.updateValidationStatus(1, ValidationStatus.PENDING)

        assertTrue(result)
        assertEquals(ValidationStatus.PENDING, target.validationStatus)
    }

    @Test
    fun `updateValidationStatus returns false when the invoice does not exist`() {
        val repo = repoWith()

        assertFalse(repo.updateValidationStatus(404, ValidationStatus.PENDING))
    }

    // ── saveAndFlush / findAllIdsForRecalculation / findByOriginalInvoiceNumber /
    //    findItemPositions / detachIfManaged ──────────────────────────────────────────────────

    @Test
    fun `happy path saveAndFlush delegates to save`() {
        val repo = repoWith()
        val toSave = invoice(1)

        assertEquals(toSave, repo.saveAndFlush(toSave))
    }

    @Test
    fun `boundary findAllIdsForRecalculation projects the ids of findAllForRecalculation`() {
        val repo = repoWith(invoice(1), invoice(2))

        assertEquals(listOf(1L, 2L), repo.findAllIdsForRecalculation())
    }

    @Test
    fun `boundary findByOriginalInvoiceNumber keeps only invoices pointing at the given number`() {
        val cancellation = invoice(2).apply { originalInvoiceNumber = "RE-2026-00001" }
        val repo = repoWith(invoice(1), cancellation)

        assertEquals(listOf(2L), repo.findByOriginalInvoiceNumber("RE-2026-00001").map { it.id })
    }

    @Test
    fun `boundary findByOriginalInvoiceNumber is empty when nothing points at the number`() {
        val repo = repoWith(invoice(1))

        assertTrue(repo.findByOriginalInvoiceNumber("RE-2026-00001").isEmpty())
    }

    @Test
    fun `boundary findItemPositions folds per-item invoice lookups, skipping ids with no match`() {
        val withItems = invoice(1).apply { itemList.asMutableForTest().add(item(10, "1")) }
        val repo = repoWith(withItems)

        assertEquals(mapOf(10L to "1"), repo.findItemPositions(listOf(10L, 99L)))
    }

    @Test
    fun `boundary findItemPositions skips an id whose invoice no longer actually contains it`() {
        // findInvoiceByItemId's own contract only promises the AGGREGATE, not that the
        // item is still among its current items (a defensive mismatch, e.g. a hard-deleted
        // position between lookup and read) -- distinct from repoWith's own find(), which by
        // construction only ever returns an invoice that does contain the id.
        val withOtherItem = invoice(1).apply { itemList.asMutableForTest().add(item(10, "1")) }
        val repo = object : InvoiceRepository by repoWith(withOtherItem) {
            override fun findInvoiceByItemId(itemId: Long): Invoice? = withOtherItem
        }

        assertTrue(repo.findItemPositions(listOf(404L)).isEmpty())
    }

    @Test
    fun `boundary detachIfManaged is a no-op for every in-memory test double`() {
        val repo = repoWith()

        repo.detachIfManaged(invoice(1))
    }

    // ── plain abstract methods with default paging parameters, exercised directly through the
    //    interface so their own $default dispatchers run (never called this way elsewhere) ─────

    @Test
    fun `boundary findAll, findOverviewPage, countForOverview, findByRecipientId and findBySenderProfileId with paging omitted use the documented defaults`() {
        val repo = repoWith(invoice(1))

        assertEquals(listOf(1L), repo.findAll().map { it.id })
        assertEquals(listOf(1L), repo.findOverviewPage().map { it.id })
        assertEquals(1L, repo.countForOverview())
        assertTrue(repo.findByRecipientId(1).isEmpty())
        assertTrue(repo.findBySenderProfileId(1).isEmpty())
    }
}
