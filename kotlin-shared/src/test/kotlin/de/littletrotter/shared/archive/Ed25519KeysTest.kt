package de.littletrotter.shared.archive

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat

/** [Ed25519Keys], the JDK-native Ed25519 key/signing primitives [ArchivePackageBuilder]'s
 *  [Ed25519ArchiveSigner] and kotlin-xpdf's `ArchiveSigningKeyProvider` both build on. */
class Ed25519KeysTest {

    @Test
    fun `RS-9 - generateKeyPair produces a working sign-verify round trip`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val data = "hello archive package".toByteArray()

        val signature = Ed25519Keys.sign(keyPair.private, data)

        assertThat(Ed25519Keys.verify(keyPair.public, data, signature)).isTrue()
    }

    @Test
    fun `RS-9 - verify rejects a signature over different data`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val signature = Ed25519Keys.sign(keyPair.private, "original".toByteArray())

        assertThat(Ed25519Keys.verify(keyPair.public, "tampered".toByteArray(), signature)).isFalse()
    }

    @Test
    fun `RS-9 - verify rejects a signature from a different key`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val otherKeyPair = Ed25519Keys.generateKeyPair()
        val data = "hello".toByteArray()
        val signature = Ed25519Keys.sign(keyPair.private, data)

        assertThat(Ed25519Keys.verify(otherKeyPair.public, data, signature)).isFalse()
    }

    @Test
    fun `RS-9 - sign is deterministic - same key and data always produce the same signature bytes`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val data = "deterministic packaging depends on this".toByteArray()

        val first = Ed25519Keys.sign(keyPair.private, data)
        val second = Ed25519Keys.sign(keyPair.private, data)

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `RS-9 - fingerprint is the lowercase hex sha256 of the public key's encoded bytes`() {
        val keyPair = Ed25519Keys.generateKeyPair()
        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded))

        assertThat(Ed25519Keys.fingerprint(keyPair.public)).isEqualTo(expected)
    }

    @Test
    fun `RS-9 - fingerprint differs between two independently generated keypairs`() {
        val a = Ed25519Keys.generateKeyPair()
        val b = Ed25519Keys.generateKeyPair()

        assertThat(Ed25519Keys.fingerprint(a.public)).isNotEqualTo(Ed25519Keys.fingerprint(b.public))
    }

    @Test
    fun `RS-9 - encodeKeyPairBase64 then decodePrivateKeyBase64-decodePublicKeyBase64 round-trips to a working keypair`() {
        val original = Ed25519Keys.generateKeyPair()
        val (privateBase64, publicBase64) = Ed25519Keys.encodeKeyPairBase64(original)

        val decodedPrivate = Ed25519Keys.decodePrivateKeyBase64(privateBase64)
        val decodedPublic = Ed25519Keys.decodePublicKeyBase64(publicBase64)

        val data = "round trip".toByteArray()
        val signature = Ed25519Keys.sign(decodedPrivate, data)
        assertThat(Ed25519Keys.verify(decodedPublic, data, signature)).isTrue()
        assertThat(Ed25519Keys.fingerprint(decodedPublic)).isEqualTo(Ed25519Keys.fingerprint(original.public))
    }

    @Test
    fun `RS-9 - decodePrivateKey-decodePublicKey accept the raw encoded bytes directly`() {
        val original = Ed25519Keys.generateKeyPair()

        val decodedPrivate = Ed25519Keys.decodePrivateKey(original.private.encoded)
        val decodedPublic = Ed25519Keys.decodePublicKey(original.public.encoded)

        val data = "raw bytes".toByteArray()
        assertThat(Ed25519Keys.verify(decodedPublic, data, Ed25519Keys.sign(decodedPrivate, data))).isTrue()
    }
}
