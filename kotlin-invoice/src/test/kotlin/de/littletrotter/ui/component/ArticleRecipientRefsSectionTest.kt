package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.data.provider.Query
import de.littletrotter.application.dto.ArticleRecipientRefDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.masterdata.ArticleRecipientRefsModule
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.spi.masterdata.ArticleRecipientRefOption
import de.littletrotter.spi.masterdata.ArticleRecipientRefsExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [ArticleRecipientRefsSection]: the extension==null "renders nothing" fork,
 * [ArticleRecipientRefsSection.readFrom]'s row/option resolution,
 * [ArticleRecipientRefsSection.submittedRows]' drop-unselected-rows filter, and the add/remove-row
 * round trip.
 *
 * The recipient [ComboBox] uses the lazy [lazyItems] idiom riding
 * [ArticleRecipientRefsExtension.searchRecipients]/[ArticleRecipientRefsExtension.countRecipients];
 * most tests below need not stub those two methods at all (the `CallbackDataProvider` they back is
 * never queried unless a test fetches from it directly, same as [LazyComboSupportTest]) — only the
 * lazy-paging pin at the bottom actually exercises the data provider.
 */
class ArticleRecipientRefsSectionTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private val tr: (String) -> String = { it }

    private fun rowsOf(section: ArticleRecipientRefsSection): List<ArticleRecipientRefsSection.Row> = section.rows

    @Test
    fun `hasContent and the add button are both absent when there is no extension`() {
        val section = ArticleRecipientRefsSection(extension = null, tr = tr)
        assertFalse(section.hasContent)
        assertTrue(section.children.toList().isEmpty(), "no extension -> nothing rendered at all")
    }

    @Test
    fun `submittedRows is null and readFrom shows nothing when there is no extension`() {
        val section = ArticleRecipientRefsSection(extension = null, tr = tr)
        section.readFrom(listOf(ArticleRecipientRefDto(1L, "AB-1")))
        assertTrue(rowsOf(section).isEmpty())
        assertNull(section.submittedRows(), "without the section the stored rows stay as they are")
    }

    @Test
    fun `hasContent is true and the add button is rendered when an extension is present`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)
        assertTrue(section.hasContent)
        assertTrue(section.children.toList().filterIsInstance<Button>().isNotEmpty())
    }

    @Test
    fun `readFrom resolves a stored row's recipient option via recipientOption, not a searched list`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val option = ArticleRecipientRefOption(recipientId = 3L, label = "Kunde GmbH")
        whenever(extension.recipientOption(3L)).thenReturn(option)
        val stored = listOf(ArticleRecipientRefDto(recipientId = 3L, buyerArticleNumber = "AB-1", buyerArticleName = "Pumpe"))
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)

        section.readFrom(stored)

        val rows = rowsOf(section)
        assertEquals(1, rows.size)
        assertEquals(option, rows[0].recipient.value)
        assertEquals("AB-1", rows[0].buyerArticleNumber.value)
        verify(extension).recipientOption(3L)
    }

    @Test
    fun `readFrom resolves each stored row's own recipient id independently, not just the first`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val first = ArticleRecipientRefOption(recipientId = 1L, label = "Andere GmbH")
        val second = ArticleRecipientRefOption(recipientId = 3L, label = "Kunde GmbH")
        whenever(extension.recipientOption(1L)).thenReturn(first)
        whenever(extension.recipientOption(3L)).thenReturn(second)
        val stored = listOf(
                ArticleRecipientRefDto(recipientId = 1L, buyerArticleNumber = null, buyerArticleName = null),
                ArticleRecipientRefDto(recipientId = 3L, buyerArticleNumber = "AB-1", buyerArticleName = "Pumpe"),
            )
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)

        section.readFrom(stored)

        val combos = rowsOf(section).map { it.recipient }
        assertEquals(2, combos.size)
        assertEquals(first, combos[0].value)
        assertEquals(second, combos[1].value)
    }

    @Test
    fun `readFrom leaves the recipient combo unselected when the stored id no longer resolves`() {
        val extension: ArticleRecipientRefsExtension = mock()
        whenever(extension.recipientOption(999L)).thenReturn(null)
        val stored = listOf(ArticleRecipientRefDto(recipientId = 999L, buyerArticleNumber = null, buyerArticleName = null))
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)

        section.readFrom(stored)

        assertEquals(null, rowsOf(section)[0].recipient.value, "an unresolvable recipient id must not crash and leaves the combo empty")
    }

    @Test
    fun `readFrom clears previous rows instead of appending on a second call`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val stored = listOf(ArticleRecipientRefDto(recipientId = 1L, buyerArticleNumber = null, buyerArticleName = null))
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)
        section.readFrom(stored)
        section.readFrom(stored)

        assertEquals(1, rowsOf(section).size, "readFrom must clear before repopulating")
    }

    @Test
    fun `clicking Add appends a new empty row`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)

        val addButton = section.children.toList().filterIsInstance<Button>().first()
        addButton.click()
        addButton.click()

        assertEquals(2, rowsOf(section).size)
    }

    @Test
    fun `new reference rows use available width and wrap before fields become cramped`() {
        val section = ArticleRecipientRefsSection(extension = mock(), tr = tr)
        section.children.toList().filterIsInstance<Button>().first().click()

        val row = rowsOf(section).single()
        assertEquals("100%", section.width)
        assertEquals("100%", row.bar.width)
        assertTrue(row.bar.hasClassName("lt-flex-wrap"))
        val recipient = row.recipient
        val fields = listOf(row.buyerArticleNumber, row.buyerArticleName)
        assertTrue(recipient.hasClassName("lt-article-ref-recipient"))
        assertTrue(fields[0].hasClassName("lt-article-ref-number"))
        assertTrue(fields[1].hasClassName("lt-article-ref-name"))
    }

    @Test
    fun `clicking a row's remove button removes exactly that row`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)
        val addButton = section.children.toList().filterIsInstance<Button>().first()
        addButton.click()
        addButton.click()
        assertEquals(2, rowsOf(section).size)

        val removeButton = rowsOf(section)[0].bar.children.toList().filterIsInstance<Button>().first()
        removeButton.click()

        assertEquals(1, rowsOf(section).size)
    }

    @Test
    fun `submittedRows drops a row whose recipient was never chosen`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val option = ArticleRecipientRefOption(recipientId = 5L, label = "Kunde")
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)
        val addButton = section.children.toList().filterIsInstance<Button>().first()
        addButton.click() // row with no recipient chosen
        addButton.click()
        rowsOf(section)[1].recipient.value = option

        assertEquals(
            listOf(ArticleRecipientRefDto(5L, null, null)),
            section.submittedRows(),
            "the row without a chosen recipient must be dropped, not written as null",
        )
    }

    @Test
    fun `submittedRows keeps non-blank buyer article number and name as typed`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val option = ArticleRecipientRefOption(recipientId = 5L, label = "Kunde")
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)
        section.children.toList().filterIsInstance<Button>().first().click()
        val row = rowsOf(section)[0]
        row.recipient.value = option
        row.buyerArticleNumber.value = "AB-77"
        row.buyerArticleName.value = "Pumpe XL"

        assertEquals(listOf(ArticleRecipientRefDto(5L, "AB-77", "Pumpe XL")), section.submittedRows())
    }

    @Test
    fun `submittedRows blanks out empty text fields to null rather than empty strings`() {
        val extension: ArticleRecipientRefsExtension = mock()
        val option = ArticleRecipientRefOption(recipientId = 5L, label = "Kunde")
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)
        section.children.toList().filterIsInstance<Button>().first().click()
        val row = rowsOf(section)[0]
        row.recipient.value = option
        row.buyerArticleNumber.value = "   "

        assertEquals(null, section.submittedRows()?.single()?.buyerArticleNumber)
    }

    // ── the recipient combo must ride the lazy, server-paged search, never an eager cap ──────

    private fun query(offset: Int = 0, limit: Int = 60, filter: String? = null) =
        Query<ArticleRecipientRefOption, String>(offset, limit, emptyList(), null, filter)

    /**
     * Pins the whole path end to end: the real [ArticleRecipientRefsModule] wired to a
     * mocked, paged [RecipientQueryPort], feeding the real [ArticleRecipientRefsSection]'s recipient
     * [ComboBox]. A recipient ranked well beyond the old `findAllRecipients()` page-size cap (60)
     * must still be fetchable and selectable once the ComboBox's own `CallbackDataProvider` queries
     * a page past that cap — proving neither the module nor the section reintroduces the eager cap.
     */
    @Test
    fun `a recipient beyond position 60 is fetchable and selectable through the section's lazy recipient combo`() {
        val recipientQuery: RecipientQueryPort = mock()
        val beyondCap = RecipientSummaryDto(
            id = 161L,
            name = "Zeta Logistik #61",
            customerNumber = "KD-0161",
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
        whenever(recipientQuery.findRecipients(1, 60, "Zeta")).thenReturn(listOf(beyondCap))
        whenever(recipientQuery.countRecipients("Zeta")).thenReturn(61L)
        val extension = ArticleRecipientRefsModule(recipientQuery)
        val section = ArticleRecipientRefsSection(extension = extension, tr = tr)
        section.children.toList().filterIsInstance<Button>().first().click()

        val row = rowsOf(section)[0]
        // offset=60, limit=60 -> page index 1, i.e. the page starting right past the old 60-row cap.
        val result = row.recipientDataProvider.fetch(query(offset = 60, limit = 60, filter = "Zeta")).toList()
        val size = row.recipientDataProvider.size(query(offset = 60, limit = 60, filter = "Zeta"))

        val expectedOption = ArticleRecipientRefOption(161L, "Zeta Logistik #61 – Kd. KD-0161")
        assertEquals(listOf(expectedOption), result)
        assertEquals(61, size)

        row.recipient.value = result.single()
        assertEquals(161L, row.recipient.value.recipientId, "a recipient beyond position 60 must be selectable, not just fetchable")
    }
}
