package de.littletrotter.xpdf.impl

import java.security.MessageDigest
import java.util.HexFormat

/**
 * SHA-256 of [data] as lowercase hex.
 *
 * Pulled out to one place rather than left as [PdfExportServiceImpl]'s private helper:
 * the pdf check compares it with the digest in its request, and the same
 * bytes must always hash to the same key in both places — two independent implementations would be
 * two chances to drift apart, not two chances to be right.
 */
internal fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return HexFormat.of().formatHex(digest.digest(data))
}
