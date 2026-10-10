package de.littletrotter.application.reference

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [PaymentTermQueries.findAllPaymentTerms]'s configured-lookup-page-size
 * defaulting and its delegation to [PaymentTermService], mapping every result to [PaymentTermDto].
 */
class PaymentTermQueriesTest {

    @Test
    fun `findAllPaymentTerms uses configured lookup page size`() {
        var capturedPageSize = -1
        val service = mock<PaymentTermService> {
            on {
                findAllPaymentTerms(any(), any(), anyOrNull(), any(), any())
            } doAnswer { invocation ->
                capturedPageSize = invocation.getArgument(1)
                emptyList<PaymentTerm>()
            }
        }
        val queries = PaymentTermQueries(service, configuredReferenceLookupPageSize = 250)

        queries.findAllPaymentTerms()

        assertEquals(250, capturedPageSize)
    }

    @Test
    fun `findAllPaymentTerms falls back to 1000 when configured lookup page size is invalid`() {
        var capturedPageSize = -1
        val service = mock<PaymentTermService> {
            on {
                findAllPaymentTerms(any(), any(), anyOrNull(), any(), any())
            } doAnswer { invocation ->
                capturedPageSize = invocation.getArgument(1)
                emptyList<PaymentTerm>()
            }
        }
        val queries = PaymentTermQueries(service, configuredReferenceLookupPageSize = 0)

        queries.findAllPaymentTerms()

        assertEquals(1000, capturedPageSize)
    }

    @Test
    fun `findPaymentTerms delegates the given page index and size unchanged, mapped to PaymentTermDto`() {
        val service = mock<PaymentTermService>()
        whenever(service.findAllPaymentTerms(2, 30, "term", "name", SortDirection.DESC)).thenReturn(listOf(PaymentTerm()))
        val queries = PaymentTermQueries(service)

        val result = queries.findPaymentTerms(2, 30, "term", "name", SortDirection.DESC)

        assertEquals(listOf(PaymentTermDto(version = 0L)), result)
    }

    @Test
    fun `findDefaultPaymentTerm delegates to the service, mapped to PaymentTermDto`() {
        val service = mock<PaymentTermService>()
        val term = PaymentTerm().apply { id = 7L }
        whenever(service.findDefaultPaymentTerm()).thenReturn(term)
        val queries = PaymentTermQueries(service)

        assertEquals(term.toDto(), queries.findDefaultPaymentTerm())
    }

    @Test
    fun `findPaymentTermById delegates to the service, mapped to PaymentTermDto`() {
        val service = mock<PaymentTermService>()
        val term = PaymentTerm().apply { id = 7L }
        whenever(service.findPaymentTermById(7L)).thenReturn(term)
        val queries = PaymentTermQueries(service)

        assertEquals(term.toDto(), queries.findPaymentTermById(7L))
    }

    @Test
    fun `countPaymentTerms delegates to the service`() {
        val service = mock<PaymentTermService>()
        whenever(service.countPaymentTerms("x")).thenReturn(3L)
        val queries = PaymentTermQueries(service)

        assertEquals(3L, queries.countPaymentTerms("x"))
    }

    @Test
    fun `countInactivePaymentTerms delegates to the service`() {
        val service = mock<PaymentTermService>()
        whenever(service.countInactivePaymentTerms("x")).thenReturn(2L)
        val queries = PaymentTermQueries(service)

        assertEquals(2L, queries.countInactivePaymentTerms("x"))
    }

    @Test
    fun `findPaymentTerms, countPaymentTerms and countInactivePaymentTerms accept their default arguments through the interface type`() {
        val service = mock<PaymentTermService>()
        whenever(service.findAllPaymentTerms(0, 60, null, "name", SortDirection.ASC)).thenReturn(emptyList())
        whenever(service.countPaymentTerms(null)).thenReturn(0L)
        whenever(service.countInactivePaymentTerms(null)).thenReturn(0L)
        val queries: PaymentTermQueryPort = PaymentTermQueries(service)

        assertEquals(emptyList<PaymentTermDto>(), queries.findPaymentTerms())
        assertEquals(0L, queries.countPaymentTerms())
        assertEquals(0L, queries.countInactivePaymentTerms())
    }

    @Test
    fun `findPaymentTermUsageCounts delegates to the service`() {
        val service = mock<PaymentTermService>()
        whenever(service.findPaymentTermUsageCounts()).thenReturn(mapOf(1L to 5L))
        val queries = PaymentTermQueries(service)

        assertEquals(mapOf(1L to 5L), queries.findPaymentTermUsageCounts())
    }
}
