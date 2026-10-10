package de.littletrotter.ui.view.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** P2 — boundary coverage for [toPartyIdentifier]/[toLegalRegistrationIdentifier]'s blank-pair fork. */
class PartyIdentifierUiSupportTest {

    // ── toPartyIdentifier ───────────────────────────────────────────────────

    @Test
    fun `toPartyIdentifier returns null when both value and schemeId are blank`() {
        assertNull(toPartyIdentifier("", ""))
        assertNull(toPartyIdentifier("   ", "\t"))
    }

    @Test
    fun `toPartyIdentifier builds a trimmed identifier when both are filled`() {
        val id = toPartyIdentifier("  ID-1  ", "  9930  ")

        assertEquals("ID-1", id?.value)
        assertEquals("9930", id?.schemeId)
    }

    @Test
    fun `toPartyIdentifier rejects a value without a scheme`() {
        assertThrows(InvalidParameterException::class.java) {
            toPartyIdentifier("ID-1", "")
        }
    }

    @Test
    fun `toPartyIdentifier rejects a scheme without a value`() {
        assertThrows(InvalidParameterException::class.java) {
            toPartyIdentifier("", "9930")
        }
    }

    // ── toLegalRegistrationIdentifier ───────────────────────────────────────

    @Test
    fun `toLegalRegistrationIdentifier returns null when both value and schemeId are blank`() {
        assertNull(toLegalRegistrationIdentifier("", ""))
        assertNull(toLegalRegistrationIdentifier("   ", "\t"))
    }

    @Test
    fun `toLegalRegistrationIdentifier builds a trimmed identifier when both are filled`() {
        val id = toLegalRegistrationIdentifier("  HRB-1  ", "  0002  ")

        assertEquals("HRB-1", id?.value)
        assertEquals("0002", id?.schemeId)
    }

    @Test
    fun `toLegalRegistrationIdentifier rejects a value without a scheme`() {
        assertThrows(InvalidParameterException::class.java) {
            toLegalRegistrationIdentifier("HRB-1", "")
        }
    }

    @Test
    fun `toLegalRegistrationIdentifier rejects a scheme without a value`() {
        assertThrows(InvalidParameterException::class.java) {
            toLegalRegistrationIdentifier("", "0002")
        }
    }
}
