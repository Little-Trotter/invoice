package de.littletrotter.domain.repository

import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * COV — [SenderProfileRepository.findPersistedAdditionalVatCountries]/[SenderProfileRepository
 * .findAllSellerNumbers] carry default bodies (compatibility fallbacks for in-memory test doubles
 * without a real DB constraint, see their own KDoc on the interface) that
 * [de.littletrotter.infrastructure.persistence.PanacheSenderProfileRepository] always overrides
 * in production. Same idiom as [ArticleRepositoryDefaultsTest] — new code gets its own direct test
 * instead of relying on incidental exercise through some other service's fake.
 */
class SenderProfileRepositoryDefaultsTest {

    private fun sender(id: Long, sellerNumber: String?, vatCountries: List<Country> = emptyList()) = aSenderProfile {
        this.id = id
        name = "Sender $id"
        this.sellerNumber = sellerNumber
        apply { updateVatRegistrations(vatCountries.map { SellerVatRegistrationCommand(it, if (it == Country.AT) VatId("ATU12345678") else VatId("LU26375245")) }, today = FIXED_TODAY) }
    }

    /** Minimal [SenderProfileRepository] implementing only [findById]/[findAll] — every other
     *  default-bodied method under test here runs the interface's own implementation. */
    private fun repoWith(vararg senders: SenderProfile): SenderProfileRepository = object : SenderProfileRepository {
        private val data = senders.toList()
        override fun findById(id: Long) = data.find { it.id == id }
        override fun findByIdForEdit(id: Long) = findById(id)
        override fun findDefault(): SenderProfile? = null
        override fun findDefaults(): List<SenderProfile> = emptyList()
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?) = data
        override fun count(filterText: String?) = data.size.toLong()
        override fun save(profile: SenderProfile) = profile
        override fun forceVersionIncrement(profile: SenderProfile) {}
        override fun delete(id: Long) {}
    }

    @Test
    fun `findPersistedAdditionalVatCountries liefert die Laender der persistierten Registrierungen`() {
        val repo = repoWith(sender(1L, "V-1", listOf(Country.AT, Country.LU)))

        assertEquals(setOf(Country.AT, Country.LU), repo.findPersistedAdditionalVatCountries(1L))
    }

    @Test
    fun `findPersistedAdditionalVatCountries ist leer ohne zusaetzliche Registrierungen`() {
        val repo = repoWith(sender(1L, "V-1"))

        assertTrue(repo.findPersistedAdditionalVatCountries(1L).isEmpty())
    }

    @Test
    fun `findPersistedAdditionalVatCountries ist leer wenn der Anbieter nicht existiert`() {
        val repo = repoWith()

        assertTrue(repo.findPersistedAdditionalVatCountries(99L).isEmpty())
    }

    @Test
    fun `findAllSellerNumbers liefert nur nicht-leere Anbieternummern`() {
        val repo = repoWith(sender(1L, "V-1"), sender(2L, null), sender(3L, ""), sender(4L, "V-4"))

        assertEquals(listOf("V-1", "V-4"), repo.findAllSellerNumbers())
    }

    @Test
    fun `findAllSellerNumbers ist leer ohne Anbieter`() {
        val repo = repoWith()

        assertTrue(repo.findAllSellerNumbers().isEmpty())
    }

    @Test
    fun `happy path findAllSorted delegates to findAll, ignoring the sort arguments`() {
        val repo = repoWith(sender(1L, "V-1"))

        val result = repo.findAllSorted(0, 60, null, de.littletrotter.domain.shared.GridSort("name", de.littletrotter.domain.shared.SortDirection.DESC))

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `boundary findAllSorted forwards pageIndex, pageSize and filterText unchanged to findAll`() {
        var captured: List<Any?>? = null
        val repo = object : SenderProfileRepository {
            override fun findById(id: Long): SenderProfile? = null
            override fun findByIdForEdit(id: Long): SenderProfile? = null
            override fun findDefault(): SenderProfile? = null
            override fun findDefaults(): List<SenderProfile> = emptyList()
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfile> {
                captured = listOf(pageIndex, pageSize, filterText)
                return emptyList()
            }
            override fun count(filterText: String?) = 0L
            override fun save(profile: SenderProfile) = profile
            override fun forceVersionIncrement(profile: SenderProfile) {}
            override fun delete(id: Long) {}
        }

        repo.findAllSorted(2, 15, "Muster", de.littletrotter.domain.shared.GridSort("name", de.littletrotter.domain.shared.SortDirection.ASC))

        assertEquals(listOf(2, 15, "Muster"), captured)
    }

    @Test
    fun `boundary saveAndFlush delegates to save`() {
        val repo = repoWith()
        val toSave = sender(9L, "V-9")

        assertEquals(toSave, repo.saveAndFlush(toSave))
    }

    @Test
    fun `boundary count is exercised directly through the interface`() {
        val repo = repoWith(sender(1L, "V-1"))

        assertEquals(1L, repo.count())
    }

    @Test
    fun `boundary findAll with every argument omitted uses the documented defaults`() {
        var captured: List<Any?>? = null
        val repo = object : SenderProfileRepository {
            override fun findById(id: Long): SenderProfile? = null
            override fun findByIdForEdit(id: Long): SenderProfile? = null
            override fun findDefault(): SenderProfile? = null
            override fun findDefaults(): List<SenderProfile> = emptyList()
            override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfile> {
                captured = listOf(pageIndex, pageSize, filterText)
                return emptyList()
            }
            override fun count(filterText: String?) = 0L
            override fun save(profile: SenderProfile) = profile
            override fun forceVersionIncrement(profile: SenderProfile) {}
            override fun delete(id: Long) {}
        }

        repo.findAll()

        assertEquals(listOf(0, 60, null), captured)
    }
}
