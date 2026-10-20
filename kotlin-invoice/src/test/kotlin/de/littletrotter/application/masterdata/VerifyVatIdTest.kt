package de.littletrotter.application.masterdata

import de.littletrotter.application.dto.VatIdVerificationResult
import de.littletrotter.application.masterdata.port.outbound.VatIdVerificationPort
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class VerifyVatIdTest {

    @Test
    fun `verify hands country and id to the registry port and returns its result`() {
        val result = VatIdVerificationResult.VERIFIED("Acme GmbH", "Berlin")
        val port = mock<VatIdVerificationPort>()
        whenever(port.verify("DE", "123456789")).thenReturn(result)

        assertSame(result, VerifyVatId(port).verify("DE", "123456789"))
    }
}
