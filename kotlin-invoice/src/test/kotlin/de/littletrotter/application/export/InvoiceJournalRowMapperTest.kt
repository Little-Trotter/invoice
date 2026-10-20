package de.littletrotter.application.export

import de.littletrotter.domain.compliance.InvoiceAuditAction
import de.littletrotter.domain.compliance.InvoiceAuditEvent
import de.littletrotter.domain.compliance.InvoiceAuditJournalEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class InvoiceJournalRowMapperTest {

    private fun entry(
        seq: Long = 1L,
        businessTx: Long = 1L,
        invoiceId: Long = 42L,
        invoiceNumber: String? = "RE-2026-05-00001",
        actor: String = "oidc-sub-1",
        actorName: String? = "Alice",
        occurredAt: OffsetDateTime = OffsetDateTime.parse("2026-05-01T10:15:30+02:00"),
        event: InvoiceAuditEvent = InvoiceAuditEvent.INVOICE_UPDATED,
        action: InvoiceAuditAction = InvoiceAuditAction.UNSPECIFIED,
        detail: String = """{"free_text": {"old": "a", "new": "b"}}""",
        documentSha256: String? = null,
        prevHash: String = "prev-hash",
        entryHash: String = "entry-hash",
    ) = InvoiceAuditJournalEntry(
        seq = seq, businessTx = businessTx, invoiceId = invoiceId, invoiceNumber = invoiceNumber,
        actor = actor, actorName = actorName, occurredAt = occurredAt, event = event, action = action,
        detail = detail, documentSha256 = documentSha256, prevHash = prevHash, entryHash = entryHash,
    )

    @Test
    fun `maps every field straight across`() {
        val row = InvoiceJournalRowMapper.fromEntry(entry())

        assertThat(row.invoiceId).isEqualTo(42L)
        assertThat(row.invoiceNumber).isEqualTo("RE-2026-05-00001")
        assertThat(row.seq).isEqualTo(1L)
        assertThat(row.occurredAt).isEqualTo(OffsetDateTime.parse("2026-05-01T10:15:30+02:00"))
        assertThat(row.event).isEqualTo(InvoiceAuditEvent.INVOICE_UPDATED)
        assertThat(row.actorName).isEqualTo("Alice")
        assertThat(row.actor).isEqualTo("oidc-sub-1")
        assertThat(row.documentSha256).isNull()
        assertThat(row.entryHash).isEqualTo("entry-hash")
        assertThat(row.prevHash).isEqualTo("prev-hash")
    }

    @Test
    fun `flattens detail through InvoiceJournalDetailFlattener rather than carrying the raw JSON`() {
        val row = InvoiceJournalRowMapper.fromEntry(entry())
        assertThat(row.detail).isEqualTo("free_text: a → b")
    }

    @Test
    fun `journal export row carries a German unit label while retaining technical audit fields`() {
        val rawDetail = """{"unit": {"old": 6, "new": 7}}"""
        val row = InvoiceJournalRowMapper.fromEntry(
            entry(
                event = InvoiceAuditEvent.INVOICE_ITEM_UPDATED,
                detail = rawDetail,
                prevHash = "prev-raw",
                entryHash = "entry-raw",
            ),
        )

        assertThat(row.detail).isEqualTo("unit: Quadratmeter → Kubikmeter")
        assertThat(row.prevHash).isEqualTo("prev-raw")
        assertThat(row.entryHash).isEqualTo("entry-raw")
    }

    @Test
    fun `a draft without an invoice number maps to a null invoiceNumber, not a crash`() {
        val row = InvoiceJournalRowMapper.fromEntry(entry(invoiceNumber = null))
        assertThat(row.invoiceNumber).isNull()
    }

    @Test
    fun `a null actorName is carried through unchanged - the writer decides the fallback`() {
        val row = InvoiceJournalRowMapper.fromEntry(entry(actorName = null))
        assertThat(row.actorName).isNull()
        assertThat(row.actor).isEqualTo("oidc-sub-1")
    }

    @Test
    fun `action and businessTx are mapped straight across`() {
        val row = InvoiceJournalRowMapper.fromEntry(entry(action = InvoiceAuditAction.CANCELLATION, businessTx = 42L))

        assertThat(row.action).isEqualTo(InvoiceAuditAction.CANCELLATION)
        assertThat(row.businessTx).isEqualTo(42L)
    }

    @Test
    fun `a document event carries its documentSha256 through unchanged`() {
        val row = InvoiceJournalRowMapper.fromEntry(
            entry(event = InvoiceAuditEvent.INVOICE_DOCUMENT_CREATED, documentSha256 = "a".repeat(64), detail = """{"sha256_checksum": "${"a".repeat(64)}"}"""),
        )
        assertThat(row.documentSha256).isEqualTo("a".repeat(64))
    }
}
