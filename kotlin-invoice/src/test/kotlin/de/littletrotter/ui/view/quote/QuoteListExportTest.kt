package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Div
import de.littletrotter.application.quote.QuoteServiceException
import de.littletrotter.application.quote.port.inbound.QuoteListCriteria
import de.littletrotter.application.quote.port.inbound.QuoteListWorkbookExportQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteWorkbookExportQueryPort
import de.littletrotter.ui.i18n.TranslationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuoteListExportTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    @Test
    fun `the workbook response carries the written bytes as xlsx`() {
        val response = quoteWorkbookResponse("angebote") { out -> out.write("xlsx".toByteArray()) }

        assertEquals("angebote.xlsx", response.fileName)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.contentType)
        assertFalse(response.hasError())
        assertEquals("xlsx", response.inputStream.use { String(it.readAllBytes()) })
    }

    @Test
    fun `a failing workbook export answers with an error status`() {
        val response = quoteWorkbookResponse("x") { throw QuoteServiceException("down") }

        assertEquals(500, response.error)
    }

    @Test
    fun `the list and detail workbook links are download anchors with their own ids`() {
        val anchor = Div()
        UI.getCurrent().add(anchor)
        val host = object : TranslationProvider {
            override fun getComponent() = anchor
        }
        val detail = quoteDetail()

        val list = buildQuoteListWorkbookLink(host, QuoteListWorkbookExportQueryPort { _, _, _ -> }) { QuoteListCriteria() }
        val single = buildQuoteWorkbookLink(host, QuoteWorkbookExportQueryPort { _, _, _ -> }, detail)

        assertTrue(list.element.hasAttribute("download"))
        assertTrue(list.children.anyMatch { it.id.orElse("") == "quote-list-export-xlsx" })
        assertTrue(single.children.anyMatch { it.id.orElse("") == "quote-export-xlsx" })
    }
}
