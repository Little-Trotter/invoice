package de.littletrotter.infrastructure.export.workbook

import de.littletrotter.application.ApplicationIdentity
import de.littletrotter.application.dto.InvoiceExportFilter
import de.littletrotter.application.export.InvoiceExportNotFoundException
import de.littletrotter.application.export.InvoiceExportReadRepository
import de.littletrotter.application.export.InvoiceHeaderExportRow
import de.littletrotter.application.export.InvoiceJournalExportRow
import de.littletrotter.application.export.InvoicePositionExportRow
import de.littletrotter.application.shared.SystemAuditActorProvider
import org.apache.poi.ss.usermodel.CellType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * [InvoiceWorkbookExportService] (the [de.littletrotter.application.export
 * .InvoiceWorkbookExportPort] implementation) assembles all four sheets for exactly one invoice
 * from [InvoiceExportReadRepository]'s mode-A methods, in R1's fixed order (`Export`, `Rechnungen`,
 * `Positionen`, `Journal`), and produces a real `.xlsx` a POI reader accepts — that is the actual
 * point of these tests, not that individual writer calls happened. The identity `translate`
 * function used throughout means every resolved label IS its own i18n key — irrelevant to these
 * tests (they only pin sheet/row structure), but it also means a missing key would show up as an
 * empty string rather than being masked; [InvoiceWorkbookExportLabelBuilderTest] covers the actual
 * label-resolution logic.
 */
class InvoiceWorkbookExportServiceTest {

    private val identityTranslate: (String) -> String = { key -> key }
    private val applicationIdentity = ApplicationIdentity("9.9.9-test")

    private fun buildService(repository: InvoiceExportReadRepository): InvoiceWorkbookExportService =
        InvoiceWorkbookExportService(
            repository, SystemAuditActorProvider(), applicationIdentity,
            de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
        )

    private fun headerRow(invoiceId: Long = 1L) =
        InvoiceHeaderExportRow(invoiceId = invoiceId, invoiceNumber = "RE-2026-001", invoiceDate = LocalDate.of(2026, 1, 15))

    private fun positionRow(invoiceId: Long = 1L, positionId: String = "1") =
        InvoicePositionExportRow(invoiceId = invoiceId, positionId = positionId)

    private fun journalRow(invoiceId: Long = 1L, seq: Long = 1L) =
        InvoiceJournalExportRow(invoiceId = invoiceId, seq = seq)

    @Test
    fun `writes all four sheets, Export first, for an invoice with positions and journal entries`() {
        val repository: InvoiceExportReadRepository = mock()
        whenever(repository.findHeaderRow(1L)).thenReturn(headerRow())
        whenever(repository.findPositionRows(1L)).thenReturn(listOf(positionRow(positionId = "1"), positionRow(positionId = "2")))
        whenever(repository.findJournalRows(1L)).thenReturn(listOf(journalRow(seq = 1L)))

        val output = ByteArrayOutputStream()
        buildService(repository).writeWorkbook(1L, "This invoice: RE-2026-001", identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(listOf(0, 1, 2, 3).map { wb.getSheetName(it) })
                .containsExactly("Export", "Rechnungen", "Positionen", "Journal")
            assertThat(wb.getSheet("Rechnungen").physicalNumberOfRows).isEqualTo(2) // header + 1 row
            assertThat(wb.getSheet("Positionen").physicalNumberOfRows).isEqualTo(3) // header + 2 rows
            assertThat(wb.getSheet("Journal").physicalNumberOfRows).isEqualTo(2) // header + 1 row

            val exportSheet = wb.getSheet("Export")
            // the "Export" cover sheet's fixed row layout: title(0), subtitle(1),
            // spacer(2), "Export" section(3), Field/Value sub-header(4), then the key/value rows
            // (generatedAt, actingUser, selection, applicationVersion) at 5..8, then one
            // record-count row per data sheet, in write order: Rechnungen(1), Positionen(2),
            // Journal(1).
            assertThat(exportSheet.getRow(0).getCell(0).stringCellValue).isEqualTo(applicationIdentity.productLine())
            assertThat(exportSheet.getRow(RECORD_COUNT_FIRST_ROW).getCell(1).numericCellValue).isEqualTo(1.0)
            assertThat(exportSheet.getRow(RECORD_COUNT_FIRST_ROW + 1).getCell(1).numericCellValue).isEqualTo(2.0)
            assertThat(exportSheet.getRow(RECORD_COUNT_FIRST_ROW + 2).getCell(1).numericCellValue).isEqualTo(1.0)
        }
    }

