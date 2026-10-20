package de.littletrotter.application.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.germanFallbackLanguage
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PaymentTermServiceTest {

    class InMemoryPaymentTermRepo : de.littletrotter.domain.repository.PaymentTermRepository {
        private val list = mutableListOf<PaymentTerm>()
        override fun findAll(pageIndex: Int, pageSize: Int, filterText: String?, sortField: String, sortDir: SortDirection) = list.toList()
        override fun count(filterText: String?) = list.size.toLong()
        override fun countInactive(filterText: String?) = list.count { !it.isActive }.toLong()
        override fun findDefault() = list.find { it.isDefault }
        override fun findDefaults() = list.filter { it.isDefault }
        override fun releaseDefaultsExcept(keepId: Long?) = list.filter { it.isDefault && it.id != keepId }.forEach { it.edit { isDefault = false } }
        override fun findById(id: Long) = list.find { it.id == id }
        override fun save(term: PaymentTerm): PaymentTerm {
            if (term.id == null) term.edit { this.id = (list.size + 1).toLong() }
            list.removeIf { it.id == term.id }
            list.add(term)
            return term
        }
        override fun delete(id: Long) { list.removeIf { it.id == id } }
        override fun isInUse(id: Long) = false
        override fun usageCounts(): Map<Long, Long> = emptyMap()
    }

    @Test
    fun `savePaymentTerm unsets other defaults`() {
        val repo = InMemoryPaymentTermRepo()
        val svc = PaymentTermService(repo)

        val a = aPaymentTerm { id = 1; changeName(germanFallbackLanguage(), "A"); isDefault = true }
        val b = aPaymentTerm { id = 2; changeName(germanFallbackLanguage(), "B"); isDefault = false }
        repo.save(a); repo.save(b)

        val newDefault = aPaymentTerm { id = 2; changeName(germanFallbackLanguage(), "B"); isDefault = true }
        svc.savePaymentTerm(newDefault)

        assertFalse(repo.findById(1)!!.isDefault)
        assertTrue(repo.findById(2)!!.isDefault)
    }

    // --- deletePaymentTerm — the in-use guard (moved from ReferenceDataServiceCacheAndGuardsTest) ---

    @Test
    fun `deletePaymentTerm lehnt eine noch verwendete Zahlungsbedingung ab`() {
        val repo = mock<PaymentTermRepository>()
        whenever(repo.isInUse(5L)).thenReturn(true)
        val service = PaymentTermService(repo)

        val ex = assertThrows(InvalidParameterException::class.java) { service.deletePaymentTerm(5L) }

        assertTrue(ex.message?.contains("verwendet") == true, ex.message)
        verify(repo, never()).delete(any())
    }

    @Test
    fun `deletePaymentTerm loescht eine unbenutzte Zahlungsbedingung`() {
        val repo = mock<PaymentTermRepository>()
        whenever(repo.isInUse(6L)).thenReturn(false)
        val service = PaymentTermService(repo)

        service.deletePaymentTerm(6L)

        verify(repo).delete(6L)
    }

    // --- moved from ReferenceDataServiceRatesTest, --

    private fun paymentTerm(id: Long?, isDefault: Boolean) = aPaymentTerm {
        this.id = id
        changeName(germanFallbackLanguage(), "Netto $id")
        this.isDefault = isDefault
    }

    @Test
    fun `boundary - unknown ids and empty pages resolve to null, never to a fallback term`() {
        val repo = mock<PaymentTermRepository>()
        val svc = PaymentTermService(repo)

        assertNull(svc.findPaymentTermById(404L))
        assertNull(svc.findDefaultPaymentTerm())
    }

    @Test
    fun `boundary - a new default payment term releases every other default but never itself`() {
        val repo = mock<PaymentTermRepository>()
        val svc = PaymentTermService(repo)
        val incoming = paymentTerm(2L, isDefault = true)
        whenever(repo.save(any())).thenAnswer { it.arguments[0] }

        assertSame(incoming, svc.savePaymentTerm(incoming))

        val order = inOrder(repo)
        order.verify(repo).releaseDefaultsExcept(2L)
        order.verify(repo).save(incoming)
    }

    @Test
    fun `boundary - a non-default payment term never touches the existing default`() {
        val repo = mock<PaymentTermRepository>()
        val svc = PaymentTermService(repo)
        val term = paymentTerm(null, isDefault = false)
        whenever(repo.save(term)).thenReturn(term)

        svc.savePaymentTerm(term)

        verify(repo, never()).releaseDefaultsExcept(any())
    }

    @Test
    fun `boundary - an invalid payment term is rejected before any repository write`() {
        val repo = mock<PaymentTermRepository>()
        val svc = PaymentTermService(repo)
        val nameless = aPaymentTerm { isDefault = true }

        assertThrows(InvalidParameterException::class.java) { svc.savePaymentTerm(nameless) }

        verify(repo, never()).releaseDefaultsExcept(any())
        verify(repo, never()).save(any())
    }

    @Test
    fun `findAllPaymentTerms without arguments reads the first page of 60 sorted by name`() {
        val repo = mock<PaymentTermRepository>()
        val term = aPaymentTerm { id = 5 }
        whenever(repo.findAll(0, 60, null, "name", SortDirection.ASC)).thenReturn(listOf(term))

        assertEquals(listOf(term), PaymentTermService(repo).findAllPaymentTerms())
    }

    @Test
    fun `explicit paging arguments are forwarded to the repository`() {
        val repo = mock<PaymentTermRepository>()

        PaymentTermService(repo).findAllPaymentTerms(2, 10, "netto", "days", SortDirection.DESC)

        verify(repo).findAll(2, 10, "netto", "days", SortDirection.DESC)
    }

    @Test
    fun `count methods delegate the filter and default to no filter`() {
        val repo = mock<PaymentTermRepository>()
        whenever(repo.count(null)).thenReturn(7L)
        whenever(repo.countInactive("x")).thenReturn(2L)
        val svc = PaymentTermService(repo)

        assertEquals(7L, svc.countPaymentTerms())
        assertEquals(2L, svc.countInactivePaymentTerms("x"))
        svc.countInactivePaymentTerms()
        verify(repo).countInactive(null)
    }

    @Test
    fun `usage counts come straight from the repository`() {
        val repo = mock<PaymentTermRepository>()
        whenever(repo.usageCounts()).thenReturn(mapOf(1L to 3L))

        assertEquals(mapOf(1L to 3L), PaymentTermService(repo).findPaymentTermUsageCounts())
    }
}
