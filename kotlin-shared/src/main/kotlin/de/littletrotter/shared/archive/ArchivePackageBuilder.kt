package de.littletrotter.shared.archive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * One file to include in an [ArchivePackageBuilder]-built package's `original/` directory.
 * [filename] is a bare name (no directory component) — the builder itself owns the `original/`/
 * `signed/` layout, see [ArchivePackageBuilder.build]'s own KDoc.
 */
data class ArchivePayload(val filename: String, val bytes: ByteArray) {
    // Kotlin's default data-class equals/hashCode compares [bytes] by reference for a ByteArray
    // property, which would make two payloads with identical content compare unequal — surprising
    // for a value type callers (and tests) reasonably expect to compare by content, e.g. asserting
    // "the same payload list built twice is equal".
    override fun equals(other: Any?): Boolean =
        this === other || (other is ArchivePayload && filename == other.filename && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * filename.hashCode() + bytes.contentHashCode()
}

/** The finished tar.gz package: its bytes, plus their own SHA-256 (so a caller can store both the
 *  package and its own checksum in one step, the same way [de.littletrotter.shared.ExportResult]
 *  already carries the PDF/XML's checksums). */
data class ArchivePackage(val bytes: ByteArray, val sha256: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ArchivePackage && sha256 == other.sha256 && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * sha256.hashCode() + bytes.contentHashCode()
}

/**
 * A document-agnostic, deterministic signed archive-package builder living in
 * `kotlin-shared` on purpose: `kotlin-xpdf` is its first caller (signed invoice PDF+XML packages),
 * but the upcoming mail component reuses this exact builder unchanged for signed
 * sent-mail packages later. Nothing here knows about invoices, PDFs or XML — only "payload files"
 * and "manifest fields", both supplied by the caller.
 *
 * ## Package layout
 * ```
 * <root>
 * ├── manifest.json                          — [manifestFields], plus this build's own additions
 * ├── original/
 * │   ├── <payload 1 filename>
 * │   ├── <payload 1 filename>.sha256        — "sha256sum -c" format: "<hex>  <filename>\n"
 * │   ├── <payload 2 filename>
 * │   └── <payload 2 filename>.sha256
 * └── signed/
 *     ├── <payload 1 filename>.sig           — detached signature over the original file's bytes
 *     ├── <payload 1 filename>.sha256.sig    — detached signature over the .sha256 file's bytes
 *     ├── <payload 2 filename>.sig
 *     ├── <payload 2 filename>.sha256.sig
 *     └── manifest.json.sig                  — detached signature over manifest.json's bytes
 * ```
 *
 * `manifest.json` combines `manifestFields` verbatim with three things only this builder can
 * compute: a `files` map (every payload filename → its own SHA-256, the same digests the
 * `.sha256` files under `original/` carry) and the signer's own `algorithm`/`keyFingerprint`
 * ([ArchiveSigner]). A caller-supplied field name colliding with `files`/`algorithm`/
 * `keyFingerprint` is rejected — see [build]'s own `require`.
 *
 * ## Determinism
 * Two `build` calls with equal `payloads`/`manifestFields` and the same `signer` key produce
 * byte-identical tar.gz output: entries are written in a fixed, sorted order (a [sortedMapOf] path
 * → bytes map, not construction order), every tar entry's mtime/uid/gid/uname/gname is normalised
 * to a fixed zero value, the gzip header's modification-time field is zeroed
 * ([GzipParameters.setModificationTime]), and Ed25519 signatures are themselves deterministic
 * (RFC 8032 — same key and message always yield the same signature bytes, unlike ECDSA's
 * randomized nonce). `manifest.json` itself is serialized with its fields in a fixed sorted order
 * (see [buildManifestJson]), not `HashMap` iteration order.
 */
object ArchivePackageBuilder {
    const val MANIFEST_ENTRY: String = "manifest.json"

    // 0644 in octal — TarArchiveEntry.mode is a plain Int, no octal-literal syntax in Kotlin.
    private const val TAR_MODE_0644 = 420

    // GzipParameters.operatingSystem's own documented sentinel for "unknown" (RFC 1952 §2.3,
    // OS byte 255) — avoids baking the build machine's real OS byte into the deterministic
    // output.
    private const val GZIP_OS_UNKNOWN = 255
    private val mapper = jacksonObjectMapper()

    private val RESERVED_MANIFEST_FIELDS = setOf("files", "algorithm", "keyFingerprint")

