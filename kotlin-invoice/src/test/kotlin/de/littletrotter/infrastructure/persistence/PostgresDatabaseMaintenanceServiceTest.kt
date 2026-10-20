package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.system.MaintenanceJobQueued
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.fixture.edit
import jakarta.enterprise.event.Event
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * [PostgresDatabaseMaintenanceService.currentPostgresMaintenance]'s own `toText` column
 * decoder and its "not actually an Object[] row" fallback have no real-Postgres path that reaches
 * them: `pg_stat_user_tables`/`pg_stat_archiver` are always empty on a fresh Testcontainers
 * database (nothing has ever been vacuumed/archived there), so every `PostgresDatabaseMaintenanceServiceIT`
 * call sees only `null` cells, and a real 4-column native aggregate query always comes back as
 * `Object[]`, never anything [toText]'s `else` branch or the row-shape guard's `else -> null` could
 * see. Both are still real defensive code (a future single-column rewrite of either query, or a
 * JDBC driver returning a boxed scalar instead of an array for a one-row aggregate, would hit
 * them), so this drives them directly against a mocked [EntityManager] instead of deleting them.
 */
class PostgresDatabaseMaintenanceServiceTest {

    private fun entityManager(aggregateResult: Any?, archiveResult: Any?): EntityManager {
        val em = mock<EntityManager>()
        val aggregateQuery = mock<Query>()
        whenever(aggregateQuery.singleResult).thenReturn(aggregateResult)
        whenever(em.createNativeQuery(argThat<String> { contains("pg_stat_user_tables") })).thenReturn(aggregateQuery)

        val archiveQuery = mock<Query>()
        whenever(archiveQuery.singleResult).thenReturn(archiveResult)
        whenever(em.createNativeQuery(argThat<String> { contains("pg_stat_archiver") })).thenReturn(archiveQuery)

        val eventQuery = mock<TypedQuery<DatabaseMaintenanceEvent>>()
        whenever(
            em.createQuery(argThat<String> { contains("DatabaseMaintenanceEvent") }, eq(DatabaseMaintenanceEvent::class.java)),
        ).thenReturn(eventQuery)
        // resultList defaults to an empty list on an un-stubbed TypedQuery mock, so both
        // latestMaintenanceEvent(REINDEX/BACKUP) calls resolve to null without extra stubbing.

        return em
    }

    private fun service(em: EntityManager, maintenanceJobQueued: Event<MaintenanceJobQueued> = mock()) = PostgresDatabaseMaintenanceService(
        em = em,
        invoices = MaintenanceInvoiceAccess(mock(), mock()),
        invoiceLifecycleService = mock(),
        generationJob = GenerationJobDependencies(mock(), mock()),
        documentStore = mock(),
        maintenanceJobQueued = maintenanceJobQueued,
        time = de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
    )

    @Test
    fun `happy path - decodes every native maintenance column type toText can receive`() {
        val aggregateRow = arrayOf<Any?>(
            Timestamp.valueOf("2026-01-01 08:00:00"),
            LocalDateTime.of(2026, 1, 2, 9, 0, 0),
            OffsetDateTime.parse("2026-01-03T10:00:00Z"),
            "2026-01-04 11:00:00",
        )
        val em = entityManager(aggregateResult = aggregateRow, archiveResult = 12345)

        val status = service(em).currentPostgresMaintenance()

        assertEquals("2026-01-01 08:00:00", status.lastVacuum, "a java.sql.Timestamp cell")
        assertEquals("2026-01-02 09:00:00", status.lastAutoVacuum, "a LocalDateTime cell")
        assertEquals("2026-01-03 10:00:00", status.lastAnalyze, "an OffsetDateTime cell")
        assertEquals("2026-01-04 11:00:00", status.lastAutoAnalyze, "a String cell is returned unformatted")
        // No BACKUP maintenance event is recorded (the event query is stubbed empty), so the
        // fallback to the archiver's own last_archived_time applies — an Int here exercises
        // toText's `else -> value.toString()` branch.
        assertEquals("12345", status.lastBackup)
    }

    @Test
    fun `boundary - a non-array aggregate row decodes every maintenance column to null instead of throwing`() {
        // A real 4-column native aggregate query always comes back as Object[]; this simulates a
        // driver/rewrite that instead hands back a single boxed scalar.
        val em = entityManager(aggregateResult = 42L, archiveResult = null)

        val status = service(em).currentPostgresMaintenance()

        assertNull(status.lastVacuum)
        assertNull(status.lastAutoVacuum)
        assertNull(status.lastAnalyze)
        assertNull(status.lastAutoAnalyze)
    }

