package de.littletrotter.domain.repository

import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.fixture.aRecipient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RecipientRepository.findByCustomerNumberIgnoreCase], [RecipientRepository
 * .findAllCustomerNumbers], [RecipientRepository.saveAndFlush] and [RecipientRepository
 * .findNamesByIds] carry default bodies (compatibility fallbacks for in-memory test doubles, per
 * each method's own KDoc), same idiom as [ArticleRepositoryDefaultsTest]/[SenderProfileRepositoryDefaultsTest].
 * [RecipientRepository.findAll]/[RecipientRepository.count] additionally declare default
 * parameter values without a body, which compile to a synthetic `$default` dispatcher — covered
 * the same way as [B2bProfileRepositoryDefaultsTest].
 */
class RecipientRepositoryDefaultsTest {

    private fun recipient(id: Long, customerNumber: String, name: String = "Kunde $id") = aRecipient {
        this.id = id
        this.customerNumber = CustomerNumber.ofNullable(customerNumber)
        this.name = name
    }

    /** Minimal [RecipientRepository] implementing only the non-default members — every default
     *  body under test here runs as the interface's own implementation, same shape as
     *  [ArticleRepositoryDefaultsTest.repoWith]. */
    private fun repoWith(vararg recipients: Recipient): RecipientRepository = object : RecipientRepository {
        private val data = recipients.toList()
        override fun findById(id: Long) = data.find { it.id == id }
        override fun findByCustomerNumber(number: String) = data.find { it.customerNumber?.value == number }
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection) = data
        override fun count(filterText: String?) = data.size.toLong()
        override fun save(recipient: Recipient): Recipient = recipient
        override fun forceVersionIncrement(recipient: Recipient) {}
        override fun delete(id: Long) {}
        override fun findNamesByB2bProfileId(b2bProfileId: Long): List<String> = emptyList()
        override fun countGroupedByB2bProfile(): Map<Long, Long> = emptyMap()
    }

    @Test
    fun `happy path findByCustomerNumberIgnoreCase delegates to the case-sensitive lookup`() {
        val repo = repoWith(recipient(1, "K-1"))

        assertEquals(recipient(1, "K-1").customerNumber, repo.findByCustomerNumberIgnoreCase("K-1")?.customerNumber)
    }

    @Test
    fun `boundary findByCustomerNumberIgnoreCase is null when no exact-case match exists`() {
        val repo = repoWith(recipient(1, "K-1"))

        assertEquals(null, repo.findByCustomerNumberIgnoreCase("k-1"))
    }

    @Test
    fun `boundary findAllCustomerNumbers filters out blank customer numbers`() {
        val repo = repoWith(recipient(1, "K-1"), recipient(2, ""), recipient(3, "K-3"))

        assertEquals(listOf("K-1", "K-3"), repo.findAllCustomerNumbers())
    }

    @Test
    fun `boundary findAllCustomerNumbers is empty without recipients`() {
        val repo = repoWith()

        assertTrue(repo.findAllCustomerNumbers().isEmpty())
    }

    @Test
    fun `boundary saveAndFlush delegates to save`() {
        val repo = repoWith()
        val toSave = recipient(9, "K-9")

        assertEquals(toSave, repo.saveAndFlush(toSave))
    }

    @Test
    fun `boundary findNamesByIds folds per-id lookups, skipping ids with no match`() {
        val repo = repoWith(recipient(1, "K-1", "Anna"), recipient(2, "K-2", "Bert"))

        assertEquals(mapOf(1L to "Anna", 2L to "Bert"), repo.findNamesByIds(listOf(1L, 2L, 99L)))
    }

    @Test
    fun `boundary count is exercised directly through the interface`() {
        val repo = repoWith(recipient(1, "K-1"))

        assertEquals(1L, repo.count())
    }

    @Test
    fun `boundary findAll with every argument omitted uses the documented defaults`() {
        var captured: List<Any?>? = null
        val repo = object : RecipientRepository {
            override fun findById(id: Long) = null
            override fun findByCustomerNumber(number: String) = null
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection): List<Recipient> {
                captured = listOf(pageIndex, pageSize, filterText, sortField, sortDir)
                return emptyList()
            }
            override fun count(filterText: String?) = 0L
            override fun save(recipient: Recipient) = recipient
            override fun forceVersionIncrement(recipient: Recipient) {}
            override fun delete(id: Long) {}
            override fun findNamesByB2bProfileId(b2bProfileId: Long) = emptyList<String>()
            override fun countGroupedByB2bProfile() = emptyMap<Long, Long>()
        }

        repo.findAll()

        assertEquals(listOf(0, 60, null, "name", SortDirection.ASC), captured)
    }
}
