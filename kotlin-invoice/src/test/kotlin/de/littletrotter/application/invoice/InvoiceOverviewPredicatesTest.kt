package de.littletrotter.application.invoice

import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.FILTER_PARAM
import de.littletrotter.domain.shared.ValidationStatus
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Join
import jakarta.persistence.criteria.ParameterExpression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.time.LocalDate

class InvoiceOverviewPredicatesTest {

    @Test
    fun `happy path filter text and status produce a lowercased LIKE-or predicate plus a status equal`() {
        val cb = mock<CriteriaBuilder>()
        val i = mock<Path<Invoice>>()
        val invoiceNumber = mock<Path<String>>()
        val recipient = mock<Path<String>>()
        val seller = mock<Path<String>>()
        val senderProfile = mock<Path<String>>()
        val partyNames = InvoiceOverviewPartyNames(recipient, seller, senderProfile)

        val param = mock<ParameterExpression<String>>()
        whenever(cb.parameter(String::class.java, FILTER_PARAM)).thenReturn(param)

        whenever(i.get<String>("invoiceNumber")).thenReturn(invoiceNumber)

        val lowerNumber = mock<Expression<String>>()
        val lowerRecipient = mock<Expression<String>>()
        val lowerSeller = mock<Expression<String>>()
        val lowerSenderProfile = mock<Expression<String>>()
        whenever(cb.lower(invoiceNumber)).thenReturn(lowerNumber)
        whenever(cb.lower(recipient)).thenReturn(lowerRecipient)
        whenever(cb.lower(seller)).thenReturn(lowerSeller)
        whenever(cb.lower(senderProfile)).thenReturn(lowerSenderProfile)

        val likeNumber = mock<Predicate>()
        val likeRecipient = mock<Predicate>()
        val likeSeller = mock<Predicate>()
        val likeSenderProfile = mock<Predicate>()
        whenever(cb.like(lowerNumber, param, '\\')).thenReturn(likeNumber)
        whenever(cb.like(lowerRecipient, param, '\\')).thenReturn(likeRecipient)
        whenever(cb.like(lowerSeller, param, '\\')).thenReturn(likeSeller)
        whenever(cb.like(lowerSenderProfile, param, '\\')).thenReturn(likeSenderProfile)

        val orPredicate = mock<Predicate>()
        whenever(cb.or(likeNumber, likeRecipient, likeSeller, likeSenderProfile)).thenReturn(orPredicate)

        val statusPath = mock<Path<InvoiceStatus>>()
        whenever(i.get<InvoiceStatus>("status")).thenReturn(statusPath)
        val equalPredicate = mock<Predicate>()
        whenever(cb.equal(statusPath, InvoiceStatus.ISSUED)).thenReturn(equalPredicate)

        val (predicates, returnedParam) = invoiceOverviewTextAndStatusPredicates(
            cb,
            i,
            partyNames,
            InvoiceOverviewTextStatusFilter("abc", InvoiceStatus.ISSUED),
        )

        assertEquals(listOf(orPredicate, equalPredicate), predicates)
        assertSame(param, returnedParam)
        verify(cb, times(1)).parameter(String::class.java, FILTER_PARAM)
        verify(cb).like(lowerNumber, param, '\\')
        verify(cb).like(lowerRecipient, param, '\\')
        verify(cb).like(lowerSeller, param, '\\')
        verify(cb).like(lowerSenderProfile, param, '\\')
        verify(cb).equal(statusPath, InvoiceStatus.ISSUED)
    }

    @Test
    fun `boundary blank or null filter text yields no LIKE predicate and a null parameter`() {
        listOf("", "   ", null).forEach { filterText ->
            val cb = mock<CriteriaBuilder>()
            val i = mock<Path<Invoice>>()
            val partyNames = InvoiceOverviewPartyNames(mock(), mock(), mock())

            val (predicates, returnedParam) = invoiceOverviewTextAndStatusPredicates(
                cb,
                i,
                partyNames,
                InvoiceOverviewTextStatusFilter(filterText, null),
            )

            assertTrue(predicates.isEmpty(), "filterText=$filterText")
            assertNull(returnedParam, "filterText=$filterText")
            verifyNoInteractions(cb)
        }
    }

