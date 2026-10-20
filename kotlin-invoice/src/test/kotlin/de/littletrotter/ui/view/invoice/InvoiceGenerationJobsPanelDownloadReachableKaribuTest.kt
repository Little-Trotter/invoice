package de.littletrotter.ui.view.invoice

import de.littletrotter.testing.jobView
import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.GenerationJobQueryPort
import de.littletrotter.application.invoice.port.inbound.InvoiceDetailQueryPort
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.fixture.edit
import de.littletrotter.shared.DocumentStore
import de.littletrotter.ui.component.DataTable
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * PO-Meldung 05.09.2026 — „bei 100 % Darstellung ist der Download-Bereich nicht mehr ganz zu
 * sehen." Relocated from the former monolithic `InvoiceExportPanel` into
 * [InvoiceGenerationJobsPanel], which now owns the job Grid.
 *
 * Die Auftragstabelle trägt acht Spalten, sechs davon mit automatischer Breite. Bei 1280 px mit
 * geöffneter Navigationsleiste ist die Summe breiter als der Platz, und Download war die LETZTE
 * Spalte — sie lag damit vollständig hinter dem rechten Rand. Sichtbar blieben die
 * Diagnosespalten, die einzige Handlung war weg, und ohne waagerechtes Scrollen kam niemand an
 * sein Dokument.
 *
 * Der Fix heftet die Download-Spalte ans Ende. Dieser Test hält genau das fest, und zwar auf der
 * Ebene, auf der es entscheidbar ist: die Spalteneigenschaft. Eine Breitenmessung im Browser
 * hinge an Schriftart, Fensterbreite und Datenlänge und wäre eine Wackelkerze; „ist geheftet" ist
 * eine Ja/Nein-Aussage, die auch dann noch gilt, wenn jemand später eine neunte Spalte ergänzt.
 */
class InvoiceGenerationJobsPanelDownloadReachableKaribuTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + (component as? HasComponents)
            ?.let { component.children.toList().flatMap { child -> flatten(child) } }
            .orEmpty()

    private fun invoiceDetailDto(): InvoiceDetailDto = InvoiceDetailDto(
        id = 1L, invoiceNumber = "INV-1", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun panelWithOneJob(): InvoiceGenerationJobsPanel {
        val generationJobQueryPort: GenerationJobQueryPort = mock()
        whenever(generationJobQueryPort.findJobViewsByInvoiceId(any())).thenReturn(
            listOf(
                jobView {
                    id = 1L
                    status = GenerationJobStatus.COMPLETED
                    invoice = Invoice().edit { id = 1L }
                    createdAt = OffsetDateTime.now()
                },
            ),
        )
        val invoiceDetailQueryPort: InvoiceDetailQueryPort = mock()
        whenever(invoiceDetailQueryPort.findLlmAnalyses(anyOrNull())).thenReturn(emptyList())
        val documentStore: DocumentStore = mock()
        whenever(documentStore.exists(any())).thenReturn(false)
        return InvoiceGenerationJobsPanel(
            generationJobQueryPort = generationJobQueryPort,
            invoiceDetailQueryPort = invoiceDetailQueryPort,
            documentStore = documentStore, time = FIXED_TIME_SOURCE,
        ).also { it.render(invoiceDetailDto()) }
    }

    private fun jobsGrid(panel: InvoiceGenerationJobsPanel): DataTable<*> =
        flatten(panel).filterIsInstance<DataTable<*>>().single()

    @Test
    fun `the download column is frozen to the end so it survives every window width`() {
        val grid = jobsGrid(panelWithOneJob())
        val downloadHeader = UiTranslations.tr("invoiceDetail.jobs.column.download")

        val download = grid.columns.singleOrNull { it.getHeaderText() == downloadHeader }
        assertTrue(download != null, "expected a '$downloadHeader' column, got: ${grid.columns.map { it.getHeaderText() }}")
        assertTrue(
            download!!.isFrozenToEnd,
            "the download column must be frozen to the end — it is the only reason to open this table, " +
                "and as the last unfrozen column it fell off the right edge at 1280 px",
        )
    }

    /** Genau EINE geheftete Spalte. Jede weitere nimmt dem scrollenden Teil die Breite, die ihn
     *  lesbar macht — der Fix darf nicht zur Gewohnheit werden. */
    @Test
    fun `no other column is frozen`() {
        val grid = jobsGrid(panelWithOneJob())
        val frozen = grid.columns.filter { it.isFrozen || it.isFrozenToEnd }.map { it.getHeaderText() }

        assertEquals(
            listOf(UiTranslations.tr("invoiceDetail.jobs.column.download")),
            frozen,
            "exactly one frozen column expected",
        )
    }
}
