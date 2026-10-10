package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * domain invariants of [LocalizedTextGroup.validate]. The DB primary key
 * `(group_id, language_id)` already enforces "at most one entry per language"; this test proves
 * the domain layer catches the same mistake before it ever reaches the DB.
 */
class LocalizedTextGroupTest {

    private fun language(id: Int, code: String, active: Boolean = true): Language =
        Language().apply { this.id = id; this.code = code; this.isActive = active }

    private fun entry(language: Language, value: String): LocalizedTextEntry =
        LocalizedTextEntry().apply { this.language = language; this.value = value }

    @Test
    fun `a group with one entry per language is accepted`() {
        val group = LocalizedTextGroup().apply {
            purpose = LocalizedTextPurpose.SELLER_INVOICE_NOTE
            entries = mutableListOf(
                entry(language(1, "de"), "Deutscher Text"),
                entry(language(2, "en"), "English text"),
            )
        }
        assertDoesNotThrow { group.validate() }
    }

    @Test
    fun `a group with an empty entry list is accepted`() {
        val group = LocalizedTextGroup()
        assertDoesNotThrow { group.validate() }
    }

    @Test
    fun `two entries for the same language are rejected`() {
        val german = language(1, "de")
        val group = LocalizedTextGroup().apply {
            entries = mutableListOf(entry(german, "Erster Text"), entry(german, "Zweiter Text"))
        }
        assertThrows(InvalidParameterException::class.java) { group.validate() }
    }

    @Test
    fun `an entry without a language is rejected`() {
        val group = LocalizedTextGroup().apply {
            entries = mutableListOf(LocalizedTextEntry().apply { value = "Text ohne Sprache" })
        }
        assertThrows(InvalidParameterException::class.java) { group.validate() }
    }

    @Test
    fun `an entry pointing at an inactive language is rejected`() {
        val inactive = language(3, "fr", active = false)
        val group = LocalizedTextGroup().apply {
            entries = mutableListOf(entry(inactive, "Texte français"))
        }
        assertThrows(InvalidParameterException::class.java) { group.validate() }
    }

    @Test
    fun `an entry longer than its purpose's max length is rejected`() {
        val group = LocalizedTextGroup().apply {
            purpose = LocalizedTextPurpose.SELLER_INVOICE_NOTE
            entries = mutableListOf(entry(language(1, "de"), "x".repeat(201)))
        }
        assertThrows(InvalidParameterException::class.java) { group.validate() }
    }

    @Test
    fun `an entry exactly at the purpose's max length is accepted`() {
        val group = LocalizedTextGroup().apply {
            purpose = LocalizedTextPurpose.SELLER_INVOICE_NOTE
            entries = mutableListOf(entry(language(1, "de"), "x".repeat(200)))
        }
        assertDoesNotThrow { group.validate() }
    }

    // ── resolvedFor() — direct coverage; PaymentTermTest only exercises it through nameFor with a
    //    German fallback entry always present, never a group without one or an entry whose
    //    language reference is itself missing. ────────────────────────────────────────────────

    @Test
    fun `resolvedFor falls back to the requested language itself when no entry is marked as fallback`() {
        val group = LocalizedTextGroup().apply {
            entries = mutableListOf(entry(language(2, "en"), "English text"))
        }
        // No fallback-language entry at all — resolveLocalizedText's own fallbackCode parameter
        // becomes "fr" itself (the elvis's right side), which also has no entry -> null.
        assertDoesNotThrow { assertNull(group.resolvedFor("fr")) }
    }

    @Test
    fun `resolvedFor skips an entry whose language reference is itself null`() {
        val group = LocalizedTextGroup().apply {
            entries = mutableListOf(
                LocalizedTextEntry().apply { language = null; value = "orphaned" },
                entry(language(1, "de", true), "Deutscher Text"),
            )
        }
        assertEquals("Deutscher Text", group.resolvedFor("de"))
    }

    @Test
    fun `exactFor never falls back to another language`() {
        val group = LocalizedTextGroup().apply {
            entries = mutableListOf(
                LocalizedTextEntry().apply { language = null; value = "orphaned" },
                entry(language(1, "de", true), "Deutscher Text"),
            )
        }
        assertEquals("Deutscher Text", group.exactFor("de"))
        assertNull(group.exactFor("en"))
    }
}
