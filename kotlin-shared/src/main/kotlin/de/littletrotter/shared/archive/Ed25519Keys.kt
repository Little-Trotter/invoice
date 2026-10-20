package de.littletrotter.shared.archive

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.HexFormat

/**
 * JDK-native Ed25519 key handling (java.security, `Ed25519` KeyPairGenerator/Signature,
 * JDK 15+): no BouncyCastle dependency needed. Used by [ArchivePackageBuilder] (via
 * [Ed25519ArchiveSigner]) to detached-sign every file of a signed archive package, and by
 * `kotlin-xpdf`'s own key-loading code to decode the configured private/public key material.
 *
 * Deliberately keeps the private and public key as two independently loadable values rather than
 * always deriving one from the other: EdDSA's public key IS mathematically derivable from the
 * private scalar, but the JDK's own `java.security` API exposes no such derivation — only signing
 * (needs the private key) and verifying/fingerprinting (needs the public key) as separate
 * operations. The one place a keypair is genuinely generated together is [generateKeyPair]; from
 * then on the private and public halves are handled, stored and configured independently (see
 * `kotlin-xpdf`'s config: two separate secrets, not one keypair blob).
 */
object Ed25519Keys {
    /** The one algorithm name this whole feature ever uses — `KeyPairGenerator`, `KeyFactory` and
     *  `Signature` all accept the same JCA name for Ed25519 (JDK 15+, no provider needed). */
    const val ALGORITHM: String = "Ed25519"

    /** Generates a fresh Ed25519 keypair. Used by the `lt-setup` key-generation flow (via a small
     *  CLI-callable entry point) and directly by tests. */
    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

    /** A detached EdDSA signature over [data], produced with [privateKey]. Ed25519 (RFC 8032) is
     *  deterministic — the same key and message always produce the same signature bytes, unlike
     *  ECDSA's randomized `k` — which is exactly what makes [ArchivePackageBuilder]'s "two builds
     *  of the same input produce byte-identical output" guarantee possible. */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(ALGORITHM).apply {
            initSign(privateKey)
            update(data)
        }.sign()

    /** Verifies a detached [signature] over [data] against [publicKey]. The external, standard-
     *  tooling counterpart is `openssl pkeyutl`. */
    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance(ALGORITHM).apply {
            initVerify(publicKey)
            update(data)
        }.verify(signature)

    /** SHA-256 of the public key's encoded (X.509 SubjectPublicKeyInfo) bytes, as lowercase hex —
     *  the "key fingerprint" every signed archive package's `manifest.json` carries, so a package
     *  stays independently verifiable (and a rotated key stays distinguishable) without the
     *  application. */
    fun fingerprint(publicKey: PublicKey): String = sha256Hex(publicKey.encoded)

    /** Decodes a PKCS#8-encoded (the JDK's own [PrivateKey.getEncoded] format) Ed25519 private key. */
    fun decodePrivateKey(pkcs8Der: ByteArray): PrivateKey =
        KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))

    /** Decodes an X.509 SubjectPublicKeyInfo-encoded (the JDK's own [PublicKey.getEncoded] format)
     *  Ed25519 public key. */
    fun decodePublicKey(x509Der: ByteArray): PublicKey =
        KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(x509Der))

    /** [decodePrivateKey], from the base64 encoding the `lt-setup`-generated `.env` secret carries. */
    fun decodePrivateKeyBase64(base64: String): PrivateKey = decodePrivateKey(Base64.getDecoder().decode(base64))

    /** [decodePublicKey], from the base64 encoding the `lt-setup`-generated `.env` value carries. */
    fun decodePublicKeyBase64(base64: String): PublicKey = decodePublicKey(Base64.getDecoder().decode(base64))

    /** [KeyPair.getPrivate]/[KeyPair.getPublic], base64-of-[java.security.Key.getEncoded] each —
     *  the exact two values `lt-setup`'s key-generation step writes into `.env`. */
    fun encodeKeyPairBase64(keyPair: KeyPair): Pair<String, String> =
        Base64.getEncoder().encodeToString(keyPair.private.encoded) to
            Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
