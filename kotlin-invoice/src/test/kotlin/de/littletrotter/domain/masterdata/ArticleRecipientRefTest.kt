package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.CustomerNumber

/** Per-recipient article identifier mappings (buyer's article number/name). */
class ArticleRecipientRefTest {

    private fun article(): Article = Article().apply { name = "Beratung"; id = 1L }

    private fun recipient(id: Long = 1L): Recipient = Recipient().apply {
        this.id = id
        customerNumber = CustomerNumber("KD-000$id")
        name = "Kunde $id"
    }

    @Test
    fun `missing or unsaved recipient is rejected before insertion`() {
        assertThrows(InvalidParameterException::class.java) {
            ArticleRecipientRef().apply { buyerArticleNumber = "DEMO2-1" }.validate()
        }
        assertThrows(InvalidParameterException::class.java) {
            ArticleRecipientRef.of(article(), Recipient(), "DEMO2-1", null)
        }
    }

    @Test
    fun `a row with only a buyer article number is valid`() {
        val a = article()
        assertDoesNotThrow { ArticleRecipientRef.of(a, recipient(), "243538", null) }
    }

    @Test
    fun `a row with only a buyer article name is valid`() {
        val a = article()
        assertDoesNotThrow { ArticleRecipientRef.of(a, recipient(), null, "Nekar-Bezeichnung") }
    }

    @Test
    fun `a row with both values set is valid`() {
        val a = article()
        assertDoesNotThrow { ArticleRecipientRef.of(a, recipient(), "243538", "Nekar-Bezeichnung") }
    }

    @Test
    fun `a row with both values blank is rejected`() {
        val a = article()
        assertThrows(InvalidParameterException::class.java) { ArticleRecipientRef.of(a, recipient(), null, null) }
    }

    @Test
    fun `a row with both values whitespace-only is rejected`() {
        val a = article()
        assertThrows(InvalidParameterException::class.java) { ArticleRecipientRef.of(a, recipient(), "   ", "  ") }
    }

    @Test
    fun `values are trimmed and blank-to-null normalized`() {
        val a = article()
        val ref = ArticleRecipientRef.of(a, recipient(), "  243538  ", "   ")
        assertEquals("243538", ref.buyerArticleNumber)
        assertNull(ref.buyerArticleName)
    }

    @Test
    fun `duplicate recipients in a candidate list are rejected`() {
        val a = article()
        val r = recipient()
        val ref1 = ArticleRecipientRef.of(a, r, "243538", null)
        val ref2 = ArticleRecipientRef.of(a, r, "999999", null)
        assertThrows(InvalidParameterException::class.java) { ArticleRecipientRef.assertUniqueRecipients(listOf(ref1, ref2)) }
    }

    @Test
    fun `different recipients in a candidate list are accepted`() {
        val a = article()
        val ref1 = ArticleRecipientRef.of(a, recipient(1L), "243538", null)
        val ref2 = ArticleRecipientRef.of(a, recipient(2L), "999999", null)
        assertDoesNotThrow { ArticleRecipientRef.assertUniqueRecipients(listOf(ref1, ref2)) }
    }

    // ── Article.recipientRefFor / replaceRecipientRefs ──────────────────────

    @Test
    fun `recipientRefFor returns null when no mapping is maintained for the recipient`() {
        val a = article()
        assertNull(a.recipientRefFor(1L))
    }

    @Test
    fun `recipientRefFor returns the matching mapping after replaceRecipientRefs`() {
        val a = article()
        val ref = ArticleRecipientRef.of(a, recipient(1L), "243538", "Nekar-Bezeichnung")
        a.replaceRecipientRefs(listOf(ref))

        val found = a.recipientRefFor(1L)
        assertEquals("243538", found?.buyerArticleNumber)
        assertEquals("Nekar-Bezeichnung", found?.buyerArticleName)
        assertNull(a.recipientRefFor(2L))
    }

    @Test
    fun `replaceRecipientRefs sets the owning article on every row`() {
        val a = article()
        val ref = ArticleRecipientRef.of(a, recipient(1L), "243538", null)
        a.replaceRecipientRefs(listOf(ref))
        assertTrue(a.recipientRefs.single().article === a)
    }

