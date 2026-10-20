package de.littletrotter.application.invoice

import java.security.MessageDigest
import java.util.HexFormat

/** Lower-case hex SHA-256 as stored in `invoice_document.sha256_checksum`. */
internal object Sha256Hex {
    fun of(data: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))
}