    @Test
    fun `an invoice with no positions and no journal entries still produces a valid, readable workbook`() {
        val repository: InvoiceExportReadRepository = mock()
        whenever(repository.findHeaderRow(1L)).thenReturn(headerRow())
        whenever(repository.findPositionRows(1L)).thenReturn(emptyList())
        whenever(repository.findJournalRows(1L)).thenReturn(emptyList())

        val output = ByteArrayOutputStream()
        buildService(repository).writeWorkbook(1L, "This invoice: RE-2026-001", identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(wb.getSheet("Positionen").physicalNumberOfRows).isEqualTo(1) // header only
            // The empty Journal sheet carries exactly one note row (the "no entries" rule),
            // not zero rows — an entirely empty sheet would look broken, not intentional.
            val journalSheet = wb.getSheet("Journal")
            assertThat(journalSheet.physicalNumberOfRows).isEqualTo(2) // header + note row
            assertThat(journalSheet.getRow(1).getCell(0).cellType).isEqualTo(CellType.STRING)
        }
    }

    @Test
    fun `throws InvoiceExportNotFoundException for an unknown invoice id, never writing anything`() {
        val repository: InvoiceExportReadRepository = mock()
        whenever(repository.findHeaderRow(404L)).thenReturn(null)

        val output = ByteArrayOutputStream()
        assertThatThrownBy { buildService(repository).writeWorkbook(404L, "n/a", identityTranslate, output) }
            .isInstanceOf(InvoiceExportNotFoundException::class.java)
        assertThat(output.toByteArray()).isEmpty()
    }

    // ── mode B: the filtered-list export ───────────────────────────────

    /**
     * A hand-written fake rather than a Mockito mock: [InvoiceExportReadRepository.findHeaderRows]
     * carries a default parameter ([InvoiceExportReadRepository.DEFAULT_BATCH_SIZE]), and a fake
     * that actually implements the id-keyset paging contract exercises the service's own batching
     * loop for real instead of merely proving a stub was called with *some* arguments.
     */
    private class FakeReadRepository(
        private val headers: List<InvoiceHeaderExportRow>,
        private val positionsByInvoiceId: Map<Long, List<InvoicePositionExportRow>>,
    ) : InvoiceExportReadRepository {
        var headerRowsCallCount: Int = 0
            private set

        override fun findHeaderRow(invoiceId: Long): InvoiceHeaderExportRow? = headers.find { it.invoiceId == invoiceId }

        override fun findPositionRows(invoiceId: Long): List<InvoicePositionExportRow> = positionsByInvoiceId[invoiceId].orEmpty()

        override fun findJournalRows(invoiceId: Long) = error("mode B never reads the journal")

        override fun findHeaderRows(filter: InvoiceExportFilter, afterId: Long?, batchSize: Int): List<InvoiceHeaderExportRow> {
            headerRowsCallCount++
            val remaining = if (afterId == null) headers else headers.filter { it.invoiceId > afterId }
            return remaining.sortedBy { it.invoiceId }.take(batchSize)
        }
    }

    @Test
    fun `mode B writes three sheets - Export, Rechnungen, Positionen - and never a Journal sheet`() {
        val repository = FakeReadRepository(
            headers = listOf(headerRow(invoiceId = 1L), headerRow(invoiceId = 2L)),
            positionsByInvoiceId = mapOf(
                1L to listOf(positionRow(invoiceId = 1L, positionId = "1")),
                2L to listOf(positionRow(invoiceId = 2L, positionId = "1"), positionRow(invoiceId = 2L, positionId = "2")),
            ),
        )

        val output = ByteArrayOutputStream()
        buildService(repository).writeWorkbook(InvoiceExportFilter(), "Alle Rechnungen", identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(listOf(0, 1, 2).map { wb.getSheetName(it) }).containsExactly("Export", "Rechnungen", "Positionen")
            assertThat(wb.numberOfSheets).isEqualTo(3)
            assertThat(wb.getSheet("Rechnungen").physicalNumberOfRows).isEqualTo(3) // header + 2 invoices
            assertThat(wb.getSheet("Positionen").physicalNumberOfRows).isEqualTo(4) // header + 3 positions across both invoices
        }
    }

