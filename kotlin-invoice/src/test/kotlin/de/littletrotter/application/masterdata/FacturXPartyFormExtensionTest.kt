package de.littletrotter.application.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.spi.party.PartyIdentifierSpec
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SIRET/NAF on the buyer, modeled on
 * [ElectronicAddressModuleTest], with added format-validation cases.
 */
class FacturXPartyFormExtensionTest {

    private val module = FacturXPartyFormExtension()

    @Test
    fun `contributes no pairs for the seller — only the French buyer needs a SIRET`() {
        assertTrue(module.pairs(PartyKind.SELLER).isEmpty())
    }

    @Test
    fun `declares two scheme-less pairs for the buyer, SIRET and NAF`() {
        val buyer = module.pairs(PartyKind.BUYER)

        assertEquals(2, buyer.size)
        assertTrue(buyer.all { !it.hasScheme }, "T8-02: SIRET/NAF have no scheme concept")
        assertEquals(setOf("recipient.facturX.siret", "recipient.facturX.nafCode"), buyer.map { it.i18nKeyPrefix }.toSet())
    }

    @Test
    fun `read ist leer fuer den Verkaeufer - SIRET-NAF gibt es nur beim franzoesischen Kaeufer`() {
        val (siretSpec, _) = module.pairs(PartyKind.BUYER)
        val seller = Recipient().apply { siret = "73282932000074" }

        assertEquals("", module.read(seller, PartyKind.SELLER, siretSpec).value)
    }

    @Test
    fun `reads persisted SIRET and NAF back off the recipient`() {
        val (siretSpec, nafSpec) = module.pairs(PartyKind.BUYER)
        val buyer = Recipient().apply {
            siret = "73282932000074"
            nafCode = "6201Z"
        }

        assertEquals("73282932000074", module.read(buyer, PartyKind.BUYER, siretSpec).value)
        assertEquals("6201Z", module.read(buyer, PartyKind.BUYER, nafSpec).value)
    }

    @Test
    fun `write ist ein No-Op fuer den Verkaeufer statt eine Ausnahme zu werfen`() {
        val (siretSpec, _) = module.pairs(PartyKind.BUYER)
        val seller = Recipient()

        module.write(seller, PartyKind.SELLER, siretSpec, PartyPairState("invalid-but-ignored"))

        assertNull(seller.siret, "die Verkaeufer-Kurzschluss-Rueckkehr darf den Wert nie anfassen")
    }

    @Test
    fun `write persists a well-formed SIRET and NAF`() {
        val (siretSpec, nafSpec) = module.pairs(PartyKind.BUYER)
        val buyer = Recipient()

        module.write(buyer, PartyKind.BUYER, siretSpec, PartyPairState("73282932000074"))
        module.write(buyer, PartyKind.BUYER, nafSpec, PartyPairState("6201Z"))

        assertEquals("73282932000074", buyer.siret)
        assertEquals("6201Z", buyer.nafCode)
    }

    @Test
    fun `blank state clears a previously stored SIRET or NAF`() {
        val (siretSpec, nafSpec) = module.pairs(PartyKind.BUYER)
        val buyer = Recipient().apply {
            siret = "73282932000074"
            nafCode = "6201Z"
        }

        module.write(buyer, PartyKind.BUYER, siretSpec, PartyPairState())
        module.write(buyer, PartyKind.BUYER, nafSpec, PartyPairState())

        assertNull(buyer.siret)
        assertNull(buyer.nafCode)
    }

    @Test
    fun `T8-03 - a SIRET with anything other than 14 digits is a format error at write time`() {
        val (siretSpec, _) = module.pairs(PartyKind.BUYER)
        val buyer = Recipient()

        assertThrows(InvalidParameterException::class.java) {
            module.write(buyer, PartyKind.BUYER, siretSpec, PartyPairState("1234"))
        }
        assertThrows(InvalidParameterException::class.java) {
            module.write(buyer, PartyKind.BUYER, siretSpec, PartyPairState("123456789012345"))
        }
        assertThrows(InvalidParameterException::class.java) {
            module.write(buyer, PartyKind.BUYER, siretSpec, PartyPairState("7328293200007A"))
        }
    }

    @Test
    fun `boundary - read returns an empty value for a spec id this module does not own`() {
        val unknownSpec = PartyIdentifierSpec(id = "unknown", i18nKeyPrefix = "recipient.unknown")
        val buyer = Recipient().apply { siret = "73282932000074"; nafCode = "6201Z" }

        assertEquals("", module.read(buyer, PartyKind.BUYER, unknownSpec).value)
    }

    @Test
    fun `a malformed NAF code is a format error at write time`() {
        val (_, nafSpec) = module.pairs(PartyKind.BUYER)
        val buyer = Recipient()

        assertThrows(InvalidParameterException::class.java) {
            module.write(buyer, PartyKind.BUYER, nafSpec, PartyPairState("620Z"))
        }
        assertThrows(InvalidParameterException::class.java) {
            module.write(buyer, PartyKind.BUYER, nafSpec, PartyPairState("62011Z"))
        }
    }
}
