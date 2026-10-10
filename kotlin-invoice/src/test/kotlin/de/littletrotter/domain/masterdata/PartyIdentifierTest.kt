package de.littletrotter.domain.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PartyIdentifierTest {

    // ── PartyIdentifier — value/scheme pair for BT-29/BT-46 ────────────────────

    @Test
    fun `of() creates an identifier with trimmed values`() {
        val id = PartyIdentifier.of("  value  ", "  scheme  ")

        assertEquals("value", id.value)
        assertEquals("scheme", id.schemeId)
    }

    @Test
    fun `of() rejects blank value`() {
        assertThrows(InvalidParameterException::class.java) {
            PartyIdentifier.of("", "scheme")
        }
    }

    @Test
    fun `of() rejects blank scheme`() {
        assertThrows(InvalidParameterException::class.java) {
            PartyIdentifier.of("value", "")
        }
    }

    @Test
    fun `equals compares value and schemeId`() {
        val a = PartyIdentifier.of("ID-001", "9930")
        val b = PartyIdentifier.of("ID-001", "9930")
        val c = PartyIdentifier.of("ID-002", "9930")
        val d = PartyIdentifier.of("ID-001", "0088")

        assertEquals(a, b, "same value and scheme should be equal")
        assertNotEquals(a, c, "different value should not be equal")
        assertNotEquals(a, d, "different scheme should not be equal")
    }

    @Test
    fun `equals rejects non-PartyIdentifier types`() {
        val id = PartyIdentifier.of("value", "scheme")
        assertNotEquals(id as Any, "not a party identifier" as Any)
    }

    @Test
    fun `hashCode is stable for equal instances`() {
        val a = PartyIdentifier.of("ID-001", "9930")
        val b = PartyIdentifier.of("ID-001", "9930")

        assertEquals(a.hashCode(), b.hashCode(), "equal instances must have equal hashCode")
    }

    @Test
    fun `toString formats as scheme colon value`() {
        val id = PartyIdentifier.of("ID-001", "9930")

        assertEquals("9930:ID-001", id.toString())
    }

    @Test
    fun `toString with special characters preserves formatting`() {
        val id = PartyIdentifier.of("DE123456789", "0088")

        assertEquals("0088:DE123456789", id.toString())
    }

    // ── ElectronicAddress — value/scheme pair for BT-34/BT-49 ─────────────────

    @Test
    fun `ElectronicAddress of() creates with trimmed values`() {
        val addr = ElectronicAddress.of("  test@example.com  ", "  EM  ")

        assertEquals("test@example.com", addr.value)
        assertEquals("EM", addr.schemeId)
    }

    @Test
    fun `ElectronicAddress of() rejects blank value`() {
        assertThrows(InvalidParameterException::class.java) {
            ElectronicAddress.of("", "EM")
        }
    }

    @Test
    fun `ElectronicAddress of() rejects blank scheme`() {
        assertThrows(InvalidParameterException::class.java) {
            ElectronicAddress.of("test@example.com", "")
        }
    }

    @Test
    fun `ElectronicAddress equals compares value and schemeId`() {
        val a = ElectronicAddress.of("test@example.com", "EM")
        val b = ElectronicAddress.of("test@example.com", "EM")
        val c = ElectronicAddress.of("other@example.com", "EM")

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `ElectronicAddress hashCode is stable for equal instances`() {
        val a = ElectronicAddress.of("test@example.com", "EM")
        val b = ElectronicAddress.of("test@example.com", "EM")

        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ElectronicAddress toString formats as scheme colon value`() {
        val addr = ElectronicAddress.of("test@example.com", "EM")

        assertEquals("EM:test@example.com", addr.toString())
    }

    // ── LegalRegistrationIdentifier — value/scheme pair for BT-30/BT-47 ──────

    @Test
    fun `LegalRegistrationIdentifier of() creates with trimmed values`() {
        val reg = LegalRegistrationIdentifier.of("  HR123456  ", "  HRB  ")

        assertEquals("HR123456", reg.value)
        assertEquals("HRB", reg.schemeId)
    }

    @Test
    fun `LegalRegistrationIdentifier of() rejects blank value`() {
        assertThrows(InvalidParameterException::class.java) {
            LegalRegistrationIdentifier.of("", "HRB")
        }
    }

    @Test
    fun `LegalRegistrationIdentifier of() rejects blank scheme`() {
        assertThrows(InvalidParameterException::class.java) {
            LegalRegistrationIdentifier.of("HR123456", "")
        }
    }

    @Test
    fun `LegalRegistrationIdentifier equals compares value and schemeId`() {
        val a = LegalRegistrationIdentifier.of("HR123456", "HRB")
        val b = LegalRegistrationIdentifier.of("HR123456", "HRB")
        val c = LegalRegistrationIdentifier.of("HR654321", "HRB")

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `LegalRegistrationIdentifier hashCode is stable for equal instances`() {
        val a = LegalRegistrationIdentifier.of("HR123456", "HRB")
        val b = LegalRegistrationIdentifier.of("HR123456", "HRB")

        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `LegalRegistrationIdentifier toString formats as scheme colon value`() {
        val reg = LegalRegistrationIdentifier.of("HR123456", "HRB")

        assertEquals("HRB:HR123456", reg.toString())
    }
}
