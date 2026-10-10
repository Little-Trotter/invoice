package de.littletrotter.application.masterdata

import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.AllocatedNumber
import de.littletrotter.domain.reference.NumberCollisionException
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.domain.value.ArticleNumber
import de.littletrotter.domain.value.CustomerNumber
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.SQLException

/**
 * [MasterDataNumberService] had no direct unit test before; every branch below
 * (propose/assign guards, and [MasterDataNumberService.saveWithNumberCollisionRetry]'s retry
 * state machine — "user typed a value" vs "untouched proposal, skip to the next free
 * number") was previously only reachable incidentally through UI-level tests. Mocks
 * [NumberRangeService] (same style as `SellerProfileServiceTest`) so every branch is driven
 * directly rather than through a real number-range/DB round trip.
 */
class MasterDataNumberServiceTest {

    private val numberRanges = mock<NumberRangeService>()
    private val recipients = mock<RecipientRepository>()
    private val articles = mock<ArticleRepository>()
    private val sellerProfiles = mock<SenderProfileRepository>()
    private val service = MasterDataNumberService(
        numberRanges, recipients, articles, sellerProfiles,
        ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
    )

    private fun recipient(customerNumber: String = "") = Recipient().apply {
        id = 1L
        name = "Kunde GmbH"
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
    }

    private fun article(articleNumber: String? = null, category: InvoiceItemCategory = InvoiceItemCategory.PRODUCT) = Article().apply {
        id = 1L
        this.category = category
        this.articleNumber = articleNumber?.let(::ArticleNumber)
    }

    private fun seller(sellerNumber: String? = null) = SenderProfile().apply {
        id = 1L
        name = "ACME GmbH"
        this.sellerNumber = sellerNumber
    }

    private fun cve(constraintName: String) =
        ConstraintViolationException("duplicate key", SQLException("simulated unique-index violation"), constraintName)

    // ── proposeCustomer / proposeArticle / proposeSeller ────────────────────────────────────

    @Test
    fun `proposeCustomer returns null when no active range is configured`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(false)

