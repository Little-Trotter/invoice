package de.littletrotter.domain

import de.littletrotter.domain.compliance.ValidationFinding
import de.littletrotter.domain.message.SystemMessage
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.LocalizedTextEntry
import de.littletrotter.domain.reference.LocalizedTextEntryId
import de.littletrotter.domain.reference.LocalizedTextGroup
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.settings.SystemSetting
import de.littletrotter.domain.system.DatabaseMaintenanceEvent
import de.littletrotter.domain.system.DatabaseMaintenanceStatus
import de.littletrotter.domain.system.DatabaseMaintenanceType
import de.littletrotter.fixture.edit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rows the database hands over carry values no intent method writes: generated identity, the
 * optimistic-lock version and imported reference data. These entities expose them read-only, so
 * a loaded row must still surface exactly what was stored.
 */
class StoredReferenceRowsTest {

    @Test
    fun `a stored language keeps its activation and fallback flags`() {
        val language = Language().edit {
            id = 2
            code = "en"
            isActive = false
            isFallback = true
            sortOrder = 3
        }

        assertEquals("en", language.code)
        assertFalse(language.isActive)
        assertTrue(language.isFallback)
        assertEquals(3, language.sortOrder)
    }

    @Test
    fun `a stored text entry keeps its composite key and owning group`() {
        val group = LocalizedTextGroup()
        val entry = LocalizedTextEntry().edit {
            id = LocalizedTextEntryId(groupId = 7, languageId = 2)
            this.group = group
        }

        assertEquals(LocalizedTextEntryId(groupId = 7, languageId = 2), entry.id)
        assertSame(group, entry.group)
    }

    @Test
    fun `stored rows keep the version that guards concurrent edits`() {
        assertEquals(5L, SystemSetting().edit { version = 5L }.version)
        assertEquals(6L, SystemMessage().edit { version = 6L }.version)
        assertEquals(7L, RecipientB2bFieldValue.forRecipient(1L, 2L).edit { version = 7L }.version)
    }

    @Test
    fun `a stored finding keeps its generated identity`() {
        assertEquals(11L, ValidationFinding().edit { id = 11L }.id)
    }

    @Test
    fun `a stored maintenance event keeps the type and details it was recorded with`() {
        val event = DatabaseMaintenanceEvent().edit {
            type = DatabaseMaintenanceType.REINDEX
            status = DatabaseMaintenanceStatus.FAILED
            details = "index bloat"
        }

        assertEquals(DatabaseMaintenanceType.REINDEX, event.type)
        assertEquals(DatabaseMaintenanceStatus.FAILED, event.status)
        assertEquals("index bloat", event.details)
    }
}
