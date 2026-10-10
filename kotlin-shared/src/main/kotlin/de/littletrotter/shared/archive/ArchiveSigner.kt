package de.littletrotter.shared.archive

import java.security.PrivateKey

/**
 * The one capability [ArchivePackageBuilder] needs from a signing key: produce a detached
 * signature over arbitrary bytes, and name the algorithm/key it used. Kept as an interface (rather
 * than [ArchivePackageBuilder] taking a raw [java.security.PrivateKey]) so the builder itself never
 * needs to know which signature scheme is in use — Ed25519 today ([Ed25519ArchiveSigner]), and the
 * seam the upcoming mail component (signed sent-mail packages) can reuse unchanged even if it one
 * day needs a different algorithm.
 */
interface ArchiveSigner {
    /** The JCA/manifest algorithm label, e.g. `"Ed25519"` — written into `manifest.json` verbatim. */
    val algorithm: String

    /** SHA-256 (hex) of the signing key's public half — written into `manifest.json` so a package
     *  stays attributable to a specific key, including across key rotation. */
    val keyFingerprint: String

    /** A detached signature over [data]. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * The JDK-native Ed25519 [ArchiveSigner] ([Ed25519Keys]). [keyFingerprint] is supplied by
 * the caller (not derived from [privateKey]) because the JDK's `java.security` API cannot derive
 * an Ed25519 public key from its private half — see [Ed25519Keys]'s own KDoc; callers load both
 * halves independently and pass [Ed25519Keys.fingerprint] of the public one here.
 */
class Ed25519ArchiveSigner(
    private val privateKey: PrivateKey,
    override val keyFingerprint: String,
) : ArchiveSigner {
    override val algorithm: String = Ed25519Keys.ALGORITHM

    override fun sign(data: ByteArray): ByteArray = Ed25519Keys.sign(privateKey, data)
}