    @Test
    fun `boundary status only yields exactly one predicate`() {
        val cb = mock<CriteriaBuilder>()
        val i = mock<Path<Invoice>>()
        val partyNames = InvoiceOverviewPartyNames(mock(), mock(), mock())

        val statusPath = mock<Path<InvoiceStatus>>()
        whenever(i.get<InvoiceStatus>("status")).thenReturn(statusPath)
        val equalPredicate = mock<Predicate>()
        whenever(cb.equal(statusPath, InvoiceStatus.DRAFT)).thenReturn(equalPredicate)

        val (predicates, returnedParam) = invoiceOverviewTextAndStatusPredicates(
            cb,
            i,
            partyNames,
            InvoiceOverviewTextStatusFilter(null, InvoiceStatus.DRAFT),
        )

        assertEquals(listOf(equalPredicate), predicates)
        assertNull(returnedParam)
        verify(cb).equal(statusPath, InvoiceStatus.DRAFT)
        verifyNoMoreInteractions(cb)
    }

    @Test
    fun `boundary all-null dimensions yield an empty predicate list`() {
        val cb = mock<CriteriaBuilder>()
        val i = mock<Path<Invoice>>()
        val paths = InvoiceOverviewDimensionPaths(mock(), mock(), mock())

        val result = invoiceOverviewDimensionPredicates(cb, i, paths, InvoiceOverviewDimensions())

        assertTrue(result.isEmpty())
        verifyNoInteractions(cb)
    }

