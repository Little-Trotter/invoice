package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._click
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import de.littletrotter.application.invoice.port.inbound.ReceiptReadabilityQueryPort
import de.littletrotter.application.quote.port.inbound.AssignQuoteAttachmentUseCase
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentUseCase
import de.littletrotter.application.quote.port.inbound.AttachQuoteFileUseCase
import de.littletrotter.application.quote.port.inbound.AttachmentLibraryQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class QuoteItemAttachmentsPoolKaribuTest {

    private val query: QuoteAttachmentQueryPort = mock()
    private val library: AttachmentLibraryQueryPort = mock()
    private val attachLibrary: AttachLibraryDocumentUseCase = mock()
    private var changes = 0
    private val agb = libraryEntry("l-1", "AGB", libraryVersion("v-1", 1))

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        whenever(query.attachments("q-1")).thenReturn(emptyList())
        whenever(library.entries()).thenReturn(listOf(agb))
        whenever(attachLibrary.attach(org.mockito.kotlin.any())).thenReturn("a-1")
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun dialog(withPool: Boolean = true) = QuoteItemAttachmentsDialog(
        testHost(), "q-1", quoteItem(1),
        QuoteItemAttachmentPorts(query, mock<AttachQuoteFileUseCase>(), mock<AssignQuoteAttachmentUseCase>(), library.takeIf { withPool }, attachLibrary.takeIf { withPool }, ReceiptReadabilityQueryPort { emptyList() }),
    ) { changes++ }.also { it.open() }

    @Test
    fun `the editor dialog offers the pool, a read-only one does not`() {
        val withPool = dialog()
        assertTrue(footerComponents(withPool.dialog).single().children.toList().contains(withPool.poolButton))
        assertEquals("Aus den Angebotsbelegen zuordnen", withPool.poolButton.text)

        val without = dialog(withPool = false)
        assertFalse(footerComponents(without.dialog).single().children.toList().contains(without.poolButton))
    }

    @Test
    fun `chosen pool entries are tied to this line`() {
        val dialog = dialog()
        dialog.poolButton._click()
        val pick = UI.getCurrent()._get<QuoteLibraryPickGrid>()
        pick.grid.select(agb)

        UI.getCurrent()._get<Button> { text = "Zuordnen" }._click()

        verify(attachLibrary).attach(AttachLibraryDocumentCommand("q-1", "v-1", "i-1"))
        assertEquals(1, changes)
    }

    @Test
    fun `a pool choice without a selection changes nothing`() {
        val dialog = dialog()
        dialog.poolButton._click()

        UI.getCurrent()._get<Button> { text = "Zuordnen" }._click()

        assertEquals(0, changes)
    }
}
