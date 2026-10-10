package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.PartyDto
import de.littletrotter.application.invoice.port.inbound.ItemEntryQueryPort
import de.littletrotter.application.dto.ArticleRecipientRefDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The item dialogs' article-selection listener prefills BT-156 (buyer item number)
 * and, for a *new* position, the description, off a per-(article, recipient) mapping. This test
 * covers the extracted, facade-mocked helpers ([resolveArticleRecipientRef],
 * [newPositionDescriptionFor], [prefillIfBlank]) the same way [ItemDialogB2bSupportTest] covers
 * the BT-155/BT-156 split — the actual dialog wiring (`GoodsItemDialog`/`ServiceItemDialog`) is a
 * thin call site around these.
 */
class ArticleRecipientMappingSupportTest {

    private val facade: ItemEntryQueryPort = mock()

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun mapping(buyerArticleNumber: String? = "243538", buyerArticleName: String? = "Nekar-Bezeichnung"): ArticleRecipientRefDto =
        ArticleRecipientRefDto(20L, buyerArticleNumber, buyerArticleName)

    private fun invoiceWithRecipient(recipientId: Long?): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = recipientId?.let { PartyDto(id = it, name = "Nekar") },
        items = emptyList(), latestGenerationJob = null,
    )

    // ── resolveArticleRecipientRef ───────────────────────────────────────────

    @Test
    fun `resolveArticleRecipientRef is null when the article has no id`() {
        val invoice = invoiceWithRecipient(20L)
        assertNull(resolveArticleRecipientRef(facade, invoice, null))
        verify(facade, never()).findArticleRecipientRef(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `resolveArticleRecipientRef is null when the invoice has no recipient`() {
        val invoice = invoiceWithRecipient(null)
        assertNull(resolveArticleRecipientRef(facade, invoice, 10L))
        verify(facade, never()).findArticleRecipientRef(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `resolveArticleRecipientRef delegates to the facade and returns its result`() {
        val invoice = invoiceWithRecipient(20L)
        val m = mapping()
        whenever(facade.findArticleRecipientRef(10L, 20L)).thenReturn(m)

        assertEquals(m, resolveArticleRecipientRef(facade, invoice, 10L))
    }

    @Test
    fun `resolveArticleRecipientRef is null for an unmapped recipient`() {
        val invoice = invoiceWithRecipient(20L)
        whenever(facade.findArticleRecipientRef(10L, 20L)).thenReturn(null)

        assertNull(resolveArticleRecipientRef(facade, invoice, 10L))
    }

    // ── newPositionDescriptionFor ─────────────────────────────────────────

    @Test
    fun `newPositionDescriptionFor prefers the mapping's buyer article name`() {
        assertEquals("Nekar-Bezeichnung", newPositionDescriptionFor(mapping(), "Fallback"))
    }

    @Test
    fun `newPositionDescriptionFor falls back when there is no mapping`() {
        assertEquals("Fallback", newPositionDescriptionFor(null, "Fallback"))
    }

    @Test
    fun `newPositionDescriptionFor falls back when the mapping's buyer article name is blank`() {
        assertEquals("Fallback", newPositionDescriptionFor(mapping(buyerArticleName = null), "Fallback"))
    }

    // ── prefillIfBlank(TextField, String?) ────────────────────────────────

    @Test
    fun `prefillIfBlank sets the value when the field is empty`() {
        val field = TextField()
        prefillIfBlank(field, "243538")
        assertEquals("243538", field.value)
    }

    @Test
    fun `prefillIfBlank never overwrites a value the user already typed`() {
        val field = TextField().apply { value = "USER-TYPED" }
        prefillIfBlank(field, "243538")
        assertEquals("USER-TYPED", field.value)
    }

    @Test
    fun `prefillIfBlank is a no-op for a blank candidate value`() {
        val field = TextField()
        prefillIfBlank(field, null)
        assertEquals("", field.value)
        prefillIfBlank(field, "   ")
        assertEquals("", field.value)
    }
}
