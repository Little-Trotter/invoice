package de.littletrotter.application.invoice

import de.littletrotter.application.dto.InvoiceCursor
import de.littletrotter.application.dto.InvoiceOverviewDto
import de.littletrotter.application.dto.InvoiceOverviewSortField
import de.littletrotter.application.dto.SortDirection
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.shared.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Pins the forwarding semantics of [InvoiceOverviewQueryService]'s default methods,
 * mirroring [InvoiceQueryFacadeDefaultsTest] for this sibling interface (Z2 split the two apart,
 * but the overload-chain shape is identical). Two mutually-recursive overload pairs exist here —
 * `findOverview`'s 6-arity (sortField/sortDir) and 7-arity (+reverseCharge), and `countOverview`'s
 * 2-arity and 3-arity (+reverseCharge) — each pair's *narrower* default supplies a null and calls
 * the *wider* sibling, while the *wider* one's own default drops that value and calls back into
 * the narrower one. [RecordingOverviewService] overrides the wider end of each pair (matching what
 * a real partial implementor — [InvoiceOverviewQueryServiceImpl] — actually does), which safely
 * terminates the narrower defaults but leaves the wider ones' own drop-the-value bodies dead;
 * [SortOnlyOverviewService]/[Count2OnlyOverviewService] override the narrower end instead, to
 * exercise those specifically without looping.
 */
class InvoiceOverviewQueryServiceDefaultsTest {

    private class RecordingOverviewService : InvoiceOverviewQueryService {
        val calls = mutableListOf<Pair<String, List<Any?>>>()

        override fun findAllOverview(): List<InvoiceOverviewDto> = emptyList()

        override fun findOverview(
            pageIndex: Int,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            reverseCharge: Boolean?,
        ): List<InvoiceOverviewDto> {
            calls += "find5" to listOf(pageIndex, pageSize, filterText, status, reverseCharge)
            return emptyList()
        }

        override fun findOverview(
            pageIndex: Int,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            sortField: InvoiceOverviewSortField,
            sortDir: SortDirection,
            reverseCharge: Boolean?,
        ): List<InvoiceOverviewDto> {
            calls += "find7" to listOf(pageIndex, pageSize, filterText, status, sortField, sortDir, reverseCharge)
            return emptyList()
        }

        override fun findOverviewAfterCursor(
            cursor: InvoiceCursor?,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            dimensions: InvoiceOverviewDimensions,
        ): List<InvoiceOverviewDto> {
            val (reverseCharge, _, recipientId, senderProfileId, dateFrom, dateTo) = dimensions
            calls += "cursor" to listOf(cursor, pageSize, filterText, status, reverseCharge, recipientId, senderProfileId, dateFrom, dateTo)
            return emptyList()
        }

        override fun countOverview(filterText: String?, status: InvoiceStatus?, reverseCharge: Boolean?): Long {
            calls += "count3" to listOf(filterText, status, reverseCharge)
            return 0L
        }

        override fun getTotals(filterText: String?, status: InvoiceStatus?): Triple<Money, Money, Money> {
            calls += "totals" to listOf(filterText, status)
            return Triple(Money.ZERO, Money.ZERO, Money.ZERO)
        }

        override fun countOverviewGroupedByValidationStatus(
            filterText: String?,
            status: InvoiceStatus?,
            reverseCharge: Boolean?,
            recipientId: Long?,
            senderProfileId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
        ): Map<ValidationStatus, Long> {
            calls += "grouped" to listOf(filterText, status, reverseCharge, recipientId, senderProfileId, dateFrom, dateTo)
            return emptyMap()
        }

        override fun countOverviewGroupedByStatus(
            filterText: String?,
            validationStatus: ValidationStatus?,
            reverseCharge: Boolean?,
            recipientId: Long?,
            senderProfileId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
        ): Map<InvoiceStatus, Long> {
            calls += "groupedByStatus" to listOf(filterText, validationStatus, reverseCharge, recipientId, senderProfileId, dateFrom, dateTo)
            return emptyMap()
        }
    }

