package de.littletrotter.ui.view.quote

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10._find
import com.github.mvysny.kaributesting.v10._getCellComponent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.data.provider.ListDataProvider
import de.littletrotter.application.quote.QuoteNotFoundException
import de.littletrotter.application.quote.port.inbound.QuoteAttachment
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentContent
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentKind
import de.littletrotter.application.quote.port.inbound.QuoteAttachmentQueryPort
import de.littletrotter.application.quote.port.inbound.QuoteStatus
import de.littletrotter.application.quote.port.inbound.QuoteVatGroup
import de.littletrotter.shared.Money
import de.littletrotter.shared.tax.VatCategory
import de.littletrotter.ui.i18n.TranslationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** The smaller building blocks of the offer detail: overview, totals block, attachments and timeline. */
class QuotePanelsKaribuTest {

    private val host = object : TranslationProvider {
        private val anchor = Div()
        override fun getComponent() = anchor
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        registerQuoteRoutes()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun overview(detail: de.littletrotter.application.quote.port.inbound.QuoteDetail) =
        QuoteOverviewPanel(host).also {
            UI.getCurrent().add(it)
            it.render(detail)
        }

    private fun labelsOf(panel: QuoteOverviewPanel): List<String> =
        panel._find<Span>().filter { it.classNames.contains("cockpit-field-label") }.map { it.text }

    @Test
    fun `the overview shows customer and offer blocks with only the fields that are set`() {
        val panel = overview(quoteDetail())

        val text = visibleText(panel)
        assertTrue(text.contains("Muster GmbH") && text.contains("Hauptstraße 1") && text.contains("50667 Köln, DE"), text)
        assertEquals(
            listOf("Kundennr.", "USt-IdNr.", "Dokumentsprache", "Betreff", "Angebotsdatum", "Gültig bis", "Preisanzeige"),
            labelsOf(panel),
        )
        assertEquals(listOf("Empfänger", "Angebot"), panel._find<Span>().filter { it.classNames.contains("sp-section-title") }.map { it.text })
    }

    @Test
    fun `empty fields are not rendered at all`() {
        val bare = quoteDetail {
            it.copy(
                subject = null,
                party = quoteParty().copy(street = null, postalCode = null, city = null, customerNumber = null, vatId = null),
            )
        }
        val panel = overview(bare)

        assertEquals(listOf("Dokumentsprache", "Angebotsdatum", "Gültig bis", "Preisanzeige"), labelsOf(panel))
        assertFalse(visibleText(panel).contains("Hauptstraße"))
        assertTrue(visibleText(panel).contains("DE"))
    }

    @Test
    fun `an accepted offer adds the answer and a rejected one the reason, a sent one neither`() {
        val accepted = overview(
            quoteDetail(QuoteStatus.ACCEPTED) {
                it.copy(acceptedOn = LocalDate.of(2026, 10, 5), customerOrderReference = "B-77", invoiceNumber = "RE-1")
            },
        )
        assertTrue(labelsOf(accepted).containsAll(listOf("Angenommen am", "Bestellnummer", "Rechnung")))
        assertFalse("Ablehnungsgrund" in labelsOf(accepted))

        val rejected = overview(quoteDetail(QuoteStatus.REJECTED) { it.copy(rejectionReason = "zu teuer") })
        assertTrue("Ablehnungsgrund" in labelsOf(rejected))
        assertTrue(visibleText(rejected).contains("zu teuer"))
        assertFalse("Angenommen am" in labelsOf(rejected))

        val unanswered = overview(quoteDetail(QuoteStatus.SENT))
        assertFalse("Angenommen am" in labelsOf(unanswered))
        assertFalse("Bestellnummer" in labelsOf(unanswered))
    }

    @Test
    fun `the totals block shows net each vat rate and a large gross with a hint for gross prices`() {
        val detail = quoteDetail {
            it.copy(
                showsGrossPrices = true,
                totals = it.totals.copy(
                    net = Money.of("350.00"), vat = Money.of("57.50"), gross = Money.of("407.50"),
                    groups = listOf(
                        QuoteVatGroup(VatCategory.STANDARD, BigDecimal("19.00"), Money.of("250.00"), Money.of("47.50")),
                        QuoteVatGroup(VatCategory.STANDARD, BigDecimal("7.00"), Money.of("100.00"), Money.of("7.00")),
                    ),
                ),
            )
        }
        val panel = QuoteTotalsPanel(host).also { UI.getCurrent().add(it); it.render(detail) }

        val lines = panel._find<Div>().filter { it.classNames.contains("quote-totals__line") }.map { visibleText(it) }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("Summe netto") && lines[0].contains("350,00"), lines[0])
        assertTrue(lines[1].contains("19 %") && lines[1].contains("47,50"), lines[1])
        assertTrue(lines[2].contains("7 %") && lines[2].contains("7,00"), lines[2])
        val gross = panel._find<Span>().single { it.classNames.contains("quote-totals__gross-amount") }
        assertTrue(gross.text.contains("407,50"), gross.text)
        assertTrue(panel._find<Div>().any { it.classNames.contains("quote-totals__rule") })
        assertTrue(panel._find<Span>().any { it.classNames.contains("quote-totals__hint") })

        panel.render(quoteDetail())
        assertTrue(panel._find<Span>().none { it.classNames.contains("quote-totals__hint") })
        assertEquals(2, panel._find<Div>().count { it.classNames.contains("quote-totals__line") })
    }