        assertNull(service.proposeCustomer(recipient().name))
    }

    @Test
    fun `proposeCustomer peeks the next number when a range is active`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        whenever(numberRanges.peek(any(), any())).thenReturn("KD-0001")

        assertEquals("KD-0001", service.proposeCustomer(recipient().name))
    }

    @Test
    fun `proposeArticle returns null when no active range is configured`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(false)

        assertNull(service.proposeArticle(article().category))
    }

    @Test
    fun `proposeArticle peeks the next number when a range is active`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        whenever(numberRanges.peek(any(), any())).thenReturn("ART-0001")

        assertEquals("ART-0001", service.proposeArticle(article().category))
    }

    @Test
    fun `proposeSeller returns null when no active range is configured`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(false)

        assertNull(service.proposeSeller(seller().name))
    }

    @Test
    fun `proposeSeller peeks the next number when a range is active`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(true)
        whenever(numberRanges.peek(any(), any())).thenReturn("S-0001")

        assertEquals("S-0001", service.proposeSeller(seller().name))
    }

    // ── assertCustomerNumberAvailable ────────────────────────────────────────────────────────

    @Test
    fun `assertCustomerNumberAvailable does not look up a blank candidate`() {
        var lookedUp = false
        service.assertCustomerNumberAvailable("", "KD-0001") { lookedUp = true; null }

        assertTrue(!lookedUp)
    }

    @Test
    fun `assertCustomerNumberAvailable does not look up an untouched proposal`() {
        var lookedUp = false
        service.assertCustomerNumberAvailable("KD-0001", "KD-0001") { lookedUp = true; null }

        assertTrue(!lookedUp)
    }

    @Test
    fun `assertCustomerNumberAvailable passes when the candidate is free`() {
        service.assertCustomerNumberAvailable("KD-0002", "KD-0001") { null }
        // no exception
    }

    @Test
    fun `assertCustomerNumberAvailable throws when the candidate is already taken`() {
        assertThrows(NumberCollisionException::class.java) {
            service.assertCustomerNumberAvailable("KD-0002", "KD-0001") { recipient() }
        }
    }

    // ── assignCustomerNumber ─────────────────────────────────────────────────────────────────

    @Test
    fun `assignCustomerNumber returns null when no active range is configured`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(false)
        val r = recipient()

        assertNull(service.assignCustomerNumber(r))
        assertNull(r.customerNumber)
    }

    @Test
    fun `assignCustomerNumber returns null when the recipient already has a number`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        val r = recipient(customerNumber = "KD-0099")

        assertNull(service.assignCustomerNumber(r))
        verify(numberRanges, never()).allocate(any(), any())
    }

    @Test
    fun `assignCustomerNumber allocates when proposedNumber matches customerNumber`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("KD", 1, "KD-0001", "2026"))
        val r = recipient(customerNumber = "KD-0001")

        val allocated = service.assignCustomerNumber(r, "KD-0001")

        assertEquals(AllocatedNumber("KD", 1, "KD-0001", "2026"), allocated)
        verify(numberRanges).allocate(eq(NumberRangeSubject.CUSTOMER), any())
    }

    @Test
    fun `assignCustomerNumber returns null when customerNumber is custom and does not match proposal`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        val r = recipient(customerNumber = "KD-CUSTOM")

        assertNull(service.assignCustomerNumber(r, "KD-0001"))
        verify(numberRanges, never()).allocate(any(), any())
    }

    @Test
    fun `assignCustomerNumber allocates and writes prefix, sequence and number onto the recipient`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("KD", 7, "KD-0007", "2026"))
        val r = recipient()

        val allocated = service.assignCustomerNumber(r)

        assertEquals(AllocatedNumber("KD", 7, "KD-0007", "2026"), allocated)
        assertEquals("KD", r.customerNumberPrefix)
        assertEquals(7L, r.customerNumberSequence)
        assertEquals("KD-0007", r.customerNumber?.value)
    }

    @Test
    fun `boundary - assignCustomerNumber without a date draws the number for today in the business zone`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("KD", 8, "KD-0008", "2026"))
        val r = recipient()

        val allocated = service.assignCustomerNumber(r)

        assertEquals("KD-0008", allocated?.value)
        verify(numberRanges).allocate(eq(NumberRangeSubject.CUSTOMER), argThat { date == java.time.LocalDate.of(2026, 6, 15) })
    }

    // ── assignArticleNumber ──────────────────────────────────────────────────────────────────

    @Test
    fun `assignArticleNumber returns null when no active range is configured`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(false)

        assertNull(service.assignArticleNumber(article()))
    }

    @Test
    fun `assignArticleNumber returns null when the article already has a number`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        val a = article(articleNumber = "ART-0099")

        assertNull(service.assignArticleNumber(a))
        verify(numberRanges, never()).allocate(any(), any())
    }

    @Test
    fun `assignArticleNumber allocates when proposedNumber matches articleNumber`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("ART", 1, "ART-0001", "2026"))
        val a = article(articleNumber = "ART-0001")

        val allocated = service.assignArticleNumber(a, "ART-0001")

        assertEquals(AllocatedNumber("ART", 1, "ART-0001", "2026"), allocated)
        verify(numberRanges).allocate(eq(NumberRangeSubject.CATALOGUE), any())
    }

    @Test
    fun `assignArticleNumber allocates when the article number is null`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("ART", 3, "ART-0003", "2026"))
        val a = article(articleNumber = null)

        val allocated = service.assignArticleNumber(a)

        assertEquals("ART-0003", allocated?.value)
        assertEquals("ART-0003", a.articleNumber?.value)
        assertEquals(3L, a.articleNumberSequence)
    }

    // A blank-but-non-null article number is unrepresentable now (ArticleNumber's own
    // init rejects it at construction), so the former "allocates when the article number is
    // blank" guard test is obsolete; the null case above already covers the guard.

    @Test
    fun `boundary - assignArticleNumber returns null when the article number is custom and does not match the proposal`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        val a = article(articleNumber = "ART-CUSTOM")

        assertNull(service.assignArticleNumber(a, "ART-0001"))
        verify(numberRanges, never()).allocate(any(), any())
    }

    @Test
    fun `boundary - assignArticleNumber without a date draws the number for today in the business zone`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("ART", 9, "ART-0009", "2026"))
        val a = article(articleNumber = null)

        val allocated = service.assignArticleNumber(a)

        assertEquals("ART-0009", allocated?.value)
        verify(numberRanges).allocate(eq(NumberRangeSubject.CATALOGUE), argThat { date == java.time.LocalDate.of(2026, 6, 15) })
    }

    // ── assignSellerNumber ───────────────────────────────────────────────────────────────────

    @Test
    fun `assignSellerNumber returns null when no active range is configured`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(false)

        assertNull(service.assignSellerNumber(seller()))
    }

    @Test
    fun `assignSellerNumber returns null when the seller already has a number`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(true)
        val s = seller(sellerNumber = "S-0099")

        assertNull(service.assignSellerNumber(s))
        verify(numberRanges, never()).allocate(any(), any())
    }

    @Test
    fun `assignSellerNumber allocates when proposedNumber matches sellerNumber`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("S", 1, "S-0001", "2026"))
        val s = seller(sellerNumber = "S-0001")

        val allocated = service.assignSellerNumber(s, "S-0001")

        assertEquals(AllocatedNumber("S", 1, "S-0001", "2026"), allocated)
        verify(numberRanges).allocate(eq(NumberRangeSubject.SELLER), any())
    }

    @Test
    fun `assignSellerNumber allocates and writes prefix, sequence and number onto the seller`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("S", 5, "S-0005", "2026"))
        val s = seller()

        val allocated = service.assignSellerNumber(s)

        assertEquals("S-0005", allocated?.value)
        assertEquals("S", s.sellerNumberPrefix)
        assertEquals(5L, s.sellerNumberSequence)
        assertEquals("S-0005", s.sellerNumber)
    }

    @Test
    fun `boundary - assignSellerNumber allocates when the seller number is blank`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("S", 6, "S-0006", "2026"))
        val s = seller(sellerNumber = "   ")

        assertEquals("S-0006", service.assignSellerNumber(s)?.value)
    }

    @Test
    fun `boundary - assignSellerNumber returns null when the seller number is custom and does not match the proposal`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(true)
        val s = seller(sellerNumber = "S-CUSTOM")

        assertNull(service.assignSellerNumber(s, "S-0001"))
        verify(numberRanges, never()).allocate(any(), any())
    }

    // ── syncCustomerCounter / syncArticleCounter / syncSellerCounter ────────────────────────

    @Test
    fun `boundary - syncCustomerCounter raises the CUSTOMER counter to the highest persisted customer number suffix`() {
        whenever(recipients.findAllCustomerNumbers()).thenReturn(listOf("KD-0001", "KD-0042"))
        val result = de.littletrotter.domain.reference.CounterSyncResult(NumberRangeSubject.CUSTOMER, 0L, 42L, changed = true)
        whenever(numberRanges.syncCounterTo(NumberRangeSubject.CUSTOMER, 42L)).thenReturn(result)

        assertEquals(result, service.syncCustomerCounter())
    }

    @Test
    fun `boundary - syncArticleCounter raises the CATALOGUE counter to the highest persisted article number suffix`() {
        whenever(articles.findAllArticleNumbers()).thenReturn(listOf("ART-0003"))
        val result = de.littletrotter.domain.reference.CounterSyncResult(NumberRangeSubject.CATALOGUE, 0L, 3L, changed = true)
        whenever(numberRanges.syncCounterTo(NumberRangeSubject.CATALOGUE, 3L)).thenReturn(result)

        assertEquals(result, service.syncArticleCounter())
    }

    @Test
    fun `boundary - syncSellerCounter raises the SELLER counter to the highest persisted seller number suffix`() {
        whenever(sellerProfiles.findAllSellerNumbers()).thenReturn(listOf("S-0005"))
        val result = de.littletrotter.domain.reference.CounterSyncResult(NumberRangeSubject.SELLER, 0L, 5L, changed = true)
        whenever(numberRanges.syncCounterTo(NumberRangeSubject.SELLER, 5L)).thenReturn(result)

        assertEquals(result, service.syncSellerCounter())
    }

    // ── saveXWithNumber / saveWithNumberCollisionRetry ──────────────────────────────────────

    @Test
    fun `saveArticleWithNumber returns the flush result when it succeeds on the first attempt`() {
        val result = service.saveArticleWithNumber(article(articleNumber = "ART-0001"), "ART-0001") { "OK" }

        assertEquals("OK", result)
    }

    @Test
    fun `saveArticleWithNumber rejects a typed taken number before persisting`() {
        var persisted = false
        val ex = assertThrows(NumberCollisionException::class.java) {
            service.saveArticleWithNumber(article(articleNumber = "ART-0009"), "ART-0001", isTaken = { it == "ART-0009" }) {
                persisted = true
            }
        }
        assertEquals("ART-0009", ex.attemptedValue)
        assertTrue(!persisted)
    }

    @Test
    fun `saveArticleWithNumber persists a typed free number`() {
        val result = service.saveArticleWithNumber(article(articleNumber = "ART-0009"), "ART-0001", isTaken = { false }) { "OK" }
        assertEquals("OK", result)
    }

    @Test
    fun `saveArticleWithNumber does not look up an untouched proposal`() {
        var lookedUp = false
        service.saveArticleWithNumber(article(articleNumber = "ART-0001"), "ART-0001", isTaken = { lookedUp = true; true }) { "OK" }
        assertTrue(!lookedUp)
    }

    @Test
    fun `saveArticleWithNumber rethrows an exception unrelated to the unique constraint`() {
        val ex = IllegalStateException("boom")

        val thrown = assertThrows(IllegalStateException::class.java) {
            service.saveArticleWithNumber(article(articleNumber = "ART-0001"), "ART-0001") { throw ex }
        }
        assertEquals(ex, thrown)
    }

    @Test
    fun `saveArticleWithNumber rethrows unchanged when the constraint violation is nested but under the wrong name`() {
        val wrongName = cve("some_other_constraint")

        val thrown = assertThrows(ConstraintViolationException::class.java) {
            service.saveArticleWithNumber(article(articleNumber = "ART-0001"), "ART-0001") { throw wrongName }
        }
        assertEquals(wrongName, thrown)
    }

    @Test
    fun `saveArticleWithNumber throws NumberCollisionException with empty value when currentValue is null and the number was user-typed`() {
        assertThrows(NumberCollisionException::class.java) {
            service.saveArticleWithNumber(article(articleNumber = null), "ART-0002") {
                throw cve(UQ_ARTICLE_NUMBER)
            }
        }
    }

    @Test
    fun `saveArticleWithNumber throws NumberCollisionException carrying currentValue when proposedValue is null`() {
        val thrown = assertThrows(NumberCollisionException::class.java) {
            service.saveArticleWithNumber(article(articleNumber = "ART-0003"), null) {
                throw cve(UQ_ARTICLE_NUMBER)
            }
        }
        assertEquals("ART-0003", thrown.attemptedValue)
    }

    @Test
    fun `saveArticleWithNumber throws NumberCollisionException when the user typed a different value than proposed`() {
        val thrown = assertThrows(NumberCollisionException::class.java) {
            service.saveArticleWithNumber(article(articleNumber = "ART-0004"), "ART-9999") {
                throw cve(UQ_ARTICLE_NUMBER)
            }
        }
        assertEquals("ART-0004", thrown.attemptedValue)
    }

    @Test
    fun `saveArticleWithNumber reassigns and retries once when currentValue equals the untouched proposal`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("ART", 8, "ART-0008", "2026"))
        val a = article(articleNumber = "ART-0005")
        var attempt = 0
        // Nested-cause chain (RuntimeException wrapping the real ConstraintViolationException) —
        // the realistic Hibernate/Panache shape; exercises
        // findConstraintViolation walking past the outer, non-matching frame.
        val nested = RuntimeException("flush failed", cve(UQ_ARTICLE_NUMBER))

        val result = service.saveArticleWithNumber(a, "ART-0005") {
            attempt++
            if (attempt == 1) throw nested else "OK-$attempt"
        }

        assertEquals("OK-2", result)
        assertEquals("ART-0008", a.articleNumber?.value)
        verify(numberRanges, times(1)).allocate(any(), any())
    }

    @Test
    fun `saveArticleWithNumber rethrows an unrelated exception raised during a reassign retry`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("ART", 9, "ART-0009", "2026"))
        val a = article(articleNumber = "ART-0006")
        var attempt = 0
        val retryFailure = IllegalStateException("db is down")

        val thrown = assertThrows(IllegalStateException::class.java) {
            service.saveArticleWithNumber(a, "ART-0006") {
                attempt++
                if (attempt == 1) throw cve(UQ_ARTICLE_NUMBER) else throw retryFailure
            }
        }

        assertEquals(retryFailure, thrown)
    }

    @Test
    fun `saveArticleWithNumber gives up with NumberCollisionException after exhausting every reassign attempt`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CATALOGUE)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("ART", 1, "ART-STALE", "2026"))
        val a = article(articleNumber = "ART-0007")

        val thrown = assertThrows(NumberCollisionException::class.java) {
            service.saveArticleWithNumber(a, "ART-0007") { throw cve(UQ_ARTICLE_NUMBER) }
        }

        assertEquals("ART-0007", thrown.attemptedValue)
        verify(numberRanges, times(100)).allocate(any(), any())
    }

    @Test
    fun `saveCustomerWithNumber reassigns via the customer-specific constraint name`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("KD", 2, "KD-0002", "2026"))
        val r = recipient(customerNumber = "KD-0001")
        var attempt = 0

        val result = service.saveCustomerWithNumber(r, "KD-0001") {
            attempt++
            if (attempt == 1) throw cve(UQ_CUSTOMER_NUMBER) else "OK"
        }

        assertEquals("OK", result)
        assertEquals("KD-0002", r.customerNumber?.value)
    }

    @Test
    fun `saveCustomerWithNumber skips an already taken untouched proposal before the insert`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("KD", 2, "KD-0002", "2026"))
        val r = recipient(customerNumber = "KD-0001")
        var attempts = 0

        val result = service.saveCustomerWithNumber(r, "KD-0001", isTaken = { it == "KD-0001" }) {
            attempts++
            "OK"
        }

        assertEquals("OK", result)
        assertEquals(1, attempts)
        assertEquals("KD-0002", r.customerNumber?.value)
    }

    @Test
    fun `saveCustomerWithNumber leaves a typed number alone even when it is taken`() {
        val r = recipient(customerNumber = "KD-0001")

        service.saveCustomerWithNumber(r, null, isTaken = { true }) { "OK" }

        assertEquals("KD-0001", r.customerNumber?.value)
        verify(numberRanges, never()).allocate(any(), any())
    }

    @Test
    fun `saveCustomerWithNumber stops skipping after the attempt bound`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.CUSTOMER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("KD", 2, "KD-0001", "2026"))
        val r = recipient(customerNumber = "KD-0001")

        service.saveCustomerWithNumber(r, "KD-0001", isTaken = { true }) { "OK" }

        verify(numberRanges, times(100)).allocate(any(), any())
    }

    @Test
    fun `saveSellerWithNumber reassigns via the seller-specific constraint name`() {
        whenever(numberRanges.hasActiveRangeFor(NumberRangeSubject.SELLER)).thenReturn(true)
        whenever(numberRanges.allocate(any(), any())).thenReturn(AllocatedNumber("S", 6, "S-0006", "2026"))
        val s = seller(sellerNumber = "S-0001")
        var attempt = 0

        val result = service.saveSellerWithNumber(s, "S-0001") {
            attempt++
            if (attempt == 1) throw cve(UQ_SELLER_NUMBER) else "OK"
        }

        assertEquals("OK", result)
        assertEquals("S-0006", s.sellerNumber)
    }

    companion object {
        private const val UQ_CUSTOMER_NUMBER = "uq_recipient_customer_number"
        private const val UQ_ARTICLE_NUMBER = "uq_article_article_number"
        private const val UQ_SELLER_NUMBER = "uq_sender_profile_seller_number"
    }
}
