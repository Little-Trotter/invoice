package de.littletrotter.application.invoice

import java.security.MessageDigest
import java.util.Locale

/** A SHA-256 digest renders as this many hex characters — [receiptSha256Hex]'s builder capacity. */
private const val SHA256_HEX_LENGTH = 64

/**
 * SHA-256 hex digest, shared by every receipt write path that checksums bytes
 * ([ReceiptUploadService.upload]'s dedup, [NormaliseReceipt.normalise]'s collision check) so
 * `uq_receipt_sha256_checksum` is computed identically everywhere.
 *
 * The JCA spec (MessageDigest's own javadoc) requires every Java platform implementation to
 * support SHA-256, so the checked NoSuchAlgorithmException `getInstance("SHA-256")` declares is
 * provably unreachable on any conformant JVM; Kotlin has no checked-exception enforcement, so
 * there is nothing to catch here.
 */
internal fun receiptSha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    val builder = StringBuilder(SHA256_HEX_LENGTH)
    for (byte in digest) builder.append(String.format(Locale.ROOT, "%02x", byte))
    return builder.toString()
}
