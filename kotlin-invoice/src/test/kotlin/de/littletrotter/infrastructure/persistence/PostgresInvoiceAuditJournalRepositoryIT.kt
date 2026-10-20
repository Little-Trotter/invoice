package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditChainVerification
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalChain
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import de.littletrotter.domain.compliance.verifyInvoiceAuditChainBatch
import de.littletrotter.domain.repository.InvoiceAuditActionFilter
import de.littletrotter.domain.repository.InvoiceAuditJournalRepository
import de.littletrotter.domain.repository.InvoiceAuditRowFilter
import de.littletrotter.domain.shared.SortDirection
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * [PostgresInvoiceAuditJournalRepository]'s direction-aware `ORDER BY seq` against a real
 * Postgres. Rows are inserted directly rather than through [de.littletrotter.application.invoice
 * .InvoiceLifecycleService]: `invoice_audit_journal` has no FK on `invoice_id` and no INSERT guard
 * (only the append-only UPDATE/DELETE/TRUNCATE guard — `V1__baseline_schema.sql` section 13/14),
 * so this gets full, deterministic control over `occurred_at` without depending on
 * `capture_invoice_audit_event()`'s own `clock_timestamp()` timing.
 *
 * B8's own point — two rows sharing an `occurred_at` still come back in a stable, seq-defined
 * order both ways — is only provable this way: two triggered writes would each get their own
 * `clock_timestamp()` and are very unlikely to collide.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PostgresInvoiceAuditJournalRepositoryIT {

    @Inject
    lateinit var repository: InvoiceAuditJournalRepository

    @Inject
    lateinit var em: EntityManager

    /** Positive, monotonically increasing, unique per test run — `invoice_id` has no FK (see class
     *  KDoc) but does have `CHECK (invoice_id > 0)`. */
    private fun freshInvoiceId(): Long = BASE_ID.incrementAndGet()

    private fun insertRow(
        invoiceId: Long,
        invoiceNumber: String,
        occurredAt: OffsetDateTime,
        chainTag: String,
        actor: String = "it-test",
    ) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                """
                INSERT INTO invoice_audit_journal
                    (invoice_id, invoice_number, actor, actor_name, occurred_at, event, action, business_tx, detail, prev_hash, entry_hash)
                VALUES
                    (:invoiceId, :invoiceNumber, :actor, 'IT Test', :occurredAt, 0, 0, :invoiceId, CAST(:detail AS JSONB), :prevHash, :entryHash)
                """,
            )
                .setParameter("invoiceId", invoiceId)
                .setParameter("invoiceNumber", invoiceNumber)
                .setParameter("actor", actor)
                .setParameter("occurredAt", occurredAt)
                .setParameter("detail", "{}")
                .setParameter("prevHash", "genesis-$chainTag")
                .setParameter("entryHash", "hash-$invoiceNumber")
                .executeUpdate()
        }
    }

    @Test
    fun `findByInvoiceId orders by seq, not occurred_at - rows sharing one occurred_at still come back in a stable order both ways`() {
        val invoiceId = freshInvoiceId()
        val sameInstant = OffsetDateTime.now()
        insertRow(invoiceId, "IT-B8-1", sameInstant, "b8")
        insertRow(invoiceId, "IT-B8-2", sameInstant, "b8")
        insertRow(invoiceId, "IT-B8-3", sameInstant, "b8")

        val ascending = repository.findByInvoiceId(invoiceId, 0, 10, SortDirection.ASC)
        val descending = repository.findByInvoiceId(invoiceId, 0, 10, SortDirection.DESC)

        assertEquals(listOf("IT-B8-1", "IT-B8-2", "IT-B8-3"), ascending.map { it.invoiceNumber })
        assertEquals(listOf("IT-B8-3", "IT-B8-2", "IT-B8-1"), descending.map { it.invoiceNumber })
        // Every row shares the exact same occurred_at — the (opposite) orderings above can only
        // come from seq, proving occurred_at plays no part in either.
        assertEquals(1, ascending.map { it.occurredAt }.toSet().size)
    }

    @Test
    fun `findByInvoiceId paging combines correctly with direction - page 2 ascending is not page 2 of the unsorted order`() {
        val invoiceId = freshInvoiceId()
        val base = OffsetDateTime.now()
        (1..5).forEach { i -> insertRow(invoiceId, "IT-PG-$i", base.plusSeconds(i.toLong()), "pg") }

        val page0Asc = repository.findByInvoiceId(invoiceId, 0, 2, SortDirection.ASC)
        val page1Asc = repository.findByInvoiceId(invoiceId, 1, 2, SortDirection.ASC)
        val page0Desc = repository.findByInvoiceId(invoiceId, 0, 2, SortDirection.DESC)

        assertEquals(listOf("IT-PG-1", "IT-PG-2"), page0Asc.map { it.invoiceNumber })
        assertEquals(listOf("IT-PG-3", "IT-PG-4"), page1Asc.map { it.invoiceNumber }, "page 2 ascending must continue from page 1, not restart from the unsorted order")
        assertEquals(listOf("IT-PG-5", "IT-PG-4"), page0Desc.map { it.invoiceNumber })
    }

    /** Same `INSERT` shape as [insertRow], but for a caller that also wants to set
     *  `action`/`business_tx` explicitly (the grouped queries key off both). */
    private fun insertActionRow(
        invoiceId: Long,
        invoiceNumber: String,
        occurredAt: OffsetDateTime,
        action: Int,
        businessTx: Long,
        seqTag: String,
    ) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                """
                INSERT INTO invoice_audit_journal
                    (invoice_id, invoice_number, actor, actor_name, occurred_at, event, action, business_tx, detail, prev_hash, entry_hash)
                VALUES
                    (:invoiceId, :invoiceNumber, 'it-test', 'IT Test', :occurredAt, 0, :action, :businessTx, CAST(:detail AS JSONB), :prevHash, :entryHash)
                """,
            )
                .setParameter("invoiceId", invoiceId)
                .setParameter("invoiceNumber", invoiceNumber)
                .setParameter("occurredAt", occurredAt)
                .setParameter("action", action)
                .setParameter("businessTx", businessTx)
                .setParameter("detail", "{}")
                .setParameter("prevHash", "genesis-$seqTag")
                .setParameter("entryHash", "hash-$seqTag-$invoiceId-${occurredAt.nano}")
                .executeUpdate()
        }
    }

    // ── an action's technical rows never split across a page ────────────────────────

    @Test
    fun `findActionGroupsByInvoiceId groups technical rows by business_tx - one line however many rows it has`() {
        val invoiceId = freshInvoiceId()
        val businessTx = freshInvoiceId()
        val base = OffsetDateTime.now()
        // 12 technical rows, all sharing one business_tx — the group must still be exactly one line.
        (1..12).forEach { i -> insertActionRow(invoiceId, "IT-B7", base.plusSeconds(i.toLong()), action = 5, businessTx = businessTx, seqTag = "b7-$i") }

        val groups = repository.findActionGroupsByInvoiceId(invoiceId, 0, 10, SortDirection.DESC)

        assertEquals(1, groups.size, "12 rows of one business_tx must collapse into exactly one group")
        assertEquals(12L, groups.single().rowCount)
        assertEquals(businessTx, groups.single().businessTx)
    }

    @Test
    fun `findActionGroupsByInvoiceId pages by action, not by row - a 12-row action never splits across pages`() {
        val invoiceId = freshInvoiceId()
        val bigTx = freshInvoiceId()
        val base = OffsetDateTime.now()
        (1..12).forEach { i -> insertActionRow(invoiceId, "IT-B7-PG", base.plusSeconds(i.toLong()), action = 5, businessTx = bigTx, seqTag = "b7pg-$i") }
        // A second, smaller action, strictly after the big one.
        val smallTx = freshInvoiceId()
        insertActionRow(invoiceId, "IT-B7-PG", base.plusSeconds(100), action = 7, businessTx = smallTx, seqTag = "b7pg-small")

        val page0 = repository.findActionGroupsByInvoiceId(invoiceId, 0, 1, SortDirection.ASC)
        val page1 = repository.findActionGroupsByInvoiceId(invoiceId, 1, 1, SortDirection.ASC)

        assertEquals(bigTx, page0.single().businessTx)
        assertEquals(12L, page0.single().rowCount, "the whole 12-row action stayed on page 0, none of it leaked onto page 1")
        assertEquals(smallTx, page1.single().businessTx)
    }

    // ── cancellation rows live on two invoices ─────────────────────────────────────────────

    @Test
    fun `a cancellation business_tx spans two invoices - the per-invoice group only ever counts its own side`() {
        val originalId = freshInvoiceId()
        val creditNoteId = freshInvoiceId()
        val cancellationTx = freshInvoiceId()
        val now = OffsetDateTime.now()
        insertActionRow(originalId, "IT-B4-ORIG", now, action = 12, businessTx = cancellationTx, seqTag = "b4-orig")
        insertActionRow(creditNoteId, "IT-B4-CREDIT", now.plusSeconds(1), action = 12, businessTx = cancellationTx, seqTag = "b4-credit")

        val originalGroups = repository.findActionGroupsByInvoiceId(originalId, 0, 10, SortDirection.DESC)
        val creditNoteGroups = repository.findActionGroupsByInvoiceId(creditNoteId, 0, 10, SortDirection.DESC)

        assertEquals(1L, originalGroups.single { it.businessTx == cancellationTx }.rowCount, "the original's own group sees only its own row")
        assertEquals(1L, creditNoteGroups.single { it.businessTx == cancellationTx }.rowCount, "the credit note's own group sees only its own row")

        val bothRows = repository.findByBusinessTx(listOf(cancellationTx))
        assertEquals(setOf(originalId, creditNoteId), bothRows.map { it.invoiceId }.toSet(), "unscoped, the same business_tx yields both invoices' rows")

        val originalOnly = repository.findByBusinessTx(listOf(cancellationTx), invoiceId = originalId)
        assertEquals(listOf(originalId), originalOnly.map { it.invoiceId }, "scoped by invoiceId, only that invoice's own row comes back")
    }

    @Test
    fun `findPage (global) honours direction the same way findByInvoiceId does`() {
        val invoiceId = freshInvoiceId()
        val base = OffsetDateTime.now()
        // The journal is shared by every IT in the run: an unfiltered ascending page would hold the
        // oldest rows of the whole table, not these two. The actor filter scopes the page to this test.
        val actor = "it-global-$invoiceId"
        insertRow(invoiceId, "IT-GLOBAL-$invoiceId-1", base, "global", actor)
        insertRow(invoiceId, "IT-GLOBAL-$invoiceId-2", base.plusSeconds(1), "global", actor)

        val ascending = repository.findPage(0, 10, InvoiceAuditRowFilter(actor = actor), direction = SortDirection.ASC)
        val descending = repository.findPage(0, 10, InvoiceAuditRowFilter(actor = actor), direction = SortDirection.DESC)

        assertEquals(listOf("IT-GLOBAL-$invoiceId-1", "IT-GLOBAL-$invoiceId-2"), ascending.map { it.invoiceNumber })
        assertEquals(listOf("IT-GLOBAL-$invoiceId-2", "IT-GLOBAL-$invoiceId-1"), descending.map { it.invoiceNumber })
    }

    @Test
    fun `boundary - findPage and countPage with an entirely empty filter build a WHERE-less query and still find a freshly inserted row`() {
        // every other findPage/countPage fixture sets at least one filter dimension;
        // WhereBuilder.clause()'s own "no conditions at all" branch is otherwise never reached.
        // The journal is shared across the whole IT run, so this only asserts a lower bound, not
        // an exact count (no unique dimension to scope an unfiltered query by).
        val invoiceId = freshInvoiceId()
        insertRow(invoiceId, "IT-NOFILTER-$invoiceId", OffsetDateTime.now(), "unfiltered", "it-nofilter-$invoiceId")

        val page = repository.findPage(pageIndex = 0, pageSize = 1000, filter = InvoiceAuditRowFilter())
        val count = repository.countPage(InvoiceAuditRowFilter())

        assertTrue(page.any { it.invoiceNumber == "IT-NOFILTER-$invoiceId" }, "unfiltered page must include the freshly inserted row")
        assertTrue(count >= 1L)
    }

    // ── hash chain round trip and filter combinations ─────────────────────────────────

    private fun nextSeq(): Long = QuarkusTransaction.requiringNew().call {
        (em.createNativeQuery("SELECT NEXTVAL('invoice_audit_journal_seq')").singleResult as Number).toLong()
    }

    /** A draft row with an explicit, pre-drawn `seq` — the only way to know `seq` up front and so
     *  compute a real `entry_hash` in Kotlin before the (append-only) INSERT. */
    private fun draft(seq: Long, invoiceId: Long, prevHash: String): InvoiceAuditJournalEntry =
        InvoiceAuditJournalEntry(
            seq = seq,
            businessTx = invoiceId,
            invoiceId = invoiceId,
            invoiceNumber = "IT-CH-$invoiceId",
            actor = "it-chain-$invoiceId",
            actorName = "IT Test",
            occurredAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS),
            event = InvoiceAuditEvent.INVOICE_UPDATED,
            action = InvoiceAuditAction.HEADER_CHANGED,
            detail = "{}",
            documentSha256 = null,
            prevHash = prevHash,
            entryHash = "",
        )

    private fun chained(entry: InvoiceAuditJournalEntry): InvoiceAuditJournalEntry =
        entry.copy(entryHash = InvoiceAuditJournalChain.computeEntryHash(entry.prevHash, entry))

    private fun persist(entry: InvoiceAuditJournalEntry) {
        QuarkusTransaction.requiringNew().run {
            em.createNativeQuery(
                """
                INSERT INTO invoice_audit_journal
                    (seq, invoice_id, invoice_number, actor, actor_name, occurred_at, event, action, business_tx, detail,
                     document_sha256, prev_hash, entry_hash)
                VALUES
                    (:seq, :invoiceId, :invoiceNumber, :actor, :actorName, :occurredAt, :event, :action, :businessTx,
                     CAST(:detail AS JSONB), CAST(:documentSha256 AS VARCHAR), :prevHash, :entryHash)
                """,
            )
                .setParameter("seq", entry.seq)
                .setParameter("invoiceId", entry.invoiceId)
                .setParameter("invoiceNumber", entry.invoiceNumber)
                .setParameter("actor", entry.actor)
                .setParameter("actorName", entry.actorName)
                .setParameter("occurredAt", entry.occurredAt)
                .setParameter("event", entry.event.ordinal)
                .setParameter("action", entry.action.ordinal)
                .setParameter("businessTx", entry.businessTx)
                .setParameter("detail", entry.detail)
                .setParameter("documentSha256", entry.documentSha256)
                .setParameter("prevHash", entry.prevHash)
                .setParameter("entryHash", entry.entryHash)
                .executeUpdate()
        }
    }

    /** Persists [count] correctly chained rows for [invoiceId], starting from [anchor]. */
    private fun persistChain(invoiceId: Long, anchor: String, count: Int): List<InvoiceAuditJournalEntry> {
        val rows = mutableListOf<InvoiceAuditJournalEntry>()
        var prev = anchor
        repeat(count) { i ->
            // Alternating business_tx (two actions) and one document event with a document hash.
            val row = chained(
                draft(nextSeq(), invoiceId, prev).copy(
                    businessTx = invoiceId + i % 2,
                    documentSha256 = if (i == 1) "a".repeat(DOCUMENT_HASH_LENGTH) else null,
                ),
            )
            persist(row)
            rows += row
            prev = row.entryHash
        }
        return rows
    }

    @Test
    fun `happy path - a correctly chained segment read back through the adapter verifies intact`() {
        val invoiceId = freshInvoiceId()
        val anchor = "anchor-$invoiceId"
        val written = persistChain(invoiceId, anchor, 3)

        val viaInvoice = repository.streamByInvoiceId(invoiceId, afterSeq = 0, batchSize = 10)
        val viaCursor = repository.streamBatch(afterSeq = written.first().seq - 1, batchSize = 3)
        val step = verifyInvoiceAuditChainBatch(anchor, viaInvoice)

        assertEquals(written.map { it.seq }, viaInvoice.map { it.seq })
        assertEquals(viaInvoice, viaCursor, "the table-wide cursor must yield the same rows in the same seq order")
        assertEquals(InvoiceAuditChainVerification.Valid, step.verification)
        assertEquals(3L, step.checked)
        assertEquals(written.last().entryHash, step.nextPrevHash)
        assertEquals(3L, repository.countByInvoiceId(invoiceId))
        assertEquals(2L, repository.countActionGroupsByInvoiceId(invoiceId), "3 rows over 2 business_tx are 2 actions")
        assertEquals(written.map { it.seq }, repository.streamAll().filter { it.invoiceId == invoiceId }.map { it.seq })
        assertEquals(listOf(written[2].seq), repository.streamByInvoiceId(invoiceId, afterSeq = written[1].seq, batchSize = 10).map { it.seq })
        assertEquals(2, repository.streamByInvoiceId(invoiceId, afterSeq = 0, batchSize = 2).size, "batchSize caps the batch")
    }

    @Test
    fun `boundary - a tampered row is detected at its own seq and the database refuses an in-place update`() {
        val invoiceId = freshInvoiceId()
        val anchor = "anchor-$invoiceId"
        val first = chained(draft(nextSeq(), invoiceId, anchor))
        persist(first)
        // Hash computed over the genuine content, stored with a forged actor: what a bypassed trigger leaves behind.
        val genuine = chained(draft(nextSeq(), invoiceId, first.entryHash))
        persist(genuine.copy(actor = "forged-$invoiceId"))

        assertThrows(Exception::class.java) {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("UPDATE invoice_audit_journal SET actor = 'x' WHERE seq = :seq")
                    .setParameter("seq", first.seq)
                    .executeUpdate()
            }
        }

        val rows = repository.streamByInvoiceId(invoiceId, afterSeq = 0, batchSize = 10)
        val step = verifyInvoiceAuditChainBatch(anchor, rows)

        assertEquals(first.actor, rows.first().actor, "the refused UPDATE must leave the row unchanged")
        assertEquals(genuine.seq, (step.verification as InvoiceAuditChainVerification.Broken).atSeq)
        assertTrue(step.verification.toString().contains("entry_hash"))
        assertEquals(1L, step.checked)
        assertEquals(first.entryHash, step.nextPrevHash, "the chain head must not advance past the break")
    }

    @Test
    fun `boundary - a deleted row is detected at its successor and the database refuses a delete`() {
        val invoiceId = freshInvoiceId()
        val anchor = "anchor-$invoiceId"
        val first = chained(draft(nextSeq(), invoiceId, anchor))
        val missing = chained(draft(nextSeq(), invoiceId, first.entryHash))
        val third = chained(draft(nextSeq(), invoiceId, missing.entryHash))
        persist(first)
        persist(third)

        assertThrows(Exception::class.java) {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("DELETE FROM invoice_audit_journal WHERE seq = :seq")
                    .setParameter("seq", first.seq)
                    .executeUpdate()
            }
        }

        val rows = repository.streamByInvoiceId(invoiceId, afterSeq = 0, batchSize = 10)
        val step = verifyInvoiceAuditChainBatch(anchor, rows)

        assertEquals(listOf(first.seq, third.seq), rows.map { it.seq }, "the refused DELETE must leave the row in place")
        assertEquals(third.seq, (step.verification as InvoiceAuditChainVerification.Broken).atSeq)
        assertTrue(step.verification.toString().contains("prevHash"))
        assertEquals(1L, step.checked)
    }

    @Test
    fun `boundary - an empty journal slice verifies as intact and every read returns nothing`() {
        val invoiceId = freshInvoiceId()
        val anchor = "anchor-$invoiceId"

        val rows = repository.streamByInvoiceId(invoiceId, afterSeq = 0, batchSize = 10)
        val step = verifyInvoiceAuditChainBatch(anchor, rows)

        assertTrue(rows.isEmpty())
        assertEquals(InvoiceAuditChainVerification.Valid, step.verification)
        assertEquals(0L, step.checked)
        assertEquals(anchor, step.nextPrevHash)
        assertTrue(repository.streamBatch(afterSeq = nextSeq(), batchSize = 10).isEmpty(), "nothing lies beyond the newest seq")
        assertTrue(repository.findByBusinessTx(emptyList()).isEmpty())
        assertTrue(repository.findActionGroupsByInvoiceId(invoiceId, 0, 10).isEmpty())
        assertEquals(0L, repository.countByInvoiceId(invoiceId))
        assertEquals(0L, repository.countActionGroupsByInvoiceId(invoiceId))
    }

    @Test
    fun `boundary - row filters combine invoice number, actor, event and a half-open date window`() {
        val invoiceId = freshInvoiceId()
        val base = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val template = draft(0, invoiceId, "anchor-$invoiceId")
        val created = template.copy(seq = nextSeq(), event = InvoiceAuditEvent.INVOICE_CREATED, occurredAt = base.plusHours(1))
        val match = template.copy(seq = nextSeq(), occurredAt = base.plusHours(1))
        val atUpperBound = template.copy(seq = nextSeq(), occurredAt = base.plusHours(2))
        val beforeWindow = template.copy(seq = nextSeq(), occurredAt = base)
        val otherNumber = template.copy(seq = nextSeq(), invoiceNumber = "IT-OTHER-$invoiceId", occurredAt = base.plusHours(1))
        listOf(created, match, atUpperBound, beforeWindow, otherNumber).forEach(::persist)

        val filter = InvoiceAuditRowFilter(
            invoiceNumber = template.invoiceNumber,
            actor = template.actor,
            event = InvoiceAuditEvent.INVOICE_UPDATED,
            occurredFrom = base.plusHours(1),
            occurredTo = base.plusHours(2),
        )
        val page = repository.findPage(pageIndex = 0, pageSize = 10, filter = filter)
        val count = repository.countPage(filter)

        assertEquals(listOf(match.seq), page.map { it.seq }, "from is inclusive, to is exclusive, every filter narrows")
        assertEquals(1L, count)
        assertEquals(5L, repository.countPage(InvoiceAuditRowFilter(actor = template.actor)), "the actor filter alone matches all five rows")
    }

    @Test
    fun `boundary - action-group filters combine an action allow-list, invoice number, actor and date window`() {
        val invoiceId = freshInvoiceId()
        val base = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val template = draft(0, invoiceId, "anchor-$invoiceId").copy(occurredAt = base.plusHours(1))
        val itemAdded = template.copy(seq = nextSeq(), action = InvoiceAuditAction.ITEM_ADDED, businessTx = invoiceId * 10 + 1)
        val itemChanged1 = template.copy(seq = nextSeq(), action = InvoiceAuditAction.ITEM_CHANGED, businessTx = invoiceId * 10 + 2)
        val itemChanged2 = itemChanged1.copy(seq = nextSeq())
        val cancellation = template.copy(seq = nextSeq(), action = InvoiceAuditAction.CANCELLATION, businessTx = invoiceId * 10 + 3)
        val lateItem = itemAdded.copy(seq = nextSeq(), businessTx = invoiceId * 10 + 4, occurredAt = base.plusHours(3))
        listOf(itemAdded, itemChanged1, itemChanged2, cancellation, lateItem).forEach(::persist)
        val actions = setOf(InvoiceAuditAction.ITEM_ADDED, InvoiceAuditAction.ITEM_CHANGED)

        val filter = InvoiceAuditActionFilter(
            actions = actions,
            invoiceNumber = template.invoiceNumber,
            actor = template.actor,
            occurredFrom = base,
            occurredTo = base.plusHours(2),
        )
        val groups = repository.findActionGroupsPage(pageIndex = 0, pageSize = 10, filter = filter, direction = SortDirection.ASC)
        val count = repository.countActionGroupsPage(filter)

        assertEquals(listOf(itemAdded.businessTx, itemChanged1.businessTx), groups.map { it.businessTx })
        assertEquals(listOf(1L, 2L), groups.map { it.rowCount })
        assertEquals(InvoiceAuditAction.ITEM_CHANGED, groups[1].action)
        assertEquals(itemChanged1.seq to itemChanged2.seq, groups[1].minSeq to groups[1].maxSeq)
        assertEquals(2L, count)
        assertEquals(4L, repository.countActionGroupsPage(InvoiceAuditActionFilter(actor = template.actor)), "without the allow-list and window all four actions count")
    }

    private companion object {
        val BASE_ID = AtomicLong(System.currentTimeMillis() * 1000)
        const val DOCUMENT_HASH_LENGTH = 64
    }
}
