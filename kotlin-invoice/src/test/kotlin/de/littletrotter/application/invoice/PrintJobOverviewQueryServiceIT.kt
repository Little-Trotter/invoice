package de.littletrotter.application.invoice

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.GenerationJobFilter
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.invoice.GenerationJob
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.masterdata.Recipient
import io.quarkus.test.TestTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import de.littletrotter.domain.value.CustomerNumber

/**
 * The real query path: [de.littletrotter.infrastructure.persistence
 * .PanacheGenerationJobRepository.findPaged]/[de.littletrotter.infrastructure.persistence
 * .PanacheGenerationJobRepository.countFiltered] through a real Postgres, exercised via
 * [PrintJobOverviewQueryService] (the layer a UI caller actually uses).
 *
 * `@TestTransaction` (rollback), not `@Transactional` (commit) — committed test data polluted
 * `InvoiceOverviewQueryServiceIT` before; every test here rolls back its own writes. That alone
 * does not make `generation_job` empty at the start of a test, though: other `@QuarkusTest` IT
 * classes in this module (e.g. `GenerationJobServiceIT`) deliberately commit real rows against
 * the same Testcontainers Postgres, which one shared `@QuarkusTestResource` instance keeps alive
 * across the whole module's IT run. Every test here therefore scopes its own assertions to its
 * own fixture via `recipientId` or a tight `createdAt` window (same defensive pattern
 * `RepositoryFetchBehaviorIT` uses with its own unique filter text) instead of assuming an empty
 * table — a global, filter-less [PrintJobOverviewQueryService.findPaged] call would otherwise
 * pick up whatever other IT classes happened to commit first.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PrintJobOverviewQueryServiceIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var printJobOverviewQueryService: PrintJobOverviewQueryService

    private fun recipient(name: String, customerNumber: String): Recipient {
        val r = Recipient().apply { this.name = name; this.customerNumber = CustomerNumber.ofNullable(customerNumber) }
        em.persist(r)
        return r
    }

    private fun invoice(number: String, recipient: Recipient?): Invoice {
        val i = Invoice().apply {
            invoiceNumber = InvoiceNumber(number)
            this.recipient = recipient
        }
        em.persist(i)
        return i
    }

    private fun job(
        invoice: Invoice,
        createdAt: OffsetDateTime,
        status: GenerationJobStatus = GenerationJobStatus.COMPLETED,
        jobType: String = "PDF_EXPORT",
        engine: DocumentEngine = DocumentEngine.MUSTANG,
        attempts: Int = 0,
        lastError: String? = null,
        llmStatus: String? = null,
        resultRefs: String? = null,
    ): GenerationJob {
        val j = GenerationJob().apply {
            this.invoice = invoice
            this.status = status
            this.createdAt = createdAt
            this.jobType = jobType
            this.engine = engine
            this.attempts = attempts
            this.lastError = lastError
            this.llmStatus = llmStatus
            this.resultRefs = resultRefs
        }
        em.persist(j)
        return j
    }

    private fun flushAndClear() {
        em.flush()
        em.clear()
    }

    @Test
    @TestTransaction
    fun `findPaged defaults to newest first, tie-broken by id descending`() {
        val recipient = recipient("Acme Corp", "PQIT-C1")
        val invoice = invoice("PQIT-2026-08-00001", recipient)
        val t0 = OffsetDateTime.parse("2026-08-20T08:00:00Z")
        val older = job(invoice, createdAt = t0)
        val newer = job(invoice, createdAt = t0.plusHours(1))
        // same createdAt as `newer`, persisted after it -> higher id, must sort first
        val sameInstantLater = job(invoice, createdAt = t0.plusHours(1))
        flushAndClear()

        // scoped to this test's own recipient — other IT classes in this module commit their own
        // generation_job rows against the same shared Testcontainers Postgres (see class KDoc)
        val rows = printJobOverviewQueryService.findPaged(GenerationJobFilter(recipientId = recipient.id))

        assertEquals(listOf(sameInstantLater.id, newer.id, older.id), rows.map { it.jobId })
    }

    @Test
    @TestTransaction
    fun `statuses filter alone restricts to jobs whose status is in the set`() {
        val recipient = recipient("Kunde AG", "PQIT-C2")
        val invoice = invoice("PQIT-2026-08-00002", recipient)
        val t0 = OffsetDateTime.parse("2026-08-20T08:00:00Z")
        job(invoice, status = GenerationJobStatus.PENDING, createdAt = t0)
        val failed = job(invoice, status = GenerationJobStatus.FAILED, createdAt = t0.plusMinutes(1))
        val completed = job(invoice, status = GenerationJobStatus.COMPLETED, createdAt = t0.plusMinutes(2))
        flushAndClear()

        val rows = printJobOverviewQueryService.findPaged(
            GenerationJobFilter(
                statuses = setOf(GenerationJobStatus.FAILED, GenerationJobStatus.COMPLETED),
                recipientId = recipient.id,
            )
        )

        assertEquals(setOf(failed.id, completed.id), rows.map { it.jobId }.toSet())
    }

    @Test
    @TestTransaction
    fun `recipientId filter alone restricts to jobs of that recipient's invoices`() {
        val r1 = recipient("Acme Corp", "PQIT-C3")
        val r2 = recipient("Beta GmbH", "PQIT-C4")
        val inv1 = invoice("PQIT-2026-08-00003", r1)
        val inv2 = invoice("PQIT-2026-08-00004", r2)
        val t0 = OffsetDateTime.parse("2026-08-20T09:00:00Z")
        val j1 = job(inv1, createdAt = t0)
        job(inv2, createdAt = t0.plusMinutes(1))
        flushAndClear()

        val rows = printJobOverviewQueryService.findPaged(GenerationJobFilter(recipientId = r1.id))

        assertEquals(listOf(j1.id), rows.map { it.jobId })
    }

    @Test
    @TestTransaction
    fun `createdFrom and createdTo filter alone restrict to the inclusive time window`() {
        val recipient = recipient("Zeitfenster GmbH", "PQIT-C5")
        val invoice = invoice("PQIT-2026-08-00005", recipient)
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-01T00:00:00Z"))
        val inWindow = job(invoice, createdAt = OffsetDateTime.parse("2026-08-15T12:00:00Z"))
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-30T00:00:00Z"))
        flushAndClear()

        val rows = printJobOverviewQueryService.findPaged(
            GenerationJobFilter(
                recipientId = recipient.id,
                createdFrom = OffsetDateTime.parse("2026-08-10T00:00:00Z"),
                createdTo = OffsetDateTime.parse("2026-08-20T00:00:00Z"),
            )
        )

        assertEquals(listOf(inWindow.id), rows.map { it.jobId })
    }

    @Test
    @TestTransaction
    fun `combined filters are AND-joined`() {
        val r1 = recipient("Kombi AG", "PQIT-C7")
        val r2 = recipient("Andere AG", "PQIT-C8")
        val inv1 = invoice("PQIT-2026-08-01001", r1)
        val inv2 = invoice("PQIT-2026-08-01002", r2)
        val matching = job(inv1, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-08-15T00:00:00Z"))
        job(inv1, status = GenerationJobStatus.COMPLETED, createdAt = OffsetDateTime.parse("2026-08-15T01:00:00Z")) // wrong status
        job(inv2, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-08-15T02:00:00Z")) // wrong recipient
        job(inv1, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z")) // outside window
        flushAndClear()

        val rows = printJobOverviewQueryService.findPaged(
            GenerationJobFilter(
                statuses = setOf(GenerationJobStatus.FAILED),
                recipientId = r1.id,
                createdFrom = OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                createdTo = OffsetDateTime.parse("2026-08-31T00:00:00Z"),
            )
        )

        assertEquals(listOf(matching.id), rows.map { it.jobId })
    }

    @Test
    @TestTransaction
    fun `offset and limit page through the newest-first ordering`() {
        val recipient = recipient("Paging GmbH", "PQIT-C9")
        val invoice = invoice("PQIT-2026-08-02001", recipient)
        val t0 = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        val jobs = (0 until 5).map { i -> job(invoice, createdAt = t0.plusMinutes(i.toLong())) }
        flushAndClear()
        // newest first: jobs[4], jobs[3], jobs[2], jobs[1], jobs[0]

        val page = printJobOverviewQueryService.findPaged(GenerationJobFilter(recipientId = recipient.id), offset = 2, limit = 2)

        assertEquals(listOf(jobs[2].id, jobs[1].id), page.map { it.jobId })
    }

    @Test
    @TestTransaction
    fun `count matches the number of rows the same filter selects, independent of paging`() {
        val recipient = recipient("Count GmbH", "PQIT-C10")
        val invoice = invoice("PQIT-2026-08-03001", recipient)
        val t0 = OffsetDateTime.parse("2026-08-20T10:00:00Z")
        repeat(7) { i ->
            job(
                invoice,
                status = if (i % 2 == 0) GenerationJobStatus.FAILED else GenerationJobStatus.COMPLETED,
                createdAt = t0.plusMinutes(i.toLong()),
            )
        }
        flushAndClear()

        val filter = GenerationJobFilter(statuses = setOf(GenerationJobStatus.FAILED), recipientId = recipient.id)
        val total = printJobOverviewQueryService.count(filter)
        val allMatching = printJobOverviewQueryService.findPaged(filter, offset = 0, limit = 100)

        assertEquals(4L, total) // i = 0, 2, 4, 6
        assertEquals(4, allMatching.size)
    }

    @Test
    @TestTransaction
    fun `attempts, lastError and llmStatus are read from their own columns`() {
        val recipient = recipient("Diagnose GmbH", "PQIT-C11")
        val invoice = invoice("PQIT-2026-08-04001", recipient)
        val j = job(
            invoice,
            status = GenerationJobStatus.FAILED,
            createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"),
            attempts = 3,
            lastError = "attempt budget exhausted",
            llmStatus = "PENDING",
        )
        flushAndClear()

        val row = printJobOverviewQueryService.findPaged(GenerationJobFilter(recipientId = recipient.id)).single()

        assertEquals(j.id, row.jobId)
        assertEquals(3, row.attempts)
        assertEquals("attempt budget exhausted", row.lastError)
        assertEquals("PENDING", row.llmStatus)
    }

    @Test
    @TestTransaction
    fun `recipientName is null when the invoice has no recipient`() {
        val invoice = invoice("PQIT-2026-08-05001", null)
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"))
        flushAndClear()

        // scoped via a tight createdAt window plus the row's own invoiceNumber, not recipientId —
        // this fixture has no recipient at all
        val row = printJobOverviewQueryService.findPaged(
            GenerationJobFilter(
                createdFrom = OffsetDateTime.parse("2026-08-20T09:59:00Z"),
                createdTo = OffsetDateTime.parse("2026-08-20T10:01:00Z"),
            ),
        ).single { it.invoiceNumber == "PQIT-2026-08-05001" }

        assertNull(row.recipientName)
    }

    // ── Extended row projection (artifact-key fields) ───────────────────────

    @Test
    @TestTransaction
    fun `pdfObjectKey is parsed from resultRefs -- xmlObjectKey is null when there is no explicit zugferdObjectKey entry`() {
        // N12 (coordinator review, 2026-09-11): xmlObjectKey is no longer derived from the pdf
        // key -- an absent zugferdObjectKey must mean no XML artifact, not a guessed one.
        val recipient = recipient("Artefakt GmbH", "PQIT-C12")
        val invoice = invoice("PQIT-2026-08-06001", recipient)
        val j = job(
            invoice,
            createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"),
            resultRefs = """{"objectKey":"documents/2026/re-06001.pdf"}""",
        )
        flushAndClear()

        val row = printJobOverviewQueryService.findPaged(GenerationJobFilter(recipientId = recipient.id)).single()

        assertEquals(j.id, row.jobId)
        assertEquals("documents/2026/re-06001.pdf", row.pdfObjectKey)
        assertNull(row.xmlObjectKey)
    }

    @Test
    @TestTransaction
    fun `xmlObjectKey reads the explicit zugferdObjectKey resultRefs entry`() {
        val recipient = recipient("Artefakt Zwei GmbH", "PQIT-C13")
        val invoice = invoice("PQIT-2026-08-06002", recipient)
        job(
            invoice,
            createdAt = OffsetDateTime.parse("2026-08-20T10:00:00Z"),
            resultRefs = """{"objectKey":"documents/2026/re-06002.pdf","zugferdObjectKey":"documents/2026/re-06002-zugferd.xml"}""",
        )
        flushAndClear()

        val row = printJobOverviewQueryService.findPaged(GenerationJobFilter(recipientId = recipient.id)).single()

        assertEquals("documents/2026/re-06002-zugferd.xml", row.xmlObjectKey)
    }

    // ── Day-summary query ────────────────────────────────────────────────────

    @Test
    @TestTransaction
    fun `findDaySummaries groups jobs by their LOCAL calendar day, newest day first`() {
        val recipient = recipient("Tagesgruppen GmbH", "PQIT-C16")
        val invoice = invoice("PQIT-2026-08-07001", recipient)
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-20T08:00:00Z"))
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-20T14:00:00Z"))
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-21T09:00:00Z"))
        flushAndClear()

        val days = printJobOverviewQueryService.findDaySummaries(GenerationJobFilter(recipientId = recipient.id))

        assertEquals(listOf(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 20)), days.map { it.day })
        assertEquals(1L, days.single { it.day == LocalDate.of(2026, 8, 21) }.jobCount)
        assertEquals(2L, days.single { it.day == LocalDate.of(2026, 8, 20) }.jobCount)
    }

    @Test
    @TestTransaction
    fun `findDaySummaries buckets a job just after Berlin midnight into the next calendar day even though its UTC instant is the previous day`() {
        val recipient = recipient("Zeitzonen GmbH", "PQIT-C17")
        val invoice = invoice("PQIT-2026-08-07002", recipient)
        // 2026-08-20T23:30:00 Europe/Berlin (CEST, UTC+2) == 2026-08-20T21:30:00Z, still the
        // previous UTC calendar day but already 2026-08-20 local — and 2026-08-20T22:05:00Z ==
        // 2026-08-21T00:05:00 CEST, already the NEXT local day despite being the same UTC date.
        val lateEvening = job(invoice, createdAt = OffsetDateTime.parse("2026-08-20T21:30:00Z"))
        val justAfterMidnightLocal = job(invoice, createdAt = OffsetDateTime.parse("2026-08-20T22:05:00Z"))
        flushAndClear()

        val days = printJobOverviewQueryService.findDaySummaries(GenerationJobFilter(recipientId = recipient.id))

        assertEquals(setOf(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 21)), days.map { it.day }.toSet())
        assertEquals(1L, days.single { it.day == LocalDate.of(2026, 8, 20) }.jobCount)
        assertEquals(1L, days.single { it.day == LocalDate.of(2026, 8, 21) }.jobCount)
        assertNotNull(lateEvening.id)
        assertNotNull(justAfterMidnightLocal.id)
    }

    @Test
    @TestTransaction
    fun `findDaySummaries counts FAILED jobs into failedCount, alongside the day's total jobCount`() {
        val recipient = recipient("Fehlgeschlagen GmbH", "PQIT-C18")
        val invoice = invoice("PQIT-2026-08-07003", recipient)
        val day = OffsetDateTime.parse("2026-08-22T10:00:00Z")
        job(invoice, createdAt = day, status = GenerationJobStatus.COMPLETED)
        job(invoice, createdAt = day.plusHours(1), status = GenerationJobStatus.FAILED)
        job(invoice, createdAt = day.plusHours(2), status = GenerationJobStatus.FAILED)
        flushAndClear()

        val summary = printJobOverviewQueryService.findDaySummaries(GenerationJobFilter(recipientId = recipient.id)).single()

        assertEquals(3L, summary.jobCount)
        assertEquals(2L, summary.failedCount)
    }

    @Test
    @TestTransaction
    fun `findDaySummaries honors the same statuses-recipientId-createdFrom-createdTo filter as findPaged`() {
        val r1 = recipient("Filter Tag AG", "PQIT-C19")
        val r2 = recipient("Andere Tag AG", "PQIT-C20")
        val inv1 = invoice("PQIT-2026-08-08001", r1)
        val inv2 = invoice("PQIT-2026-08-08002", r2)
        job(inv1, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-08-15T00:00:00Z"))
        job(inv1, status = GenerationJobStatus.COMPLETED, createdAt = OffsetDateTime.parse("2026-08-15T01:00:00Z")) // wrong status
        job(inv2, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-08-15T02:00:00Z")) // wrong recipient
        job(inv1, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z")) // outside window
        flushAndClear()

        val days = printJobOverviewQueryService.findDaySummaries(
            GenerationJobFilter(
                statuses = setOf(GenerationJobStatus.FAILED),
                recipientId = r1.id,
                createdFrom = OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                createdTo = OffsetDateTime.parse("2026-08-31T00:00:00Z"),
            )
        )

        assertEquals(1, days.size)
        assertEquals(1L, days.single().jobCount)
        assertEquals(1L, days.single().failedCount)
    }

    /**
     * F3 (fix-job-panel-flicker-and-vanishing-entry) — [PrintJobListView] used to derive its
     * grid/empty-state visibility from a separate [PrintJobOverviewQueryService.count] round trip
     * while the grid itself rendered [PrintJobOverviewQueryService.findDaySummaries]; the fix made
     * `findDaySummaries` alone the single source of truth. This pins the precondition that fix
     * relies on against a REAL Postgres, not mocks: for the identical filter, the two queries must
     * always agree — both on non-emptiness (so "is anything shown at all" can never differ between
     * them) and on the total row count (`count`'s own total must equal the sum of every day's
     * `jobCount` from `findDaySummaries`), across several representative filters, not just the
     * unfiltered case.
     */
    @Test
    @TestTransaction
    fun `count and findDaySummaries agree on non-emptiness and total row count for the same filter`() {
        val r1 = recipient("Übereinstimmung AG", "PQIT-C22")
        val r2 = recipient("Übereinstimmung Andere AG", "PQIT-C23")
        val inv1 = invoice("PQIT-2026-08-10001", r1)
        val inv2 = invoice("PQIT-2026-08-10002", r2)
        job(inv1, status = GenerationJobStatus.COMPLETED, createdAt = OffsetDateTime.parse("2026-08-24T08:00:00Z"))
        job(inv1, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-08-24T14:00:00Z"))
        job(inv1, status = GenerationJobStatus.FAILED, createdAt = OffsetDateTime.parse("2026-08-25T09:00:00Z"))
        job(inv2, status = GenerationJobStatus.COMPLETED, createdAt = OffsetDateTime.parse("2026-08-24T10:00:00Z"))
        flushAndClear()

        fun assertAgreement(filter: GenerationJobFilter) {
            val total = printJobOverviewQueryService.count(filter)
            val days = printJobOverviewQueryService.findDaySummaries(filter)
            assertEquals(total > 0, days.isNotEmpty(), "non-emptiness must agree for filter=$filter")
            assertEquals(total, days.sumOf { it.jobCount }, "total row count must agree for filter=$filter")
        }

        // Unfiltered (scoped to both of this test's own recipients via the date window, since
        // this module's shared Testcontainers Postgres carries other IT classes' committed rows —
        // see this class's own KDoc).
        val window = GenerationJobFilter(
            createdFrom = OffsetDateTime.parse("2026-08-24T00:00:00Z"),
            createdTo = OffsetDateTime.parse("2026-08-26T00:00:00Z"),
        )
        assertAgreement(window)
        // Narrowed to one recipient.
        assertAgreement(window.copy(recipientId = r1.id))
        // Narrowed to a status set that excludes everything (still zero on both sides).
        assertAgreement(window.copy(recipientId = r1.id, statuses = setOf(GenerationJobStatus.PENDING)))
        // Narrowed to a status set with matches.
        assertAgreement(window.copy(recipientId = r1.id, statuses = setOf(GenerationJobStatus.FAILED)))
        // A window with no rows in it at all.
        assertAgreement(
            GenerationJobFilter(
                recipientId = r1.id,
                createdFrom = OffsetDateTime.parse("2020-01-01T00:00:00Z"),
                createdTo = OffsetDateTime.parse("2020-01-02T00:00:00Z"),
            ),
        )
    }

    @Test
    @TestTransaction
    fun `findPaged narrowed to one day's own createdFrom-createdTo bounds returns exactly that day's jobs`() {
        val recipient = recipient("Kindzeilen GmbH", "PQIT-C21")
        val invoice = invoice("PQIT-2026-08-09001", recipient)
        val day = LocalDate.of(2026, 8, 23)
        val inDay = job(invoice, createdAt = OffsetDateTime.parse("2026-08-23T06:00:00Z"))
        // 2026-08-23 (Berlin, CEST/UTC+2) spans [2026-08-22T22:00:00Z, 2026-08-23T22:00:00Z) —
        // these two instants sit just outside that window on either side.
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-22T19:00:00Z")) // previous Berlin day (21:00 CEST)
        job(invoice, createdAt = OffsetDateTime.parse("2026-08-23T22:30:00Z")) // next Berlin day (00:30 CEST, 08-24)
        flushAndClear()

        val zone = de.littletrotter.shared.GermanTime.ZONE
        val start = day.atStartOfDay(zone).toOffsetDateTime()
        val end = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime().minusNanos(1)

        val rows = printJobOverviewQueryService.findPaged(
            GenerationJobFilter(recipientId = recipient.id, createdFrom = start, createdTo = end),
        )

        assertEquals(listOf(inDay.id), rows.map { it.jobId })
    }
}
