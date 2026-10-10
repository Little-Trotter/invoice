package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.ArticleNumber
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * [PanacheArticleRepository]'s CRUD surface (save's persist-vs-merge branch,
 * findById's eager-collection touch, isInUse, findAllArticleNumbers, forceVersionIncrement,
 * delete) had no direct test against a real Postgres before this class: every reference above
 * ([PanacheArticleRepositorySelectIT]/[PanacheArticleRepositorySearchSortIT]) exercises only the
 * query-building methods.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PanacheArticleRepositoryCrudIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var repository: ArticleRepository

    private fun newArticle(name: String, number: String? = null): Article = Article().apply {
        this.name = name
        this.category = InvoiceItemCategory.PRODUCT
        this.defaultUnit = InvoiceItemUnit.PIECE
        this.articleNumber = number?.let(::ArticleNumber)
    }

    @Test
    fun `save persists a new article, then merges an update to the same row`() {
        // ST-1 update-vs-delete race — save now flushes immediately in the CALLER's own ambient
        // transaction (mirrors PanacheSenderProfileRepositoryIT's own wrapping): a bare call with
        // no active transaction would fail with TransactionRequiredException at that flush.
        val created = QuarkusTransaction.requiringNew().call<Article> {
            repository.save(newArticle("COV38-ARC Neu", "COV38-ARC-0001"))
        }
        assertNotNull(created.id, "save's persist branch must assign an id")

        created.name = "COV38-ARC Umbenannt"
        val merged = QuarkusTransaction.requiringNew().call<Article> { repository.save(created) }

        assertEquals(created.id, merged.id, "save's merge branch must keep the same row identity")
        val reloaded = QuarkusTransaction.requiringNew().call<Article> { em.find(Article::class.java, created.id) }
        assertEquals("COV38-ARC Umbenannt", reloaded.name)
    }

    @Test
    fun `saveAndFlush surfaces the persisted row immediately in the same transaction`() {
        val saved = repository.saveAndFlush(newArticle("COV38-ARC Flush"))

        assertNotNull(saved.id)
        val expectedCount = (
            em.createNativeQuery("SELECT COUNT(*) FROM article WHERE id = :id")
                .setParameter("id", saved.id)
                .singleResult as Number
            ).toLong()
        assertEquals(1L, expectedCount, "the flush must have made the row visible without waiting for transaction end")
    }

    @Test
    fun `findById loads the article and touches both owned collections without throwing`() {
        val saved = QuarkusTransaction.requiringNew().call<Article> { repository.save(newArticle("COV38-ARC FindById")) }

        val loaded = repository.findById(saved.id!!)

        assertNotNull(loaded)
        assertEquals(saved.id, loaded?.id)
    }

    @Test
    fun `findById returns null for an id that does not exist`() {
        assertNull(repository.findById(-1L))
    }

    @Test
    fun `forceVersionIncrement bumps the optimistic version of an already-persisted article`() {
        val saved = QuarkusTransaction.requiringNew().call<Article> { repository.save(newArticle("COV38-ARC Version")) }
        // -- every read/write below goes through its own QuarkusTransaction.requiringNew()
        // block, the same discipline PanacheSenderProfileRepositoryIT's own forceVersionIncrement
        // test uses: a bare top-level em.find() call reuses this test method's ambient persistence
        // context, so a later em.find() for the same id returns the SAME cached Java instance
        // instead of re-reading the row -- "before" and "after" would silently be the identical,
        // stale object.
        val before = QuarkusTransaction.requiringNew().call<Long> { em.find(Article::class.java, saved.id)!!.version!! }

        QuarkusTransaction.requiringNew().run {
            val managed = em.find(Article::class.java, saved.id)
            repository.forceVersionIncrement(managed)
        }

        val after = QuarkusTransaction.requiringNew().call<Long> { em.find(Article::class.java, saved.id)!!.version!! }
        assertTrue(after > before, "forceVersionIncrement must bump the version even without a field change")
    }

    @Test
    fun `delete removes the row, a repeat delete of the same id is a harmless no-op`() {
        val saved = QuarkusTransaction.requiringNew().call<Article> { repository.save(newArticle("COV38-ARC Delete")) }

        // ST-1 delete-vs-use race — delete now flushes immediately in the CALLER's own ambient
        // transaction too (same reasoning as save above).
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }

        assertNull(em.find(Article::class.java, saved.id), "the row must be gone")
        QuarkusTransaction.requiringNew().run { repository.delete(saved.id!!) }
    }

    @Test
    fun `findAllArticleNumbers includes an assigned number and excludes a null one`() {
        // -- deliberately NOT the German phrase "ohne Nummer": this shared, unrestricted
        // IT container is also scanned by PanacheArticleRepositorySelectIT's own PRODUCT-category
        // B3 test, which filters on exactly that substring across the whole table -- a genuine
        // fixture collision, not a coincidence, reproduced once already.
        val numbered = QuarkusTransaction.requiringNew()
            .call<Article> { repository.save(newArticle("COV38-ARC Nummeriert", "COV38-ARC-NUM-0001")) }
        QuarkusTransaction.requiringNew().run { repository.save(newArticle("COV38-ARC Nummerlos", number = null)) }

        val numbers = repository.findAllArticleNumbers()

        assertTrue(numbers.contains(numbered.articleNumber!!.value), "an assigned number must be listed")
        assertTrue(numbers.none { it.isBlank() }, "a null/blank article_number must never appear in the list")
    }

    @Test
    fun `isInUse sees an article referenced by an invoice item and ignores an unused one`() {
        val used = QuarkusTransaction.requiringNew().call<Article> { repository.save(newArticle("COV38-ARC Verwendet", "COV38-ARC-USED")) }
        val unused = QuarkusTransaction.requiringNew().call<Article> { repository.save(newArticle("COV38-ARC Unverwendet", "COV38-ARC-UNUSED")) }

        QuarkusTransaction.requiringNew().run {
            val sender = SenderProfile().apply {
                name = "COV38-ARC Sender"
                email = Email("cov38-arc@overview.example")
                taxNumber = TaxNumber("12/345/67892")
                vatId = VatId("DE333333333")
                iban = Iban("DE89370400440532013000")
                registerCourt = "AG Musterstadt"
                invoicePrefix = "COV38ARC-"
            }
            val recipient = Recipient().apply {
                customerNumber = CustomerNumber("COV38-ARC-KD")
                name = "COV38-ARC Kunde"
                email = Email("kunde@overview.example")
                vatId = VatId("DE444444444")
                iban = Iban("DE02120300000000202051")
            }
            em.persist(sender)
            em.persist(recipient)
            val invoice = Invoice().apply {
                invoiceNumber = InvoiceNumber("COV38ARC-2026-0001")
                invoiceDate = LocalDate.now()
                senderProfile = sender
                this.recipient = recipient
                totals.netAmount = Money(BigDecimal("100.00"))
                totals.vatAmount = Money(BigDecimal("19.00"))
                totals.grossAmount = Money(BigDecimal("119.00"))
            }
            val item = InvoiceItem().apply {
                positionId = "COV38-ARC-1"
                description = "COV38-ARC test position"
                articleRef = em.getReference(Article::class.java, used.id!!)
            }
            invoice.addItem(item)
            em.persist(invoice)
        }

        assertTrue(repository.isInUse(used.id!!), "an article referenced by an invoice_item row must be reported in use")
        assertFalse(repository.isInUse(unused.id!!), "an article with no referencing row anywhere must not be in use")
    }
}