    // recordMaintenanceEvent's `event.status == PENDING && event.type in
    // QUEUED_MAINTENANCE_TYPES` guard: every call site elsewhere always pairs PENDING with a queued
    // type (queueRecalculation/queueIssuedGenerationJobsSweep) or a non-PENDING status with either
    // type (recordStandaloneOutcome, the REINDEX/BACKUP scheduler calls), so the
    // "PENDING but NOT a queued type" combination -- the `&&`'s second operand evaluating false --
    // had no test of its own.
    @Test
    fun `boundary - recordMaintenanceEvent never fires the queue trigger for a PENDING event of a non-queued type`() {
        val em = mock<EntityManager>()
        val maintenanceJobQueued = mock<Event<MaintenanceJobQueued>>()

        service(em, maintenanceJobQueued).recordMaintenanceEvent(
            DatabaseMaintenanceEvent(
                type = DatabaseMaintenanceType.REINDEX,
                status = DatabaseMaintenanceStatus.PENDING,
                details = "COV-38 non-queued PENDING event",
                createdAt = OffsetDateTime.now(),
            ),
        )

        verify(em).persist(org.mockito.kotlin.any<DatabaseMaintenanceEvent>())
        verifyNoInteractions(maintenanceJobQueued)
    }

    @Test
    fun `happy path - recordMaintenanceEvent fires the queue trigger for a PENDING event of a queued type`() {
        val em = mock<EntityManager>()
        val maintenanceJobQueued = mock<Event<MaintenanceJobQueued>>()
        val event = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
            status = DatabaseMaintenanceStatus.PENDING,
            details = "COV-45 queued PENDING event",
            createdAt = OffsetDateTime.now(),
        )
        // A real Postgres assigns the id inside em.persist() itself (IDENTITY generation); this
        // mock simulates that assignment so the guarded `event.id ?: error(...)` sees a real value.
        whenever(em.persist(org.mockito.kotlin.any<DatabaseMaintenanceEvent>())).then { event.edit { id = 42L } }

        service(em, maintenanceJobQueued).recordMaintenanceEvent(event)

        verify(maintenanceJobQueued).fire(MaintenanceJobQueued(42L))
    }

    @Test
    fun `boundary - recordMaintenanceEvent fails loudly when persist leaves the queued event without an id`() {
        // event.id stays null once persist() itself does not assign one, e.g. on a
        // driver/mapping defect. A real Postgres always assigns an IDENTITY id, so this branch is
        // only reachable through a mocked EntityManager.
        val em = mock<EntityManager>()
        val maintenanceJobQueued = mock<Event<MaintenanceJobQueued>>()

        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            service(em, maintenanceJobQueued).recordMaintenanceEvent(
                DatabaseMaintenanceEvent(
                    type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
                    status = DatabaseMaintenanceStatus.PENDING,
                    details = "COV-45 queued PENDING event without an id",
                    createdAt = OffsetDateTime.now(),
                ),
            )
        }
        assertEquals("Persisted maintenance request has no id", ex.message)
    }

    // ── claimOldestPendingMaintenanceJob — the candidate.id null guard ────────────────────────

    @Test
    fun `boundary - claimOldestPendingMaintenanceJob returns null when the claimed candidate has no id`() {
        // a real query result always carries its row's own persisted id; only a mocked
        // EntityManager can hand back a candidate whose id is null.
        val em = mock<EntityManager>()
        val candidate = DatabaseMaintenanceEvent(
            type = DatabaseMaintenanceType.RECALCULATE_TOTALS,
            status = DatabaseMaintenanceStatus.PENDING,
            details = "no id yet",
            createdAt = OffsetDateTime.now(),
        )
        val selectQuery = mock<TypedQuery<DatabaseMaintenanceEvent>>(defaultAnswer = org.mockito.Mockito.RETURNS_SELF)
        whenever(selectQuery.resultList).thenReturn(listOf(candidate))
        whenever(em.createQuery(argThat<String> { contains("SELECT e FROM DatabaseMaintenanceEvent") }, eq(DatabaseMaintenanceEvent::class.java)))
            .thenReturn(selectQuery)

        val result = service(em).claimOldestPendingMaintenanceJob()

        assertNull(result)
    }

    // ── describeCause — reflection-driven since it is private and has no other direct caller ────

    private fun describeCause(ex: Exception): String {
        val m = PostgresDatabaseMaintenanceService::class.java.getDeclaredMethod("describeCause", Exception::class.java)
        m.isAccessible = true
        return m.invoke(service(mock()), ex) as String
    }

    @Test
    fun `boundary - describeCause names just the exception class when the message is null`() {
        assertEquals("IllegalStateException", describeCause(IllegalStateException()))
    }

    @Test
    fun `boundary - describeCause names just the exception class when the message is blank`() {
        assertEquals("IllegalStateException", describeCause(IllegalStateException("   ")))
    }

    @Test
    fun `boundary - describeCause appends the message's first line for a multi-line message`() {
        assertEquals(
            "IllegalStateException: first line",
            describeCause(IllegalStateException("first line\nsecond line")),
        )
    }
}