    @Test
    fun `boundary each dimension set alone yields exactly its own predicate`() {
        run {
            val cb = mock<CriteriaBuilder>()
            val i = mock<Path<Invoice>>()
            val paths = InvoiceOverviewDimensionPaths(mock(), mock(), mock())
            val reverseChargePath = mock<Path<Boolean>>()
            whenever(i.get<Boolean>("reverseCharge")).thenReturn(reverseChargePath)
            val predicate = mock<Predicate>()
            whenever(cb.equal(reverseChargePath, true)).thenReturn(predicate)

            val result = invoiceOverviewDimensionPredicates(
                cb,
                i,
                paths,
                InvoiceOverviewDimensions(reverseCharge = true),
            )

            assertEquals(listOf(predicate), result)
            verify(cb).equal(reverseChargePath, true)
        }
        run {
            val cb = mock<CriteriaBuilder>()
            val i = mock<Path<Invoice>>()
            val paths = InvoiceOverviewDimensionPaths(mock(), mock(), mock())
            val validationStatusPath = mock<Path<ValidationStatus>>()
            whenever(i.get<ValidationStatus>("validationStatus")).thenReturn(validationStatusPath)
            val predicate = mock<Predicate>()
            whenever(cb.equal(validationStatusPath, ValidationStatus.VALID)).thenReturn(predicate)

            val result = invoiceOverviewDimensionPredicates(
                cb,
                i,
                paths,
                InvoiceOverviewDimensions(validationStatus = ValidationStatus.VALID),
            )

            assertEquals(listOf(predicate), result)
            verify(cb).equal(validationStatusPath, ValidationStatus.VALID)
        }
        run {
            val cb = mock<CriteriaBuilder>()
            val i = mock<Path<Invoice>>()
            val recipientIdPath = mock<Expression<Long>>()
            val paths = InvoiceOverviewDimensionPaths(recipientIdPath, mock(), mock())
            val predicate = mock<Predicate>()
            whenever(cb.equal(recipientIdPath, 7L)).thenReturn(predicate)

            val result = invoiceOverviewDimensionPredicates(cb, i, paths, InvoiceOverviewDimensions(recipientId = 7L))

            assertEquals(listOf(predicate), result)
            verify(cb).equal(recipientIdPath, 7L)
        }
        run {
            val cb = mock<CriteriaBuilder>()
            val i = mock<Path<Invoice>>()
            val selectedSellerIdPath = mock<Expression<Long>>()
            val senderProfileIdPath = mock<Expression<Long>>()
            val paths = InvoiceOverviewDimensionPaths(mock(), selectedSellerIdPath, senderProfileIdPath)
            val coalesced = mock<Expression<Long>>()
            whenever(cb.coalesce(selectedSellerIdPath, senderProfileIdPath)).thenReturn(coalesced)
            val predicate = mock<Predicate>()
            whenever(cb.equal(coalesced, 9L)).thenReturn(predicate)

            val result = invoiceOverviewDimensionPredicates(
                cb,
                i,
                paths,
                InvoiceOverviewDimensions(senderProfileId = 9L),
            )

            assertEquals(listOf(predicate), result)
            verify(cb).coalesce(selectedSellerIdPath, senderProfileIdPath)
            verify(cb).equal(coalesced, 9L)
        }
        run {
            val cb = mock<CriteriaBuilder>()
            val i = mock<Path<Invoice>>()
            val paths = InvoiceOverviewDimensionPaths(mock(), mock(), mock())
            val invoiceDatePath = mock<Path<LocalDate>>()
            whenever(i.get<LocalDate>("invoiceDate")).thenReturn(invoiceDatePath)
            val predicate = mock<Predicate>()
            val from = LocalDate.of(2026, 1, 1)
            whenever(cb.greaterThanOrEqualTo(invoiceDatePath, from)).thenReturn(predicate)

            val result = invoiceOverviewDimensionPredicates(cb, i, paths, InvoiceOverviewDimensions(dateFrom = from))

            assertEquals(listOf(predicate), result)
            verify(cb).greaterThanOrEqualTo(invoiceDatePath, from)
        }
        run {
            val cb = mock<CriteriaBuilder>()
            val i = mock<Path<Invoice>>()
            val paths = InvoiceOverviewDimensionPaths(mock(), mock(), mock())
            val invoiceDatePath = mock<Path<LocalDate>>()
            whenever(i.get<LocalDate>("invoiceDate")).thenReturn(invoiceDatePath)
            val predicate = mock<Predicate>()
            val to = LocalDate.of(2026, 12, 31)
            whenever(cb.lessThanOrEqualTo(invoiceDatePath, to)).thenReturn(predicate)

            val result = invoiceOverviewDimensionPredicates(cb, i, paths, InvoiceOverviewDimensions(dateTo = to))

            assertEquals(listOf(predicate), result)
            verify(cb).lessThanOrEqualTo(invoiceDatePath, to)
        }
    }

    @Test
    fun `boundary InvoiceOverviewDimensionPaths of reads the id path of every join`() {
        val recipientJoin = mock<Join<Invoice, *>>()
        val selectedSellerJoin = mock<Join<Invoice, *>>()
        val senderProfileJoin = mock<Join<Invoice, *>>()
        val recipientId = mock<Path<Long>>()
        val selectedSellerId = mock<Path<Long>>()
        val senderProfileId = mock<Path<Long>>()
        whenever(recipientJoin.get<Long>("id")).thenReturn(recipientId)
        whenever(selectedSellerJoin.get<Long>("id")).thenReturn(selectedSellerId)
        whenever(senderProfileJoin.get<Long>("id")).thenReturn(senderProfileId)

        val paths = InvoiceOverviewDimensionPaths.of(recipientJoin, selectedSellerJoin, senderProfileJoin)

        assertSame(recipientId, paths.recipientId)
        assertSame(selectedSellerId, paths.selectedSellerId)
        assertSame(senderProfileId, paths.senderProfileId)
        verify(recipientJoin).get<Long>("id")
        verify(selectedSellerJoin).get<Long>("id")
        verify(senderProfileJoin).get<Long>("id")
    }
}
