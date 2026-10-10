package de.littletrotter.xpdf.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The deployment-wide GiroCode switch: lenient about spelling, strict about unknown names. */
class PaymentQrSettingTest {

    @Test
    fun `happy path - unset or blank selects ON, the default`() {
        assertEquals(PaymentQrSetting.ON, PaymentQrSetting.fromConfig(null))
        assertEquals(PaymentQrSetting.ON, PaymentQrSetting.fromConfig(""))
        assertEquals(PaymentQrSetting.ON, PaymentQrSetting.fromConfig("   "))
    }

    @Test
    fun `happy path - names are matched case-insensitively and trimmed`() {
        assertEquals(PaymentQrSetting.ON, PaymentQrSetting.fromConfig("on"))
        assertEquals(PaymentQrSetting.ON, PaymentQrSetting.fromConfig(" on "))
        assertEquals(PaymentQrSetting.OFF, PaymentQrSetting.fromConfig("OFF"))
    }

    @Test
    fun `boundary - an unknown name fails loudly and names the variable`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { PaymentQrSetting.fromConfig("x") }

        assertTrue(ex.message!!.contains(PaymentQrSetting.ENV_KEY), ex.message)
        assertTrue(ex.message!!.contains("on, off"), ex.message)
    }

    @Test
    fun `boundary - fromEnv reads XPDF_PAYMENT_QR and the producer hands it to CDI`() {
        org.mockito.Mockito.mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use { staticEnv ->
            staticEnv.`when`<String?> { de.littletrotter.xpdf.readEnv(PaymentQrSetting.ENV_KEY) }.thenReturn("off")

            assertEquals(PaymentQrSetting.OFF, PaymentQrSetting.fromEnv())
            assertEquals(PaymentQrSetting.OFF, PaymentQrSettingProducer().paymentQrSetting())
        }
    }

    @Test
    fun `boundary - fromEnv without the variable is the ON default`() {
        org.mockito.Mockito.mockStatic(Class.forName("de.littletrotter.xpdf.EnvConfig")).use {
            assertEquals(PaymentQrSetting.ON, PaymentQrSettingProducer().paymentQrSetting())
        }
    }
}