    private val service = RecordingOverviewService()
    private val sortField = InvoiceOverviewSortField.entries.first()
    private val sortDir = SortDirection.entries.first()

    @Test
    fun `happy path - sortField-sortDir overview forwards with reverseCharge null and named optional filters`() {
        service.findOverview(pageIndex = 2, pageSize = 25, sortField = sortField, sortDir = sortDir)
        assertEquals(listOf("find7" to listOf<Any?>(2, 25, null, null, sortField, sortDir, null)), service.calls)
    }

    @Test
    fun `boundary - the validationStatus overview overload drops validationStatus on the way down to the 7-arity`() {
        service.findOverview(0, 10, "abc", InvoiceStatus.DRAFT, InvoiceOverviewSort(sortField, sortDir), InvoiceOverviewDimensions(reverseCharge = true, validationStatus = ValidationStatus.VALID))
        assertEquals(listOf("find7" to listOf<Any?>(0, 10, "abc", InvoiceStatus.DRAFT, sortField, sortDir, true)), service.calls)
    }

    @Test
    fun `boundary - the ARC-19 overview overload cascades through validationStatus down to the 7-arity, dropping recipient-sender-date range`() {
        service.findOverview(
            0, 10, null, null, InvoiceOverviewSort(sortField, sortDir),
            InvoiceOverviewDimensions(
                reverseCharge = false, validationStatus = null,
                recipientId = 7L, senderProfileId = 3L,
                dateFrom = LocalDate.of(2026, 1, 1), dateTo = LocalDate.of(2026, 12, 31),
            ),
        )
        assertEquals(listOf("find7" to listOf<Any?>(0, 10, null, null, sortField, sortDir, false)), service.calls)
    }

    @Test
    fun `boundary - the 7-arity overview overload's own default drops reverseCharge when calling the narrower sortField-sortDir overload`() {
        val sortOnly = SortOnlyOverviewService()
        sortOnly.findOverview(1, 20, "abc", InvoiceStatus.DRAFT, sortField, sortDir, true)
        assertEquals(listOf(listOf<Any?>(1, 20, "abc", InvoiceStatus.DRAFT, sortField, sortDir)), sortOnly.calls)
    }

    @Test
    fun `boundary - countOverview chain bottoms out in the reverseCharge arity, including the ARC-19 overload`() {
        service.countOverview()
        service.countOverview("x", InvoiceStatus.ISSUED, true, ValidationStatus.INVALID)
        service.countOverview("y", null, InvoiceOverviewDimensions(reverseCharge = false, recipientId = 1L, senderProfileId = 2L))
        assertEquals(
            listOf(
                "count3" to listOf<Any?>(null, null, null),
                "count3" to listOf<Any?>("x", InvoiceStatus.ISSUED, true),
                "count3" to listOf<Any?>("y", null, false),
            ),
            service.calls,
        )
    }

    @Test
    fun `boundary - the 3-arity countOverview's own default drops reverseCharge when calling the 2-arity overload`() {
        val count2Only = Count2OnlyOverviewService()
        count2Only.countOverview("z", InvoiceStatus.ISSUED, true)
        assertEquals(listOf("z" to InvoiceStatus.ISSUED), count2Only.calls)
    }

    @Test
    fun `boundary - findOverviewAfterCursor and getTotals forward their defaults unchanged`() {
        service.findOverviewAfterCursor(null, 50)
        service.getTotals()
        assertEquals(
            listOf(
                "cursor" to listOf<Any?>(null, 50, null, null, null, null, null, null, null),
                "totals" to listOf<Any?>(null, null),
            ),
            service.calls,
        )
    }

    @Test
    fun `boundary - findOverviewAfterCursor forwards a partially-explicit call through its own default bridge`() {
        service.findOverviewAfterCursor(null, 15, filterText = "partial", dimensions = InvoiceOverviewDimensions(recipientId = 9L))
        assertEquals(
            listOf("cursor" to listOf<Any?>(null, 15, "partial", null, null, 9L, null, null, null)),
            service.calls,
        )
    }

