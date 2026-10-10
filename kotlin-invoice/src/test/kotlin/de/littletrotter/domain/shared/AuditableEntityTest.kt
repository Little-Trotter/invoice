package de.littletrotter.domain.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.OffsetDateTime
import de.littletrotter.shared.time.TimeSource

class AuditableEntityTest {

    class E : AuditableEntity()

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-12-31T23:00:00Z")
    }

    @Test
    fun `stamp created and stampPersisted sets timestamps and default actor`() {
        val e = E()
        e.stampCreatedBy("alice")
        assertEquals("alice", e.createdBy)
        assertEquals("alice", e.updatedBy)

        e.createdBy = null
        e.updatedBy = null
        e.stampPersisted(NOW)
        assertEquals(NOW, e.createdAt)
        assertEquals(NOW, e.updatedAt)
        assertEquals("system", e.createdBy)
        assertEquals(e.createdBy, e.updatedBy)
    }

    @Test
    fun `stampUpdated updates updatedAt and preserves createdBy when present`() {
        val e = E()
        e.stampCreatedBy("bob")
        val before = e.createdAt
        e.stampUpdated(NOW)
        assertEquals(NOW, e.updatedAt)
        assertEquals("bob", e.createdBy)
        assertEquals(before, e.createdAt, "stampUpdated must not touch createdAt")
    }

    // ── the fallback chain of author attribution ──────────
    //
    // The `if` bodies in `stampPersisted`/`stampUpdated` decide who is recorded as the author in a
    // GoBD-relevant record when the application does not supply a user. An empty name must
    // never slip through: `""` in an invoice's `updated_by` is worse than an honest "system".

    @Test
    fun `stampUpdated uebernimmt den Ersteller, wenn kein Bearbeiter gesetzt ist`() {
        val e = E()
        e.createdBy = "carol"
        e.updatedBy = null

        e.stampUpdated(NOW)

        assertEquals("carol", e.updatedBy, "ohne eigenen Bearbeiter zaehlt der Ersteller")
    }

    @Test
    fun `stampUpdated faellt auf system zurueck, wenn weder Bearbeiter noch Ersteller bekannt sind`() {
        val e = E()
        e.createdBy = null
        e.updatedBy = "   " // whitespace-only counts as not set

        e.stampUpdated(NOW)

        assertEquals("system", e.updatedBy, "ein leerer Urheber darf nicht bestehen bleiben")
    }

    @Test
    fun `stampUpdated laesst einen gesetzten Bearbeiter unangetastet`() {
        val e = E()
        e.createdBy = "carol"
        e.updatedBy = "dora"

        e.stampUpdated(NOW)

        assertEquals("dora", e.updatedBy, "wer die Aenderung gemacht hat, wird nicht ueberschrieben")
        assertEquals("carol", e.createdBy)
    }

    @Test
    fun `stampPersisted laesst gesetzte Urheber unangetastet`() {
        val e = E()
        e.createdBy = "erik"
        e.updatedBy = "frida"

        e.stampPersisted(NOW)

        assertEquals("erik", e.createdBy, "ein mitgelieferter Ersteller wird nicht durch system ersetzt")
        assertEquals("frida", e.updatedBy)
        assertEquals(NOW, e.createdAt)
    }

    @Test
    fun `stampPersisted setzt den Bearbeiter auf den Ersteller, wenn nur dieser bekannt ist`() {
        val e = E()
        e.createdBy = "gustav"
        e.updatedBy = "" // blank counts as not set

        e.stampPersisted(NOW)

        assertEquals("gustav", e.createdBy)
        assertEquals("gustav", e.updatedBy, "beim Anlegen sind Ersteller und Bearbeiter dieselbe Person")
    }

    // ── stampForPersist extension ────────────────────

    @Test
    fun `stampForPersist stempelt beim ersten Speichern den Ersteller`() {
        val e = E()

        e.stampForPersist("heidi")

        assertEquals("heidi", e.createdBy, "ohne createdAt ist es die erste Speicherung")
        assertEquals("heidi", e.updatedBy)
    }

    @Test
    fun `stampForPersist stempelt bei einer bestehenden Zeile nur den Bearbeiter`() {
        val e = E()
        e.stampCreatedBy("heidi")
        e.createdAt = NOW

        e.stampForPersist("ivan")

        assertEquals("heidi", e.createdBy, "der Ersteller einer bestehenden Zeile bleibt")
        assertEquals("ivan", e.updatedBy)
    }

    @Test
    fun `the entity listener stamps insert and update from the injected time source`() {
        val later = NOW.plusMinutes(5)
        val time = mock<TimeSource> { on { nowUtc() } doReturn NOW doReturn later }
        val listener = AuditTimestampListener(time)
        val e = E()

        listener.beforeInsert(e)
        listener.beforeUpdate(e)

        assertEquals(NOW, e.createdAt)
        assertEquals(later, e.updatedAt)
    }
}
