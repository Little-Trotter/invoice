package de.littletrotter.shared

import java.time.Instant
import java.time.LocalDate

/**
 * Port for object storage. S3-compatible implementations (RustFS) exist in both
 * little-trotter-invoice (`S3DocumentStore`, used by the yearly document backup) and kotlin-xpdf
 * (`S3DocumentStore`, the worker's own document store); both share their CRUD/listing logic via
 * [AbstractS3DocumentStore] and differ only in client construction/CDI wiring.
 */
interface DocumentStore {
    fun put(objectKey: String, data: ByteArray, contentType: String)
    fun get(objectKey: String): ByteArray
    fun exists(objectKey: String): Boolean
    fun enableImmutability(objectKey: String)
    fun delete(objectKey: String)

    /**
     * List every object key stored under [prefix].
     *
     * Real backends page through the underlying store themselves (e.g. S3's `ListObjectsV2`
     * continuation tokens) so callers never have to. Returns keys only, not payloads: even a
     * year's worth of receipts is a negligible number of short strings compared to the
     * receipts' bytes, which is what lets callers such as a yearly backup list first and then
     * fetch (and discard) one object's bytes at a time via [get] instead of holding the whole
     * year on the heap at once.
     *
     * Ships as a default that fails loudly rather than as an abstract member: this codebase
     * has many small `DocumentStore` test doubles that only ever exercise put/get/exists/
     * delete round-trips, and forcing every one of them to grow a listing implementation they
     * never call would be churn without benefit. The three real backends
     * (`S3DocumentStore` ×2, `LocalFileSystemDocumentStore`) all override this properly.
     * Anything that reaches this default does not actually support listing and must not be
     * wired into a feature that depends on it (e.g. the yearly RustFS backup).
     */
    fun listObjectKeys(prefix: String): List<String> =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not implement listObjectKeys(); override it if this DocumentStore backs a year-scoped listing feature such as backups",
        )

    /**
     * List every stored document key for [year] (i.e. everything under
     * `documents/<year>/`, see [DocumentKeyRules.yearPrefix]).
     *
     * Delegates to [listObjectKeys] so implementations only need to get the one raw listing
     * primitive right; the year-scoping stays defined once, in [DocumentKeyRules].
     */
    fun listKeysForYear(year: Int): List<String> = listObjectKeys(DocumentKeyRules.yearPrefix(year))

    /**
     * Best-effort last-modified timestamp
     * for [objectKey], used only by the S3 consistency report to show how old an orphaned object
     * is. Unlike [listObjectKeys], this is genuinely optional: a backend that cannot report it
     * returns `null` rather than failing loudly, since "age unknown" is an acceptable degraded
     * display for one report column, whereas silently returning an incomplete key set (see
     * [listObjectKeys]'s own KDoc) is not. [AbstractS3DocumentStore] overrides this via
     * `HeadObject`; a local-filesystem implementation may override it via the file's mtime.
     */
    fun headLastModified(objectKey: String): Instant? = null

    /**
     * Store a document using the year-based key structure.
     *
     * The year is derived from the invoiceDate, not the current date.
     * This ensures back-dated invoices end up in their correct year folder.
     */
    fun put(invoiceDate: LocalDate, objectKey: String, data: ByteArray, contentType: String) {
        val yearBasedKey = buildDocumentKey(invoiceDate, objectKey)
        put(yearBasedKey, data, contentType)
    }

    /**
     * Get a document using the year-based key structure.
     *
     * Tries the year-based key first, then falls back to legacy keys without year prefix
     * for backward compatibility with existing documents.
     */
    fun get(invoiceDate: LocalDate, objectKey: String): ByteArray {
        val yearBasedKey = buildDocumentKey(invoiceDate, objectKey)
        return try {
            get(yearBasedKey)
        } catch (_: Exception) {
            // Fallback to legacy key (no year prefix)
            get(objectKey)
        }
    }

    /**
     * Check if a document exists using the year-based key structure.
     *
     * Checks both year-based and legacy keys for backward compatibility.
     */
    fun exists(invoiceDate: LocalDate, objectKey: String): Boolean {
        val yearBasedKey = buildDocumentKey(invoiceDate, objectKey)
        return exists(yearBasedKey) || exists(objectKey)
    }

    /**
     * Delete a document using the year-based key structure.
     *
     * Tries to delete both year-based and legacy keys (best effort).
     */
    fun delete(invoiceDate: LocalDate, objectKey: String) {
        val yearBasedKey = buildDocumentKey(invoiceDate, objectKey)
        try {
            delete(yearBasedKey)
        } catch (_: Exception) {
            // Continue to legacy key
        }
        try {
            delete(objectKey)
        } catch (_: Exception) {
            // Best effort deletion
        }
    }

    /** Delegates to [DocumentKeyRules], the single owner of the key layout. */
    private fun buildDocumentKey(invoiceDate: LocalDate, filename: String): String =
        DocumentKeyRules.buildDocumentKey(invoiceDate, filename)
}
