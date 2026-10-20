package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.assertj.core.api.Assertions.assertThat

class KositValidatorPortTest {
    @Test
    fun `mock validator port returns result`() {
        val port = mock<KositValidatorPort>()
        val result = KositValidationResult(KositOutcome.VALID, "ok")
        whenever(port.validate("<xml/>", 1000)).thenReturn(result)

        val got = port.validate("<xml/>", 1000)
        assertThat(got.status).isEqualTo(KositOutcome.VALID)
        assertThat(got.summary).isEqualTo("ok")
    }

    @Test
    fun `validator port can be called with default timeout parameter`() {
        val impl = object : KositValidatorPort {
            override fun validate(xml: String, timeoutMs: Long): KositValidationResult {
                return KositValidationResult(KositOutcome.VALID, "ok")
            }
        }
        val result = impl.validate("<xml/>")
        assertThat(result.status).isEqualTo(KositOutcome.VALID)
    }
}
