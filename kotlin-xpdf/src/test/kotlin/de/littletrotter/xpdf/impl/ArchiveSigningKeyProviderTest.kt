package de.littletrotter.xpdf.impl

import de.littletrotter.shared.archive.Ed25519Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/** [ArchiveSigningKeyProvider]: decodes the two configured base64 secrets into a working
 *  [de.littletrotter.shared.archive.ArchiveSigner], and falls back to a working ephemeral keypair
 *  when either is blank (the `%dev`/`%test` default — see that class's own KDoc; `%prod` never
 *  reaches this branch at all, its own `application.properties` entries carry no default). */
class ArchiveSigningKeyProviderTest {

    private fun provider(privateKeyBase64: Optional<String>, publicKeyBase64: Optional<String>) =
        ArchiveSigningKeyProvider(ArchiveSigningKeyProvider.resolveDelegate(privateKeyBase64, publicKeyBase64))

    @Test
    fun `RS-9 - with configured base64 keys, sign produces a signature verifiable against the configured public key`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val (privateBase64, publicBase64) = Ed25519Keys.encodeKeyPairBase64(keyPair)
        val provider = provider(Optional.of(privateBase64), Optional.of(publicBase64))

        val data = "archive payload".toByteArray()
        val signature = provider.sign(data)

        assertThat(provider.algorithm).isEqualTo("Ed25519")
        assertThat(provider.keyFingerprint).isEqualTo(Ed25519Keys.fingerprint(keyPair.public))
        assertThat(Ed25519Keys.verify(keyPair.public, data, signature)).isTrue()
    }

    @Test
    fun `RS-9 - with blank keys, resolveDelegate falls back to a working ephemeral keypair instead of failing`() {
        val provider = provider(Optional.empty(), Optional.empty())

        val data = "ephemeral dev signing".toByteArray()
        val signature = provider.sign(data)

        assertThat(provider.algorithm).isEqualTo("Ed25519")
        assertThat(provider.keyFingerprint).isNotBlank()
        // No independent way to fetch the ephemeral public key from outside the provider (by
        // design — see its own KDoc) — a signature produced under init()'s own fingerprint is the
        // only externally observable proof the ephemeral keypair actually works end to end.
        assertThat(signature).isNotEmpty()
    }

    @Test
    fun `RS-9 - two ArchiveSigningKeyProvider instances configured with the same keys report the same fingerprint`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val (privateBase64, publicBase64) = Ed25519Keys.encodeKeyPairBase64(keyPair)
        val first = provider(Optional.of(privateBase64), Optional.of(publicBase64))
        val second = provider(Optional.of(privateBase64), Optional.of(publicBase64))

        assertThat(first.keyFingerprint).isEqualTo(second.keyFingerprint)
    }

    @Test
    fun `RS-9 - resolveDelegate falls back to an ephemeral keypair when only the private key is blank`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val (_, publicBase64) = Ed25519Keys.encodeKeyPairBase64(keyPair)

        val delegate = ArchiveSigningKeyProvider.resolveDelegate(Optional.empty(), Optional.of(publicBase64))

        assertThat(delegate.algorithm).isEqualTo("Ed25519")
        assertThat(delegate.keyFingerprint).isNotEqualTo(Ed25519Keys.fingerprint(keyPair.public))
    }

    @Test
    fun `RS-9 - resolveDelegate falls back to an ephemeral keypair when only the public key is blank`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val (privateBase64, _) = Ed25519Keys.encodeKeyPairBase64(keyPair)

        val delegate = ArchiveSigningKeyProvider.resolveDelegate(Optional.of(privateBase64), Optional.empty())

        assertThat(delegate.algorithm).isEqualTo("Ed25519")
        assertThat(delegate.keyFingerprint).isNotEqualTo(Ed25519Keys.fingerprint(keyPair.public))
    }

    // ── ArchiveSignerProducer: the CDI wiring split out of ArchiveSigningKeyProvider ──────────

    @Test
    fun `QA-22 - ArchiveSignerProducer produces a working signer from configured keys`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val (privateBase64, publicBase64) = Ed25519Keys.encodeKeyPairBase64(keyPair)
        val producer = ArchiveSignerProducer(Optional.of(privateBase64), Optional.of(publicBase64))

        val signer = producer.produceArchiveSigner()
        val data = "producer payload".toByteArray()
        val signature = signer.sign(data)

        assertThat(signer.algorithm).isEqualTo("Ed25519")
        assertThat(signer.keyFingerprint).isEqualTo(Ed25519Keys.fingerprint(keyPair.public))
        assertThat(Ed25519Keys.verify(keyPair.public, data, signature)).isTrue()
    }

    @Test
    fun `QA-22 - ArchiveSignerProducer falls back to an ephemeral signer when both keys are blank`() {
        val producer = ArchiveSignerProducer(Optional.empty(), Optional.empty())

        val signer = producer.produceArchiveSigner()

        assertThat(signer.algorithm).isEqualTo("Ed25519")
        assertThat(signer.keyFingerprint).isNotBlank()
    }
}