    @Test
    fun `without attachments the tab shows one quiet sentence`() {
        val query: QuoteAttachmentQueryPort = mock()
        whenever(query.attachments("q-1")).thenReturn(emptyList())
        val panel = QuoteAttachmentsReadPanel(host, query).also { UI.getCurrent().add(it); it.bind("q-1") }

        assertTrue(panel.emptyState.isVisible)
        assertFalse(panel.grid.isVisible)
    }

    @Test
    fun `attachments are listed read-only with kind size and a download`() {
        val query: QuoteAttachmentQueryPort = mock()
        val file = QuoteAttachment("a-1", QuoteAttachmentKind.CUSTOMER, "Leistung.pdf", "application/pdf", 2048, "h", Instant.parse("2026-10-02T08:00:00Z"))
        whenever(query.attachments("q-1")).thenReturn(listOf(file))
        whenever(query.content("q-1", "a-1")).thenReturn(QuoteAttachmentContent("Leistung.pdf", "application/pdf", byteArrayOf(1, 2)))
        val panel = QuoteAttachmentsReadPanel(host, query).also { UI.getCurrent().add(it); it.bind("q-1") }

        assertFalse(panel.emptyState.isVisible)
        assertEquals(listOf(file), (panel.grid.dataProvider as ListDataProvider<QuoteAttachment>).items.toList())
        assertEquals("2 KB", attachmentSizeText(2048))
        assertEquals(2, panel.grid._getCellComponent(0, "actions")._find<Anchor>().size)
        val response = panel.downloadResponse(file)
        assertEquals("Leistung.pdf", response.fileName)
    }

    @Test
    fun `a missing attachment file becomes an empty answer`() {
        val query: QuoteAttachmentQueryPort = mock()
        val file = QuoteAttachment("a-9", QuoteAttachmentKind.INTERNAL, "weg.pdf", "application/pdf", 1, "h", Instant.EPOCH)
        whenever(query.attachments("q-1")).thenReturn(listOf(file))
        whenever(query.content("q-1", "a-9")).thenThrow(QuoteNotFoundException("Anlage nicht gefunden"))
        val panel = QuoteAttachmentsReadPanel(host, query).also { UI.getCurrent().add(it); it.bind("q-1") }

        val response = panel.downloadResponse(file)

        assertFalse(response.hasError())
        assertEquals(0L, response.contentLength)
    }

    @Test
    fun `every status maps to an accent class of the invoice cockpit`() {
        assertEquals(
            listOf(
                "cockpit-hero--draft", "cockpit-hero--review", "cockpit-hero--sent", "cockpit-hero--sent", "cockpit-hero--cancelled",
                "cockpit-hero--voided",
            ),
            QuoteStatus.known.map { QuoteCockpitHero.heroClass(it) },
        )
    }

    @Test
    fun `an unknown status falls back to the neutral draft accent`() {
        assertEquals("cockpit-hero--draft", QuoteCockpitHero.heroClass(QuoteStatus.UNKNOWN))
    }
}
