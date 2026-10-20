package de.littletrotter.application.masterdata

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.spi.party.PartyKind
import de.littletrotter.spi.party.PartyPairState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The electronic-address pair (seller BT-34 / buyer BT-49). */
class ElectronicAddressModuleTest {

    private val module = ElectronicAddressModule()

    @Test
    fun `declares one pair per party kind with the right bundle prefix`() {
        val seller = module.pairs(PartyKind.SELLER)
        val buyer = module.pairs(PartyKind.BUYER)

        assertEquals(1, seller.size)
        assertEquals(1, buyer.size)
        assertEquals("senderProfile.electronicAddress", seller.single().i18nKeyPrefix)
        assertEquals("recipient.electronicAddress", buyer.single().i18nKeyPrefix)
        assertEquals("electronicAddress", seller.single().id)
        assertEquals("electronicAddress", buyer.single().id)
    }

    @Test
    fun `reads a persisted electronic address back off the seller and buyer`() {
        val spec = module.pairs(PartyKind.SELLER).single()

        val seller = aSenderProfile {
            electronicAddress = de.littletrotter.domain.masterdata.ElectronicAddress.of("seller@example.org", "EM")
        }
        val sellerState = module.read(seller, PartyKind.SELLER, spec)
        assertEquals("seller@example.org", sellerState.value)
        assertEquals("EM", sellerState.schemeId)

        val buyerSpec = module.pairs(PartyKind.BUYER).single()
        val buyer = aRecipient {
            electronicAddress = de.littletrotter.domain.masterdata.ElectronicAddress.of("9930:DE1234", "9930")
        }
        val buyerState = module.read(buyer, PartyKind.BUYER, buyerSpec)
        assertEquals("9930:DE1234", buyerState.value)
        assertEquals("9930", buyerState.schemeId)
    }

    @Test
    fun `write persists the pair onto the party`() {
        val spec = module.pairs(PartyKind.SELLER).single()
        val seller = SenderProfile()

        module.write(seller, PartyKind.SELLER, spec, PartyPairState("seller@example.org", "EM"))

        assertEquals("seller@example.org", seller.electronicAddress?.value)
        assertEquals("EM", seller.electronicAddress?.schemeId)
    }

    @Test
    fun `blank pair clears the stored address`() {
        val spec = module.pairs(PartyKind.BUYER).single()
        val buyer = aRecipient {
            electronicAddress = de.littletrotter.domain.masterdata.ElectronicAddress.of("old@example.org", "EM")
        }

        module.write(buyer, PartyKind.BUYER, spec, PartyPairState())

        assertNull(buyer.electronicAddress)
    }

    @Test
    fun `a scheme-less value is rejected, not silently accepted`() {
        val spec = module.pairs(PartyKind.SELLER).single()
        val seller = SenderProfile()

        assertThrows(InvalidParameterException::class.java) {
            module.write(seller, PartyKind.SELLER, spec, PartyPairState("seller@example.org", ""))
        }
    }

    @Test
    fun `blank helper reports the pair as unset`() {
        assertTrue(PartyPairState().isBlank)
    }
}
