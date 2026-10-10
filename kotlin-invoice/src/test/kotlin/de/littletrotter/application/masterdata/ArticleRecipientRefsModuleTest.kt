package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.spi.masterdata.ArticleRecipientRefOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The recipient picker of an article's per-recipient identifier mappings.
 *
 * [ArticleRecipientRefsModule.searchRecipients]/[ArticleRecipientRefsModule.countRecipients]
 * replaced the old eager, page-capped full-list lookup; the fake `recipientSearch`/`recipientCount`
 * constructor lambdas below stand in for [RecipientQueryPort.findRecipients]/[RecipientQueryPort
 * .countRecipients]'s server-side paging and filtering, letting these tests assert the module
 * forwards its own filter/offset/limit arguments through untouched rather than re-implementing
 * paging itself.
 */
class ArticleRecipientRefsModuleTest {

    private fun summary(id: Long, name: String = "Kunde $id", customerNumber: String? = "KD-000$id"): RecipientSummaryDto =
        RecipientSummaryDto(
            id = id,
            name = name,
            customerNumber = customerNumber?.ifBlank { null },
            shortHash = null,
            contactPerson = null,
            routingId = null,
            vatId = null,
            customerType = null,
            vatIdVerificationStatus = null,
            reverseChargePreference = ReverseChargePreference.ASK,
            billingCity = null,
            billingCountryAlpha2 = null,
            deliveryCountryAlpha2 = null,
        )

    private val nekarSummary = summary(1L, "Nekar Supermarkt", "KD-0001")
    private val otherSummary = summary(2L, "Andere GmbH", "KD-0002")
    private val summariesById = listOf(nekarSummary, otherSummary).associateBy { it.id }

    private fun moduleOf(
        search: (String?, Int, Int) -> List<RecipientSummaryDto> = { _, _, _ -> summariesById.values.toList() },
        count: (String?) -> Long = { summariesById.size.toLong() },
        findById: (Long) -> RecipientSummaryDto? = { id -> summariesById[id] },
    ): ArticleRecipientRefsModule = ArticleRecipientRefsModule(search, count, findById)

    private val module = moduleOf()

    // ── the @Inject constructor — production CDI wiring against RecipientQueryPort ──────────────

    @Test
    fun `the CDI constructor delegates search, count and lookup to the RecipientQueryPort`() {
        val facade: RecipientQueryPort = mock()
        whenever(facade.findRecipients(pageIndex = 2, pageSize = 30, filterText = "Nekar")).thenReturn(listOf(nekarSummary))
        whenever(facade.countRecipients("Nekar")).thenReturn(1L)
        whenever(facade.findRecipientById(1L)).thenReturn(nekarSummary)
        val m = ArticleRecipientRefsModule(facade)

        val options = m.searchRecipients("Nekar", offset = 60, limit = 30)
        val count = m.countRecipients("Nekar")
        val option = m.recipientOption(1L)

        assertEquals(listOf(ArticleRecipientRefOption(1L, "Nekar Supermarkt – Kd. KD-0001")), options)
        assertEquals(1, count)
        assertEquals(ArticleRecipientRefOption(1L, "Nekar Supermarkt – Kd. KD-0001"), option)
        verify(facade).findRecipients(pageIndex = 2, pageSize = 30, filterText = "Nekar")
        verify(facade).countRecipients("Nekar")
        verify(facade).findRecipientById(1L)
    }

    // ── searchRecipients — argument forwarding and option mapping ────────────────

    @Test
    fun `searchRecipients converts offset and limit to a page index and forwards the filter`() {
        var capturedFilter: String? = "unset"
        var capturedPageIndex = -1
        var capturedPageSize = -1
        val m = moduleOf(search = { filter, pageIndex, pageSize ->
            capturedFilter = filter; capturedPageIndex = pageIndex; capturedPageSize = pageSize
            emptyList()
        })

        m.searchRecipients("Nekar", offset = 60, limit = 30)

        assertEquals("Nekar", capturedFilter)
        assertEquals(2, capturedPageIndex)
        assertEquals(30, capturedPageSize)
    }

    @Test
    fun `searchRecipients treats a zero limit as page zero rather than dividing by zero`() {
        var capturedPageIndex = -1
        val m = moduleOf(search = { _, pageIndex, _ -> capturedPageIndex = pageIndex; emptyList() })

        m.searchRecipients(null, offset = 0, limit = 0)

        assertEquals(0, capturedPageIndex)
    }

    @Test
    fun `searchRecipients maps each recipient the search callback returns to a labelled option`() {
        val options = module.searchRecipients(null, offset = 0, limit = 60)

        assertEquals(
            setOf(
                ArticleRecipientRefOption(1L, "Nekar Supermarkt – Kd. KD-0001"),
                ArticleRecipientRefOption(2L, "Andere GmbH – Kd. KD-0002"),
            ),
            options.toSet(),
        )
    }

    @Test
    fun `searchRecipients omits the customer number suffix when none is set`() {
        val withoutNumber = summary(3L, "Ohne Kundennummer", customerNumber = "")
        val m = moduleOf(search = { _, _, _ -> listOf(withoutNumber) })

        assertEquals(listOf(ArticleRecipientRefOption(3L, "Ohne Kundennummer")), m.searchRecipients(null, 0, 60))
    }

    // ── countRecipients — delegates and narrows Long to Int ──────────────────────

    @Test
    fun `countRecipients forwards the filter and narrows the callback's Long to Int`() {
        var capturedFilter: String? = "unset"
        val m = moduleOf(count = { filter -> capturedFilter = filter; 7L })

        val count = m.countRecipients("Meier")

        assertEquals("Meier", capturedFilter)
        assertEquals(7, count)
    }

    // ── recipientOption — single-id lookup for readFrom's pre-selection ──────────

    @Test
    fun `recipientOption resolves a known id to its labelled option`() {
        assertEquals(ArticleRecipientRefOption(1L, "Nekar Supermarkt – Kd. KD-0001"), module.recipientOption(1L))
    }

    @Test
    fun `recipientOption is null when the id no longer resolves`() {
        assertNull(module.recipientOption(999L))
    }
}
