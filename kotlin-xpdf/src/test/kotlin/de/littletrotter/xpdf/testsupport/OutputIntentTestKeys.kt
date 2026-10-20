package de.littletrotter.xpdf.testsupport

import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.resolveOutputIntentSubject
import de.littletrotter.xpdf.impl.OutputIntentVerifier
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.LocalDate
import java.util.Base64

internal object OutputIntentTestKeys {
    val SELLER = PartyDto(name = "Granted Seller GmbH", vatId = "DE123456789")

    private val keyPair: KeyPair = newKeyPair()

    val verifier: OutputIntentVerifier = OutputIntentVerifier(listOf(keyPair.public))

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun grantFor(subject: String, notAfter: LocalDate? = null, signWith: KeyPair = keyPair): String {
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(StandardCharsets.UTF_8)),
        )
        val payload = "oi1.$hash.${notAfter?.toString() ?: "-"}"
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signWith.private)
            update(payload.toByteArray(StandardCharsets.UTF_8))
            sign()
        }
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    fun grantFor(invoice: InvoiceDto, notAfter: LocalDate? = null): String =
        grantFor(checkNotNull(resolveOutputIntentSubject(invoice)), notAfter)

    fun sellerInvoice(invoice: InvoiceDto): InvoiceDto = invoice.copy(senderProfile = SELLER)
}
