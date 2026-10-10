package de.littletrotter.xpdf.impl

import de.littletrotter.shared.Currency
import de.littletrotter.shared.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Inputs for the EPC QR payload; nullable fields mirror the sender profile. */
internal data class EpcQrInput(
    val name: String?,
    val iban: String?,
    val bic: String?,
    val amount: Money,
    val currency: Currency,
    val remittance: String,
)

/**
 * Builds the EPC069-12 (v002) "GiroCode" payload printed as a QR code in the payment block.
 *
 * The twelve-line text format is fixed by the EPC quality label so any SEPA-capable banking app
 * can parse it without further negotiation: service tag, version and character-set markers first
 * (lines 1–3), then the SEPA Credit Transfer fields a bank needs to prefill a transfer (BIC,
 * name, IBAN, amount), then purpose/reference/remittance, then an optional note to the payer.
 * This invoice never fills purpose (9) or the structured reference (10) — those two lines stay
 * present but empty, because line 11 (the unstructured remittance, here the invoice number) is
 * positional: dropping an unused line above it would shift it into the wrong slot for the reader.
 * Line 12 (note to payer) is genuinely unused here and is omitted outright, since nothing below
 * it depends on its position.
 */
internal object EpcQrPayload {

    /** EPC069-12 hard limit for the whole payload once it is scanned; error correction M. */
    const val MAX_BYTES = 331

    private const val MAX_NAME_CHARS = 70
    private const val MAX_REMITTANCE_CHARS = 140
    private val MAX_AMOUNT = Money.of("999999999.99")

    fun build(input: EpcQrInput): String? {
        val name = input.name
        val iban = input.iban
        // Gate on the amount as it will print: a fraction of a cent rounds to EUR0.00, and a code
        // asking for nothing is worse than no code.
        val amount = input.amount.amount.setScale(2, RoundingMode.HALF_UP)
        val violatesGateRules =
            name.isNullOrBlank() ||
                iban.isNullOrBlank() ||
                input.currency != Currency.EUR ||
                amount <= BigDecimal.ZERO ||
                amount > MAX_AMOUNT.amount
        if (violatesGateRules) return null

        val normalizedIban = iban.replace(WHITESPACE, "").uppercase()
        val normalizedBic = input.bic?.replace(WHITESPACE, "").orEmpty()
        val amountLine = "EUR" + amount.toPlainString()
        val remittance = input.remittance.take(MAX_REMITTANCE_CHARS)
        fun payloadWithName(nameLine: String) =
            listOf("BCD", "002", "1", "SCT", normalizedBic, nameLine, normalizedIban, amountLine, "", "", remittance)
                .joinToString("\n")

        // Line 6 (name) is the only field the EPC limit forces us to shorten dynamically: every
        // other line has a fixed or already-bounded length, so name is what gives when a long
        // company name plus multi-byte characters (umlauts) push the payload past 331 bytes.
        var nameLine = name.take(MAX_NAME_CHARS)
        var payload = payloadWithName(nameLine)
        while (payload.toByteArray(Charsets.UTF_8).size > MAX_BYTES && nameLine.isNotEmpty()) {
            nameLine = nameLine.dropLast(1)
            payload = payloadWithName(nameLine)
        }
        return payload.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    private val WHITESPACE = Regex("\\s+")
}