    /**
     * Builds one signed archive package from [payloads] (the files that go under `original/`) and
     * [manifestFields] (arbitrary caller-supplied key-value fields, e.g. invoice number/type code/
     * timestamp/job id/actor — this builder never interprets them, only carries them into
     * `manifest.json` verbatim), signed with [signer].
     *
     * @throws IllegalArgumentException if [payloads] is empty, if two payloads share a filename,
     *   or if [manifestFields] uses one of the three field names this builder itself writes
     *   ([RESERVED_MANIFEST_FIELDS]) — a silent overwrite in either direction would make the
     *   resulting manifest misleading about which value actually won.
     */
    fun build(payloads: List<ArchivePayload>, manifestFields: Map<String, String>, signer: ArchiveSigner): ArchivePackage {
        require(payloads.isNotEmpty()) { "At least one payload is required to build an archive package" }
        val filenames = payloads.map { it.filename }
        require(filenames.size == filenames.toSet().size) { "Payload filenames must be unique, got: $filenames" }
        val collidingFields = manifestFields.keys intersect RESERVED_MANIFEST_FIELDS
        require(collidingFields.isEmpty()) {
            "manifestFields must not use reserved field name(s) $collidingFields — " +
                "the builder itself writes those"
        }

        val sortedPayloads = payloads.sortedBy { it.filename }
        val fileHashes = sortedPayloads.associate { it.filename to sha256Hex(it.bytes) }
        val manifestBytes = buildManifestJson(manifestFields, fileHashes, signer).toByteArray(Charsets.UTF_8)

        val entries = sortedMapOf<String, ByteArray>()
        entries[MANIFEST_ENTRY] = manifestBytes
        entries["signed/$MANIFEST_ENTRY.sig"] = signer.sign(manifestBytes)
        sortedPayloads.forEach { payload ->
            val hashLine = "${fileHashes.getValue(payload.filename)}  ${payload.filename}\n".toByteArray(Charsets.UTF_8)
            entries["original/${payload.filename}"] = payload.bytes
            entries["original/${payload.filename}.sha256"] = hashLine
            entries["signed/${payload.filename}.sig"] = signer.sign(payload.bytes)
            entries["signed/${payload.filename}.sha256.sig"] = signer.sign(hashLine)
        }

        val tarGzBytes = writeDeterministicTarGz(entries)
        return ArchivePackage(tarGzBytes, sha256Hex(tarGzBytes))
    }

    /** `manifestFields` first (sorted by key, never `HashMap` order), then the three fields this
     *  builder itself owns — `files` (sorted by filename), `algorithm`, `keyFingerprint`. A
     *  [LinkedHashMap] built by inserting in that fixed order, not a plain `mapOf`, so Jackson's
     *  serialization order matches it (Jackson preserves `Map` iteration order for both
     *  [LinkedHashMap] and [java.util.SortedMap]). */
    private fun buildManifestJson(fields: Map<String, String>, fileHashes: Map<String, String>, signer: ArchiveSigner): String {
        val manifest = LinkedHashMap<String, Any>()
        fields.toSortedMap().forEach { (key, value) -> manifest[key] = value }
        manifest["files"] = fileHashes.toSortedMap()
        manifest["algorithm"] = signer.algorithm
        manifest["keyFingerprint"] = signer.keyFingerprint
        return mapper.writeValueAsString(manifest)
    }

    /** Writes [entries] (already in their final, sorted iteration order — a [sortedMapOf]) as a
     *  gzip-compressed tar with every entry's metadata normalised (fixed mtime/uid/gid/uname/gname/
     *  mode) and the gzip header's own modification-time zeroed, so the resulting bytes depend only
     *  on [entries]' content — never on the machine or moment this method ran. */
    private fun writeDeterministicTarGz(entries: Map<String, ByteArray>): ByteArray {
        val byteOut = ByteArrayOutputStream()
        val gzipParameters = GzipParameters().apply {
            modificationTime = 0
            operatingSystem = GZIP_OS_UNKNOWN
        }
        GzipCompressorOutputStream(byteOut, gzipParameters).use { gzipOut ->
            TarArchiveOutputStream(gzipOut).use { tarOut ->
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                entries.forEach { (path, bytes) -> writeTarEntry(tarOut, path, bytes) }
                tarOut.finish()
            }
        }
        return byteOut.toByteArray()
    }

    private fun writeTarEntry(tarOut: TarArchiveOutputStream, path: String, bytes: ByteArray) {
        tarOut.putArchiveEntry(deterministicTarEntry(path, bytes.size.toLong()))
        tarOut.write(bytes)
        tarOut.closeArchiveEntry()
    }

    private fun deterministicTarEntry(path: String, sizeBytes: Long): TarArchiveEntry =
        TarArchiveEntry(path).apply {
            size = sizeBytes
            setModTime(0L)
            userId = 0
            groupId = 0
            userName = ""
            groupName = ""
            mode = TAR_MODE_0644
        }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
