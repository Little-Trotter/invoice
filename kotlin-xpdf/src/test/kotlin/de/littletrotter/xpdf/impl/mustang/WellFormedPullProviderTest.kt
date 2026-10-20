package de.littletrotter.xpdf.impl.mustang

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider

class WellFormedPullProviderTest {

    private fun providerWith(generated: ByteArray?): WellFormedPullProvider {
        val provider = WellFormedPullProvider("Rechnung R-1")
        ZUGFeRD2PullProvider::class.java.getDeclaredField("zugferdData").apply { isAccessible = true }.set(provider, generated)
        return provider
    }

    @Test
    fun `malformed xml fails with a message that names the invoice`() {
        val provider = providerWith("<rsm:CrossIndustryInvoice><ram:Name>Abschnitt <b</ram:Name>".toByteArray())

        val failure = assertThrows<IllegalStateException> { provider.xml }

        assertTrue(failure.message!!.contains("Rechnung R-1"), failure.message)
        assertTrue(failure.message!!.startsWith("Ungültiges XML"), failure.message)
    }

    @Test
    fun `missing generated xml fails instead of dereferencing null`() {
        val failure = assertThrows<IllegalStateException> { providerWith(null).xml }

        assertTrue(failure.message!!.contains("Rechnung R-1"), failure.message)
    }
}