    @Test
    fun `boundary - grouped-by-validationStatus and grouped-by-status default to no filter at all`() {
        service.countOverviewGroupedByValidationStatus()
        service.countOverviewGroupedByStatus()
        assertEquals(
            listOf(
                "grouped" to listOf<Any?>(null, null, null, null, null, null, null),
                "groupedByStatus" to listOf<Any?>(null, null, null, null, null, null, null),
            ),
            service.calls,
        )
    }

    /** Mirrors [InvoiceQueryFacadeDefaultsTest]'s `SortOnlyFacade` — see this file's class KDoc. */
    private class SortOnlyOverviewService : InvoiceOverviewQueryService {
        val calls = mutableListOf<List<Any?>>()

        override fun findAllOverview(): List<InvoiceOverviewDto> = emptyList()

        override fun findOverview(
            pageIndex: Int,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            reverseCharge: Boolean?,
        ): List<InvoiceOverviewDto> = emptyList()

        override fun findOverview(
            pageIndex: Int,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            sortField: InvoiceOverviewSortField,
            sortDir: SortDirection,
        ): List<InvoiceOverviewDto> {
            calls += listOf(pageIndex, pageSize, filterText, status, sortField, sortDir)
            return emptyList()
        }

        override fun findOverviewAfterCursor(
            cursor: InvoiceCursor?,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            dimensions: InvoiceOverviewDimensions,
        ): List<InvoiceOverviewDto> = emptyList()

        override fun countOverview(filterText: String?, status: InvoiceStatus?, reverseCharge: Boolean?): Long = 0L
        override fun getTotals(filterText: String?, status: InvoiceStatus?): Triple<Money, Money, Money> = Triple(Money.ZERO, Money.ZERO, Money.ZERO)

        override fun countOverviewGroupedByValidationStatus(
            filterText: String?,
            status: InvoiceStatus?,
            reverseCharge: Boolean?,
            recipientId: Long?,
            senderProfileId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
        ): Map<ValidationStatus, Long> = emptyMap()

        override fun countOverviewGroupedByStatus(
            filterText: String?,
            validationStatus: ValidationStatus?,
            reverseCharge: Boolean?,
            recipientId: Long?,
            senderProfileId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
        ): Map<InvoiceStatus, Long> = emptyMap()
    }

    /** Mirrors [InvoiceQueryFacadeDefaultsTest]'s `Count2OnlyFacade` — see this file's class KDoc. */
    private class Count2OnlyOverviewService : InvoiceOverviewQueryService {
        val calls = mutableListOf<Pair<String?, InvoiceStatus?>>()

        override fun findAllOverview(): List<InvoiceOverviewDto> = emptyList()

        override fun findOverview(
            pageIndex: Int,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            reverseCharge: Boolean?,
        ): List<InvoiceOverviewDto> = emptyList()

        override fun findOverviewAfterCursor(
            cursor: InvoiceCursor?,
            pageSize: Int,
            filterText: String?,
            status: InvoiceStatus?,
            dimensions: InvoiceOverviewDimensions,
        ): List<InvoiceOverviewDto> = emptyList()

        override fun countOverview(filterText: String?, status: InvoiceStatus?): Long {
            calls += filterText to status
            return 0L
        }

        override fun getTotals(filterText: String?, status: InvoiceStatus?): Triple<Money, Money, Money> = Triple(Money.ZERO, Money.ZERO, Money.ZERO)

        override fun countOverviewGroupedByValidationStatus(
            filterText: String?,
            status: InvoiceStatus?,
            reverseCharge: Boolean?,
            recipientId: Long?,
            senderProfileId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
        ): Map<ValidationStatus, Long> = emptyMap()

        override fun countOverviewGroupedByStatus(
            filterText: String?,
            validationStatus: ValidationStatus?,
            reverseCharge: Boolean?,
            recipientId: Long?,
            senderProfileId: Long?,
            dateFrom: LocalDate?,
            dateTo: LocalDate?,
        ): Map<InvoiceStatus, Long> = emptyMap()
    }
}
