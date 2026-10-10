package de.littletrotter.application.invoice

import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [PartyDtoMapper.senderToDto]'s website/registeredSeat passthrough — every other
 *  field of that mapping is already exercised indirectly through [InvoiceDtoMapperTest]. */
class PartyDtoMapperTest {

    @Test
    fun `senderToDto carries website and registeredSeat through verbatim`() {
        val sender = SenderProfile().apply {
            name = "Muster GmbH"
            website = "https://www.muster.example/"
            registeredSeat = "Musterstadt"
        }

        val dto = PartyDtoMapper.senderToDto(sender, billing = null, invoiceNote = null)

        assertEquals("https://www.muster.example/", dto.website)
        assertEquals("Musterstadt", dto.registeredSeat)
    }

    @Test
    fun `senderToDto leaves website and registeredSeat null when not set`() {
        val sender = SenderProfile().apply { name = "Muster GmbH" }

        val dto = PartyDtoMapper.senderToDto(sender, billing = null, invoiceNote = null)

        assertNull(dto.website)
        assertNull(dto.registeredSeat)
    }
}
