package de.littletrotter.xpdf.impl

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** [EpcQrPayload]: the EPC069-12 twelve-line payload, byte-exact and §4 gate-checked. */
class EpcQrPayloadTest {

    private fun input(
        name: String? = "Muster Consulting GmbH",
        iban: String? = "DE02 1234 5678 9012 3456 78",
        bic: String? = "MUSTDE12XXX",
        amount: Money = Money(BigDecimal("1428.00")),
        currency: Currency = Currency.EUR,
        remittance: String = "R-2026-0042",
    ) = EpcQrInput(name, iban, bic, amount, currency, remittance)

    @Test
    fun `happy path - the spec example renders byte-exact`() {
        val payload = EpcQrPayload.build(input())

        assertThat(payload).isEqualTo(
            "BCD\n002\n1\nSCT\nMUSTDE12XXX\nMuster Consulting GmbH\nDE02123456789012345678\nEUR1428.00\n\n\nR-2026-0042",
        )
    }

    @Test
    fun `happy path - a blank BIC leaves line 5 empty but keeps the payload valid`() {
        val payload = EpcQrPayload.build(input(bic = ""))

        assertThat(payload).isEqualTo(
            "BCD\n002\n1\nSCT\n\nMuster Consulting GmbH\nDE02123456789012345678\nEUR1428.00\n\n\nR-2026-0042",
        )
    }

    @Test
    fun `happy path - a null BIC is treated the same as a blank one`() {
        val payload = EpcQrPayload.build(input(bic = null))

        assertThat(payload).startsWith("BCD\n002\n1\nSCT\n\nMuster Consulting GmbH")
    }

    @Test
    fun `boundary - a name over 70 characters is truncated to 70`() {
        val longName = "A".repeat(90)

        val payload = EpcQrPayload.build(input(name = longName))

        val nameLine = payload!!.lines()[5]
        assertThat(nameLine).isEqualTo("A".repeat(70))
    }

    @Test
    fun `boundary - a remittance over 140 characters is truncated to 140`() {
        val longRemittance = "R-" + "9".repeat(200)

        val payload = EpcQrPayload.build(input(remittance = longRemittance))

        val remittanceLine = payload!!.lines()[10]
        assertThat(remittanceLine).hasSize(140)
        assertThat(remittanceLine).isEqualTo(longRemittance.take(140))
    }

    @Test
    fun `boundary - IBAN whitespace and lowercase are normalized`() {
        val payload = EpcQrPayload.build(input(iban = "de02 1234 5678\t9012 3456 78"))

        assertThat(payload!!.lines()[6]).isEqualTo("DE02123456789012345678")
    }

    @Test
    fun `boundary - zero amount yields no payload`() {
        assertThat(EpcQrPayload.build(input(amount = Money(BigDecimal.ZERO)))).isNull()
    }

    @Test
    fun `boundary - a fraction of a cent rounds to zero and yields no payload`() {
        assertThat(EpcQrPayload.build(input(amount = Money(BigDecimal("0.004"))))).isNull()
    }

    @Test
    fun `boundary - negative amount yields no payload`() {
        assertThat(EpcQrPayload.build(input(amount = Money(BigDecimal("-1.00"))))).isNull()
    }

    @Test
    fun `boundary - amount above the EPC limit yields no payload`() {
        assertThat(EpcQrPayload.build(input(amount = Money(BigDecimal("1000000000.00"))))).isNull()
    }

    @Test
    fun `boundary - amount exactly at the EPC limit is accepted`() {
        val payload = EpcQrPayload.build(input(amount = Money(BigDecimal("999999999.99"))))

        assertThat(payload!!.lines()[7]).isEqualTo("EUR999999999.99")
    }

    @Test
    fun `boundary - a currency other than EUR yields no payload`() {
        assertThat(EpcQrPayload.build(input(currency = Currency.USD))).isNull()
    }

    @Test
    fun `boundary - a three-decimal amount is rounded half-up to two decimals`() {
        val payload = EpcQrPayload.build(input(amount = Money(BigDecimal("1428.005"))))

        assertThat(payload!!.lines()[7]).isEqualTo("EUR1428.01")
    }

    @Test
    fun `boundary - a blank name yields no payload`() {
        assertThat(EpcQrPayload.build(input(name = "   "))).isNull()
        assertThat(EpcQrPayload.build(input(name = null))).isNull()
    }

    @Test
    fun `boundary - a blank or null IBAN yields no payload`() {
        assertThat(EpcQrPayload.build(input(iban = "   "))).isNull()
        assertThat(EpcQrPayload.build(input(iban = null))).isNull()
    }

    @Test
    fun `boundary - an umlaut-heavy name near the byte limit is shortened until the payload fits`() {
        // Every field pushed to its practical maximum plus a name of 70 double-byte characters
        // pushes the raw payload to 357 bytes — well past MAX_BYTES — so the byte-trimming loop
        // in EpcQrPayload must actually run, not just the 70-char truncation.
        val payload =
            EpcQrPayload.build(
                input(
                    iban = "DE02123456789012345678901234567",
                    amount = Money(BigDecimal("999999999.99")),
                    remittance = ("R-2026-0042-".repeat(12)).take(140),
                    name = "ü".repeat(70),
                ),
            )

        assertThat(payload).isNotNull
        assertThat(payload!!.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(EpcQrPayload.MAX_BYTES)
        val nameLine = payload.lines()[5]
        assertThat(nameLine.length).isLessThan(70)
        assertThat(nameLine).isEqualTo("ü".repeat(57))
    }
}