    @Test
    fun `adding and editing recipient references preserves persisted identities`() {
        val a = article()
        a.replaceRecipientRefs(listOf(ArticleRecipientRef.of(a, recipient(1L), "DEMO2-1", "Demo 2 item")))
        val first = a.recipientRefs.single().apply { id = 42L; version = 3L }
        val next = listOf(
            ArticleRecipientRef.of(a, recipient(1L), "DEMO2-UPDATED", "Demo 2 changed item"),
            ArticleRecipientRef.of(a, recipient(2L), "DEMO2-2", null),
        )

        a.replaceRecipientRefs(next)
        a.replaceRecipientRefs(next)

        assertEquals(2, a.recipientRefs.size)
        assertTrue(a.recipientRefFor(1L) === first)
        assertEquals(42L, first.id)
        assertEquals(3L, first.version)
        assertEquals("DEMO2-UPDATED", first.buyerArticleNumber)
        assertEquals("Demo 2 changed item", first.buyerArticleName)
        a.replaceRecipientRefs(a.recipientRefs)
        assertEquals(2, a.recipientRefs.size)
        a.replaceRecipientRefs(listOf(next.last()))
        assertNull(a.recipientRefFor(1L))
        a.replaceRecipientRefs(emptyList())
        assertTrue(a.recipientRefs.isEmpty())
    }

    @Test
    fun `invalid later recipient reference leaves managed data untouched`() {
        val a = article()
        a.replaceRecipientRefs(listOf(ArticleRecipientRef.of(a, recipient(), "DEMO2-1", "Demo 2 item")))
        val first = a.recipientRefs.single()
        val invalid = ArticleRecipientRef().apply { recipient = recipient(2L) }

        assertThrows(InvalidParameterException::class.java) {
            a.replaceRecipientRefs(listOf(ArticleRecipientRef.of(a, recipient(), "CHANGED", null), invalid))
        }

        assertEquals(listOf(first), a.recipientRefs)
        assertEquals("DEMO2-1", first.buyerArticleNumber)
        assertEquals("Demo 2 item", first.buyerArticleName)
        assertNull(invalid.article)
    }

    @Test
    fun `foreign recipient mapping identity and owner cannot transfer to another aggregate`() {
        val source = article()
        val target = article().apply { id = 2L }
        source.replaceRecipientRefs(listOf(ArticleRecipientRef.of(source, recipient(), "DEMO2-1", "Demo 2 item")))
        val foreign = source.recipientRefs.single().apply { id = 42L; version = 3L }

        target.replaceRecipientRefs(listOf(foreign))

        val added = target.recipientRefs.single()
        assertNull(added.id)
        assertEquals(0L, added.version)
        assertTrue(added !== foreign)
        assertTrue(added.article === target)
        assertTrue(foreign.article === source)
        assertTrue(source.recipientRefs.single() === foreign)
        assertEquals(42L, foreign.id)
        assertEquals(3L, foreign.version)
        assertEquals(foreign.recipient, added.recipient)
        assertEquals(foreign.buyerArticleNumber, added.buyerArticleNumber)
        assertEquals(foreign.buyerArticleName, added.buyerArticleName)
    }

    @Test
    fun `recipient references cannot bypass aggregate replacement`() {
        assertThrows(UnsupportedOperationException::class.java) {
            (article().recipientRefs as MutableList<ArticleRecipientRef>).clear()
        }
    }

    @Test
    fun `replacing with a duplicate recipient throws and leaves the previous list untouched`() {
        val a = article()
        val r1 = recipient(1L)
        a.replaceRecipientRefs(listOf(ArticleRecipientRef.of(a, r1, "243538", null)))
        val existing = a.recipientRefs.single()

        val dup1 = ArticleRecipientRef.of(a, r1, "111111", null)
        val dup2 = ArticleRecipientRef.of(a, r1, "222222", null)
        assertThrows(InvalidParameterException::class.java) { a.replaceRecipientRefs(listOf(dup1, dup2)) }
        assertEquals(1, a.recipientRefs.size)
        assertTrue(a.recipientRefs.single() === existing)
    }
}
