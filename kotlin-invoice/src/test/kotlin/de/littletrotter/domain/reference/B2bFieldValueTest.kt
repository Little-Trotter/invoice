package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.fixture.edit
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [B2bFieldValue.validate]'s two hard checks: `maxLength` and, for an ENUM-kind
 * term, [de.littletrotter.shared.b2b.codes.B2bCodeList.contains]. Neither existed before this
 * task (`saveFieldValues` only ever checked `ownerId`/`term` presence) — both guard a
 * *programmatic* writer, not the ComboBox (`B2bFieldWidgetFactoryTest` covers that path).
 */
class B2bFieldValueTest {

    private fun value(term: B2bTerm, value: String?) = B2bFieldValue().edit {
        ownerId = 1L
        this.term = term
        this.value = value
    }

    @Test
    fun `ownerId and term presence are still checked first`() {
        assertThrows(InvalidParameterException::class.java) { B2bFieldValue().edit { term = B2bTerm.BT_155 }.validate() }
        assertThrows(InvalidParameterException::class.java) { B2bFieldValue().edit { ownerId = 1L }.validate() }
    }

    @Test
    fun `a blank or null value skips both new checks`() {
        assertDoesNotThrow { value(B2bTerm.BT_155, null).validate() }
        assertDoesNotThrow { value(B2bTerm.BT_155, "   ").validate() }
        assertDoesNotThrow { value(B2bTerm.BT_130, null).validate() }
    }

    // ── maxLength ─────────────────────────────────────────────────────────────

    @Test
    fun `a value within maxLength is accepted`() {
        // BT-155 (Item Seller's item identifier) has maxLength = 50.
        assertDoesNotThrow { value(B2bTerm.BT_155, "A".repeat(50)).validate() }
    }

    @Test
    fun `a value exceeding maxLength is rejected, naming the term`() {
        val ex = assertThrows(InvalidParameterException::class.java) { value(B2bTerm.BT_155, "A".repeat(51)).validate() }
        assertTrue(ex.message!!.contains("BT-155"), "expected the term in the message: ${ex.message}")
    }

    @Test
    fun `a reference term with no maxLength never rejects on length`() {
        // BT_1 (Invoice number) is a reference term, maxLength == null.
        assertDoesNotThrow { value(B2bTerm.BT_1, "A".repeat(500)).validate() }
    }

    // ── ENUM / valueDomain membership ────────────────────────────────────────

    @Test
    fun `a valid ENUM value is accepted`() {
        assertDoesNotThrow { value(B2bTerm.BT_130, "HUR").validate() }
        assertDoesNotThrow { value(B2bTerm.BT_151, "S").validate() }
    }

    @Test
    fun `an unknown ENUM value is rejected, naming the term and the value`() {
        val ex = assertThrows(InvalidParameterException::class.java) { value(B2bTerm.BT_130, "NOT-A-UNIT").validate() }
        assertTrue(ex.message!!.contains("BT-130"), "expected the term in the message: ${ex.message}")
        assertTrue(ex.message!!.contains("NOT-A-UNIT"), "expected the offending value in the message: ${ex.message}")
    }

    @Test
    fun `a VAT category outside VatCategory but still officially valid is accepted`() {
        // "L" (Canary Islands IGIC) is a real UNTDID 5305/EN16931 code (B2bCodeList.VAT_CATEGORY_5305
        // has all 9) even though the hand-maintained VatCategory enum models only 7 of them — the
        // domain check governs the codedb list, not VatCategory's narrower subset.
        assertDoesNotThrow { value(B2bTerm.BT_151, "L").validate() }
    }

    @Test
    fun `a VAT category not even in the official codedb list is rejected`() {
        val ex = assertThrows(InvalidParameterException::class.java) { value(B2bTerm.BT_151, "X").validate() }
        assertTrue(ex.message!!.contains("BT-151"))
        assertTrue(ex.message!!.contains("X"))
    }

    @Test
    fun `a non-ENUM term never enforces domain membership`() {
        assertDoesNotThrow { value(B2bTerm.BT_155, "anything at all") }
    }

    // ── NUMBER kind format ────────────────────────────────────────────────────

    @Test
    fun `a NUMBER-kind value that parses as a number is accepted`() {
        // BT-129 (Invoiced quantity) is NUMBER-kind.
        assertDoesNotThrow { value(B2bTerm.BT_129, "42").validate() }
        assertDoesNotThrow { value(B2bTerm.BT_129, "-3.5").validate() }
    }

    @Test
    fun `a NUMBER-kind value that does not parse as a number is rejected, naming the term`() {
        val ex = assertThrows(InvalidParameterException::class.java) { value(B2bTerm.BT_129, "not-a-number").validate() }
        assertTrue(ex.message!!.contains("BT-129"), "expected the term in the message: ${ex.message}")
    }

    @Test
    fun `a non-NUMBER term never enforces the numeric-format check`() {
        assertDoesNotThrow { value(B2bTerm.BT_155, "not-a-number-either").validate() }
    }

    // ── DATE kind format ──────────────────────────────────────────────────────

    @Test
    fun `a DATE-kind value in ISO-8601 form is accepted`() {
        // BT-72 (Actual delivery date) is DATE-kind.
        assertDoesNotThrow { value(B2bTerm.BT_72, "2026-08-30").validate() }
    }

    @Test
    fun `a DATE-kind value that is not ISO-8601 is rejected, naming the term`() {
        val ex = assertThrows(InvalidParameterException::class.java) { value(B2bTerm.BT_72, "30.08.2026").validate() }
        assertTrue(ex.message!!.contains("BT-72"), "expected the term in the message: ${ex.message}")
    }

    @Test
    fun `a non-DATE term never enforces the ISO-8601 check`() {
        assertDoesNotThrow { value(B2bTerm.BT_155, "30.08.2026").validate() }
    }

    @Test
    fun `a loaded row keeps the stored version that guards concurrent edits`() {
        val stored = value(B2bTerm.BT_10, "Leitweg").edit { version = 4L }

        assertEquals(4L, stored.version)
    }
}
