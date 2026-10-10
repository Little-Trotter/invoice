package de.littletrotter.domain.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * [ChainAnchorFormat] is the canonical, tested definition of `anchor.txt`'s text format;
 * `admin-tools/scripts/backup/backup.sh`'s heredoc must stay byte-for-byte identical to what this
 * produces (see that function's own comment, "write_chain_anchor").
 */
class ChainAnchorFormatTest {

    private val generatedAt = OffsetDateTime.parse("2026-09-04T12:00:00Z")

    @Test
    fun `RS-12 - anchor formatting produces the exact header, generated and run lines`() {
        val text = ChainAnchorFormat.format(
            generatedAt,
            "lt-2026-09-04-1200",
            listOf(JournalAnchor("number_journal", "abc123", 42L)),
        )
        val lines = text.lines()

        assertEquals("# little-trotter-invoice chain-head anchor (RS-12, M6)", lines[0])
        assertEquals("# generated=2026-09-04T12:00:00Z run=lt-2026-09-04-1200", lines[1])
    }

    @Test
    fun `RS-12 - anchor formatting emits one journal line per entry, in the given order`() {
        val text = ChainAnchorFormat.format(
            generatedAt,
            "lt-2026-09-04-1200",
            listOf(
                JournalAnchor("number_journal", "abc123", 42L),
                JournalAnchor("invoice_audit_journal", "def456", 7L),
            ),
        )

        assertTrue(text.contains("journal=number_journal chain_head=abc123 row_count=42"))
        assertTrue(text.contains("journal=invoice_audit_journal chain_head=def456 row_count=7"))
        val numberJournalIndex = text.indexOf("journal=number_journal")
        val invoiceAuditIndex = text.indexOf("journal=invoice_audit_journal")
        assertTrue(numberJournalIndex < invoiceAuditIndex, "journals must appear in the given order")
    }

    @Test
    fun `RS-12 - anchor formatting represents an empty journal's chain_head as an empty value`() {
        val text = ChainAnchorFormat.format(
            generatedAt,
            "lt-2026-09-04-1200",
            listOf(JournalAnchor("number_journal", "", 0L)),
        )

        assertTrue(text.contains("journal=number_journal chain_head= row_count=0"))
    }

    @Test
    fun `RS-12 - anchor formatting ends with a trailing newline`() {
        val text = ChainAnchorFormat.format(generatedAt, "lt-2026-09-04-1200", listOf(JournalAnchor("number_journal", "abc", 1L)))
        assertTrue(text.endsWith("\n"))
    }
}
