package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.anArticle
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber

/**
 * DB-level guarantees for `article_recipient_ref` (V915) that the domain layer
 * ([ArticleRecipientRef.validate]/[ArticleRecipientRef.assertUniqueRecipients]) cannot be
 * bypassed on: the `(article_id, recipient_id)` unique index, the not-both-blank CHECK, and the
 * two deletion-cascade paths — article delete (JPA `cascade = ALL, orphanRemoval = true`, mirrors
 * `ArticleTaxVariant`) and recipient delete (DB `ON DELETE CASCADE`, the recipient-
 * deletion decision: a mapping row is meaningless without the recipient it maps to).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ArticleRecipientRefRepositoryIT {

    @Inject
    lateinit var em: EntityManager

    /** Own rows only, own naming prefix — Quarkus test transactions commit, they don't roll back, so leftovers from a
     *  previous run (or a previous test method) must not collide with this one. */
    @BeforeEach
    fun removeOwnRows() {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery("DELETE FROM article_recipient_ref WHERE article_id IN (SELECT id FROM article WHERE name LIKE 't11-%')").executeUpdate()
            em.createNativeQuery("DELETE FROM recipient WHERE customer_number LIKE 'T11-%'").executeUpdate()
            em.createNativeQuery("DELETE FROM article WHERE name LIKE 't11-%'").executeUpdate()
        }
    }

    private fun newArticle(name: String): Article = anArticle { this.name = name }.also { em.persist(it); em.flush() }

    private fun newRecipient(customerNumber: String, name: String): Recipient =
        aRecipient { this.customerNumber = CustomerNumber.ofNullable(customerNumber); this.name = name }.also { em.persist(it); em.flush() }

    @Test
    @Transactional
    fun `a valid mapping persists and reloads`() {
        val article = newArticle("t11-widget")
        val recipient = newRecipient("T11-0001", "Nekar Supermarkt")

        val ref = ArticleRecipientRef.of(article, recipient, "243538", "Nekar-Bezeichnung")
        em.persist(ref)
        em.flush()

        assertNotNull(ref.id)
        em.clear()

        val reloaded = em.find(ArticleRecipientRef::class.java, ref.id)!!
        assertEquals("243538", reloaded.buyerArticleNumber)
        assertEquals("Nekar-Bezeichnung", reloaded.buyerArticleName)
        assertEquals(article.id, reloaded.article?.id)
        assertEquals(recipient.id, reloaded.recipient?.id)
    }

    @Test
    @Transactional
    fun `the unique index rejects a second mapping for the same article and recipient`() {
        val article = newArticle("t11-widget-dup")
        val recipient = newRecipient("T11-0002", "Nekar Supermarkt Dup")

        em.persist(ArticleRecipientRef.of(article, recipient, "243538", null))
        em.flush()

        val ex = assertThrows(PersistenceException::class.java) {
            em.persist(ArticleRecipientRef.of(article, recipient, "999999", null))
            em.flush()
        }
        assertTrue(
            ex.message?.contains("uq_article_recipient_ref_article_recipient") == true ||
                ex.message?.contains("duplicate key value violates unique constraint") == true,
            "Expected a unique-constraint violation, got: ${ex.message}",
        )
    }

    @Test
    @Transactional
    fun `the CHECK constraint rejects a row with neither value set, bypassing the domain layer`() {
        val article = newArticle("t11-widget-blank")
        val recipient = newRecipient("T11-0003", "Blank Recipient")

        val ex = assertThrows(PersistenceException::class.java) {
            em.createNativeQuery(
                "INSERT INTO article_recipient_ref (article_id, recipient_id, version) VALUES (${article.id}, ${recipient.id}, 0)"
            ).executeUpdate()
        }
        assertTrue(
            ex.message?.contains("chk_article_recipient_ref_not_both_blank") == true ||
                ex.message?.contains("violates check constraint") == true,
            "Expected a CHECK-constraint violation, got: ${ex.message}",
        )
    }

    @Test
    @Transactional
    fun `deleting the recipient cascades and removes the mapping`() {
        val article = newArticle("t11-widget-rcpt-del")
        val recipient = newRecipient("T11-0004", "To Be Deleted")
        val ref = ArticleRecipientRef.of(article, recipient, "243538", null)
        em.persist(ref)
        em.flush()
        val refId = ref.id!!
        val recipientId = recipient.id!!

        // Detach everything first: with `ref` still managed in this persistence context, Hibernate's
        // own pre-flush cascade check flags the about-to-be-removed recipient as an "unsaved
        // transient instance" referenced by `ref` — a Java-side consistency check that has no idea
        // about the DB's `ON DELETE CASCADE`. Clearing forces the DELETE straight to Postgres, which
        // is the actual mechanism this test proves.
        em.clear()
        em.remove(em.getReference(Recipient::class.java, recipientId))
        em.flush()
        em.clear()

        assertNull(em.find(ArticleRecipientRef::class.java, refId), "the mapping must be gone once its recipient is deleted")
    }

    @Test
    @Transactional
    fun `deleting the article cascades via JPA and removes the mapping (mirrors ArticleTaxVariant)`() {
        val article = newArticle("t11-widget-article-del")
        val recipient = newRecipient("T11-0005", "Survives Article Delete")
        val ref = ArticleRecipientRef.of(article, recipient, "243538", null)
        em.persist(ref)
        em.flush()
        val refId = ref.id!!
        val articleId = article.id!!
        val recipientId = recipient.id!!

        // Same reasoning as the recipient-delete test above: detach first, then reload the article
        // fresh so Hibernate genuinely cascades through its own (JPA-level) `recipientRefs`
        // collection instead of tripping over an already-managed, unrelated `ref` instance.
        em.clear()
        em.remove(em.find(Article::class.java, articleId))
        em.flush()
        em.clear()

        assertNull(em.find(ArticleRecipientRef::class.java, refId), "the mapping must be gone once its article is deleted")
        assertNotNull(em.find(Recipient::class.java, recipientId), "the recipient itself must survive an article delete")
    }
}