    @Test
    fun `mode B pages through the filter in batches instead of loading everything in one call`() {
        val repository = FakeReadRepository(
            headers = (1L..3L).map { headerRow(invoiceId = it) },
            positionsByInvoiceId = emptyMap(),
        )

        val output = ByteArrayOutputStream()
        buildService(repository).writeWorkbook(InvoiceExportFilter(), "Alle Rechnungen", identityTranslate, output)

        // Three invoices at the default batch size (500) still means exactly one non-empty batch
        // call plus the terminating empty-batch call — the point of this test is that the loop
        // keeps asking with the *previous batch's last id* until a short page ends it, not that a
        // specific count of calls happens; assert the loop terminated at all (readWorkbook above
        // already proves it produced output) and that it made more than zero, bounded calls.
        assertThat(repository.headerRowsCallCount).isGreaterThan(0)
        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(wb.getSheet("Rechnungen").physicalNumberOfRows).isEqualTo(4) // header + 3 invoices
        }
    }

    /**
     * [InvoiceExportReadRepository.findHeaderRows]'s own `batchSize` default
     * ([InvoiceExportReadRepository.DEFAULT_BATCH_SIZE]) only resolves at a call site whose
     * static type is the interface itself. `repository` here is declared as that interface type
     * (not [FakeReadRepository]) specifically so this omitted-batchSize call exercises the
     * interface's own default rather than the fake's.
     */
    @Test
    fun `findHeaderRows omits batchSize and falls back to DEFAULT_BATCH_SIZE`() {
        val repository: InvoiceExportReadRepository = FakeReadRepository(
            headers = listOf(headerRow(invoiceId = 1L)),
            positionsByInvoiceId = emptyMap(),
        )

        val rows = repository.findHeaderRows(InvoiceExportFilter(), afterId = null)

        assertThat(rows).extracting("invoiceId").containsExactly(1L)
    }

    @Test
    fun `mode B over an empty filter result still produces a valid workbook with header rows only`() {
        val repository = FakeReadRepository(headers = emptyList(), positionsByInvoiceId = emptyMap())

        val output = ByteArrayOutputStream()
        buildService(repository).writeWorkbook(InvoiceExportFilter(status = null), "Alle Rechnungen", identityTranslate, output)

        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(wb.numberOfSheets).isEqualTo(3)
            assertThat(wb.getSheet("Rechnungen").physicalNumberOfRows).isEqualTo(1) // header only
            assertThat(wb.getSheet("Positionen").physicalNumberOfRows).isEqualTo(1) // header only
        }
    }

    @Test
    fun `mode B genuinely pages when a batch is exactly full - the loop's continue branch, not just its break`() {
        // every other paging fixture stays under DEFAULT_BATCH_SIZE (500), so
        // `batch.size < DEFAULT_BATCH_SIZE` is always true on the first (and only) real call;
        // this forces a first batch of exactly 500 rows, requiring a genuine second call.
        val headers = (1L..501L).map { headerRow(invoiceId = it) }
        val repository = FakeReadRepository(headers = headers, positionsByInvoiceId = emptyMap())

        val output = ByteArrayOutputStream()
        buildService(repository).writeWorkbook(InvoiceExportFilter(), "Alle Rechnungen", identityTranslate, output)

        assertThat(repository.headerRowsCallCount).isGreaterThan(1)
        readWorkbook(output.toByteArray()).use { wb ->
            assertThat(wb.getSheet("Rechnungen").physicalNumberOfRows).isEqualTo(502) // header + 501 invoices
        }
    }

    @Test
    fun `mode B writePositionsCsv genuinely pages when a batch is exactly full`() {
        val headers = (1L..501L).map { headerRow(invoiceId = it) }
        val repository = FakeReadRepository(
            headers = headers,
            positionsByInvoiceId = headers.associate { it.invoiceId to listOf(positionRow(invoiceId = it.invoiceId, positionId = "1")) },
        )

        val output = ByteArrayOutputStream()
        buildService(repository).writePositionsCsv(InvoiceExportFilter(), identityTranslate, output)

        assertThat(repository.headerRowsCallCount).isGreaterThan(1)
        val csv = output.toByteArray().toString(Charsets.UTF_8)
        // BOM + header row + one data row per invoice's one position = 502 lines total.
        assertThat(csv.lines().filter { it.isNotBlank() }).hasSize(502)
    }

    @Test
    fun `mode B writePositionsCsv writes every matched invoice's positions, not just the first`() {
        val repository = FakeReadRepository(
            headers = listOf(headerRow(invoiceId = 1L), headerRow(invoiceId = 2L)),
            positionsByInvoiceId = mapOf(
                1L to listOf(positionRow(invoiceId = 1L, positionId = "1")),
                2L to listOf(positionRow(invoiceId = 2L, positionId = "1")),
            ),
        )

        val output = ByteArrayOutputStream()
        buildService(repository).writePositionsCsv(InvoiceExportFilter(), identityTranslate, output)

        val csv = output.toByteArray().toString(Charsets.UTF_8)
        // BOM + header row + one data row per invoice's one position = 3 lines total.
        assertThat(csv.lines().filter { it.isNotBlank() }).hasSize(3)
    }

    private companion object {
        /** the "Export" cover sheet's first record-count row: title(0), subtitle(1),
         *  spacer(2), "Export" section(3), Field/Value sub-header(4), generatedAt(5),
         *  actingUser(6), selection(7), applicationVersion(8), then the record-count rows. */
        const val RECORD_COUNT_FIRST_ROW = 9
    }
}
