package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.domain.masterdata.SenderProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** [SenderQueries] on top of [SellerProfileService]'s reads. */
class SenderQueriesTest {

    private val sellerProfileService: SellerProfileService = mock()
    private val queries = SenderQueries(sellerProfileService)

    private fun sender(id: Long, name: String) = SenderProfile().apply {
        this.id = id
        this.name = name
    }

    @Test
    fun `findAllSenders loads the unpaged (Int-MAX_VALUE) set, mapped to SenderSummaryDto`() {
        val senders = listOf(sender(1L, "Haupt-Anbieter GmbH"))
        whenever(sellerProfileService.findAll(0, Int.MAX_VALUE, null)).thenReturn(senders)

        assertEquals(
            listOf(SenderSummaryDto(id = 1L, name = "Haupt-Anbieter GmbH", vatId = null, isDefault = false, billingAddressCity = null)),
            queries.findAllSenders(),
        )
    }

    @Test
    fun `findSenderById forwards to the service, mapped to SenderSummaryDto`() {
        val sender = sender(9L, "Filial-Anbieter")
        whenever(sellerProfileService.findById(9L)).thenReturn(sender)

        assertEquals(
            SenderSummaryDto(id = 9L, name = "Filial-Anbieter", vatId = null, isDefault = false, billingAddressCity = null),
            queries.findSenderById(9L),
        )
    }
}
