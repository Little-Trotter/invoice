package de.littletrotter.xpdf.impl

import de.littletrotter.xpdf.readEnv
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Whether the standard invoice prints a GiroCode (EPC QR) in the payment block.
 *
 * Chosen once per deployment through `XPDF_PAYMENT_QR`, not per invoice: printing a payment code
 * is an operational/legal decision for the whole tenant, not something an individual invoice
 * should toggle. Unset means [ON] — the code is the improvement, so it ships by default once the
 * per-invoice rules in [EpcQrPayload] are met. A value that names no setting is a configuration
 * error and fails the start (see [PaymentQrSettingProducer]): a typo
 * must never silently fall back to printing (or omitting) the code.
 */
enum class PaymentQrSetting(val configValue: String) {
    ON("on"),
    OFF("off"),
    ;

    companion object {
        const val ENV_KEY = "XPDF_PAYMENT_QR"

        /** Case-insensitive, whitespace-tolerant; null or blank selects the default. */
        fun fromConfig(value: String?): PaymentQrSetting {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return ON
            return entries.firstOrNull { it.configValue == normalized }
                ?: throw IllegalArgumentException(
                    "$ENV_KEY='$value' names no setting; allowed: ${entries.joinToString { it.configValue }}",
                )
        }

        fun fromEnv(): PaymentQrSetting = fromConfig(readEnv(ENV_KEY))
    }
}

/**
 * Resolves the setting eagerly at start so a misconfigured deployment fails before its first job.
 * `@Singleton`, not `@ApplicationScoped`: an enum cannot be client-proxied, and a value read once
 * from the environment needs no proxy anyway.
 */
@ApplicationScoped
class PaymentQrSettingProducer {

    @Produces
    @Singleton
    @Startup
    fun paymentQrSetting(): PaymentQrSetting = PaymentQrSetting.fromEnv()
}
