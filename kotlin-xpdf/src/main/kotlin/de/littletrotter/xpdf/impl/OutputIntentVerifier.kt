package de.littletrotter.xpdf.impl

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat

@ApplicationScoped
class OutputIntentVerifier(private val publicKeys: List<PublicKey>) {

    @Inject
    constructor() : this(PUBLIC_KEYS_BASE64.map(::ed25519PublicKey))

    fun subjectHash(subject: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(StandardCharsets.UTF_8)),
        )

    fun covers(tokens: String?, subject: String?, today: LocalDate): Boolean {
        if (subject == null) return false
        return tokens.orEmpty().split(WHITESPACE).any { coversOne(it, subject, today) }
    }

    private fun coversOne(token: String, subject: String, today: LocalDate): Boolean {
        val grant = OutputIntentGrant.parse(token) ?: return false
        val expected = subjectHash(subject).toByteArray(StandardCharsets.UTF_8)
        val actual = grant.subjectHash.toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(expected, actual)) return false
        val notAfter = grant.notAfter
        if (notAfter != null && today.isAfter(notAfter)) return false
        return signatureHolds(grant)
    }

    private fun signatureHolds(grant: OutputIntentGrant): Boolean = publicKeys.any { signedBy(grant, it) }

    private fun signedBy(grant: OutputIntentGrant, publicKey: PublicKey): Boolean =
        try {
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey)
                update(grant.signedPayload.toByteArray(StandardCharsets.UTF_8))
                verify(grant.signature)
            }
        } catch (_: java.security.GeneralSecurityException) {
            false
        }

    companion object {
        val PUBLIC_KEYS_BASE64 = listOf("pEDG6nsWr11ivNWN1FAbPY57AjF270SMnKtYdbB+bMo=")

        private val WHITESPACE = Regex("\\s+")

        private const val SPKI_ED25519_PREFIX = "302a300506032b6570032100"

        private fun ed25519PublicKey(rawBase64: String): PublicKey {
            val spki = HexFormat.of().parseHex(SPKI_ED25519_PREFIX) + Base64.getDecoder().decode(rawBase64)
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        }
    }
}
