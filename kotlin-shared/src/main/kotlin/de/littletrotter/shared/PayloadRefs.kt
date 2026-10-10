package de.littletrotter.shared

/**
 * AP-M6 — the wire-carried pointer to a KoSIT validation report stored in [PayloadExchange]
 * under [key] (`lt:kosit:{jobId}`, `SETEX … 3600`), replacing the old inline `kositReport` field
 * on the result envelope `ResultPublisher` publishes. No integrity hash: a missing/expired report degrades to the documented "report unavailable"
 * marker, never to a processing failure — there is nothing to verify against, only something to
 * note as absent.
 */
data class KositReportRefDto(val key: String)

/**
 * The wire-carried pointer to a per-trip workbook (`.xlsx`) stored in
 * [PayloadExchange] under [key] (`lt:tripwb:{sha256}`, `SETEX … 86400`), base64-encoded because
 * [PayloadExchange] only ever carries text (see its own KDoc). [sha256] is computed over the
 * base64 string itself — an integrity check so a Valkey eviction/rewrite race is detected the same way. [fileName] is
 * the BG-24 attachment name (`<invoice-number>-dienstreise-<pos>.xlsx`), computed once by the
 * producer (which already has the invoice number and position id) so the consumer
 * (`kotlin-xpdf`'s `MustangExportModelMapper`) never has to rebuild it.
 */
data class TripWorkbookRefDto(val key: String, val sha256: String, val fileName: String)
